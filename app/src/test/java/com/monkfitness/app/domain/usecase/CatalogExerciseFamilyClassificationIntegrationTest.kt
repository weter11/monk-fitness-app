package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import com.monkfitness.app.domain.workout.AdaptiveCompletion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production claim, measured on a real database with the real repositories and the **production**
 * classification, against a ladder source that declares **nothing**.
 *
 * That emptied source is the case the derived rung exists for: a family the catalogue does not describe is
 * adapted from the element the plan already presents, so the exercise climbs its own range and holds at
 * that range's top. The assertions are ordered by how easily each could pass for the wrong reason: the
 * classification is reached at all, the pass reaches the engine rather than a gap, the classification is
 * what made that possible, and the only row written is the family's own state.
 */
class CatalogExerciseFamilyClassificationIntegrationTest {

    /**
     * The rig, with the ladder source **explicitly emptied**.
     *
     * The rig's default is the **production** stored provider, under which these passes read a real
     * `pushups` ladder; the empty case is stated rather than assumed, because it is the one in which the
     * rung has to be derived. The positive assertions over the production provider live in
     * `ProductionAdaptiveProgressionContentTest`, over the same rig.
     */
    private val rig = CatalogAdaptiveIntegrationRig.of().withoutLadder()

    @After
    fun close() = rig.close()

    /**
     * **The central assertion.** With the production classification and no declared ladder, a pass over a
     * day presenting a family the completion exposed reaches the engine with the rung the plan presents —
     * *not* `NO_FAMILY_CLASSIFICATION`, where the same pass stopped while the gap was open, and not
     * `NO_DECLARED_PROGRESSION_RELATION`, which it used to report.
     */
    @Test
    fun withClassificationAndNoLadderTheFamilyIsAdaptedOnItsOwnRung() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertTrue(
            "production can classify the exercise, so the pass reaches the engine instead of reporting a " +
                "gap: $outcome",
            outcome !is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest
        )
        assertEquals("and it is the family the completion trained", "pushups", outcome.familyId)
        assertTrue(
            "with a position to store, so its window was evaluated: ${outcome.completion}",
            outcome.completion is AdaptiveCompletion.WindowEvaluated
        )
    }

    /**
     * **The negative control, kept live.** Removing the classification returns the pass to the earlier
     * gap.
     *
     * This is the falsification of the test above: without it, a rig whose day simply presented nothing
     * classifiable would reach the engine for the wrong reason and the central assertion would be
     * decoration.
     */
    @Test
    fun withoutTheClassificationTheEarlierTypedGapIsRestoredExactly() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        rig.classification = ExerciseFamilyClassification { null }

        assertEquals(
            "without a classification the pass stops one gap earlier, which is what makes the " +
                "assertion above a statement about the classification and not about the day",
            AdaptiveInputGap.NO_FAMILY_CLASSIFICATION,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
    }

    /**
     * The classification is genuinely **reached**, and it is reached *before* the engine is asked.
     *
     * A counting classification records the ids the pass asks about; with the catalogue source behind it,
     * the pass must ask about real catalogue ids. Asserting the ids — rather than merely that a
     * classification was consulted — is what separates "the classification is wired in" from "the day
     * happened to present nothing the classification knows".
     */
    @Test
    fun theClassificationIsConsultedAboutRealCatalogueIdsBeforeTheEngineDecides() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()

        val asked = mutableListOf<String>()
        val production = CatalogExerciseFamilyClassification()
        rig.classification = ExerciseFamilyClassification { exerciseId ->
            asked.add(exerciseId)
            production.familyOf(exerciseId)
        }

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "the pass reached the engine on the family the classification named",
            "pushups",
            outcome.familyId
        )
        assertTrue(
            "the classification was consulted at all (asked: $asked)",
            asked.isNotEmpty()
        )
        CatalogAdaptiveIntegrationRig.DAY_EXERCISES.forEach { exerciseId ->
            assertTrue(
                "the pass asked about the day's own exercise '$exerciseId', so the classification ran " +
                    "before the engine rather than being skipped (asked: $asked)",
                exerciseId in asked
            )
            assertNotEquals(
                "'$exerciseId' is a catalogue id and the production source classifies it",
                null,
                production.familyOf(exerciseId)
            )
        }
    }

    /**
     * **Only the family's state is written.** Being adapted without a ladder must not become making a
     * decision: the state leg is the single row, and no decision or adjustment is invented for a rung the
     * catalogue does not describe.
     */
    @Test
    fun onlyTheFamilyStateIsWritten() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        val before = rig.counts()

        rig.completeWithAdaptive(trigger.sessionId)

        assertEquals("no decision row", 0, rig.database.count("program_adaptive_decision_record"))
        assertEquals("no adjustment", 0, rig.database.count("adaptive_adjustment"))
        val state = requireNotNull(rig.storedFamilyStateOf("pushups")) {
            "the window was evaluated, so the family's state is stored"
        }
        assertEquals(
            "at the only position a family with no hierarchy has",
            0,
            state.progressionLevel
        )
        assertEquals(
            "naming the exercise the plan presents, not a variant nobody authored",
            "pushups",
            state.currentExerciseId
        )
        assertEquals(
            "and no other table moved: ${before.keys}",
            before.filterKeys { table -> table != "program_family_progression_state" },
            rig.counts().filterKeys { table -> table != "program_family_progression_state" }
        )
    }

    /**
     * **The stored state is not a ladder.** A family state naming a *variant* is a fact about the family,
     * not a hierarchy: the rung is read from the presentation, so the window that follows names the
     * exercise the plan presents.
     */
    @Test
    fun aStoredStateDoesNotStandInForTheRung() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        rig.database.exec(
            "INSERT INTO `program_family_progression_state` " +
                "(`revisionId`, `familyId`, `progressionLevel`, `adaptationState`, `currentExerciseId`, " +
                "`updatedAt`) VALUES (?, 'pushups', 0, 'HOLD', 'pushups_wide', 0)",
            trigger.revisionId.value
        )
        assertEquals(
            "the seeded state names the variant the plan presents second",
            "pushups_wide",
            requireNotNull(rig.storedFamilyStateOf("pushups")).currentExerciseId
        )

        rig.completeWithAdaptive(trigger.sessionId)

        assertEquals(
            "the rung comes from the plan's first element for the family, not from the state",
            "pushups",
            requireNotNull(rig.storedFamilyStateOf("pushups")).currentExerciseId
        )
        assertEquals(
            "and the state's own level is the rung's, not a level nobody declared",
            0,
            requireNotNull(rig.storedFamilyStateOf("pushups")).progressionLevel
        )
    }

    /**
     * **No cross-Program behaviour.** Two Programs, two databases, two histories — and Program B's pass
     * answers from its own facts alone, with nothing of Program A's reaching it.
     *
     * The falsification is on the *family*: both Programs exercise the same catalogue family, so if the
     * classification carried any accumulated per-Program state, B's answer would be the only way to see
     * it. What is asserted is that each pass writes only its own family's state and no decision.
     */
    @Test
    fun programAHistoryNeverEntersProgramB() = runBlocking {
        val first = CatalogAdaptiveIntegrationRig.of().withoutLadder()
        try {
            first.createSingleFamilyGraph()
            val trigger = first.seedHistoryAndTrigger()

            assertEquals(
                "Program A adapts the family it trained",
                "pushups",
                first.completeWithAdaptive(trigger.sessionId).familyId
            )
            assertEquals(
                "and writes no decision for a rung nobody authored",
                0,
                first.database.count("program_adaptive_decision_record")
            )
            assertTrue(
                "with its own state row",
                first.storedFamilyStateOf("pushups") != null
            )
        } finally {
            first.close()
        }

        val second = CatalogAdaptiveIntegrationRig.secondProgram().withoutLadder()
        try {
            second.createSingleFamilyGraph()
            val trigger = second.seedHistoryAndTrigger()

            assertNotEquals(
                "the two Programs are genuinely different Programs",
                first.programId.value,
                second.programId.value
            )
            assertEquals(
                "Program B adapts the same catalogue family from its own facts",
                "pushups",
                second.completeWithAdaptive(trigger.sessionId).familyId
            )
            assertEquals(
                "and wrote no decision of its own",
                0,
                second.database.count("program_adaptive_decision_record")
            )
            assertNull(
                "and nothing of Program A's is in Program B's database",
                second.storedFamilyStateOf("squats")
            )
        } finally {
            second.close()
        }
    }

    /**
     * **A day presenting only an unexposed family still reads that family's window.** The completion
     * exposed `pushups`; the next opportunity presents only `squats`, so there is no element to attach an
     * adjustment to — and nothing to derive a rung from. The family's bookkeeping advances, nothing is
     * attached and nothing is decided, and the pass names the family rather than collapsing into a gap
     * that names none.
     */
    @Test
    fun aDayPresentingOnlyAnUnexposedFamilyAdvancesThatFamilysWindow() = runBlocking {
        // The rig's own default graph is already the two-day plan: the past opportunities present the
        // push day and the next future one presents the leg day.
        rig.createGraph()
        val trigger = rig.seedHistoryAndTrigger()
        val before = rig.counts()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertTrue(
            "the completion exposed 'pushups' and the next opportunity presents only 'squats', so the " +
                "family's window is read and nothing is attached to the opportunity: $outcome",
            outcome is AdaptiveIntegrationOutcome.WindowOnly
        )
        assertEquals("pushups", outcome.familyId)
        assertEquals(
            "and with no position to store, nothing adaptive was written",
            before,
            rig.counts()
        )
    }

    /**
     * **One leg per family.** A day presenting `pushups` and `squats` exposes both families in one
     * completion, and both are decided in the same pass: the pass stops choosing one.
     */
    @Test
    fun aCompletionOverTwoFamiliesCarriesOneLegForEach() = runBlocking {
        rig.createTwoFamilyGraph()
        // The class's rig is the empty catalogue; this claim is about two ladders being decided, so it
        // reads the production source.
        rig.relations = rig.productionProvider()
        val trigger = rig.seedHistoryAndTrigger()

        val outcomes = rig.outcomesOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "one outcome per family the completion exposed, in the order the session presents them",
            listOf("pushups", "squats"),
            outcomes.map { outcome -> outcome.familyId }
        )
        val completion = AdaptiveCompletion.of(outcomes.map { outcome -> outcome.completion })
        assertTrue(
            "and the completion carries both as legs of one unit: $completion",
            completion is AdaptiveCompletion.Completions && completion.legs.size == 2
        )
    }
}
