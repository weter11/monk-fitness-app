package com.monkfitness.app

import com.monkfitness.app.data.model.defaultWorkoutTypeForDay
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

        /**
         * The fixture was recorded when these thirty were the whole catalogue. The pool is narrowed to
         * them so the comparison is of selection, not of which dishes the wider catalogue now offers.
         */
        private val LEGACY_TEMPLATE_IDS = setOf(
            "breakfast_high_carb_1", "breakfast_high_carb_2",
            "breakfast_high_protein_1", "breakfast_high_protein_2",
            "breakfast_balanced_1", "breakfast_balanced_2",
            "lunch_high_carb_1", "lunch_high_carb_2",
            "lunch_high_protein_1", "lunch_high_protein_2",
            "lunch_balanced_1", "lunch_balanced_2",
            "dinner_high_carb_1", "dinner_high_carb_2",
            "dinner_high_protein_1", "dinner_high_protein_2",
            "dinner_balanced_1", "dinner_balanced_2",
            "post_workout_high_carb_1", "post_workout_high_carb_2",
            "post_workout_high_protein_1", "post_workout_high_protein_2",
            "post_workout_balanced_1", "post_workout_balanced_2",
            "snack_high_carb_1", "snack_high_carb_2",
            "snack_high_protein_1", "snack_high_protein_2",
            "snack_balanced_1", "snack_balanced_2"
        )
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
                energy = NutritionProfiles.energy(NutritionProfiles.man(80)),
                excludedIngredientKeys = exclusions,
                replacements = emptyMap(),
                preferredIngredientKeys = emptySet(),
                cycleId = 0L,
                previousPlan = null,
                workoutTypeForDay = ::defaultWorkoutTypeForDay,
                templateIds = LEGACY_TEMPLATE_IDS
            )
            val actual = plan.days.flatMap { day ->
                day.meals.map { meal -> "${meal.type.key}:${meal.templateId}" }
            }

            assertEquals("seed $seed, $days days from $startDay, excluding $exclusions", expected, actual)
        }
    }
}
