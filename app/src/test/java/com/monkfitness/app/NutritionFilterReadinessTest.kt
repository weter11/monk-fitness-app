package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The filter UI does not exist yet, so the only thing that can be true today is that its data is ready:
 * a food's pork or lactose tag must mean what the filter will read it to mean, and each filter must
 * leave enough distinct dishes for every slot of a seven-day cycle. A filter that empties a slot would
 * only be discovered on a user's phone otherwise.
 */
class NutritionFilterReadinessTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()

        /** What the two tags mean, stated once: dairy foods carry lactose, cured pork is pork. */
        private val DAIRY = setOf("milk", "yogurt", "cottage_cheese")
        private val PORK = setOf("ham")

        private val FILTERS = linkedMapOf(
            "no pork" to PORK,
            "no lactose" to DAIRY,
            "both" to DAIRY + PORK
        )

        private const val SEEDS = 24
    }

    private val catalogue = parseCatalogue(NutritionCatalogueFixture.asset("catalogue.json").readText())

    @Test
    fun theLactoseTagMarksExactlyTheDairyFoods() {
        assertEquals(DAIRY, catalogue.ingredients.filter { it.lactose }.map { it.key }.toSet())
    }

    @Test
    fun thePorkTagMarksExactlyThePorkFoods() {
        assertEquals(PORK, catalogue.ingredients.filter { it.pork }.map { it.key }.toSet())
    }

    @Test
    fun everyFilterLeavesEnoughDishesForASevenDayCycleWithoutARepeat() {
        val report = StringBuilder("filter,slot,days_in_cycle,templates_available,result\n")

        FILTERS.forEach { (name, excluded) ->
            val available = catalogue.templates
                .filter { template -> template.ingredients.none { it.ingredient.key in excluded } }
                .groupBy { it.type }

            repeat(SEEDS) { seed ->
                // 1 is a training day, so the cycle is the one the app builds from the default week.
                val plan = generateNutritionPlan(
                    seed = seed,
                    startDay = 1,
                    daysCount = 7,
                    energy = NutritionProfiles.energy(NutritionProfiles.man(80)),
                    excludedIngredientKeys = excluded
                )

                val bySlot = plan.days.flatMap { it.meals }.groupBy { it.type }
                bySlot.forEach { (slot, meals) ->
                    assertFalse(
                        "$name, seed $seed: ${slot.key} serves food a ${name} plan must not contain",
                        meals.any { meal -> meal.ingredients.any { it.ingredient.key in excluded } }
                    )
                    assertEquals(
                        "$name, seed $seed: ${slot.key} repeats a dish inside a seven-day cycle",
                        meals.size,
                        meals.map { it.templateId }.toSet().size
                    )
                    if (seed == 0) {
                        report.append(
                            "$name,${slot.key},${meals.size},${available[slot].orEmpty().size}," +
                                "distinct ${meals.map { it.templateId }.toSet().size}\n"
                        )
                    }
                }
            }
        }

        println(report)
        assertTrue("no plan was generated", report.isNotEmpty())
    }

    @Test
    fun theSlotThatEatsEveryDayKeepsAFullWeekOfDishesUnderEveryFilter() {
        // Breakfast, lunch and dinner appear on all seven days; the other two slots appear fewer times.
        val everyday = catalogue.templates.filter { it.type.name in setOf("BREAKFAST", "LUNCH", "DINNER") }
        FILTERS.forEach { (name, excluded) ->
            everyday.groupBy { it.type }.forEach { (slot, templates) ->
                val left = templates.count { template ->
                    template.ingredients.none { it.ingredient.key in excluded }
                }
                assertTrue(
                    "$name: ${slot.key} has only $left dishes for a seven-day cycle",
                    left >= 7
                )
            }
        }
    }

    @Test
    fun theCatalogueReportsItsOwnSize() {
        // The statistics screen will read these; they must be the real counts, not a constant.
        assertEquals(catalogue.ingredients.size, NutritionCatalogue.ingredientCount)
        assertEquals(catalogue.templates.size, NutritionCatalogue.dishCount)
        assertTrue(NutritionCatalogue.ingredientCount >= 26)
        assertTrue(NutritionCatalogue.dishCount >= 60)
    }
}
