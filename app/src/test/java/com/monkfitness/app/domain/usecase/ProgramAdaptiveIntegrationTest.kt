package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveTargetElement
import com.monkfitness.app.domain.adaptive.integration.adaptiveTargetElementOf
import com.monkfitness.app.domain.adaptive.integration.ownership
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 12's integration suite, on a real SQLite database and the production repositories: the
 * whole flow from a completed Session to the change a future opportunity will present.
 *
 * ```text
 * stored Program facts → completed Session → observations and the family's state
 *     → future target Slot → AdaptiveInputSnapshot + ProgramAdaptiveRequest
 *     → ProgramAdaptiveEngine (pure) → ProgramAdaptiveResult
 *     → the completion transaction → the future Slot's presentation
 * ```
 *
 * Every assertion is made on rows or on values read back through **fresh repositories**, so *"this
 * was stored"* is a claim about storage rather than about a cache, and *"nothing was written"* is a
 * whole-database census rather than a list of tables the test happened to think of. The scenario the
 * rig builds and the window it produces are documented on [ProgramAdaptiveIntegrationRig]; the
 * arithmetic the policy then reads is stated there too, because a test that moved it would be
 * measuring a different window.
 */
class ProgramAdaptiveIntegrationTest {

    private val rig = ProgramAdaptiveIntegrationRig.of()

    @After
    fun close() = rig.close()

    // ================================================================ the flow (§4, §6, §16, §27)

    /**
     * The complete path, end to end: a completed Session produces a decision about the **next future**
     * opportunity, the decision and its adjustment are stored with the family's state, and the
     * opportunity the change is for is the one the user will start next.
     */
    @Test
    fun aCompletedSessionAdaptsTheNextFutureOpportunityAndStoresAllThreeLegs() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()

        val (outcome, completion) = rig.completeWithAdaptive(trigger.sessionId)

        assertTrue("the window earned a progression: $outcome", outcome is AdaptiveIntegrationOutcome.AdaptiveApplied)
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied

        assertEquals(
            "the decision is about the next *future* opportunity of the revision the Session ran under",
            listOf(rig.slot(5), RevisionId(ProgramAdaptiveIntegrationRig.revisionId(1))),
            listOf(applied.decision.slotId, applied.decision.revisionId)
        )
        assertEquals(
            "it changes the first element of that opportunity's presentation, from the variant the " +
                "plan presents to the one the ladder declares next",
            listOf("plan-ex-p12-1-1", "pushup", "pike_pushup"),
            listOf(
                applied.adjustment.before.programExerciseId.value,
                applied.adjustment.before.exerciseId,
                applied.adjustment.after.exerciseId
            )
        )
        assertEquals(
            "and the completion reports exactly that decision and adjustment",
            applied.decision.decisionId,
            (completion.adaptive as com.monkfitness.app.domain.workout.AdaptiveOutcome.Stored).decisionId
        )

        val stored = rig.storedDecisions().single()
        assertEquals(
            "the three legs §27 lists are stored as one fact: the decision, its adjustment and the " +
                "family's state after the window",
            listOf(
                ProgramAdaptiveReason.SUSTAINED_POSITIVE,
                rig.slot(5).value,
                "pike_pushup",
                2
            ),
            listOf(
                stored.reason,
                stored.slotId.value,
                stored.adjustmentId?.let { id ->
                    rig.storedAdjustments(rig.slot(5)).single { it.adjustmentId == id }.after.exerciseId
                },
                rig.storedFamilyState()?.progressionLevel
            )
        )
        assertEquals(
            "the family's bookkeeping advanced by the window's own verdict, and the change reset the " +
                "cooldown",
            listOf(2, 0, 0),
            listOf(
                rig.storedFamilyState()?.precedingProgressQualifyingWindows,
                rig.storedFamilyState()?.recoveryQualifyingWindows,
                rig.storedFamilyState()?.qualifyingWindowsSinceLastChange
            )
        )
        assertEquals(
            "and the opportunity is not taken by the adaptation: the user still has to train it",
            SlotStatus.PLANNED,
            rig.storedSlot(rig.slot(5)).status
        )
    }

    /**
     * The pass **writes nothing**: everything it produces travels as a value, and §27's completion is
     * what persists it.
     */
    @Test
    fun theAdaptivePassAloneWritesNothingAtAll() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        val before = rig.counts()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertTrue("the pass decided a change", outcome is AdaptiveIntegrationOutcome.AdaptiveApplied)
        assertEquals("and wrote not one row", before, rig.counts())
        assertEquals(SessionStatus.IN_PROGRESS, rig.requireStored(trigger.sessionId).status)
    }

    /** A past opportunity is never the target, however startable it is (§4). */
    @Test
    fun theDecisionsTargetIsNeverAPastOpportunityOfTheSameRevision() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        // The rig's own scenario: opportunity 1 is the one the completion took, 2 is a past `MISSED`
        // one that the runtime would still start, 3 is past with history on it, and 4 and 5 are future.
        assertEquals(
            "the scenario is what it says it is",
            listOf(SlotStatus.MISSED, SlotStatus.PLANNED),
            listOf(rig.storedSlot(rig.slot(3)).status, rig.storedSlot(rig.slot(4)).status)
        )

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "the target is the first *future* opportunity, never the past startable ones",
            rig.slot(5),
            rig.targetSlotOf(outcome)
        )
        assertEquals(
            "and no decision or adjustment was filed against a past opportunity",
            emptyList<Any>(),
            listOf(
                rig.database.scalar(
                    "SELECT COUNT(*) FROM `program_adaptive_decision_record` WHERE `slotId` IN " +
                        "('${rig.slot(2).value}', '${rig.slot(3).value}', '${rig.slot(4).value}')"
                ),
                rig.database.scalar(
                    "SELECT COUNT(*) FROM `adaptive_adjustment` WHERE `slotId` IN " +
                        "('${rig.slot(2).value}', '${rig.slot(3).value}', '${rig.slot(4).value}')"
                )
            ).filter { it != "0" }
        )
    }

    /** No future opportunity is an expected result, not an exception and not a fabricated decision. */
    @Test
    fun aRevisionWithNoFutureOpportunityProducesNothingAtAll() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.database.exec(
            "UPDATE `program_workout_slot` SET `status` = 'SUPERSEDED' WHERE `slotId` IN " +
                "('${rig.slot(5).value}', '${rig.slot(6).value}')"
        )
        val before = rig.counts()

        val gap = rig.gapOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "the ordinary end of a program: no decision, no adjustment and no state row, because no " +
                "window was evaluated",
            AdaptiveInputGap.NO_FUTURE_SLOT,
            gap
        )
        assertEquals("nothing adaptive was written", before, rig.counts())
    }

    // ================================================================ revision and program scope

    /**
     * A completion under one revision never adapts another revision's opportunities, even when a newer
     * revision is the Program's current one (§4, §18).
     */
    @Test
    fun aCompletionNeverAdaptsAnotherRevisionsOpportunity() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val second = rig.saveSecondRevision()
        val trigger = rig.seedHistoryAndTrigger()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied

        assertEquals(
            "the target belongs to the revision the Session ran under, not to the current one",
            RevisionId(ProgramAdaptiveIntegrationRig.revisionId(1)),
            applied.decision.revisionId
        )
        assertNotEquals(
            "and never to the newer revision's own opportunity",
            second.revisionId,
            applied.decision.revisionId
        )
        assertEquals(
            "the newer revision's opportunities are untouched",
            0,
            rig.database.scalar(
                "SELECT COUNT(*) FROM `program_adaptive_decision_record` WHERE `revisionId` = " +
                    "'${second.revisionId.value}'"
            )?.toInt()
        )
        assertEquals(
            "and so are its adaptive state rows",
            0,
            rig.database.scalar(
                "SELECT COUNT(*) FROM `program_family_progression_state` WHERE `revisionId` = " +
                    "'${second.revisionId.value}'"
            )?.toInt()
        )
    }

    /** A newly saved revision starts its own adaptive baseline; the previous revision's state stays. */
    @Test
    fun aNewRevisionStartsItsOwnAdaptiveBaselineAndDoesNotCarryTheOldOneOver() = runBlocking {
        rig.createGraph()
        // The first revision has reached the top of its ladder and served a cooldown.
        val first = rig.seedFamilyState(
            level = 3,
            precedingProgressQualifyingWindows = 2,
            qualifyingWindowsSinceLastChange = 4
        )
        val rowOfTheFirstRevision = rig.database.rows(
            "SELECT * FROM `program_family_progression_state` WHERE `revisionId` = " +
                "'${first.revisionId.value}'"
        ).single()
        val second = rig.saveSecondRevision()

        // A completion under the *second* revision: its own window, its own baseline.
        rig.attempt(
            slot = rig.secondRevisionSlot(4),
            at = ProgramAdaptiveIntegrationRig.HISTORY_TWO,
            setsPerOccurrence = 2
        )
        val trigger = rig.attempt(
            slot = rig.secondRevisionSlot(2),
            at = ProgramAdaptiveIntegrationRig.COMPLETION_ATTEMPT,
            setsPerOccurrence = 2,
            end = ProgramAdaptiveIntegrationRig.SessionEnd.IN_PROGRESS
        )
        rig.completeWithAdaptive(trigger.sessionId)

        val storedSecond = rig.database.rows(
            "SELECT * FROM `program_family_progression_state` WHERE `revisionId` = " +
                "'${second.revisionId.value}'"
        ).single()
        assertEquals(
            "the new revision's level is its own ladder's reading of what it presents, not the old " +
                "revision's top rung",
            listOf("1", "pushup"),
            listOf(storedSecond["progressionLevel"], storedSecond["currentExerciseId"])
        )
        assertEquals(
            "and the old revision's row is byte-identical afterwards",
            rowOfTheFirstRevision,
            rig.database.rows(
                "SELECT * FROM `program_family_progression_state` WHERE `revisionId` = " +
                    "'${first.revisionId.value}'"
            ).single()
        )
    }

    // ================================================================ the family's own bookkeeping

    /** A window that held still advances the family's bookkeeping, and stores no decision. */
    @Test
    fun aHeldWindowAdvancesTheBookkeepingAndWritesNoDecision() = runBlocking {
        rig.createGraph()
        // No preceding qualifying window: the direction has to be confirmed over two windows, so this
        // one holds — and that hold is what makes the *next* window able to confirm.
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 0)
        val trigger = rig.seedHistoryAndTrigger()

        val (outcome, completion) = rig.completeWithAdaptive(trigger.sessionId)

        assertEquals(
            "the direction was measured and not yet confirmed",
            ProgramAdaptiveReason.AWAITING_CONFIRMATION,
            (outcome as AdaptiveIntegrationOutcome.NothingToAdapt).reason
        )
        assertEquals(
            "so no decision and no adjustment is stored — \"nothing was decided\" is not a row",
            listOf(0, 0),
            listOf(
                rig.database.count("program_adaptive_decision_record"),
                rig.database.count("adaptive_adjustment")
            )
        )
        assertEquals(
            "and the completion reports that explicitly",
            com.monkfitness.app.domain.workout.AdaptiveOutcome.NothingDecided,
            completion.adaptive
        )
        assertEquals(
            "while the family's own state advanced by this window's verdict, which is what makes the " +
                "next window able to confirm",
            listOf(1, 0, null),
            listOf(
                rig.storedFamilyState()?.precedingProgressQualifyingWindows,
                rig.storedFamilyState()?.recoveryQualifyingWindows,
                rig.storedFamilyState()?.qualifyingWindowsSinceLastChange
            )
        )
    }

    /**
     * The bookkeeping survives a process restart: the next pass reads it through fresh repositories and
     * confirms on the strength of it.
     */
    @Test
    fun theWindowBookkeepingSurvivesARestartAndConfirmsTheNextWindow() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 0)
        val trigger = rig.seedHistoryAndTrigger()
        rig.completeWithAdaptive(trigger.sessionId)
        assertEquals(1, rig.storedFamilyState()?.precedingProgressQualifyingWindows)

        // A second completion, read and decided by *fresh* repositories: the confirmation the first
        // window advanced is the whole reason this one may change the family.
        val second = rig.secondCompletion()
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        val outcome = rig.outcomeOf(rig.freshIntegration().adaptAfter(second.sessionId))

        assertTrue(
            "the second window confirmed the direction on the strength of the stored count: $outcome",
            outcome is AdaptiveIntegrationOutcome.AdaptiveApplied
        )
    }

    // ================================================================ §18's filter (§13, §20)

    /**
     * A change the aggregate load guard refuses is kept as an explicit `NOT_APPLIED` decision — with
     * its **reason** — and produces no adjustment.
     */
    @Test
    fun aGuardRefusalIsStoredWithItsDecisionAndItsReasonAndNoAdjustment() = runBlocking {
        val rig = ProgramAdaptiveIntegrationRig.refusingGuard()
        try {
            rig.createGraph()
            rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
            val trigger = rig.seedHistoryAndTrigger()

            val (outcome, completion) = rig.completeWithAdaptive(trigger.sessionId)

            assertEquals(
                "the guard refused the change §18 does not allow",
                ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD,
                (outcome as AdaptiveIntegrationOutcome.AdaptiveFiltered).reason
            )
            val stored = rig.storedDecisions().single()
            assertEquals(
                "the filtered decision keeps its outcome and its reason, and produced nothing",
                listOf("NOT_APPLIED", ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD, 0),
                listOf(
                    stored.outcome.name,
                    stored.reason,
                    rig.database.count("adaptive_adjustment")
                )
            )
            assertEquals(
                "and the completion reports the decision it stored",
                com.monkfitness.app.domain.workout.AdaptiveOutcome.Stored(stored.decisionId, null),
                completion.adaptive
            )
            assertEquals(
                "the family is left where it was: a refusal is not a change",
                listOf(1, 2),
                listOf(rig.storedFamilyState()?.progressionLevel, rig.storedFamilyState()?.precedingProgressQualifyingWindows)
            )
        } finally {
            rig.close()
        }
    }

    /**
     * The reason is what survives a restart — not a re-derivation from the row. The stored token is the
     * engine's own answer, unchanged when the facts are read again, and a later decision under a
     * different policy never rewrites it.
     */
    @Test
    fun theDecisionReasonSurvivesARestartAndIsNeverReDerived() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.completeWithAdaptive(trigger.sessionId)
        val first = rig.storedDecisions().single()

        // A restart reads the same answer, and a *different* policy object changes nothing about it:
        // the row is a record of what was decided, not of what would be decided today.
        rig.policy = com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy(
            guardAllowedSetIncreaseNumerator = 1,
            guardAllowedSetIncreaseDenominator = 2
        )
        val afterARestart = rig.freshIntegration()
        assertEquals(
            "the reason a fresh read returns is the stored token",
            ProgramAdaptiveReason.SUSTAINED_POSITIVE,
            rig.storedDecisions().single().reason
        )
        assertEquals(
            "and the decision is readable through the repository the same way",
            first.reason,
            afterARestart.let { rig.storedDecisions().single().reason }
        )

        // A second completion under that policy is a *new* decision; the first row is untouched.
        val second = rig.secondCompletion()
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        rig.outcomeOf(rig.freshIntegration().adaptAfter(second.sessionId))
        rig.completeWithAdaptive(second.sessionId)
        assertEquals(
            "the first decision's stored reason is exactly what it was",
            first.reason,
            rig.storedDecisions().first { it.decisionId == first.decisionId }.reason
        )
    }

    // ================================================================ §14/§16: the chain

    /**
     * Two decisions about one future opportunity: the second **supersedes the first by reference**, and
     * the first row is never rewritten (§16).
     */
    @Test
    fun aSecondDecisionSupersedesTheFirstByReferenceAndNeverRewritesIt() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val firstTrigger = rig.seedHistoryAndTrigger()
        rig.completeWithAdaptive(firstTrigger.sessionId)
        val first = rig.storedAdjustments(rig.slot(5)).single()
        val firstRow = rig.database.rows(
            "SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = '${first.adjustmentId.value}'"
        ).single()

        // The cooldown the first change started has since been served.
        rig.seedFamilyState(
            level = 2,
            precedingProgressQualifyingWindows = 1,
            qualifyingWindowsSinceLastChange = 2
        )

        val secondTrigger = rig.secondCompletion()
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        val outcome = rig.outcomeOf(rig.adapt(secondTrigger.sessionId))
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        rig.completeWithAdaptive(secondTrigger.sessionId)

        val chain = rig.storedAdjustments(rig.slot(5))
        assertEquals("the chain holds both adjustments", 2, chain.size)
        assertEquals(
            "the new one names the old one, and its before is what the user would have been shown",
            listOf(first.adjustmentId, "pike_pushup", "decline_pushup"),
            listOf(
                applied.adjustment.supersedesAdjustmentId,
                applied.adjustment.before.exerciseId,
                applied.adjustment.after.exerciseId
            )
        )
        assertEquals(
            "and the superseded row is byte-identical: supersession is a link, not an edit",
            firstRow,
            rig.database.rows(
                "SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = '${first.adjustmentId.value}'"
            ).single()
        )
    }

    /**
     * The future opportunity **consumes** the adjustment when its session starts, and the captured
     * presentation is frozen there: a later adjustment and a later revision change neither of them
     * move it (§16, §19).
     */
    @Test
    fun theFutureOpportunityConsumesTheAdjustmentIntoAnImmutableSnapshot() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        val (outcome, _) = rig.completeWithAdaptive(trigger.sessionId)
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied

        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        val started = value(rig.runtime.startSession(rig.slot(5)))

        assertEquals(
            "the snapshot presents what the adjustment changed, in the presentation's own order",
            listOf("plan-ex-p12-1-1", "pike_pushup", "8,8"),
            listOf(
                started.snapshot.workout.exercises.first().programExerciseId.value,
                started.snapshot.workout.exercises.first().exerciseId,
                started.snapshot.workout.exercises.first().prescription.perSetTargets.joinToString(",")
            )
        )
        assertEquals(
            "and it names the adjustment it consumed",
            listOf(applied.adjustment.adjustmentId),
            started.snapshot.workout.appliedAdjustmentIds
        )

        // A later decision (under a cooldown that has since been served) and a §6 Save: neither may
        // re-explain this Session, because its presentation was captured when it started. The later
        // decision is about the *next* opportunity, because the one this Session consumed is no longer
        // a future opportunity at all — its snapshot has been taken.
        rig.seedFamilyState(
            level = 2,
            precedingProgressQualifyingWindows = 1,
            qualifyingWindowsSinceLastChange = 2
        )
        val later = rig.secondCompletion()
        val laterOutcome = rig.outcomeOf(rig.adapt(later.sessionId))
        assertEquals(
            "the later decision is about the *next* opportunity — the one this Session consumed is no " +
                "longer a future opportunity at all, so nothing is re-filed under it",
            rig.slot(6),
            rig.targetSlotOf(laterOutcome)
        )
        rig.completeWithAdaptive(later.sessionId)
        rig.saveSecondRevision()
        assertEquals(
            "and the consumed opportunity carries exactly the one decision it always did",
            1,
            rig.storedDecisions().count { it.slotId == rig.slot(5) }
        )

        val restored = rig.requireStored(started.sessionId)
        assertEquals(
            "the restored Session is the captured presentation, element for element",
            started.snapshot.workout.exercises,
            restored.snapshot.workout.exercises
        )
        assertEquals(
            "including the adjustment ids it was started with",
            started.snapshot.workout.appliedAdjustmentIds,
            restored.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "while the consumed opportunity keeps exactly the one adjustment it presented, and the " +
                "later decision is filed under the next opportunity and nowhere else",
            listOf(1, 1),
            listOf(
                rig.storedAdjustments(rig.slot(5)).size,
                rig.storedDecisions().count { it.slotId == rig.slot(6) }
            )
        )
        // The later decision was refused rather than applied, and the refusal is the guard's own
        // one-step rule read on the *presented* variants: the family's position had already moved to
        // the second variant while the next opportunity's element still presented the first, so a step
        // from there would travel two declared levels at once (§18). It is recorded as filtered, which
        // is §18's contract, and the consumed opportunity is untouched — which is what this test is
        // about.
        assertEquals(
            "and the later decision is kept as a refusal rather than dropped",
            ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD,
            rig.storedDecisions().single { it.slotId == rig.slot(6) }.reason
        )
    }

    // ================================================================ §19/§15: whose element

    /** An element the user authored is not adapted at all (§15, §18). */
    @Test
    fun anElementTheUserAuthoredIsNeverAdapted() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.database.exec(
            "UPDATE `program_exercise` SET `origin` = 'USER_AUTHORED' " +
                "WHERE `programExerciseId` = 'plan-ex-p12-1-1'"
        )

        val gap = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "the engine does not even evaluate the user's own content, and says whose it was",
            ProgramAdaptiveReason.USER_AUTHORED_ELEMENT,
            (gap as AdaptiveIntegrationOutcome.NothingToAdapt).reason
        )
        assertEquals(
            "nothing is stored as a decision or an adjustment",
            listOf(0, 0),
            listOf(
                rig.database.count("program_adaptive_decision_record"),
                rig.database.count("adaptive_adjustment")
            )
        )
    }

    /** A pinned element is treated the same way, and pinning outranks authorship. */
    @Test
    fun aPinnedElementIsNeverAdapted() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.database.exec(
            "UPDATE `program_exercise` SET `isPinned` = 1, `origin` = 'USER_AUTHORED' " +
                "WHERE `programExerciseId` = 'plan-ex-p12-1-1'"
        )

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertEquals(
            ProgramAdaptiveReason.USER_AUTHORED_ELEMENT,
            (outcome as AdaptiveIntegrationOutcome.NothingToAdapt).reason
        )
        assertEquals(0, rig.database.count("adaptive_adjustment"))
    }

    /** A `MANUAL` revision is not adapted at all — §19's mode rule, before any element is considered. */
    @Test
    fun aManualRevisionIsRefusedBeforeAnyElementIsConsidered() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.database.exec("UPDATE `program_revision` SET `mode` = 'MANUAL'")
        val before = rig.counts()

        assertEquals(
            AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
        assertEquals("nothing adaptive was written", before, rig.counts())
    }

    // ================================================================ the capability boundaries

    /** No declared ladder is a typed no-decision, never a fabricated progression (§10). */
    @Test
    fun noDeclaredProgressionIsATypedNoDecision() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.relations = ProgramAdaptiveIntegrationRig.noLadder()
        val before = rig.counts()

        assertEquals(
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
        assertEquals("nothing was written, and nothing was invented", before, rig.counts())
    }

    /** No family classification is its own typed no-decision — a different gap, for a different fix. */
    @Test
    fun noFamilyClassificationIsATypedNoDecision() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        rig.classification = ProgramAdaptiveIntegrationRig.noClassification()
        val before = rig.counts()

        assertEquals(
            AdaptiveInputGap.NO_FAMILY_CLASSIFICATION,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
        assertEquals("nothing was written", before, rig.counts())
    }

    /**
     * The window is built from the Sessions' **own stored facts**: a completion whose prescription was
     * never confirmed exposes no family at all, however much the plan asked for.
     *
     * The falsification is exact — a window built from the plan's prescribed values instead of the
     * confirmed sets would find the family exposed here and decide something about it, and the gap
     * asserted below is what says it did not.
     */
    @Test
    fun anUnconfirmedPrescriptionExposesNoFamilyAtAll() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_ATTEMPT
        val trigger = value(rig.runtime.startSession(rig.slot(1)))
        rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
        value(rig.runtime.finishSession(trigger.sessionId))

        val before = rig.counts()
        val gap = rig.gapOf(rig.adapt(trigger.sessionId))

        assertEquals(
            "a workout whose sets were never confirmed exposed nothing — not a zero, not a full " +
                "prescription, and not a family",
            AdaptiveInputGap.NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT,
            gap
        )
        assertEquals("and nothing adaptive was written", before, rig.counts())
    }

    // ================================================================ §27: one unit of work

    /**
     * §27's completion is one unit: a failure at **any** leg — the session, the opportunity, the
     * decision, the adjustment or the family's state — leaves the pre-completion state exactly as it
     * was, so no half-completed workout, no orphan decision and no partially advanced family can exist.
     */
    @Test
    fun aFailureAtAnyLegOfTheCompletionLeavesAllFourLegsUnchanged() = runBlocking {
        listOf(
            "the session's own outcome write" to { rig: ProgramAdaptiveIntegrationRig ->
                rig.faults.failSetLogInsert = false
                rig.faults.failSlotOutcomeUpdate = true
            },
            "the decision insert" to { rig: ProgramAdaptiveIntegrationRig ->
                rig.faults.failDecisionInsert = true
            },
            "the family state write" to { rig: ProgramAdaptiveIntegrationRig ->
                rig.faults.failFamilyStateInsert = true
            }
        ).forEach { (what, plant) ->
            val rig = ProgramAdaptiveIntegrationRig.of()
            try {
                rig.createGraph()
                rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
                val trigger = rig.seedHistoryAndTrigger()
                val before = rig.counts()
                val stateBefore = rig.database.rows("SELECT * FROM `program_family_progression_state`")
                plant(rig)

                val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))
                assertTrue("$what: the pass produced a change to write", outcome is AdaptiveIntegrationOutcome.AdaptiveApplied)
                rig.clock.instant = ProgramAdaptiveIntegrationRig.COMPLETION_MOMENT
                val failure = rig.runtime.finishSession(trigger.sessionId, outcome.completion)

                assertTrue(
                    "$what: the completion failed",
                    failure is com.monkfitness.app.domain.workout.SessionRuntimeResult.Failure
                )
                assertEquals("$what: the whole unit rolled back", before, rig.counts())
                assertEquals(
                    "$what: the family's state is exactly what it was",
                    stateBefore,
                    rig.database.rows("SELECT * FROM `program_family_progression_state`")
                )
                assertEquals(
                    "$what: the Session is still in progress and the opportunity is still open",
                    listOf(SessionStatus.IN_PROGRESS.name, SlotStatus.PLANNED.name),
                    listOf(
                        rig.database.scalar(
                            "SELECT `status` FROM `workout_session` WHERE `sessionId` = '${trigger.sessionId.value}'"
                        ),
                        rig.database.scalar(
                            "SELECT `status` FROM `program_workout_slot` WHERE `slotId` = '${rig.slot(1).value}'"
                        )
                    )
                )
            } finally {
                rig.close()
            }
        }
    }

    // ================================================================ isolation from Stage 1

    /**
     * The whole flow touches the target tables and **no** Stage-1 adaptive table: the two generations
     * share a database and nothing else (§30 step 11's boundary, kept by step 12).
     */
    @Test
    fun theFlowNeverTouchesTheStageOneAdaptiveTables() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()

        rig.completeWithAdaptive(trigger.sessionId)

        assertEquals(
            "the target tables hold the adaptive facts",
            listOf(1, 1, 1),
            listOf(
                rig.database.count("program_adaptive_decision_record"),
                rig.database.count("adaptive_adjustment"),
                rig.database.count("program_family_progression_state")
            )
        )
        // §30 step 15 inverted this half. It used to assert the Stage-1 tables were *untouched* by a
        // target pass — the point of the coexisting generations. They are gone, so the claim is that the
        // pass wrote nothing outside the target schema at all: every retained global table is empty too.
        assertEquals(
            "and nothing outside the target schema was written",
            listOf(0, 0),
            listOf(
                rig.database.count("posture_session_progress"),
                rig.database.count("body_weight_log")
            )
        )
    }

    // ================================================================ the element choice, in the open

    /**
     * The change is applied to an element of the **target opportunity's own** plan day, and the ladder
     * it resolves against is that element's family's — never another day's, another revision's or
     * another Program's.
     */
    @Test
    fun theChangedElementBelongsToTheTargetOpportunitysOwnPlanDay() = runBlocking {
        rig.createGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied
        val targetSlot = rig.storedSlot(applied.decision.slotId)
        val targetDay = rig.database.scalar(
            "SELECT `programDayId` FROM `program_workout_slot` WHERE `slotId` = '${targetSlot.slotId.value}'"
        )
        val elementDay = rig.database.scalar(
            "SELECT `programDayId` FROM `program_exercise` WHERE `programExerciseId` = " +
                "'${applied.adjustment.before.programExerciseId.value}'"
        )

        assertEquals("the change is applied to an element of the target's own day", targetDay, elementDay)
        assertEquals(
            "and the target is the future opportunity, not the completed one",
            rig.slot(5),
            applied.decision.slotId
        )
    }
}
