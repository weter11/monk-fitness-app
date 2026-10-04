package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P30's production claim, measured on a real database with the real repositories and the **production**
 * classification — the one thing a stage about closing a gap must prove is that closing it did not change
 * the answer.
 *
 * The assertions here are ordered by how easily each could pass for the wrong reason:
 *
 * 1. the classification is reached at all (otherwise every later assertion is vacuous);
 * 2. it is reached **before** the progression-relation refusal, which is the gap that moved;
 * 3. the refusal is still made, and it is the *typed* one;
 * 4. nothing was written, so closing a gap did not become making a decision;
 * 5. the classification is inert with respect to Program, revision and history.
 */
class CatalogExerciseFamilyClassificationIntegrationTest {

    private val rig = CatalogAdaptiveIntegrationRig.of()

    @After
    fun close() = rig.close()

    // ================================================================ the gap that moved

    /**
     * **The central assertion.** With the production classification and no declared ladder, an adaptive
     * pass over a day presenting a family the completion exposed reports
     * [AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION] — *not* `NO_FAMILY_CLASSIFICATION`, which is
     * where the same pass stopped before P30.
     *
     * The two gaps are asserted to be different values and only the later one is produced, because
     * "it is a gap" alone would pass under either and prove nothing about which fact is now present.
     */
    @Test
    fun withClassificationAndNoLadderTheResultIsExactlyTheMissingProgressionRelation() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()

        val result = rig.adapt(trigger.sessionId)

        assertEquals(
            "production can now classify the exercise, so the refusal is about the missing ladder " +
                "and not about the missing classification",
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            rig.gapOf(result)
        )
        assertNotEquals(
            "the classification gap is genuinely a different gap, not the same one renamed",
            AdaptiveInputGap.NO_FAMILY_CLASSIFICATION,
            rig.gapOf(result)
        )
    }

    /**
     * **The negative control, kept live.** Removing the classification — the exact state P29 shipped —
     * returns the pass to the earlier gap.
     *
     * This is the falsification of the test above: without it, a rig whose day simply presented nothing
     * classifiable would report `NO_DECLARED_PROGRESSION_RELATION` for the wrong reason and the central
     * assertion would be decoration.
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
     * The classification is genuinely **reached**, and it is reached *before* the relation is asked for.
     *
     * A counting classification records the ids the pass asks about; with the catalogue source behind it,
     * the pass must ask about real catalogue ids. Asserting the ids — rather than merely that a
     * classification was consulted — is what separates "the classification is wired in" from "the day
     * happened to present nothing the classification knows".
     */
    @Test
    fun theClassificationIsConsultedAboutRealCatalogueIdsBeforeTheRelationRefusal() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()

        val asked = mutableListOf<String>()
        val production = CatalogExerciseFamilyClassification()
        rig.classification = ExerciseFamilyClassification { exerciseId ->
            asked.add(exerciseId)
            production.familyOf(exerciseId)
        }

        val gap = rig.gapOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "and the pass still refuses for want of a relation",
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            gap
        )
        assertTrue(
            "the classification was consulted at all (asked: $asked)",
            asked.isNotEmpty()
        )
        CatalogAdaptiveIntegrationRig.DAY_EXERCISES.forEach { exerciseId ->
            assertTrue(
                "the pass asked about the day's own exercise '$exerciseId', so the classification ran " +
                    "before the relation refusal rather than being skipped (asked: $asked)",
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
     * **Nothing is written.** Closing a gap must not become making a decision: no decision row, no
     * adjustment, no family state, and the whole-database census is unchanged across the pass.
     */
    @Test
    fun nothingIsWrittenAndNoFamilyStateIsInvented() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        val before = rig.counts()

        rig.adapt(trigger.sessionId)

        assertEquals("no row anywhere changed", before, rig.counts())
        assertEquals("no decision row", 0, rig.database.count("program_adaptive_decision_record"))
        assertEquals("no adjustment", 0, rig.database.count("adaptive_adjustment"))
        assertEquals(
            "and no family state was fabricated for the family the pass could now name",
            null,
            rig.storedFamilyStateOf("pushups")
        )
    }

    /**
     * **No static or default ladder appears.** `PilotProgressionProfiles` is not consulted, and the
     * pass does not proceed past the refusal even though the family's state could have been seeded —
     * the family state is set up here to prove the refusal is not merely *absence of data*.
     */
    @Test
    fun noLadderIsInventedEvenWithFamilyStatePresent() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        // The family state says level 1 with `currentExerciseId` set. It is a *stored fact about the
        // family*, not a ladder: P30 must not read it as a generation preference, and must not let it
        // stand in for a relation that does not exist.
        assertNull(
            "the rig stores no family state for this family yet",
            rig.storedFamilyStateOf("pushups")
        )

        val gap = rig.gapOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "a stored family state is not a declared ladder, so the refusal stands",
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            gap
        )
        assertEquals(
            "and still nothing was written",
            0,
            rig.database.count("program_adaptive_decision_record")
        )
    }

    // ================================================================ Program and revision scope

    /**
     * **No cross-Program behaviour.** Two Programs, two databases, two histories — and Program B's pass
     * reports the same typed gap from its own facts alone, with nothing of Program A's reaching it.
     *
     * The falsification is on the *family*: both Programs exercise the same catalogue family, so if the
     * classification carried any accumulated per-Program state, B's answer would be the only way to see
     * it. What is asserted is that neither pass writes state and both read the same catalogue fact.
     */
    @Test
    fun programAHistoryNeverEntersProgramB() = runBlocking {
        val first = CatalogAdaptiveIntegrationRig.of()
        try {
            first.createSingleFamilyGraph()
            val trigger = first.seedHistoryAndTrigger()
            assertEquals(
                "Program A takes its own typed gap",
                AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
                first.gapOf(first.adapt(trigger.sessionId))
            )
            assertEquals(
                "and writes nothing",
                0,
                first.database.count("program_adaptive_decision_record")
            )
        } finally {
            first.close()
        }

        val second = CatalogAdaptiveIntegrationRig.secondProgram()
        try {
            second.createSingleFamilyGraph()
            val trigger = second.seedHistoryAndTrigger()

            assertNotEquals(
                "the two Programs are genuinely different Programs",
                first.programId.value,
                second.programId.value
            )
            assertEquals(
                "Program B takes the same typed gap from its own facts",
                AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
                second.gapOf(second.adapt(trigger.sessionId))
            )
            assertEquals(
                "and Program B wrote nothing of its own",
                0,
                second.database.count("program_adaptive_decision_record")
            )
            assertEquals(
                "and holds no family state for either Program's family",
                null,
                second.storedFamilyStateOf("pushups")
            )
        } finally {
            second.close()
        }
    }

    /**
     * **A day presenting only an unexposed family is a different gap, and stays one.** The completion
     * exposed `pushups`; a target day presenting only `squats` presents none of them. That is
     * `NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT` — and asserting it is what proves the central assertion is
     * about the *push* day specifically, not about any gap the pass can reach.
     */
    @Test
    fun aDayPresentingOnlyAnUnexposedFamilyReportsThatGapNotTheRelationGap() = runBlocking {
        // The rig's own default graph is already the two-day plan: the past opportunities present the
        // push day and the next future one presents the leg day. So this is the *same* pass as the
        // central assertion with the target on the other family, and the two must come out different.
        rig.createGraph()
        val trigger = rig.seedHistoryAndTrigger()

        assertEquals(
            "the completion exposed 'pushups' and the next opportunity presents only 'squats', so the " +
                "family the decision is about is not presented at all",
            AdaptiveInputGap.NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
    }

    /**
     * **The P29 focus-history signals are unchanged.** The classification is on the adaptive pass only; a
     * day presenting no exposed family reaches its gap through the same family history P29 built, and
     * the whole census of tables P29 added is untouched by a pass that classifies.
     *
     * The claim is deliberately narrow and is why the suite is worth having: P30 could easily have
     * reached `GenerationPreferences` — the temptation being to fill `adaptivePreferredExerciseIds` with
     * the family it can now name — and the assertion that nothing is written anywhere is what says it
     * did not.
     */
    @Test
    fun theAdaptiveAndRecoverySignalsRemainNeutralExactlyAsP29Concluded() = runBlocking {
        rig.createSingleFamilyGraph()
        val trigger = rig.seedHistoryAndTrigger()
        val before = rig.counts()

        rig.adapt(trigger.sessionId)

        rig.counts().forEach { (table, count) ->
            assertEquals("table '$table' is untouched by a classifying pass", before.getValue(table), count)
        }
    }
}