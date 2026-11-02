package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.adaptive.engine.FamilyProgression
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production family classification, measured against the catalogue it describes: every family the
 * app ships is classified, exactly the families with an authored ladder climb variants, and every
 * exercise the catalogue holds belongs to a classified family.
 *
 * A family the classification does not know is a family the engine reports instead of adapting, so an
 * omission here is a silently unadapted family — which is what these three claims prevent.
 */
class ProductionFamilyProgressionTest {

    private val catalogue = WorkoutGenerator()

    @Test
    fun everyFamilyTheCatalogueDeclaresIsClassified() {
        val declared = catalogue.getFamilyNames().keys

        assertEquals(
            "the classification covers the catalogue's families exactly: " +
                "unclassified=${declared - ProductionFamilyProgression.classifications.keys} " +
                "invented=${ProductionFamilyProgression.classifications.keys - declared}",
            declared,
            ProductionFamilyProgression.classifications.keys
        )
    }

    @Test
    fun theFamiliesThatClimbVariantsAreExactlyTheOnesWithAnAuthoredLadder() {
        assertEquals(
            "a family is a ladder family because it has a ladder, and the two cannot disagree",
            ProductionProgressionRelationDefinitions.authorisedFamilyIds,
            ProductionFamilyProgression.ladderFamilies
        )
        assertTrue(
            "and every other family is classified as one that advances inside its exercises' ranges",
            ProductionFamilyProgression.classifications.values.count { it == FamilyProgression.WITHIN_EXERCISE } > 0
        )
    }

    @Test
    fun everyCatalogueExerciseBelongsToAClassifiedFamily() {
        val unclassified = catalogue.getExerciseLibrary()
            .filter { exercise -> ProductionFamilyProgression.of(exercise.familyId) == null }
            .map { exercise -> "${exercise.id} → ${exercise.familyId}" }

        assertTrue("every shipped exercise's family is classified, unclassified=$unclassified", unclassified.isEmpty())
    }
}
