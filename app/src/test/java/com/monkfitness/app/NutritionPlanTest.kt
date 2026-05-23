package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.data.model.generateThreeDayMuscleGainPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPlanTest {

    @Test
    fun testGeneratedPlanTracksProgramDayTypeAndAdaptiveCalories() {
        val plan = generateThreeDayMuscleGainPlan(seed = 0, startDay = 1)

        assertEquals(3, plan.days.size)
        assertEquals(listOf(1, 2, 3), plan.days.map { it.programDay })
        assertEquals(
            listOf(NutritionDayType.TRAINING, NutritionDayType.LIGHT, NutritionDayType.TRAINING),
            plan.days.map { it.dayType }
        )

        plan.days.forEach { day ->
            assertEquals(2500, day.targetCalories)
            assertTrue(day.totalCalories in (day.targetCalories - 120)..(day.targetCalories + 120))
            assertTrue(day.totalProteinGrams >= 100)
        }

        assertTrue(plan.days.first().meals.any { it.type == NutritionMealType.POST_WORKOUT })
        assertTrue(plan.days[1].meals.any { it.type == NutritionMealType.SNACK && it.optional })
    }

    @Test
    fun testWeekMultiplierRaisesCalorieTargetsAsProgramProgresses() {
        val weekThreePlan = generateThreeDayMuscleGainPlan(seed = 0, startDay = 15)
        val weekSixPlan = generateThreeDayMuscleGainPlan(seed = 0, startDay = 36)
        val weekEightPlan = generateThreeDayMuscleGainPlan(seed = 0, startDay = 50)

        assertEquals(2750, weekThreePlan.days.first().targetCalories)
        assertEquals(3000, weekSixPlan.days.first().targetCalories)
        assertEquals(3250, weekEightPlan.days.first().targetCalories)
        assertTrue(weekEightPlan.days.first().totalCalories > weekThreePlan.days.first().totalCalories)
    }

    @Test
    fun testShoppingListCombinesAdaptiveIngredientsIntoGroupedItems() {
        val plan = generateThreeDayMuscleGainPlan(seed = 2, startDay = 4)
        val proteins = plan.shoppingList[NutritionShoppingGroup.PROTEIN].orEmpty()
        val carbs = plan.shoppingList[NutritionShoppingGroup.CARBS].orEmpty()
        val fats = plan.shoppingList[NutritionShoppingGroup.FATS].orEmpty()
        val produce = plan.shoppingList[NutritionShoppingGroup.FRUITS_VEGETABLES].orEmpty()
        val allowedKeys = setOf(
            "chicken",
            "eggs",
            "rice",
            "oats",
            "potatoes",
            "buckwheat",
            "cottage_cheese",
            "yogurt",
            "banana",
            "apple",
            "nuts"
        )

        val allKeys = plan.shoppingList.values.flatten().map { it.ingredient.key }
        assertTrue(allKeys.all { it in allowedKeys })
        assertEquals(proteins.map { it.ingredient.key }.distinct().size, proteins.size)
        assertEquals(carbs.map { it.ingredient.key }.distinct().size, carbs.size)
        assertTrue(fats.any { it.ingredient.key == "nuts" && it.totalAmount >= 20 })
        assertTrue(produce.any { it.ingredient.key == "apple" || it.ingredient.key == "banana" })
    }

    @Test
    fun testChangingSeedRegeneratesMealSelection() {
        val firstPlan = generateThreeDayMuscleGainPlan(seed = 0, startDay = 1)
        val secondPlan = generateThreeDayMuscleGainPlan(seed = 1, startDay = 1)

        val firstDaySignature = firstPlan.days.first().meals.map { meal ->
            meal.type.key to meal.ingredients.map { ingredient -> ingredient.ingredient.key }
        }
        val secondDaySignature = secondPlan.days.first().meals.map { meal ->
            meal.type.key to meal.ingredients.map { ingredient -> ingredient.ingredient.key }
        }

        assertNotEquals(firstDaySignature, secondDaySignature)
    }
}
