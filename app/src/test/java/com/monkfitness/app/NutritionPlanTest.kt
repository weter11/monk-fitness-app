package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.MealType
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.calculateNutritionCompletionPercent
import com.monkfitness.app.data.model.NutritionGenerationIssue
import com.monkfitness.app.data.model.findReplacementMealTemplateId
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.getTodayCookingInstructionResIds
import com.monkfitness.app.data.model.nutritionReplacementKey
import com.monkfitness.app.data.model.validateAvailableProductSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPlanTest {

    @Test
    fun testAvailableProductsValidationRequiresCoreFoodGroups() {
        assertEquals(
            NutritionGenerationIssue.NOT_ENOUGH_PROTEIN,
            validateAvailableProductSelection(setOf("rice", "banana"))
        )
        assertEquals(
            NutritionGenerationIssue.NOT_ENOUGH_CARBS,
            validateAvailableProductSelection(setOf("chicken", "banana"))
        )
        assertEquals(
            NutritionGenerationIssue.NOT_ENOUGH_FRUITS_OR_VEGETABLES,
            validateAvailableProductSelection(setOf("chicken", "rice"))
        )
    }

    @Test
    fun testPreferredIngredientsBiasMealGenerationWhenPossible() {
        val plan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 1,
            weightKg = 80,
            preferredIngredientKeys = setOf("eggs", "rice", "banana")
        )

        val ingredientKeys = plan.days.first().meals
            .flatMap { it.ingredients }
            .map { it.ingredient.key }
            .toSet()

        assertTrue(ingredientKeys.any { it in setOf("eggs", "rice", "banana") })
    }

    @Test
    fun testGeneratedPlanUsesWeightBasedCaloriesAndDayTypes() {
        val plan = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80)

        assertEquals(3, plan.days.size)
        assertEquals(listOf(1, 2, 3), plan.days.map { it.programDay })
        assertEquals(
            listOf(NutritionDayType.TRAINING, NutritionDayType.LIGHT, NutritionDayType.TRAINING),
            plan.days.map { it.dayType }
        )
        assertEquals(listOf(3300, 3100, 3300), plan.days.map { it.targetCalories })
        assertTrue(plan.days.first().meals.any { it.type == NutritionMealType.POST_WORKOUT })
        assertTrue(plan.days[1].meals.any { it.type == NutritionMealType.SNACK && it.optional })
    }

    @Test
    fun testPlanGenerationSupportsOneThreeAndSevenDays() {
        assertEquals(1, generateNutritionPlan(seed = 0, startDay = 1, daysCount = 1, weightKg = 80).days.size)
        assertEquals(3, generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80).days.size)
        assertEquals(7, generateNutritionPlan(seed = 0, startDay = 1, daysCount = 7, weightKg = 80).days.size)
    }

    @Test
    fun testExcludedFoodsAreRemovedFromMeals() {
        val plan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 3,
            weightKg = 80,
            excludedIngredientKeys = setOf("tuna", "nuts")
        )

        val ingredientKeys = plan.days
            .flatMap { day -> day.meals }
            .flatMap { meal -> meal.ingredients }
            .map { ingredient -> ingredient.ingredient.key }
            .toSet()

        assertTrue("tuna" !in ingredientKeys)
        assertTrue("nuts" !in ingredientKeys)
    }

    @Test
    fun testExcludedFoodsAreNotReintroducedByFallbackSelection() {
        val plan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 3,
            weightKg = 80,
            excludedIngredientKeys = setOf("chicken", "eggs", "tuna")
        )

        val ingredientKeys = plan.days
            .flatMap { day -> day.meals }
            .flatMap { meal -> meal.ingredients }
            .map { ingredient -> ingredient.ingredient.key }
            .toSet()

        assertTrue("chicken" !in ingredientKeys)
        assertTrue("eggs" !in ingredientKeys)
        assertTrue("tuna" !in ingredientKeys)
    }

    @Test
    fun testReplacementKeepsMealTypeAndProfileCloseToOriginalCalories() {
        val originalPlan = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80)
        val originalDay = originalPlan.days.first()
        val originalMeal = originalDay.meals.first { it.type == NutritionMealType.BREAKFAST }
        val replacementId = findReplacementMealTemplateId(originalMeal, emptySet())

        checkNotNull(replacementId)

        val replacedPlan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 3,
            weightKg = 80,
            replacements = mapOf(nutritionReplacementKey(originalDay.programDay, originalMeal.type) to replacementId)
        )
        val replacedMeal = replacedPlan.days.first().meals.first { it.type == NutritionMealType.BREAKFAST }

        assertNotEquals(originalMeal.templateId, replacedMeal.templateId)
        assertEquals(originalMeal.type, replacedMeal.type)
        assertEquals(originalMeal.mealType, replacedMeal.mealType)
        assertTrue(kotlin.math.abs(originalMeal.calories - replacedMeal.calories) <= 100)
    }

    @Test
    fun testShoppingListChangesAfterReplacement() {
        val originalPlan = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80)
        val mealToReplace = originalPlan.days.first().meals.first { it.mealType == MealType.BALANCED }
        val replacementId = findReplacementMealTemplateId(mealToReplace, emptySet())

        checkNotNull(replacementId)

        val replacedPlan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 3,
            weightKg = 80,
            replacements = mapOf(nutritionReplacementKey(originalPlan.days.first().programDay, mealToReplace.type) to replacementId)
        )

        assertNotEquals(originalPlan.shoppingList, replacedPlan.shoppingList)
    }

    @Test
    fun testTodayCompletionPercentUsesCurrentDayMeals() {
        val today = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80).days.first()

        assertEquals(0, calculateNutritionCompletionPercent(today, emptySet()))
        assertEquals(50, calculateNutritionCompletionPercent(today, setOf("breakfast", "dinner")))
        assertEquals(100, calculateNutritionCompletionPercent(today, today.meals.map { it.type.key }.toSet()))
    }

    @Test
    fun testTodayCookingInstructionsStayShortAndActionable() {
        val plan = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 3, weightKg = 80)

        plan.days.first().meals.forEach { meal ->
            val instructions = getTodayCookingInstructionResIds(meal)
            assertTrue(instructions.size in 2..3)
        }
    }

    @Test
    fun testMealCanonicalOrdering() {
        val types = listOf(
            NutritionMealType.SNACK,
            NutritionMealType.DINNER,
            NutritionMealType.LUNCH,
            NutritionMealType.BREAKFAST,
            NutritionMealType.POST_WORKOUT
        )
        val sorted = types.sortedBy { it.orderIndex }
        assertEquals(
            listOf(
                NutritionMealType.BREAKFAST,
                NutritionMealType.LUNCH,
                NutritionMealType.DINNER,
                NutritionMealType.POST_WORKOUT,
                NutritionMealType.SNACK
            ),
            sorted
        )
    }
}
