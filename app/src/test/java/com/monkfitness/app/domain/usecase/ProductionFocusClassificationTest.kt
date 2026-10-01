package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production focus classification, measured against the **real** shipped catalogue.
 *
 * P23 refused to infer membership from `ExerciseCategory`, `ExerciseSubCategory` or
 * `exerciseToFamiliesMap`, and left the gap open for this stage to close. Closing it means *stating*
 * the membership, and the failure mode of a stated table is the opposite of a computed one: not that
 * a rule fabricates a focus, but that a table quietly goes stale, admits an id the catalogue does not
 * have, or states nothing at all for a shipped exercise. Each of those is silent — a plan still
 * appears — so each is asserted here against `WorkoutGenerator().getExerciseLibrary()` rather than
 * about a fixture.
 *
 * The census is **measured, never hardcoded**: the exercise count is read from the catalogue, so
 * adding or removing an exercise makes the coverage assertions move with it rather than needing an
 * edit here.
 */
class ProductionFocusClassificationTest {

    /** The real shipped catalogue. Read through the same call production reads it through. */
    private val shipped: List<com.monkfitness.app.data.model.Exercise> =
        WorkoutGenerator().getExerciseLibrary()

    // ------------------------------------------------------------------ coverage of the real catalogue

    @Test
    fun everyShippedExerciseStatesTheFocusesItTrains() {
        val unclassified = shipped.map { it.id }.filterNot { id ->
            ProductionFocusClassification.classifies(id)
        }

        assertTrue(
            "the real catalogue is not empty — a coverage claim over nothing proves nothing " +
                "(found ${shipped.size})",
            shipped.isNotEmpty()
        )
        assertEquals(
            "every one of the app's ${shipped.size} shipped exercises states the focuses it trains. " +
                "An unclassified exercise is not in the plan and the user is not told: $unclassified",
            emptyList<String>(),
            unclassified
        )
    }

    @Test
    fun theClassificationNamesNoExerciseTheCatalogueDoesNotHave() {
        val shippedIds = shipped.map { it.id }.toSet()
        val unknown = ProductionFocusClassification.classifiedExerciseIds.filterNot { id ->
            id in shippedIds
        }

        assertEquals(
            "a table entry for an exercise that no longer exists is a classification of nothing: " +
                "$unknown",
            emptyList<String>(),
            unknown
        )
    }

    @Test
    fun noShippedExerciseIsLeftWithoutAStatedFocus() {
        val empty = shipped.map { it.id }.filter { id ->
            ProductionFocusClassification.focusesOf(id)?.isEmpty() != false
        }

        assertEquals(
            "an exercise whose focuses are not stated trains *nothing* stated, which is a different " +
                "claim from 'it trains something' and is refused by GenerationCandidate itself. " +
                "Found: $empty",
            emptyList<String>(),
            empty
        )
    }

    @Test
    fun noExerciseIsStatedAsTrainingTheWholeVocabulary() {
        val everything = Focus.entries.toSet()
        val overbroad = ProductionFocusClassification.classifiedExerciseIds.filter { id ->
            ProductionFocusClassification.focusesOf(id) == everything
        }

        assertEquals(
            "\"trains everything\" is a substitution for a missing answer, not a training fact — it " +
                "makes an exercise selectable for every focus and removes the plan's focus structure " +
                "entirely. Found: $overbroad",
            emptyList<String>(),
            overbroad
        )
    }

    @Test
    fun everyStatedFocusIsOneOfTheSevenTheVocabularyDefines() {
        val legal = Focus.entries.toSet()
        val illegal = ProductionFocusClassification.classifiedExerciseIds.flatMap { id ->
            ProductionFocusClassification.focusesOf(id).orEmpty()
                .filterNot { it in legal }
                .map { "$id -> $it" }
        }

        assertEquals(
            "only the seven §8 focuses may be stated; anything else is a second vocabulary silently " +
                "in force. Found: $illegal",
            emptyList<String>(),
            illegal
        )
    }

    // ------------------------------------------------------------------ it is data, not a rule

    @Test
    fun theClassificationIsDeterministicAndOrderedByTheVocabulary() {
        // Determinism in two senses, because a plan is only reproducible if both hold: the same id
        // yields the same set on every call, and the set's iteration order is the vocabulary's own
        // rather than a hash's.
        ProductionFocusClassification.classifiedExerciseIds.forEach { id ->
            val first = ProductionFocusClassification.focusesOf(id)
            repeat(5) {
                assertEquals(
                    "the same id must classify the same on every read ($id)",
                    first,
                    ProductionFocusClassification.focusesOf(id)
                )
            }
            assertEquals(
                "and the stated focuses are held in the canonical order ($id)",
                FocusPlan.canonical(first!!),
                first.toList()
            )
        }
    }

    @Test
    fun theClassificationIsNotDerivedFromTheCataloguesOwnGroupings() {
        // The falsifiable half of "this is explicit data". If membership were a function of the
        // category, then exercises sharing a category would share a focus set exactly — and the real
        // catalogue has exercises of one category that train different things, so a classifier built
        // on the category would have to give one of them a wrong focus.
        val byCategory = shipped.groupBy { it.category }
        val mixedCategory = byCategory.filter { (_, exercises) ->
            exercises.map { it.id }
                .mapNotNull { ProductionFocusClassification.focusesOf(it) }
                .distinct()
                .size > 1
        }

        assertTrue(
            "the sweep must actually cover the real catalogue — a renamed field would empty this set " +
                "and the claim would pass without having looked at anything",
            mixedCategory.isNotEmpty()
        )
        mixedCategory.forEach { (category, exercises) ->
            val stated = exercises.associate { it.id to ProductionFocusClassification.focusesOf(it.id) }
            assertTrue(
                "a category does not determine membership: $category states $stated — a `when (category)` " +
                    "classifier could not produce this, which is the point",
                stated.values.toSet().size > 1
            )
        }
    }

    @Test
    fun theClassificationCrossesTheCataloguesVocabulariesRatherThanFollowingOne() {
        // The three focuses the catalogue never names (P23's finding) must nonetheless be planned for,
        // and the two categories that *share a name* with a focus must not have absorbed it. If the
        // classification were a lookup, PUSH and PULL could not appear at all.
        val stated = ProductionFocusClassification.classifiedExerciseIds.flatMap {
            ProductionFocusClassification.focusesOf(it).orEmpty()
        }.toSet()

        assertEquals(
            "all seven §8 focuses are reachable from the shipped catalogue, including the three the " +
                "catalogue's own vocabularies never name",
            Focus.entries.toSet(),
            stated
        )
        // And the category that shares a *name* with a focus did not decide membership: several
        // POSTURE-category exercises are pulling or conditioning work as well, and a
        // `when (category)` classifier would have had to call them posture-only.
        val postureCategory = shipped.filter { it.category.name == "POSTURE" }
        val alsoSomethingElse = postureCategory.map { it.id }.filter { id ->
            ProductionFocusClassification.focusesOf(id).orEmpty() - Focus.POSTURE != emptySet<Focus>()
        }
        assertTrue(
            "at least one POSTURE-category exercise states a focus beyond POSTURE, so the category " +
                "did not decide it: $postureCategory / $alsoSomethingElse",
            alsoSomethingElse.isNotEmpty()
        )
    }

    // ------------------------------------------------------------------ the port contract

    @Test
    fun anUnknownIdIsStatedAsUnclassifiedRatherThanGivenAFocus() {
        assertNull(
            "'no-such-exercise' states nothing, which is what the boundary reports as unclassified",
            ProductionFocusClassification.focusesOf("no-such-exercise")
        )
        assertFalse(
            "and it is not counted as classified either",
            ProductionFocusClassification.classifies("no-such-exercise")
        )
    }

    @Test
    fun theClassificationIsThePortsOwnContractAndIsSubstitutable() {
        // The service reaches it through P23's port, so a test — or a future user-facing Goals &
        // Focus editor — can state a different classification without touching this one.
        val port: GenerationFocusSource = ProductionFocusClassification
        val pushups = port.focusesOf("pushups")

        assertNotNull("the port reads the table", pushups)
        assertEquals(
            "a standard push-up is pressing work, stated by id rather than by any category rule",
            setOf(Focus.PUSH),
            pushups
        )
    }
}
