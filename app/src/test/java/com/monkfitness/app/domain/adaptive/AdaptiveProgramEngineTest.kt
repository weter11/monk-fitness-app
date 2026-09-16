package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract tests for `AdaptiveProgramEngine` — the Stage 1 orchestration layer.
 *
 * Architecture rule these tests pin: the engine is a pure function
 * (`SessionObservation` history → [AdaptiveSignalCalculator] → [AdaptiveSignals] → [AdaptivePolicy]
 * → family-level [AdaptiveDecision]). It re-implements no policy threshold, resolves no progression
 * level, selects no concrete exercise, reads no clock and touches no Android/Room/DataStore type.
 *
 * Every expectation below is driven from a history whose signal values were computed outside Gradle
 * with an independent replica of the Task 5 math, and from adaptation evidence the caller supplies
 * explicitly — the engine must never invent a qualifying-window count, a cooldown position or a
 * recovery-session count from the history it was handed.
 *
 * Fixture summary (verified against `AdaptiveSignalCalculator`, cycle 1):
 *
 * | fixture | eligible | exposureScore | session trend | recent load | family trends |
 * | --- | --- | --- | --- | --- | --- |
 * | [positiveHistory] | 5 | 0.92 | POSITIVE (+0.08) | NORMAL (no baseline) | pushups POSITIVE |
 * | [decliningHistory] | 5 | 0.5575 | NEGATIVE (−0.125) | NORMAL | pushups NEGATIVE |
 * | [highLoadDeclineHistory] | 6 | 0.5047619047619049 | NEGATIVE (−0.15) | HIGH (x3.0) | pushups NEGATIVE |
 * | [mixedFamilyHistory] | 6 | 0.7904761904761906 | NEGATIVE (−0.08) | HIGH (x3.0) | pushups NEGATIVE, squats STABLE |
 * | [threeFamilyHistory] | 5 | 0.92 | POSITIVE (+0.08) | NORMAL | pushups/squats/lunges POSITIVE |
 */
class AdaptiveProgramEngineTest {

    private val policy = AdaptivePolicy.V1

    // ------------------------------------------------------------------ insufficient evidence

    /**
     * Requirement 1: no evidence at all is HOLD, and the reason is the Task 4 insufficient-evidence
     * code — never a fabricated action. The decision still names every evaluated family.
     */
    @Test
    fun anEmptyHistoryHoldsEveryFamilyWithInsufficientEvidence() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = emptyList(),
                enabled = listOf(PUSHUP, SQUAT),
                states = listOf(familyState(FAMILY_PUSHUP), familyState(FAMILY_SQUAT))
            )
        )

        assertEquals(listOf(FAMILY_PUSHUP, FAMILY_SQUAT), decision.families.map { it.familyId })
        decision.families.forEach { family ->
            assertEquals(family.familyId, AdaptiveState.HOLD, family.state)
            assertEquals(family.familyId, AdaptiveState.HOLD, family.previousState)
            assertEquals(family.familyId, AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, family.reasonCode)
            assertEquals(family.familyId, listOf(AdaptiveAction.MAINTAIN_STIMULUS), family.actions)
        }
    }

    @Test
    fun aProgramWithNothingEnabledProducesNoFamilyDecision() {
        val decision = AdaptiveProgramEngine.evaluate(input(history = positiveHistory(), enabled = emptyList()))

        assertTrue("no enabled exercise means no family to evaluate", decision.families.isEmpty())
        assertEquals(1, decision.policyVersion)
    }

    /**
     * The documented conservative default for a family the caller supplied no state for: it can hold,
     * and it can take the recovery safety path, but the confirmation windows it never reported keep it
     * out of PROGRESS and REGRESS.
     */
    @Test
    fun aFamilyWithoutASuppliedStateCanOnlyHoldOrEnterRecovery() {
        val holding = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP, PULLUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            )
        )
        // The supplied family progresses on the same window the untracked family holds in.
        assertEquals(AdaptiveState.PROGRESS, holding.families.single { it.familyId == FAMILY_PUSHUP }.state)
        assertEquals(AdaptiveState.HOLD, holding.families.single { it.familyId == FAMILY_PULLUP }.state)
        assertEquals(
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            holding.families.single { it.familyId == FAMILY_PULLUP }.reasonCode
        )

        val recovery = AdaptiveProgramEngine.evaluate(
            input(history = highLoadDeclineHistory(), enabled = listOf(PULLUP))
        )
        assertEquals(AdaptiveState.RECOVERY, recovery.families.single().state)
        assertEquals(AdaptiveReasonCode.HIGH_LOAD_DETERIORATION, recovery.families.single().reasonCode)
    }

    // ------------------------------------------------------------------ PROGRESS

    /** Requirement 2: a sustained positive *family* trend, with the confirmation window supplied. */
    @Test
    fun sustainedPositiveFamilyTrendProgressesThatFamily() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(FAMILY_PUSHUP, family.familyId)
        assertEquals(AdaptiveState.PROGRESS, family.state)
        assertEquals(AdaptiveState.HOLD, family.previousState)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, family.reasonCode)
        assertEquals(listOf(AdaptiveAction.INCREASE_STIMULUS), family.actions)
        assertTrue(family.isTransition)
    }

    /** Requirement 4: one good workout is not a progression decision. */
    @Test
    fun oneGoodWorkoutDoesNotImmediatelyProgress() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = listOf(session(1, listOf(Performed(PUSHUP, PLANNED_REPS, PLANNED_REPS)))),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.HOLD, family.state)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, family.reasonCode)
    }

    /** Requirement 4 (window form): PROGRESS needs two consecutive qualifying windows. */
    @Test
    fun progressNeedsTwoConsecutiveQualifyingWindows() {
        val firstWindow = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 0))
            )
        )
        val secondWindow = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            )
        )

        assertEquals(AdaptiveState.HOLD, firstWindow.families.single().state)
        assertEquals(AdaptiveState.PROGRESS, secondWindow.families.single().state)
    }

    /** Acceptance criterion 7: a partially attended window can still support progression. */
    @Test
    fun aNotStartedDayIsAttendanceEvidenceAndDoesNotWithholdProgress() {
        val history = positiveHistory() + notStartedSession(day = 6)
        assertEquals(0.8333333333333334, signals(history).adherence, 1e-12)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            )
        )

        assertEquals(AdaptiveState.PROGRESS, decision.families.single().state)
    }

    // ------------------------------------------------------------------ REGRESS

    /** Requirement 3: a sustained negative *family* trend regresses that family. */
    @Test
    fun sustainedNegativeFamilyTrendRegressesThatFamily() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = decliningHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.REGRESS, family.state)
        assertEquals(AdaptiveState.HOLD, family.previousState)
        assertEquals(AdaptiveReasonCode.SUSTAINED_DECLINE, family.reasonCode)
        assertEquals(listOf(AdaptiveAction.REDUCE_STIMULUS), family.actions)
        assertTrue(family.isTransition)
    }

    /** Requirement 5: one bad workout is not a regression. */
    @Test
    fun oneBadWorkoutDoesNotImmediatelyRegress() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = listOf(session(1, listOf(Performed(PUSHUP, PLANNED_REPS, 2)))),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.HOLD, family.state)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, family.reasonCode)
    }

    /** Requirement 5 (window form): an unconfirmed decline window does not regress. */
    @Test
    fun unconfirmedDecliningWindowDoesNotRegress() {
        val history = decliningHistory()
        val windowSignals = signals(history)

        // The window itself is as bad as the decline rule requires — but it is one window.
        assertEquals(PerformanceTrend.NEGATIVE, windowSignals.performanceTrend)
        assertTrue(windowSignals.exposureScore < policy.regressMaximumExposureScore)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 0))
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.families.single().state)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, decision.families.single().reasonCode)
    }

    // ------------------------------------------------------------------ RECOVERY

    /** Requirement 6: high recent load with deterioration enters RECOVERY on one qualifying window. */
    @Test
    fun highLoadDeteriorationEntersRecovery() {
        val history = highLoadDeclineHistory()
        val windowSignals = signals(history)
        assertEquals(RecentLoadBucket.HIGH, windowSignals.recentLoadBucket)
        assertEquals(RecoveryRiskPattern.HIGH_LOAD_DETERIORATION, windowSignals.recoveryRisk.pattern)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.RECOVERY, family.state)
        assertEquals(AdaptiveState.HOLD, family.previousState)
        assertEquals(AdaptiveReasonCode.HIGH_LOAD_DETERIORATION, family.reasonCode)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), family.actions)
        assertTrue(family.isTransition)
    }

    /** Requirement 6 (second documented entry path): the prolonged low-exposure decline pattern. */
    @Test
    fun prolongedLowExposureDeclineEntersRecovery() {
        val history = decliningHistory()
        assertEquals(RecentLoadBucket.NORMAL, signals(history).recentLoadBucket)
        assertEquals(RecoveryRiskPattern.LOW_EXPOSURE_DECLINE, signals(history).recoveryRisk.pattern)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1, highRiskWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.RECOVERY, family.state)
        assertEquals(AdaptiveReasonCode.RECOVERY, family.reasonCode)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), family.actions)
    }

    /**
     * Requirement 18: the signal layer's per-window `recoveryRisk.pattern` is a reading, not a state.
     * Confirmation stays with [AdaptivePolicy] — with no preceding high-risk window the same pattern
     * produces HOLD.
     */
    @Test
    fun recoveryRiskPatternAloneDoesNotEnterRecovery() {
        val history = decliningHistory()
        val windowSignals = signals(history)
        assertEquals(RecoveryRiskPattern.LOW_EXPOSURE_DECLINE, windowSignals.recoveryRisk.pattern)
        assertTrue(windowSignals.recoveryRisk.performanceDecline)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 0, highRiskWindows = 0))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.HOLD, family.state)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, family.reasonCode)
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), family.actions)
    }

    /** Requirement 7: RECOVERY is held until its exit gate has counted the qualifying sessions. */
    @Test
    fun recoveryIsHeldUntilTheExitGateIsMet() {
        val history = positiveHistory()

        val belowGate = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, adaptationState = AdaptiveState.RECOVERY, recoverySessions = 1)
                )
            )
        )
        val atGate = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, adaptationState = AdaptiveState.RECOVERY, recoverySessions = 2)
                )
            )
        )

        assertEquals(AdaptiveState.RECOVERY, belowGate.families.single().state)
        assertEquals(AdaptiveReasonCode.RECOVERY, belowGate.families.single().reasonCode)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), belowGate.families.single().actions)
        assertEquals(AdaptiveState.RECOVERY, belowGate.families.single().previousState)
        assertFalse(belowGate.families.single().isTransition)

        assertEquals(AdaptiveState.HOLD, atGate.families.single().state)
        assertEquals(AdaptiveReasonCode.RECOVERY, atGate.families.single().reasonCode)
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), atGate.families.single().actions)
        assertTrue(atGate.families.single().isTransition)
    }

    /**
     * Requirement 8: leaving RECOVERY goes to HOLD, never straight to PROGRESS — even when the window
     * would otherwise qualify for PROGRESS in full.
     */
    @Test
    fun recoveryExitsToHoldAndNeverDirectlyToProgress() {
        val history = positiveHistory()

        // The identical evidence, held instead of recovered, confirms a progression.
        val holdingFamily = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 5))
            )
        ).families.single()
        assertEquals(AdaptiveState.PROGRESS, holdingFamily.state)

        val recoveringFamily = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(
                        familyId = FAMILY_PUSHUP,
                        adaptationState = AdaptiveState.RECOVERY,
                        progressWindows = 5,
                        recoverySessions = 2
                    )
                )
            )
        ).families.single()

        assertEquals(AdaptiveState.HOLD, recoveringFamily.state)
        assertEquals(AdaptiveReasonCode.RECOVERY, recoveringFamily.reasonCode)
        assertNotEquals(AdaptiveState.PROGRESS, recoveringFamily.state)
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), recoveringFamily.actions)
    }

    /** Recovery entry is the priority path: it is taken even when a regress would also confirm. */
    @Test
    fun recoveryEntryTakesPriorityOverAConfirmedDecline() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = decliningHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1, highRiskWindows = 1))
            )
        )

        assertEquals(AdaptiveState.RECOVERY, decision.families.single().state)
        assertEquals(AdaptiveReasonCode.RECOVERY, decision.families.single().reasonCode)
    }

    /** Recovery orders the recovery load — it never removes progression the family already earned. */
    @Test
    fun recoveryOrdersTheRecoveryLoadRatherThanReducingStimulus() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = highLoadDeclineHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressionLevel = 2, regressWindows = 1))
            )
        )

        val family = decision.families.single()
        assertEquals(AdaptiveState.RECOVERY, family.state)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), family.actions)
        assertFalse(family.actions.contains(AdaptiveAction.REDUCE_STIMULUS))
    }

    // ------------------------------------------------------------------ cooldown

    /** Requirement 9: a confirmed progression is refused while the cooldown has not elapsed. */
    @Test
    fun progressionCooldownBlocksAConfirmedProgress() {
        val blocked = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, progressWindows = 1, sessionsSinceLastChange = 1)
                )
            )
        )
        val elapsed = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, progressWindows = 1, sessionsSinceLastChange = 2)
                )
            )
        )

        assertEquals(AdaptiveState.HOLD, blocked.families.single().state)
        assertEquals(AdaptiveReasonCode.PROGRESSION_COOLDOWN, blocked.families.single().reasonCode)
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), blocked.families.single().actions)
        assertEquals(AdaptiveState.PROGRESS, elapsed.families.single().state)
    }

    /** Requirement 10: a family with no recorded level change has nothing to cool down from. */
    @Test
    fun theFirstProgressionWithNoRecordedChangeIsNotBlocked() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, progressWindows = 1, sessionsSinceLastChange = null)
                )
            )
        )

        assertEquals(AdaptiveState.PROGRESS, decision.families.single().state)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, decision.families.single().reasonCode)
    }

    // ------------------------------------------------------------------ family scope

    /** Requirement 11: one decision window, three families, three different outcomes. */
    @Test
    fun differentFamiliesReceiveDifferentOutcomesInTheSameWindow() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = threeFamilyHistory(),
                enabled = listOf(PUSHUP, SQUAT, LUNGE),
                states = listOf(
                    familyState(FAMILY_PUSHUP, progressWindows = 1),
                    familyState(FAMILY_SQUAT, progressWindows = 1, sessionsSinceLastChange = 1),
                    familyState(FAMILY_LUNGE, progressWindows = 0)
                )
            )
        )

        assertEquals(listOf(FAMILY_LUNGE, FAMILY_PUSHUP, FAMILY_SQUAT), decision.families.map { it.familyId })

        val pushups = decision.families.single { it.familyId == FAMILY_PUSHUP }
        val squats = decision.families.single { it.familyId == FAMILY_SQUAT }
        val lunges = decision.families.single { it.familyId == FAMILY_LUNGE }

        assertEquals(AdaptiveState.PROGRESS, pushups.state)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, pushups.reasonCode)

        assertEquals(AdaptiveState.HOLD, squats.state)
        assertEquals(AdaptiveReasonCode.PROGRESSION_COOLDOWN, squats.reasonCode)

        assertEquals(AdaptiveState.HOLD, lunges.state)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, lunges.reasonCode)

        assertEquals(3, decision.families.map { it.reasonCode }.toSet().size)
        assertTrue(
            "a family is adapted on its own evidence, not on the window's aggregate outcome",
            decision.families.single { it.familyId == FAMILY_PUSHUP }.actions !=
                decision.families.single { it.familyId == FAMILY_SQUAT }.actions
        )
    }

    /**
     * Requirement 12: a family with no mapped performance trend must not inherit the global decline
     * as if it had been measured for that family.
     */
    @Test
    fun aFamilyWithNoMappedTrendDoesNotBecomeRegressFromTheGlobalDecline() {
        val history = decliningHistory()
        assertEquals(PerformanceTrend.NEGATIVE, signals(history).performanceTrend)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP, PULLUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, regressWindows = 1),
                    familyState(FAMILY_PULLUP, regressWindows = 1)
                )
            )
        )

        // The measured family declines; the unmeasured one is reported with no direction at all.
        assertEquals(AdaptiveState.REGRESS, decision.families.single { it.familyId == FAMILY_PUSHUP }.state)
        assertEquals(
            AdaptiveState.HOLD,
            decision.families.single { it.familyId == FAMILY_PULLUP }.state
        )
        assertEquals(
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            decision.families.single { it.familyId == FAMILY_PULLUP }.reasonCode
        )
    }

    /**
     * Requirement 19: the global window signals are shared by every family in the window, while the
     * performance trend each family is judged on comes from that family's own exposures.
     */
    @Test
    fun globalSignalsAreSharedWhileFamilyTrendsStayFamilySpecific() {
        val history = mixedFamilyHistory()
        val windowSignals = signals(history)

        assertEquals(PerformanceTrend.NEGATIVE, windowSignals.performanceTrends.getValue(FAMILY_PUSHUP))
        assertEquals(PerformanceTrend.STABLE, windowSignals.performanceTrends.getValue(FAMILY_SQUAT))
        assertEquals(RecentLoadBucket.HIGH, windowSignals.recentLoadBucket)

        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = history,
                enabled = listOf(PUSHUP, SQUAT),
                states = listOf(familyState(FAMILY_PUSHUP), familyState(FAMILY_SQUAT))
            )
        )

        // One shared HIGH-load window: only the family whose own trend declined takes the safety path.
        assertEquals(AdaptiveState.RECOVERY, decision.families.single { it.familyId == FAMILY_PUSHUP }.state)
        assertEquals(
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            decision.families.single { it.familyId == FAMILY_PUSHUP }.reasonCode
        )
        assertEquals(AdaptiveState.HOLD, decision.families.single { it.familyId == FAMILY_SQUAT }.state)
        assertEquals(
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            decision.families.single { it.familyId == FAMILY_SQUAT }.reasonCode
        )
    }

    /** A family no enabled exercise maps to is not evaluated — and never silently re-enabled. */
    @Test
    fun disabledFamiliesAreNotEvaluatedEvenWhenTheirTrendExists() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = threeFamilyHistory(),
                enabled = listOf(PUSHUP, SQUAT),
                states = listOf(
                    familyState(FAMILY_PUSHUP, progressWindows = 1),
                    familyState(FAMILY_SQUAT, progressWindows = 1),
                    familyState(FAMILY_LUNGE, progressWindows = 1)
                )
            )
        )

        assertEquals(listOf(FAMILY_PUSHUP, FAMILY_SQUAT), decision.families.map { it.familyId })
    }

    /** Two exercises of one family are one family decision. */
    @Test
    fun twoExercisesOfOneFamilyProduceOneFamilyDecision() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = listOf(
                    session(
                        1,
                        listOf(
                            Performed(PUSHUP, PLANNED_REPS, 14),
                            Performed("pushup_knees", PLANNED_REPS, 14)
                        )
                    ),
                    session(
                        2,
                        listOf(
                            Performed(PUSHUP, PLANNED_REPS, 20),
                            Performed("pushup_knees", PLANNED_REPS, 20)
                        )
                    )
                ),
                enabled = listOf(PUSHUP, "pushup_knees"),
                states = listOf(familyState(FAMILY_PUSHUP)),
                familyOfExercise = FAMILY_OF + ("pushup_knees" to FAMILY_PUSHUP)
            )
        )

        assertEquals(listOf(FAMILY_PUSHUP), decision.families.map { it.familyId })
    }

    /** An exercise the caller's mapping does not know is its own family, as in the signal layer. */
    @Test
    fun anUnmappedExerciseIsItsOwnFamily() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = listOf(session(1, listOf(Performed("mystery_move", PLANNED_REPS, 20)))),
                enabled = listOf("mystery_move"),
                states = listOf(familyState("mystery_move")),
                familyOfExercise = emptyMap()
            )
        )

        assertEquals(listOf("mystery_move"), decision.families.map { it.familyId })
    }

    // ------------------------------------------------------------------ determinism and purity

    /** Requirement 15: family and action ordering is deterministic, whatever order the caller used. */
    @Test
    fun familyOrderIsDeterministicRegardlessOfTheOrderOfEnabledExercises() {
        val states = listOf(
            familyState(FAMILY_PUSHUP, progressWindows = 1),
            familyState(FAMILY_SQUAT, progressWindows = 1),
            familyState(FAMILY_LUNGE, progressWindows = 1)
        )
        val first = AdaptiveProgramEngine.evaluate(
            input(history = threeFamilyHistory(), enabled = listOf(LUNGE, PUSHUP, SQUAT), states = states)
        )
        val second = AdaptiveProgramEngine.evaluate(
            input(history = threeFamilyHistory(), enabled = listOf(SQUAT, LUNGE, PUSHUP), states = states)
        )

        assertEquals(listOf(FAMILY_LUNGE, FAMILY_PUSHUP, FAMILY_SQUAT), first.families.map { it.familyId })
        assertEquals(first.families.map { it.familyId }, second.families.map { it.familyId })
        assertEquals(1, first.families.map { it.actions }.toSet().size)
        assertEquals(first, second)
    }

    /** Requirement 16: identical inputs produce identical output. */
    @Test
    fun identicalInputsProduceIdenticalOutput() {
        val states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
        val first = AdaptiveProgramEngine.evaluate(
            input(history = positiveHistory(), enabled = listOf(PUSHUP), states = states)
        )
        val second = AdaptiveProgramEngine.evaluate(
            input(history = positiveHistory(), enabled = listOf(PUSHUP), states = states)
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    /** Requirement 14: the policy version on the decision is the caller's policy's version. */
    @Test
    fun policyVersionIsPreservedOnEveryDecision() {
        val revisedPolicy = AdaptivePolicy(version = 7)
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1)),
                policy = revisedPolicy
            )
        )

        assertEquals(7, decision.policyVersion)
        decision.families.forEach { assertEquals(7, it.policyVersion) }
    }

    /** Requirement 13: the Task 4 reason codes are what the engine reports, and nothing else. */
    @Test
    fun theEngineEmitsTheTask4ReasonAndActionVocabulary() {
        val decisions = listOf(
            input(history = emptyList(), enabled = listOf(PUSHUP), states = listOf(familyState(FAMILY_PUSHUP))),
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1))
            ),
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 1, sessionsSinceLastChange = 1))
            ),
            input(
                history = decliningHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1))
            ),
            input(history = highLoadDeclineHistory(), enabled = listOf(PUSHUP), states = listOf(familyState(FAMILY_PUSHUP))),
            input(
                history = decliningHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, regressWindows = 1, highRiskWindows = 1))
            )
        ).map(AdaptiveProgramEngine::evaluate)

        val emittedReasons = decisions.flatMap { it.families }.map { it.reasonCode }.toSet()
        assertEquals(
            setOf(
                AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
                AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
                AdaptiveReasonCode.SUSTAINED_DECLINE,
                AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
                AdaptiveReasonCode.RECOVERY,
                AdaptiveReasonCode.PROGRESSION_COOLDOWN
            ),
            emittedReasons
        )
        assertFalse(
            "the custom-configuration reason is reserved for the configuration stage and never emitted here",
            emittedReasons.contains(AdaptiveReasonCode.CUSTOM_CONFIGURATION_LIMITATION)
        )

        val emittedActions = decisions.flatMap { it.families }.map { it.actions }
        assertEquals(1, emittedActions.map { it.size }.toSet().size)
        emittedActions.forEach { actions ->
            assertEquals(1, actions.size)
            assertTrue(actions.single() in AdaptiveAction.entries)
        }
        assertEquals(AdaptiveAction.entries.toSet(), emittedActions.map { it.single() }.toSet())
    }

    /**
     * The engine holds no threshold, window or confirmation count of its own: every one of them is
     * read from the policy it was handed.
     */
    @Test
    fun theEngineReproducesNoThresholdOfItsOwn() {
        val permissive = AdaptivePolicy(
            version = 2,
            progressConfirmingWindows = 1,
            progressionCooldownEligibleSessions = 0,
            recoveryExitQualifyingSessions = 3
        )

        val firstWindowUnderV1 = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 0, sessionsSinceLastChange = 1))
            )
        ).families.single()
        assertEquals(AdaptiveState.HOLD, firstWindowUnderV1.state)

        val sameWindowUnderPermissive = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP, progressWindows = 0, sessionsSinceLastChange = 1)),
                policy = permissive
            )
        ).families.single()
        assertEquals(AdaptiveState.PROGRESS, sameWindowUnderPermissive.state)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, sameWindowUnderPermissive.reasonCode)

        val recovering = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(
                    familyState(FAMILY_PUSHUP, adaptationState = AdaptiveState.RECOVERY, recoverySessions = 2)
                ),
                policy = permissive
            )
        ).families.single()
        assertEquals(AdaptiveState.RECOVERY, recovering.state)
    }

    /**
     * Requirement 17: the engine resolves no progression level and picks no concrete exercise. Two
     * inputs that differ only in the family's stored progression level decide identically, and a
     * family nothing is known about is held rather than substituted with another exercise.
     */
    @Test
    fun theEnginePerformsNoProgressionResolutionAndSelectsNoExercise() {
        val advancing = FamilyAdaptationState(
            familyId = FAMILY_PUSHUP,
            progressionLevel = 2,
            precedingProgressQualifyingWindows = 1,
            eligibleSessionsSinceLastProgressionChange = null
        )
        val reduced = advancing.copy(progressionLevel = -2)

        val fromAdvanced = AdaptiveProgramEngine.evaluate(
            input(history = positiveHistory(), enabled = listOf(PUSHUP), states = listOf(advancing))
        )
        val fromReduced = AdaptiveProgramEngine.evaluate(
            input(history = positiveHistory(), enabled = listOf(PUSHUP), states = listOf(reduced))
        )

        assertEquals(fromAdvanced, fromReduced)
        assertEquals(listOf(FAMILY_PUSHUP), fromAdvanced.families.map { it.familyId })
        assertEquals(listOf(AdaptiveAction.INCREASE_STIMULUS), fromAdvanced.families.single().actions)

        // Nothing about the substitution case: an unknown exercise family keeps its own name and is held.
        val unknown = AdaptiveProgramEngine.evaluate(
            input(
                history = emptyList(),
                enabled = listOf("mystery_move"),
                states = listOf(familyState("mystery_move"))
            )
        )
        assertEquals(listOf("mystery_move"), unknown.families.map { it.familyId })
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), unknown.families.single().actions)
    }

    /** The engine's output is the Task 4 decision type, scope-tagged with the family it applies to. */
    @Test
    fun everyFamilyDecisionIsATask4DecisionCarryingItsFamilyScope() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = threeFamilyHistory(),
                enabled = listOf(PUSHUP, SQUAT),
                states = listOf(familyState(FAMILY_PUSHUP), familyState(FAMILY_SQUAT))
            )
        )

        decision.families.forEach { family ->
            assertEquals(AdaptiveDecision::class, family::class)
            assertTrue("the engine always scopes a decision to a family", !family.familyId.isNullOrBlank())
        }
        // The policy's own output stays family-agnostic; only the engine attaches the scope.
        val unscoped = AdaptivePolicy.V1.evaluate(
            AdaptiveEvidence(eligibleSessionsSinceLastProgressionChange = null)
        )
        assertEquals(null, unscoped.familyId)
    }

    /** The program context is carried through the decision, and is never re-derived from a calendar. */
    @Test
    fun programContextIsCarriedIntoTheDecision() {
        val decision = AdaptiveProgramEngine.evaluate(
            input(
                history = positiveHistory(),
                enabled = listOf(PUSHUP),
                states = listOf(familyState(FAMILY_PUSHUP)),
                programDay = 40,
                programCycle = 2,
                programType = ProgramType.REVISED
            )
        )

        assertEquals(40, decision.programDay)
        assertEquals(2, decision.programCycle)
        assertEquals(ProgramType.REVISED, decision.programType)
    }

    /** Requirement 17 (structural): the engine and its input stay pure, deterministic domain code. */
    @Test
    fun theEngineAndItsInputArePureDeterministicDomainCode() {
        assertTrue("expected $adaptiveSourceDir to exist", adaptiveSourceDir.isDirectory)

        val forbidden = listOf(
            "System.currentTimeMillis", "System.nanoTime", "LocalDate.now", "LocalDateTime", "Instant.now",
            "Random", "UUID", "kotlin.time", "java.util.concurrent",
            "import android.", "import androidx.", "import com.monkfitness.app.data.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "WorkoutGenerator", "ProgressionResolver", "ProgressionProfile", "applyDifficultyAdjustment",
            "FamilyProgressionState", "AdaptiveDecisionRecord", "AdaptationAction"
        )

        listOf("AdaptiveProgramEngine.kt", "AdaptiveProgramInput.kt").forEach { name ->
            val source = File(adaptiveSourceDir, name)
            assertTrue("expected $name to exist", source.isFile)
            val text = source.readText()
            val hits = forbidden.filter { text.contains(it) }
            assertTrue("$name must stay pure domain code, found: $hits", hits.isEmpty())
        }
    }

    /** The Task 6 plan's `AdaptationAction` is not created: the Task 4 vocabulary is reused. */
    @Test
    fun noSecondDecisionOrActionVocabularyIsIntroduced() {
        assertFalse(
            "AdaptiveAction already covers the family-level actions; AdaptationAction.kt must not exist",
            File(adaptiveSourceDir, "AdaptationAction.kt").exists()
        )
    }

    // ------------------------------------------------------------------ fixtures

    private data class Performed(val exerciseId: String, val plannedReps: Int, val completedReps: Int)

    private fun session(
        day: Int,
        exercises: List<Performed>,
        setsPerExercise: Int = 3,
        cycle: Int = 1
    ): SessionObservation {
        val plannedReps = exercises.sumOf { it.plannedReps }
        val completedReps = exercises.sumOf { it.completedReps }
        val sets = setsPerExercise * exercises.size
        return SessionObservation(
            cycleNumber = cycle,
            programDay = day,
            startedAt = STARTED_AT,
            finishedAt = FINISHED_AT,
            outcome = SessionOutcome.COMPLETED,
            plannedExercises = exercises.size,
            completedExercises = exercises.count { it.completedReps > 0 },
            plannedWork = Workload(sets = sets, reps = plannedReps),
            actualWork = Workload(sets = sets, reps = completedReps),
            exerciseResults = exercises.map { performed ->
                ExerciseResult(
                    exerciseId = performed.exerciseId,
                    plannedSets = setsPerExercise,
                    completedSets = if (performed.completedReps > 0) setsPerExercise else 0,
                    plannedReps = performed.plannedReps,
                    completedReps = performed.completedReps,
                    plannedDurationSeconds = 0,
                    completedDurationSeconds = 0
                )
            }
        )
    }

    private fun notStartedSession(day: Int, cycle: Int = 1): SessionObservation = SessionObservation(
        cycleNumber = cycle,
        programDay = day,
        startedAt = null,
        finishedAt = null,
        outcome = SessionOutcome.NOT_STARTED,
        plannedExercises = 1,
        completedExercises = 0,
        plannedWork = Workload(sets = 3, reps = PLANNED_REPS),
        actualWork = Workload()
    )

    /** Five sessions, exposures 0.70 / 0.80 / 0.90 / 1.00 / 1.00 → exposureScore 0.92, slope +0.08. */
    private fun positiveHistory(): List<SessionObservation> =
        listOf(14, 16, 18, 20, 20).mapIndexed { index, reps ->
            session(index + 1, listOf(Performed(PUSHUP, PLANNED_REPS, reps)))
        }

    /** Five sessions, exposures 0.85 / 0.75 / 0.65 / 0.50 / 0.35 → exposureScore 0.5575, slope −0.125. */
    private fun decliningHistory(): List<SessionObservation> =
        listOf(17, 15, 13, 10, 7).mapIndexed { index, reps ->
            session(index + 1, listOf(Performed(PUSHUP, PLANNED_REPS, reps)))
        }

    /**
     * Fourteen sessions: two sets per day for days 1..7 against six per day for days 8..14 (load ratio
     * 3.0) with the newest exposures declining to 0.30 → exposureScore 0.5047619047619049, slope −0.15.
     */
    private fun highLoadDeclineHistory(): List<SessionObservation> {
        val completedReps = mapOf(
            1 to 20, 2 to 20, 3 to 20, 4 to 20, 5 to 20, 6 to 20, 7 to 20,
            8 to 18, 9 to 18, 10 to 18, 11 to 14, 12 to 10, 13 to 8, 14 to 6
        )
        return (1..14).map { day ->
            session(
                day,
                listOf(Performed(PUSHUP, PLANNED_REPS, completedReps.getValue(day))),
                setsPerExercise = if (day <= 7) 2 else 6
            )
        }
    }

    /**
     * Fourteen sessions of two families: squats stay at full exposure every day while the push-up
     * exposure declines over the newest five → exposureScore 0.7904761904761906, load ratio 3.0,
     * pushups NEGATIVE (−0.16) against squats STABLE.
     */
    private fun mixedFamilyHistory(): List<SessionObservation> {
        val pushupReps = mapOf(
            1 to 20, 2 to 20, 3 to 20, 4 to 20, 5 to 20, 6 to 20, 7 to 20, 8 to 20, 9 to 20, 10 to 20,
            11 to 16, 12 to 12, 13 to 8, 14 to 8
        )
        return (1..14).map { day ->
            session(
                day,
                listOf(
                    Performed(PUSHUP, PLANNED_REPS, pushupReps.getValue(day)),
                    Performed(SQUAT, PLANNED_REPS, PLANNED_REPS)
                ),
                setsPerExercise = if (day <= 7) 1 else 3
            )
        }
    }

    /** Five sessions of three families, all at exposures 0.70 / 0.80 / 0.90 / 1.00 / 1.00. */
    private fun threeFamilyHistory(): List<SessionObservation> =
        listOf(14, 16, 18, 20, 20).mapIndexed { index, reps ->
            session(
                index + 1,
                listOf(
                    Performed(PUSHUP, PLANNED_REPS, reps),
                    Performed(SQUAT, PLANNED_REPS, reps),
                    Performed(LUNGE, PLANNED_REPS, reps)
                )
            )
        }

    private fun familyState(
        familyId: String,
        adaptationState: AdaptiveState = AdaptiveState.HOLD,
        progressionLevel: Int = 0,
        progressWindows: Int = 0,
        regressWindows: Int = 0,
        highRiskWindows: Int = 0,
        recoverySessions: Int = 0,
        sessionsSinceLastChange: Int? = null
    ): FamilyAdaptationState = FamilyAdaptationState(
        familyId = familyId,
        progressionLevel = progressionLevel,
        adaptationState = adaptationState,
        precedingProgressQualifyingWindows = progressWindows,
        precedingRegressQualifyingWindows = regressWindows,
        precedingHighRiskWindows = highRiskWindows,
        recoveryQualifyingSessions = recoverySessions,
        eligibleSessionsSinceLastProgressionChange = sessionsSinceLastChange
    )

    private fun input(
        history: List<SessionObservation>,
        enabled: List<String>,
        states: List<FamilyAdaptationState> = emptyList(),
        policy: AdaptivePolicy = AdaptivePolicy.V1,
        programDay: Int = 10,
        programCycle: Int = 1,
        programType: ProgramType = ProgramType.STANDARD,
        familyOfExercise: Map<String, String> = FAMILY_OF
    ): AdaptiveProgramInput = AdaptiveProgramInput(
        programDay = programDay,
        programCycle = programCycle,
        programType = programType,
        enabledExerciseIds = enabled,
        recentSessions = history,
        currentProgressionStates = states,
        familyOfExercise = familyOfExercise,
        policy = policy
    )

    private fun signals(
        history: List<SessionObservation>,
        policy: AdaptivePolicy = AdaptivePolicy.V1,
        familyOfExercise: Map<String, String> = FAMILY_OF
    ): AdaptiveSignals = AdaptiveSignalCalculator.calculate(
        history = history,
        policy = policy,
        familyOfExercise = familyOfExercise
    )

    private companion object {
        const val PUSHUP = "pushup_standard"
        const val SQUAT = "squat_bodyweight"
        const val LUNGE = "lunge_bodyweight"
        const val PULLUP = "pullup_assisted"

        const val FAMILY_PUSHUP = "pushups"
        const val FAMILY_SQUAT = "squats"
        const val FAMILY_LUNGE = "lunges"
        const val FAMILY_PULLUP = "pullups"

        const val PLANNED_REPS = 20

        const val STARTED_AT = 1_700_000_000_000L
        const val FINISHED_AT = 1_700_000_600_000L

        val FAMILY_OF = mapOf(
            PUSHUP to FAMILY_PUSHUP,
            SQUAT to FAMILY_SQUAT,
            LUNGE to FAMILY_LUNGE,
            PULLUP to FAMILY_PULLUP
        )
    }
}

private val adaptiveSourceDir = File("src/main/java/com/monkfitness/app/domain/adaptive")
    .let { dir -> if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app/domain/adaptive") }
