package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.nutritionDietaryExcludedKeys
import com.monkfitness.app.data.model.nutritionIngredientsByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * What the two dietary switches promise: the tag becomes an exclusion set, and every plan built from that
 * set leaves the tagged food out of both the meals and the shopping list. The selection and scaling are
 * the ones the app already had, so this test also states that a filter narrows the menu without breaking
 * the variety rules a cycle relies on.
 */
class NutritionDietaryFilterTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()

        private const val SEEDS = 24
        private const val WEIGHT_KG = 80

        /** Plausible personal exclusions that still leave every slot a seven-day cycle needs. */
        private val MANUAL_EXCLUSIONS = setOf("pasta", "cod")
    }

    /** The four switch positions, read the way the app reads them. */
    private val combinations: Map<String, Set<String>> = linkedMapOf(
        "both off" to nutritionDietaryExcludedKeys(noPork = false, lactoseFree = false),
        "no pork" to nutritionDietaryExcludedKeys(noPork = true, lactoseFree = false),
        "lactose-free" to nutritionDietaryExcludedKeys(noPork = false, lactoseFree = true),
        "both on" to nutritionDietaryExcludedKeys(noPork = true, lactoseFree = true)
    )

    @Test
    fun eachSwitchPositionBecomesTheExclusionSetItsTagsDescribe() {
        assertEquals(emptySet<String>(), combinations.getValue("both off"))
        assertEquals(setOf("ham"), combinations.getValue("no pork"))
        assertEquals(setOf("cottage_cheese", "milk", "yogurt"), combinations.getValue("lactose-free"))
        assertEquals(
            setOf("cottage_cheese", "ham", "milk", "yogurt"),
            combinations.getValue("both on")
        )
        assertTrue(
            "the set must name shipped foods, not a second list of keys",
            combinations.getValue("both on").all { it in nutritionIngredientsByKey }
        )
    }

    @Test
    fun noPlanUnderAnyFilterServesATaggedFoodOrRepeatsADish() {
        combinations.forEach { (name, excluded) ->
            for (seed in 0 until SEEDS) {
                listOf(1, 3, 7).forEach { days ->
                    val plan = generateNutritionPlan(
                        seed = seed,
                        startDay = 1,
                        daysCount = days,
                        weightKg = WEIGHT_KG,
                        excludedIngredientKeys = excluded
                    )
                    val where = "$name, seed $seed, $days days"

                    assertEquals("$where: the plan's length", days, plan.days.size)
                    plan.days.forEach { day ->
                        assertFalse("$where: day ${day.dayNumber} has no meals", day.meals.isEmpty())
                        val served = day.meals.flatMap { meal ->
                            meal.ingredients.map { it.ingredient.key }
                        }
                        assertTrue(
                            "$where: day ${day.dayNumber} serves $excluded",
                            served.none { it in excluded }
                        )
                    }
                    val rows = plan.shoppingList.values.flatten().map { it.ingredient.key }
                    assertTrue("$where: the shopping list carries $excluded", rows.none { it in excluded })

                    val dishes = plan.days.flatMap { day -> day.meals }.map { it.templateId }
                    assertEquals("$where: a dish repeats inside the cycle", dishes.size, dishes.toSet().size)
                }
            }
        }
    }

    @Test
    fun bothFiltersWithPersonalExclusionsStillBuildASevenDayCycleWithoutARepeat() {
        val excluded = MANUAL_EXCLUSIONS + combinations.getValue("both on")

        repeat(SEEDS) { seed ->
            val plan = generateNutritionPlan(
                seed = seed,
                startDay = 1,
                daysCount = 7,
                weightKg = WEIGHT_KG,
                excludedIngredientKeys = excluded
            )

            assertEquals("seed $seed: a seven-day cycle", 7, plan.days.size)
            plan.days.forEach { day ->
                assertEquals("seed $seed: day ${day.dayNumber} lost a slot", 4, day.meals.size)
                val served = day.meals.flatMap { meal -> meal.ingredients.map { it.ingredient.key } }
                assertTrue("seed $seed: day ${day.dayNumber} serves $excluded", served.none { it in excluded })
            }
            val dishes = plan.days.flatMap { day -> day.meals }.map { it.templateId }
            assertEquals("seed $seed: a dish repeats inside the cycle", dishes.size, dishes.toSet().size)
            val rows = plan.shoppingList.values.flatten().map { it.ingredient.key }
            assertTrue("seed $seed: the shopping list carries $excluded", rows.none { it in excluded })
        }
    }

    /**
     * Existing behaviour, pinned: a slot whose every dish is excluded is left out of the day rather than
     * filled with a tagged dish — the rest of the day still builds and prices itself.
     */
    @Test
    fun aSlotWhoseWholeMenuIsExcludedIsOmittedInsteadOfFilledWithATaggedDish() {
        // Every lunch dish carries one of these four, so lunch has no candidate while the other slots do.
        val lunchOnlyExclusions = setOf("bread", "chicken", "potatoes", "rice")

        repeat(SEEDS) { seed ->
            val plan = generateNutritionPlan(
                seed = seed,
                startDay = 1,
                daysCount = 1,
                weightKg = WEIGHT_KG,
                excludedIngredientKeys = lunchOnlyExclusions
            )

            val day = plan.days.single()
            assertEquals("seed $seed: the other three slots still build", 3, day.meals.size)
            assertTrue(
                "seed $seed: a slot with no dish left was filled anyway",
                day.meals.none { it.type == NutritionMealType.LUNCH }
            )
            assertEquals(
                "seed $seed: a day's calories are still its meals",
                day.meals.sumOf { it.calories },
                day.totalCalories
            )
            assertTrue("seed $seed: a built day has no calories", day.totalCalories > 0)
        }
    }

    /**
     * The floor of that rule: a day whose whole menu is excluded is still a day — no meal, no crash, and a
     * total of zero. A filter (or a user's own list) can narrow the plan this far; nothing here may throw.
     */
    @Test
    fun aDayWhoseWholeMenuIsExcludedIsAnEmptyDayRatherThanAFailure() {
        val everyFood = nutritionIngredientsByKey.keys

        val plan = generateNutritionPlan(
            seed = 0,
            startDay = 1,
            daysCount = 1,
            weightKg = WEIGHT_KG,
            excludedIngredientKeys = everyFood
        )

        val day = plan.days.single()
        assertTrue("a day with no dish left was filled anyway", day.meals.isEmpty())
        assertEquals("an empty day still reports a target", 0, day.totalCalories)
        assertTrue("an empty day must still have a plan row", plan.shoppingList.isEmpty())
    }

    /**
     * The report's accuracy table: over the same 24 seeds, how close a seven-day plan's own totals land on
     * the day's target with each switch position. Scaling is not changed here; a filter narrows the dishes
     * the scale is applied to, and this states that it does not collapse the day's accuracy.
     */
    @Test
    fun everyFilterKeepsDaysAsCloseToTheirTargetsAsAnUnfilteredPlanDoes() {
        val table = combinations.map { (name, excluded) -> name to accuracyUnder(excluded) }

        val report = StringBuilder("filter,mean_kcal_off,worst_kcal_off,mean_protein_off,worst_protein_off\n")
        table.forEach { (name, row) ->
            report.append("$name,${row.meanKcal},${row.worstKcal},${row.meanProtein},${row.worstProtein}\n")
        }
        println(report)

        val unfiltered = table.first { it.first == "both off" }.second
        table.drop(1).forEach { (name, row) ->
            assertTrue(
                "$name: mean calorie deviation ${row.meanKcal} against the unfiltered ${unfiltered.meanKcal}",
                row.meanKcal <= unfiltered.meanKcal * 3 / 2 + 50
            )
            assertTrue(
                "$name: mean protein deviation ${row.meanProtein} against the unfiltered ${unfiltered.meanProtein}",
                row.meanProtein <= unfiltered.meanProtein * 3 / 2 + 10
            )
        }
        assertEquals("one table row per switch position", 4, table.size)
    }

    private data class Accuracy(val meanKcal: Int, val worstKcal: Int, val meanProtein: Int, val worstProtein: Int)

    private fun accuracyUnder(excludedIngredientKeys: Set<String>): Accuracy {
        val kcalDeviations = mutableListOf<Int>()
        val proteinDeviations = mutableListOf<Int>()

        repeat(SEEDS) { seed ->
            val plan = generateNutritionPlan(
                seed = seed,
                startDay = 1,
                daysCount = 7,
                weightKg = WEIGHT_KG,
                excludedIngredientKeys = excludedIngredientKeys
            )
            plan.days.forEach { day ->
                val proteinTarget = calculateMuscleGainNutritionTargets(
                    weightKg = WEIGHT_KG,
                    heightCm = null,
                    dayType = day.dayType
                ).let { (it.proteinMinGrams + it.proteinMaxGrams) / 2 }

                kcalDeviations += abs(day.totalCalories - day.targetCalories)
                proteinDeviations += abs(day.totalProteinGrams - proteinTarget)
            }
        }

        return Accuracy(
            meanKcal = kcalDeviations.average().toInt(),
            worstKcal = kcalDeviations.max(),
            meanProtein = proteinDeviations.average().toInt(),
            worstProtein = proteinDeviations.max()
        )
    }

    /** The catalogue's own slot pools, so a shrinking menu is visible before a user meets it. */
    @Test
    fun everySlotKeepsEnoughDishesForASevenDayCycleUnderEachFilter() {
        val report = StringBuilder("filter,slot,templates_left\n")
        val slotsNeedingSeven = setOf(NutritionMealType.BREAKFAST, NutritionMealType.LUNCH, NutritionMealType.DINNER)

        combinations.forEach { (name, excluded) ->
            NutritionCatalogue.templates.groupBy { it.type }.forEach { (slot, dishes) ->
                val left = dishes.count { dish -> dish.ingredients.none { it.ingredient.key in excluded } }
                report.append("$name,${slot.key},$left\n")
                if (slot in slotsNeedingSeven) {
                    assertTrue("$name: ${slot.key} has only $left dishes for seven days", left >= 7)
                }
            }
        }

        println(report)
        assertTrue("no pool was counted", report.lines().size > 4)
    }
}
