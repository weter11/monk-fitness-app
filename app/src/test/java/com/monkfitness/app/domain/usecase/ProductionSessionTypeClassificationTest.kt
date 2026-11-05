package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseCategoryFilter
import com.monkfitness.app.data.model.exerciseToFamiliesMap
import com.monkfitness.app.domain.program.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production session-type table, measured against the **real** shipped catalogue.
 *
 * The four memberships are the planning report's own figures (strength 30, mobility + stretching 24,
 * posture 12, hyperlordosis 6), so the counts are asserted as the agreed contract: a catalogue edit that
 * silently reshapes what a chip means is exactly what this fails on. Strength and the two flexibility
 * categories are checked against the catalogue's category, and hyperlordosis against the training-style map,
 * because those are the rules the table was written from.
 */
class ProductionSessionTypeClassificationTest {

    private val shipped: List<Exercise> = WorkoutGenerator().getExerciseLibrary()

    private fun membersOf(type: SessionType): Set<String> =
        shipped.map { it.id }
            .filter { id -> ProductionSessionTypeClassification.sessionTypesOf(id)?.contains(type) == true }
            .toSet()

    @Test
    fun everyShippedExerciseBelongsToAStatedSessionType() {
        val unstated = shipped.map { it.id }.filter { id ->
            ProductionSessionTypeClassification.sessionTypesOf(id).isNullOrEmpty()
        }

        assertTrue("the real catalogue is not empty (found ${shipped.size})", shipped.isNotEmpty())
        assertEquals(
            "a type is a filter, so an exercise this table does not place belongs to no selection and can " +
                "never be generated from one: $unstated",
            emptyList<String>(),
            unstated
        )
    }

    @Test
    fun theTableNamesNoExerciseTheCatalogueDoesNotHave() {
        val shippedIds = shipped.map { it.id }.toSet()

        assertEquals(
            "an entry for an exercise the catalogue no longer has is a filter over nothing",
            emptyList<String>(),
            ProductionSessionTypeClassification.classifiedExerciseIds.filterNot { it in shippedIds }
        )
    }

    @Test
    fun theFourMembershipsAreTheOnesTheChipsPromise() {
        assertEquals(
            "strength is the whole STRENGTH category",
            shipped.filter { it.category == ExerciseCategory.STRENGTH }.map { it.id }.toSet(),
            membersOf(SessionType.STRENGTH)
        )
        assertEquals(
            "mobility folds stretching into it — one chip, two catalogue categories",
            shipped.filter {
                it.category == ExerciseCategory.MOBILITY || it.category == ExerciseCategory.STRETCHING
            }.map { it.id }.toSet(),
            membersOf(SessionType.MOBILITY)
        )
        assertEquals(
            "posture is the POSTURE category",
            shipped.filter { it.category == ExerciseCategory.POSTURE }.map { it.id }.toSet(),
            membersOf(SessionType.POSTURE)
        )
        assertEquals(
            "hyperlordosis is the curated cross-category set the style map states, not the two exercises " +
                "whose sub-category says so",
            exerciseToFamiliesMap.filterValues { families ->
                ExerciseCategoryFilter.HYPERLORDOSIS in families
            }.keys.filter { id -> shippedIds().contains(id) }.toSet(),
            membersOf(SessionType.HYPERLORDOSIS)
        )
    }

    @Test
    fun theMembershipsHoldTheAgreedCounts() {
        assertEquals(30, membersOf(SessionType.STRENGTH).size)
        assertEquals(24, membersOf(SessionType.MOBILITY).size)
        assertEquals(12, membersOf(SessionType.POSTURE).size)
        assertEquals(6, membersOf(SessionType.HYPERLORDOSIS).size)
    }

    @Test
    fun theCuratedSetOverlapsTheCategoriesRatherThanReplacingThem() {
        val hyperlordosis = membersOf(SessionType.HYPERLORDOSIS)

        assertTrue(
            "the curated set spans the catalogue's categories, which is why it can be combined with any of " +
                "them: $hyperlordosis",
            hyperlordosis.map { id -> shipped.first { it.id == id }.category }.toSet().size > 1
        )
        assertTrue(
            "and every one of its exercises keeps the type its own category gives it, so selecting both " +
                "adds no duplicate and selecting either still offers it",
            hyperlordosis.all { id ->
                ProductionSessionTypeClassification.sessionTypesOf(id).orEmpty().size > 1
            }
        )
    }

    @Test
    fun theSetsAreHeldInTheVocabularyOrderAndNoExerciseBelongsToEveryType() {
        ProductionSessionTypeClassification.classifiedExerciseIds.forEach { id ->
            val stated = ProductionSessionTypeClassification.sessionTypesOf(id).orEmpty()

            assertEquals(
                "the same id must classify the same on every read ($id)",
                stated,
                ProductionSessionTypeClassification.sessionTypesOf(id)
            )
            assertEquals(
                "and the stated types are held in the canonical order ($id)",
                SessionType.canonical(stated),
                stated.toList()
            )
            assertTrue(
                "'belongs to every type' would make the chips meaningless: $id states $stated",
                stated.size < SessionType.entries.size
            )
        }
    }

    @Test
    fun anUnknownIdIsStatedAsUnclassifiedRatherThanGivenEveryType() {
        assertNull(ProductionSessionTypeClassification.sessionTypesOf("no-such-exercise"))
        assertFalse(
            "an id the table does not state is not silently admitted to a selection",
            ProductionSessionTypeClassification.sessionTypesOf("no-such-exercise")?.isNotEmpty() == true
        )
    }

    private fun shippedIds(): Set<String> = shipped.map { it.id }.toSet()
}
