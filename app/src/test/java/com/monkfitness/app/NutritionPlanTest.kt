package com.monkfitness.app

import com.monkfitness.app.data.model.NUTRITION_PROGRAM_DAY_CAP
import com.monkfitness.app.data.model.NutritionCalorieBasis
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionDayStatus
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.MealType
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.calculateNutritionCompletionPercent
import com.monkfitness.app.data.model.calorieTargetGap
import com.monkfitness.app.data.model.NutritionGenerationIssue
import com.monkfitness.app.data.model.findReplacementMealTemplateId
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.getTodayCookingInstructionResIds
import com.monkfitness.app.data.model.isProgramDayCapped
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionCycleDayNumber
import com.monkfitness.app.data.model.nutritionExclusionIngredients
import com.monkfitness.app.data.model.nutritionReplacementKey
import com.monkfitness.app.data.model.nutritionVariety
import com.monkfitness.app.data.model.orderedForDisplay
import com.monkfitness.app.data.model.statusOn
import com.monkfitness.app.data.model.toMealEntities
import com.monkfitness.app.data.model.validateAvailableProductSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate

class NutritionPlanTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadCatalogue() = NutritionCatalogueFixture.load()
    }

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
    fun testReplacementKeepsMealTypeAndProfileAndTheDayOnItsTarget() {
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
        val replacedDay = replacedPlan.days.first()
        val replacedMeal = replacedDay.meals.first { it.type == NutritionMealType.BREAKFAST }

        assertNotEquals(originalMeal.templateId, replacedMeal.templateId)
        assertEquals(originalMeal.type, replacedMeal.type)
        assertEquals(originalMeal.mealType, replacedMeal.mealType)
        // The dish is portioned with the rest of the day, so a lighter dish takes fewer of the day's
        // calories; what the swap must not cost is the day itself.
        assertTrue(
            "replaced day ${replacedDay.totalCalories} against ${replacedDay.targetCalories}",
            kotlin.math.abs(replacedDay.targetCalories - replacedDay.totalCalories) * 100 <= replacedDay.targetCalories * 7
        )
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

    private fun NutritionPlan.templateIds(): List<String> =
        days.flatMap { day -> day.meals.map { "${it.type.key}:${it.templateId}" } }

    private fun planOf(dayCount: Int, vararg templateIds: String): NutritionPlan =
        NutritionPlan(
            days = (1..dayCount).map { dayNumber ->
                NutritionDayPlan(
                    dayNumber = dayNumber,
                    programDay = dayNumber,
                    week = 1,
                    dayType = NutritionDayType.TRAINING,
                    targetCalories = 2000,
                    meals = templateIds.getOrNull(dayNumber - 1)?.let { id ->
                        listOf(
                            NutritionMeal(
                                templateId = id,
                                type = NutritionMealType.BREAKFAST,
                                mealType = MealType.BALANCED,
                                ingredients = emptyList(),
                                calories = 500,
                                proteinGrams = 30
                            )
                        )
                    }.orEmpty()
                )
            }
        )

    @Test
    fun testSelectionIsDeterministicForTheSameSeed() {
        val first = generateNutritionPlan(seed = 42, startDay = 1, daysCount = 7, weightKg = 80)
        val second = generateNutritionPlan(seed = 42, startDay = 1, daysCount = 7, weightKg = 80)

        assertEquals(first.templateIds(), second.templateIds())
    }

    @Test
    fun testDifferentCyclesGetDifferentPlans() {
        val first = generateNutritionPlan(seed = 100, startDay = 1, daysCount = 3, weightKg = 80)
        val second = generateNutritionPlan(seed = 103, startDay = 1, daysCount = 3, weightKg = 80)

        assertNotEquals(first.templateIds(), second.templateIds())
    }

    @Test
    fun testSlotUsesEachTemplateBeforeRepeating() {
        listOf(3, 7).forEach { days ->
            val plan = generateNutritionPlan(seed = 8, startDay = 1, daysCount = days, weightKg = 80)
            NutritionMealType.entries.forEach { slot ->
                val picks = plan.days.mapNotNull { day -> day.meals.firstOrNull { it.type == slot }?.templateId }
                if (picks.isEmpty()) return@forEach
                assertEquals("slot $slot in a $days-day plan", picks.size, picks.distinct().size)
            }
        }
    }

    @Test
    fun testSlotDoesNotRepeatOnConsecutiveDays() {
        val plan = generateNutritionPlan(seed = 9, startDay = 1, daysCount = 7, weightKg = 80)
        val dailySlots = listOf(NutritionMealType.BREAKFAST, NutritionMealType.LUNCH, NutritionMealType.DINNER)

        dailySlots.forEach { slot ->
            val picks = plan.days.map { day -> day.meals.first { it.type == slot }.templateId }
            picks.zipWithNext().forEach { (first, second) -> assertNotEquals("slot $slot", first, second) }
        }
    }

    @Test
    fun testConsecutiveThreeDayCyclesShareNoSlotPosition() {
        val first = generateNutritionPlan(seed = 200, startDay = 1, daysCount = 3, weightKg = 80)
        val second = generateNutritionPlan(
            seed = 203,
            startDay = 4,
            daysCount = 3,
            weightKg = 80,
            previousPlan = first
        )

        var comparisons = 0
        second.days.forEachIndexed { index, day ->
            day.meals.forEach { meal ->
                val previous = first.days[index].meals.firstOrNull { it.type == meal.type } ?: return@forEach
                assertNotEquals("${meal.type} on day ${index + 1}", previous.templateId, meal.templateId)
                comparisons++
            }
        }
        assertTrue(comparisons > 0)
    }

    @Test
    fun testHeavyExclusionsGenerateWithFewerDistinctDishes() {
        val full = generateNutritionPlan(seed = 5, startDay = 1, daysCount = 7, weightKg = 80)
        val reduced = generateNutritionPlan(
            seed = 5,
            startDay = 1,
            daysCount = 7,
            weightKg = 80,
            excludedIngredientKeys = setOf("chicken")
        )
        val empty = generateNutritionPlan(
            seed = 5,
            startDay = 1,
            daysCount = 7,
            weightKg = 80,
            excludedIngredientKeys = nutritionExclusionIngredients.map { it.key }.toSet()
        )

        assertEquals(7, reduced.days.size)
        assertTrue(reduced.days.all { it.meals.isNotEmpty() })
        assertTrue(reduced.days.flatMap { it.meals }.map { it.templateId }.distinct().size <
            full.days.flatMap { it.meals }.map { it.templateId }.distinct().size)
        assertEquals(7, empty.days.size)
        assertTrue(empty.days.all { it.meals.isEmpty() })
    }

    @Test
    fun testVarietyScoreAgreesWithRepeatsFromPreviousCycle() {
        val previous = planOf(3, "a", "b", "c")

        val halfNew = nutritionVariety(planOf(2, "a", "d"), previous)
        assertEquals(2, halfNew.distinctTemplates)
        assertEquals(1, halfNew.repeatedFromPrevious)
        assertEquals(0.5f, halfNew.newRatio)

        val allNew = nutritionVariety(planOf(2, "d", "e"), previous)
        assertEquals(0, allNew.repeatedFromPrevious)
        assertEquals(1f, allNew.newRatio)

        val firstCycle = nutritionVariety(planOf(2, "d", "e"), null)
        assertEquals(0, firstCycle.repeatedFromPrevious)
        assertEquals(1f, firstCycle.newRatio)
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
                // A planned day is portioned onto its target, so the gap it used to state is no longer there.
                assertTrue(
                    "weight $weight day $startDay: ${day.totalCalories} against ${day.targetCalories}",
                    kotlin.math.abs(day.targetCalories - day.totalCalories) * 100 <= day.targetCalories * 7
                )
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
