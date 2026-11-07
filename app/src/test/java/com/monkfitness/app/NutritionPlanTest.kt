package com.monkfitness.app

import com.monkfitness.app.data.model.NUTRITION_PROGRAM_DAY_CAP
import com.monkfitness.app.data.model.NutritionCalorieBasis
import com.monkfitness.app.data.model.NutritionDayStatus
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.MealType
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.calculateNutritionCompletionPercent
import com.monkfitness.app.data.model.calorieTargetGap
import com.monkfitness.app.data.model.NutritionGenerationIssue
import com.monkfitness.app.data.model.findReplacementMealTemplateId
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.getTodayCookingInstructionResIds
import com.monkfitness.app.data.model.isProgramDayCapped
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionCycleDayNumber
import com.monkfitness.app.data.model.nutritionReplacementKey
import com.monkfitness.app.data.model.orderedForDisplay
import com.monkfitness.app.data.model.statusOn
import com.monkfitness.app.data.model.toMealEntities
import com.monkfitness.app.data.model.validateAvailableProductSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
    fun testDeliveredCaloriesAndTargetGapAgreeWithMeals() {
        listOf(90, 60).forEach { weight ->
            listOf(1, 2, 4).forEach { startDay ->
                val day = generateNutritionPlan(
                    seed = 20260101,
                    startDay = startDay,
                    daysCount = 1,
                    weightKg = weight
                ).days.single()

                assertEquals(day.meals.sumOf { it.calories }, day.totalCalories)
                val gap = day.calorieTargetGap()
                if (gap == null) {
                    assertTrue(kotlin.math.abs(day.targetCalories - day.totalCalories) * 100 <= day.targetCalories * 5)
                } else {
                    assertEquals(day.targetCalories - day.totalCalories, gap)
                    assertTrue(kotlin.math.abs(gap) * 100 > day.targetCalories * 5)
                }
                if (weight == 90) {
                    assertNotNull("90 kg day ${day.dayType} must state the gap", gap)
                    assertTrue(gap!! > 0)
                }
            }
        }
    }

    @Test
    fun testReloadedCycleKeepsCaloriesAsPlannedTotal() {
        val plan = generateNutritionPlan(seed = 7, startDay = 1, daysCount = 3, weightKg = 90)
        val reloaded = mealEntitiesToNutritionPlan(11L, plan.toMealEntities(11L))

        reloaded.days.forEach { day ->
            assertEquals(NutritionCalorieBasis.PLANNED_TOTAL, day.calorieBasis)
            assertEquals(day.totalCalories, day.targetCalories)
            assertNull(day.calorieTargetGap())
        }
    }

    @Test
    fun testCycleDayNumberAndTodayMarkingForAgedCycles() {
        val start = LocalDate.of(2026, 11, 1)
        for (k in 0..4) {
            val expected = if (k < 3) k + 1 else null
            assertEquals(expected, nutritionCycleDayNumber(start, start.plusDays(k.toLong()), 3))
        }

        val plan = generateNutritionPlan(seed = 1, startDay = 1, daysCount = 3, weightKg = 80)
        val todayDayNumber = nutritionCycleDayNumber(start, start.plusDays(2), 3)

        assertEquals(
            listOf(NutritionDayStatus.PAST, NutritionDayStatus.PAST, NutritionDayStatus.TODAY),
            plan.days.map { it.statusOn(todayDayNumber) }
        )
        assertEquals(listOf(3, 1, 2), plan.orderedForDisplay(todayDayNumber).map { it.dayNumber })
    }

    @Test
    fun testCappedProgramDayStillNumbersTheCyclesOwnDays() {
        val plan = generateNutritionPlan(seed = 3, startDay = 56, daysCount = 5, weightKg = 80)

        assertTrue(plan.days.all { it.programDay == NUTRITION_PROGRAM_DAY_CAP })
        assertTrue(plan.days.all { it.isProgramDayCapped() })
        assertEquals(listOf(1, 2, 3, 4, 5), plan.days.map { it.dayNumber })
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
