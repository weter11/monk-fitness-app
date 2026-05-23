package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.data.model.generateThreeDayMuscleGainPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPlanTest {

    @Test
    fun testGeneratedPlanHasThreeStructuredDaysWithinTargetRange() {
        val plan = generateThreeDayMuscleGainPlan(seed = 0)

        assertEquals(3, plan.days.size)

        plan.days.forEach { day ->
            assertEquals(
                listOf(
                    NutritionMealType.BREAKFAST,
                    NutritionMealType.LUNCH,
                    NutritionMealType.DINNER,
                    NutritionMealType.POST_WORKOUT
                ),
                day.meals.map { it.type }
            )
            assertTrue(day.totalCalories in 2400..2600)
            assertTrue(day.totalProteinGrams in 100..120)
            assertTrue(day.meals.last().optional)
        }
    }

    @Test
    fun testShoppingListCombinesDuplicateIngredientsIntoGroupedItems() {
        val plan = generateThreeDayMuscleGainPlan(seed = 0)
        val proteins = plan.shoppingList[NutritionShoppingGroup.PROTEIN].orEmpty()
        val carbs = plan.shoppingList[NutritionShoppingGroup.CARBS].orEmpty()

        assertEquals(proteins.map { it.ingredient.key }.distinct().size, proteins.size)
        assertEquals(carbs.map { it.ingredient.key }.distinct().size, carbs.size)
        assertTrue(proteins.any { it.ingredient.key == "chicken_breast" && it.totalAmount >= 250 })
        assertTrue(carbs.any { it.ingredient.key == "rice" && it.totalAmount >= 400 })
    }

    @Test
    fun testChangingSeedRegeneratesMealSelection() {
        val firstPlan = generateThreeDayMuscleGainPlan(seed = 0)
        val secondPlan = generateThreeDayMuscleGainPlan(seed = 1)

        val firstDaySignature = firstPlan.days.first().meals.map { meal ->
            meal.type.key to meal.ingredients.map { ingredient -> ingredient.ingredient.key }
        }
        val secondDaySignature = secondPlan.days.first().meals.map { meal ->
            meal.type.key to meal.ingredients.map { ingredient -> ingredient.ingredient.key }
        }

        assertNotEquals(firstDaySignature, secondDaySignature)
    }
}
