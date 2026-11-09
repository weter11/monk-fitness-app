package com.monkfitness.app

import com.monkfitness.app.data.model.NUTRITION_PORTION_CEILING
import com.monkfitness.app.data.model.NutritionCalorieBasis
import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionTargets
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.kcalFor
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionDietaryExcludedKeys
import com.monkfitness.app.data.model.proteinFor
import com.monkfitness.app.data.model.toMealEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a built day owes its targets, measured on the matrix the accuracy reports used: 24 seeds, 1/3/7-day
 * plans, all four filter combinations, three body weights.
 */
class NutritionDayAccuracyTest {

    private class MeasuredDay(
        val planKey: String,
        val weight: Int,
        val day: NutritionDayPlan,
        val targets: NutritionTargets
    )

    companion object {
        private val SEEDS = 0 until 24
        private val DAY_COUNTS = listOf(1, 3, 7)

        /**
         * The bodies the matrix plans for: a light, a typical and a heavy man still gaining, the same man
         * at the 59 kg start of the product's own example, and one at his 70 kg goal — where the plan
         * maintains instead of gaining.
         */
        private val PROFILES = listOf(
            NutritionProfiles.man(55),
            NutritionProfiles.man(59),
            NutritionProfiles.man(70, goalWeightKg = 70),
            NutritionProfiles.man(80),
            NutritionProfiles.man(110)
        )
        private val FILTERS = listOf(
            "none" to (false to false),
            "no pork" to (true to false),
            "lactose free" to (false to true),
            "both" to (true to true)
        )

        private lateinit var measured: List<MeasuredDay>

        @BeforeClass
        @JvmStatic
        fun loadAndMeasure() {
            NutritionCatalogueFixture.load()
            val days = mutableListOf<MeasuredDay>()
            SEEDS.forEach { seed ->
                DAY_COUNTS.forEach { dayCount ->
                    FILTERS.forEach { (name, flags) ->
                        PROFILES.forEach { profile ->
                            val energy = NutritionProfiles.energy(profile)
                            val plan = generateNutritionPlan(
                                seed = seed,
                                startDay = 1,
                                daysCount = dayCount,
                                energy = energy,
                                excludedIngredientKeys = nutritionDietaryExcludedKeys(flags.first, flags.second)
                            )
                            val key = "seed $seed, $dayCount days, $name, ${profile.weightKg}kg"
                            plan.days.forEach { day ->
                                days += MeasuredDay(
                                    planKey = key,
                                    weight = profile.weightKg ?: 0,
                                    day = day,
                                    targets = energy.targetsFor(day.dayType)
                                )
                            }
                        }
                    }
                }
            }
            measured = days
        }
    }

    @Test
    fun everyDayLandsWithinTheCalorieTolerance() {
        val off = measured.map { abs(it.day.totalCalories - it.day.targetCalories).toDouble() / it.day.targetCalories }
        val withinSeven = off.count { it <= 0.07 } * 100.0 / off.size

        val report = StringBuilder("profile,days,within_7_percent,worst_percent\n")
        measured.groupBy { it.weight }.toSortedMap().forEach { (weight, days) ->
            val deviations = days.map { abs(it.day.totalCalories - it.day.targetCalories).toDouble() / it.day.targetCalories }
            report.append(
                "$weight,${days.size},%.1f,%.1f\n".format(
                    deviations.count { it <= 0.07 } * 100.0 / deviations.size,
                    deviations.max() * 100
                )
            )
        }
        println(report)

        assertTrue("only $withinSeven% of days are within 7 % of their target", withinSeven >= 90.0)
        assertTrue("the worst day is ${off.max() * 100} % off its target", off.max() <= 0.12)
        measured.forEach {
            assertTrue(
                "${it.planKey} day ${it.day.dayNumber}: ${it.day.totalCalories} kcal over a ${it.day.targetCalories} target",
                it.day.totalCalories <= it.day.targetCalories * 1.07
            )
        }
    }

    /**
     * The dish set is richer in protein than the day targets are, so a day that needs every calorie the
     * dishes can give can only be kept under the top of the range by serving implausible portions. Those
     * days are allowed to run high; the rest of the matrix must sit inside the range.
     */
    @Test
    fun proteinLandsInsideItsRangeOnMostDays() {
        val inRange = measured.count {
            it.day.totalProteinGrams in it.targets.proteinMinGrams..it.targets.proteinMaxGrams
        }
        val share = inRange * 100.0 / measured.size
        val meanOff = measured.map {
            abs(it.day.totalProteinGrams - (it.targets.proteinMinGrams + it.targets.proteinMaxGrams) / 2.0)
        }.average()

        val report = StringBuilder("profile,days,protein_in_range_percent\n")
        measured.groupBy { it.weight }.toSortedMap().forEach { (weight, days) ->
            val inside = days.count { it.day.totalProteinGrams in it.targets.proteinMinGrams..it.targets.proteinMaxGrams }
            report.append("$weight,${days.size},%.1f\n".format(inside * 100.0 / days.size))
        }
        println(report)

        assertTrue("protein is inside its range on only $share% of days", share >= 80.0)
        assertTrue("protein is $meanOff g from the middle of its range on average", meanOff <= 15.0)
    }

    @Test
    fun noPortionLeavesItsCeilingAndNoMealOutgrowsItsDish() {
        var largestAmount = 0
        var largestMultiple = 0.0

        measured.forEach { measuredDay ->
            measuredDay.day.meals.forEach { meal ->
                val template = NutritionCatalogue.templatesById.getValue(meal.templateId)
                meal.ingredients.forEach { served ->
                    val ceiling = NUTRITION_PORTION_CEILING.getValue(served.ingredient.key)
                    val recipe = template.ingredients.first { it.ingredient.key == served.ingredient.key }
                    assertTrue(
                        "${measuredDay.planKey}: ${served.ingredient.key} served ${served.amount} over a $ceiling ceiling",
                        served.amount <= ceiling
                    )
                    if (served.ingredient.unit != NutritionQuantityUnit.PIECES) {
                        assertTrue(
                            "${measuredDay.planKey}: ${served.ingredient.key} served ${served.amount} for a ${recipe.amount} recipe",
                            served.amount <= recipe.amount * 3.5 + 5
                        )
                        largestAmount = maxOf(largestAmount, served.amount)
                    }
                }
                val multiple = meal.calories.toDouble() / template.calories
                largestMultiple = maxOf(largestMultiple, multiple)
                assertTrue(
                    "${measuredDay.planKey}: ${meal.templateId} at ${meal.calories} kcal is $multiple x its dish",
                    multiple <= 3.5
                )
            }
        }

        assertTrue("no portion was ever scaled", largestAmount > 0)
        assertTrue("the largest meal is only $largestMultiple x its dish", largestMultiple > 1.5)
    }

    @Test
    fun everyDishIsServedOncePerSlotInsideACycle() {
        SEEDS.forEach { seed ->
            val plan = generateNutritionPlan(seed = seed, startDay = 1, daysCount = 7, energy = NutritionProfiles.energy(NutritionProfiles.man(80)))
            NutritionMealType.entries.forEach { slot ->
                val picks = plan.days.mapNotNull { day -> day.meals.firstOrNull { it.type == slot }?.templateId }
                assertEquals("seed $seed: slot $slot repeats a dish", picks.size, picks.distinct().size)
            }
        }
    }

    @Test
    fun theSameSeedBuildsTheSamePlan() {
        SEEDS.forEach { seed ->
            val first = planSignature(generateNutritionPlan(seed = seed, startDay = 1, daysCount = 7, energy = NutritionProfiles.energy(NutritionProfiles.man(80))))
            val second = planSignature(generateNutritionPlan(seed = seed, startDay = 1, daysCount = 7, energy = NutritionProfiles.energy(NutritionProfiles.man(80))))
            assertEquals("seed $seed: the same seed built two different plans", first, second)
        }
    }

    @Test
    fun everyPortionIsAWholePieceOrAFiveGramStep() {
        measured.forEach { measuredDay ->
            measuredDay.day.meals.flatMap { it.ingredients }.forEach { served ->
                if (served.ingredient.unit == NutritionQuantityUnit.PIECES) {
                    assertTrue("${measuredDay.planKey}: ${served.ingredient.key} is ${served.amount} pieces", served.amount >= 1)
                } else {
                    assertEquals(
                        "${measuredDay.planKey}: ${served.ingredient.key} is ${served.amount} off a 5 g step",
                        0,
                        served.amount % 5
                    )
                }
            }
        }
    }

    @Test
    fun aMealsNumbersAreTheNumbersOfItsPortions() {
        measured.forEach { measuredDay ->
            measuredDay.day.meals.forEach { meal ->
                assertEquals(
                    "${measuredDay.planKey}: ${meal.templateId} calories",
                    meal.ingredients.sumOf { it.ingredient.kcalFor(it.amount) }.roundToInt(),
                    meal.calories
                )
                assertEquals(
                    "${measuredDay.planKey}: ${meal.templateId} protein",
                    meal.ingredients.sumOf { it.ingredient.proteinFor(it.amount) }.roundToInt(),
                    meal.proteinGrams
                )
            }
        }
    }

    @Test
    fun theShoppingListSellsExactlyWhatTheMealsServe() {
        SEEDS.take(6).forEach { seed ->
            val plan = generateNutritionPlan(seed = seed, startDay = 1, daysCount = 7, energy = NutritionProfiles.energy(NutritionProfiles.man(110)))
            val served = plan.days.flatMap { it.meals }.flatMap { it.ingredients }
                .groupBy { it.ingredient.key }
                .mapValues { (_, amounts) -> amounts.sumOf { it.amount } }
            plan.shoppingList.values.flatten().forEach { item ->
                assertEquals("seed $seed: ${item.ingredient.key}", served.getValue(item.ingredient.key), item.totalAmount)
            }
            assertEquals("seed $seed: a food is served but never sold", served.keys, plan.shoppingList.values.flatten().map { it.ingredient.key }.toSet())
        }
    }

    /**
     * A cycle written to the database keeps the numbers it was written with: loading it never runs the
     * planner again, so a later change to portions cannot re-price a plan a user is already eating.
     */
    @Test
    fun aStoredCycleKeepsTheNumbersItWasSavedWith() {
        val stored = generateNutritionPlan(seed = 11, startDay = 1, daysCount = 3, energy = NutritionProfiles.energy(NutritionProfiles.man(110)))
        val entities = stored.toMealEntities(7L)

        // Build another cycle at another weight, which must not touch what is already stored.
        generateNutritionPlan(seed = 12, startDay = 1, daysCount = 3, energy = NutritionProfiles.energy(NutritionProfiles.man(55)))

        val reloaded = mealEntitiesToNutritionPlan(7L, entities)
        assertEquals(NutritionCalorieBasis.PLANNED_TOTAL, reloaded.days.first().calorieBasis)
        reloaded.days.zip(stored.days).forEach { (loaded, planned) ->
            assertEquals(planned.totalCalories, loaded.totalCalories)
            assertEquals(planned.totalProteinGrams, loaded.totalProteinGrams)
            loaded.meals.zip(planned.meals).forEach { (loadedMeal, plannedMeal) ->
                assertEquals(plannedMeal.calories, loadedMeal.calories)
                assertEquals(plannedMeal.proteinGrams, loadedMeal.proteinGrams)
                assertEquals(
                    plannedMeal.ingredients.map { it.ingredient.key to it.amount },
                    loadedMeal.ingredients.map { it.ingredient.key to it.amount }
                )
            }
        }
    }

    private fun planSignature(plan: NutritionPlan): List<String> = plan.days.flatMap { day ->
        day.meals.map { meal -> "${day.dayNumber}:${meal.type.key}:${meal.templateId}:" + meal.ingredients.joinToString(",") { "${it.ingredient.key}=${it.amount}" } }
    }
}
