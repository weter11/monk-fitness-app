package com.monkfitness.app

import com.monkfitness.app.data.model.generateNutritionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Moving the catalogue into data rewrote every meal's numbers. Selection reads only ids, ingredient
 * keys and profiles, so the dishes — and their positions — must be exactly the ones this build chose
 * before the move; the fixture was recorded from that build.
 */
class NutritionPlanSelectionParityTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()
    }

    @Test
    fun theSameSettingsSelectTheSameTemplatesAsBeforeTheCatalogueBecameData() {
        val rows = NutritionCatalogueFixture
            .csv(NutritionCatalogueFixture.testResource("legacy_selection.csv"))
            .drop(1)

        assertTrue("the fixture is empty", rows.isNotEmpty())
        rows.forEach { row ->
            val seed = row[0].toInt()
            val days = row[1].toInt()
            val startDay = row[2].toInt()
            val exclusions = row[3].split(";").filter { it.isNotBlank() }.toSet()
            val expected = row[4].split(",").filter { it.isNotBlank() }

            val plan = generateNutritionPlan(
                seed = seed,
                startDay = startDay,
                daysCount = days,
                weightKg = 80,
                excludedIngredientKeys = exclusions
            )
            val actual = plan.days.flatMap { day ->
                day.meals.map { meal -> "${meal.type.key}:${meal.templateId}" }
            }

            assertEquals("seed $seed, $days days from $startDay, excluding $exclusions", expected, actual)
        }
    }
}
