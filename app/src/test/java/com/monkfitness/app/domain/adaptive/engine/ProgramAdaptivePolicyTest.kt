package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.evidence
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.exposure
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.profile
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.signals
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §15: the target adaptive policy's rules, each measured on its own.
 *
 * The suite states one window per claim and asserts the **reason** as well as the state and the action,
 * because a `HOLD` that cannot say which of §15's hold situations it is would make every claim below
 * about a test that passes for the wrong reason. The signals are built directly rather than derived, so
 * a policy claim is never entangled with the calculator's arithmetic — that separation is asserted by
 * `ProgramAdaptiveSignalCalculatorTest` instead.
 *
 * What the suite is for: §7's progression requires confirmation, §7's regression requires sustained
 * negative evidence, §14's recovery outranks both and exits only to a hold, and the cooldown bounds a
 * change without ever inventing one.
 */
class ProgramAdaptivePolicyTest {

    private val policy = ProgramAdaptivePolicy.V1

    /** The window's own reading when a family is handling its prescription without improving on it. */
    private val plateauSignals = signals(
        exposures = exposure(exposures = 4, prescribedSets = 12, completedSets = 12),
        trend = ProgramPerformanceTrend.STABLE
    )

    /**
     * The reading of a family that fell short early in the window and completed everything since: the
     * whole window still carries a shortfall, and the recent end carries none — which is what §7's
     * progression actually requires.
     */
    private val improvementSignals = signals(
        exposures = exposure(exposures = 4, prescribedSets = 15, completedSets = 10),
        trend = ProgramPerformanceTrend.POSITIVE,
        olderHalf = exposure(exposures = 2, prescribedSets = 8, completedSets = 3),
        newerHalf = exposure(exposures = 2, prescribedSets = 7, completedSets = 7)
    )

    /** The reading of a family whose recent end has stopped meeting what it is asked for. */
    private val declineSignals = signals(
        exposures = exposure(exposures = 4, prescribedSets = 12, completedSets = 9),
        trend = ProgramPerformanceTrend.NEGATIVE,
        olderHalf = exposure(exposures = 2, prescribedSets = 6, completedSets = 6),
        newerHalf = exposure(exposures = 2, prescribedSets = 6, completedSets = 3)
    )

    // ------------------------------------------------------------------ evidence

    @Test
    fun insufficientEvidenceHoldsAndSaysSo() {
        val decision = policy.evaluate(
            evidence(
                signals = signals(exposure(exposures = 1, prescribedSets = 3, completedSets = 3)),
                evidence = EvidenceLevel.INSUFFICIENT
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE, decision.reason)
    }

    @Test
    fun aWindowWithNoHistoryIsNeverReadAsAFailure() {
        val decision = policy.evaluate(
            evidence(
                signals = signals(exposure(exposures = 0, prescribedSets = 0, completedSets = 0)),
                evidence = EvidenceLevel.INSUFFICIENT
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertNotEquals(
            "no history is missing evidence, and a regression is evidence",
            AdaptiveAction.REGRESS, decision.action
        )
        assertEquals(ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE, decision.reason)
    }

    @Test
    fun aStablePlateauHolds() {
        val decision = policy.evaluate(evidence(signals = plateauSignals, evidence = EvidenceLevel.STABLE))

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(ProgramAdaptiveReason.STABLE_PLATEAU, decision.reason)
    }

    @Test
    fun aWindowThatPointsTwoWaysAtOnceIsAMixedOneAndNotADirection() {
        val mixed = signals(
            exposures = exposure(exposures = 4, prescribedSets = 12, completedSets = 10),
            trend = ProgramPerformanceTrend.STABLE
        )

        val decision = policy.evaluate(evidence(signals = mixed))

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(ProgramAdaptiveReason.MIXED_EVIDENCE, decision.reason)
    }

    // ------------------------------------------------------------------ progression

    @Test
    fun confirmedPositiveEvidenceProgresses() {
        val decision = policy.evaluate(
            evidence(window = window(precedingProgressQualifyingWindows = 1), signals = improvementSignals)
        )

        assertEquals(AdaptiveState.PROGRESS, decision.state)
        assertEquals(AdaptiveAction.PROGRESS, decision.action)
        assertEquals(ProgramAdaptiveReason.SUSTAINED_POSITIVE, decision.reason)
    }

    @Test
    fun positiveEvidenceBeforeItIsConfirmedHolds() {
        val decision = policy.evaluate(
            evidence(window = window(precedingProgressQualifyingWindows = 0), signals = improvementSignals)
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.AWAITING_CONFIRMATION, decision.reason)
    }

    @Test
    fun aConfirmedChangeIsPossibleWhenNoChangeHasEverBeenRecorded() {
        val decision = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1, qualifyingWindowsSinceLastChange = null),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.PROGRESS, decision.state)
        assertEquals(ProgramAdaptiveReason.SUSTAINED_POSITIVE, decision.reason)
    }

    @Test
    fun theCooldownBlocksAnEarnedChangeAndSaysSo() {
        val decision = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1, qualifyingWindowsSinceLastChange = 0),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(
            "a change held back by the cooldown is reported as the cooldown, never as an absence of " +
                "evidence",
            ProgramAdaptiveReason.PROGRESSION_COOLDOWN, decision.reason
        )
    }

    @Test
    fun aCooldownThatHasElapsedDoesNotBlockTheChange() {
        val decision = policy.evaluate(
            evidence(
                window = window(
                    precedingProgressQualifyingWindows = 1,
                    qualifyingWindowsSinceLastChange = policy.progressionCooldownWindows
                ),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.PROGRESS, decision.state)
    }

    @Test
    fun aRecentContextAboveThePlanBlocksProgression() {
        val aboveThePlan = ProgramLoadComparison.of(
            profile(repetitions = 30),
            profile(repetitions = 40)
        )

        val decision = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1),
                signals = improvementSignals.copy(recentContext = aboveThePlan)
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(ProgramAdaptiveReason.RECENT_LOAD_ELEVATED, decision.reason)
    }

    @Test
    fun aWindowWhoseOpportunitiesWentUntakenDoesNotProgress() {
        val decision = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1),
                signals = improvementSignals.copy(consistency = ProgramConsistency.LOW)
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(
            "attendance is not a failure of ability, and it is not evidence for a change either",
            ProgramAdaptiveReason.INCONSISTENT_ATTENDANCE, decision.reason
        )
    }

    // ------------------------------------------------------------------ regression

    @Test
    fun confirmedNegativeEvidenceRegresses() {
        val decision = policy.evaluate(
            evidence(window = window(precedingRegressQualifyingWindows = 1), signals = declineSignals)
        )

        assertEquals(AdaptiveState.REGRESS, decision.state)
        assertEquals(AdaptiveAction.REGRESS, decision.action)
        assertEquals(ProgramAdaptiveReason.SUSTAINED_NEGATIVE, decision.reason)
    }

    @Test
    fun aSingleBadResultDoesNotRegressAFamily() {
        val oneBadResult = signals(
            exposures = exposure(exposures = 3, prescribedSets = 9, completedSets = 7),
            trend = ProgramPerformanceTrend.NEGATIVE,
            olderHalf = exposure(exposures = 1, prescribedSets = 3, completedSets = 3),
            newerHalf = exposure(exposures = 2, prescribedSets = 6, completedSets = 4)
        )

        val decision = policy.evaluate(
            evidence(window = window(precedingRegressQualifyingWindows = 3), signals = oneBadResult)
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE, decision.reason)
    }

    @Test
    fun anUnconfirmedDeclineHoldsRatherThanRegressing() {
        val decision = policy.evaluate(
            evidence(window = window(precedingRegressQualifyingWindows = 0), signals = declineSignals)
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(ProgramAdaptiveReason.AWAITING_CONFIRMATION, decision.reason)
    }

    // ------------------------------------------------------------------ recovery

    @Test
    fun recoveryIsEnteredOnUnabsorbedLoadAndOutranksTheCooldown() {
        val aboveThePlan = ProgramLoadComparison.of(profile(repetitions = 30), profile(repetitions = 40))
        val decision = policy.evaluate(
            evidence(
                window = window(qualifyingWindowsSinceLastChange = 0),
                signals = declineSignals.copy(recentContext = aboveThePlan)
            )
        )

        assertEquals(AdaptiveState.RECOVERY, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.RECOVERY_ENTERED, decision.reason)
    }

    @Test
    fun recoveryOutranksAConfirmedProgression() {
        val decision = policy.evaluate(
            evidence(
                window = window(
                    state = AdaptiveState.RECOVERY,
                    recoveryQualifyingWindows = 0,
                    precedingProgressQualifyingWindows = 1,
                    qualifyingWindowsSinceLastChange = null
                ),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.RECOVERY, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.RECOVERY_HELD, decision.reason)
    }

    @Test
    fun recoveryExitsToHoldAndNeverStraightToAProgression() {
        val decision = policy.evaluate(
            evidence(
                window = window(
                    state = AdaptiveState.RECOVERY,
                    recoveryQualifyingWindows = policy.recoveryExitQualifyingWindows,
                    precedingProgressQualifyingWindows = 1,
                    qualifyingWindowsSinceLastChange = null
                ),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.RECOVERY_EXITED, decision.reason)
        assertNotEquals(AdaptiveState.PROGRESS, decision.state)
    }

    @Test
    fun anIdleWindowNeverEntersRecovery() {
        val aboveThePlan = ProgramLoadComparison.of(profile(repetitions = 30), profile(repetitions = 40))
        val decision = policy.evaluate(
            evidence(
                signals = signals(
                    exposure(exposures = 0, prescribedSets = 0, completedSets = 0),
                    recentContext = aboveThePlan
                ),
                evidence = EvidenceLevel.INSUFFICIENT
            )
        )

        assertEquals(
            "inactivity is not failure, so an idle window is an absence of evidence and not a " +
                "recovery signal",
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE, decision.reason
        )
        assertEquals(AdaptiveState.HOLD, decision.state)
    }

    @Test
    fun aCautiousContextMakesProgressionConservativeAndInventsNothingElse() {
        val decision = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1),
                signals = improvementSignals,
                recovery = RecoveryContext.CAUTIOUS
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(ProgramAdaptiveReason.CAUTIOUS_CONTEXT, decision.reason)
        assertNotEquals("a cautious context is not a regression", AdaptiveState.REGRESS, decision.state)
    }

    // ------------------------------------------------------------------ the two judgements

    @Test
    fun confidenceIsASeparateJudgementFromEvidence() {
        val lowConfidence = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1),
                signals = improvementSignals,
                confidence = ConfidenceLevel.LOW
            )
        )
        val weakEvidence = policy.evaluate(
            evidence(
                window = window(precedingProgressQualifyingWindows = 1),
                signals = improvementSignals,
                evidence = EvidenceLevel.STABLE
            )
        )

        assertEquals(ProgramAdaptiveReason.LOW_CONFIDENCE, lowConfidence.reason)
        assertEquals(ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE, weakEvidence.reason)
        assertNotEquals(
            "the two judgements have two reasons, so neither is a relabelling of the other",
            lowConfidence.reason, weakEvidence.reason
        )
    }

    // ------------------------------------------------------------------ unsupported

    @Test
    fun aRestAdaptationIsReportedUnsupportedRatherThanFaked() {
        val decision = policy.evaluate(
            evidence(
                window = window(restChangeRequested = true, precedingProgressQualifyingWindows = 1),
                signals = improvementSignals
            )
        )

        assertEquals(AdaptiveState.HOLD, decision.state)
        assertEquals(AdaptiveAction.HOLD, decision.action)
        assertEquals(ProgramAdaptiveReason.REST_CHANGE_UNSUPPORTED, decision.reason)
    }

    // ------------------------------------------------------------------ the policy's own values

    @Test
    fun everyThresholdIsTheDocumentedV1Value() {
        assertEquals(1, policy.version)
        assertEquals(3, policy.trendMinimumExposures)
        assertEquals(4, policy.progressMinimumExposures)
        assertEquals(3, policy.regressMinimumExposures)
        assertEquals(3, policy.regressMinimumShortfallSets)
        assertEquals(3, policy.consistencyHighNumerator)
        assertEquals(4, policy.consistencyHighDenominator)
        assertEquals(1, policy.consistencyMediumNumerator)
        assertEquals(2, policy.consistencyMediumDenominator)
        assertEquals(2, policy.progressConfirmingWindows)
        assertEquals(2, policy.regressConfirmingWindows)
        assertEquals(1, policy.recoveryEntryConfirmingWindows)
        assertEquals(2, policy.recoveryProlongedWindows)
        assertEquals(2, policy.recoveryExitQualifyingWindows)
        assertEquals(2, policy.progressionCooldownWindows)
        assertTrue(policy.variantRealignmentEnabled)
        assertEquals(0, policy.guardAllowedSetIncreaseNumerator)
        assertEquals(1, policy.guardAllowedSetIncreaseDenominator)
        assertEquals(0, policy.guardAllowedAmountIncreaseNumerator)
        assertEquals(1, policy.guardAllowedAmountIncreaseDenominator)
        assertEquals(0, policy.guardAllowedWorkIncreaseNumerator)
        assertEquals(1, policy.guardAllowedWorkIncreaseDenominator)
        assertEquals(1, policy.guardAllowedLevelSteps)
        assertEquals(1, policy.guardAllowedRestDecreaseNumerator)
        assertEquals(1, policy.guardAllowedRestDecreaseDenominator)
        assertEquals(ProgramAdaptivePolicy.V1, policy)
    }

    @Test
    fun theReasonVocabularyIsExactlyTheRulesThatEmitIt() {
        assertEquals(
            "every reason belongs to exactly one side of the vocabulary",
            ProgramAdaptiveReason.entries.toSet(),
            (ProgramAdaptiveReason.holdingReasons + ProgramAdaptiveReason.changeReasons).toSet()
        )
        assertEquals(
            ProgramAdaptiveReason.entries.size,
            ProgramAdaptiveReason.holdingReasons.size + ProgramAdaptiveReason.changeReasons.size
        )
        ProgramAdaptiveReason.entries.forEach { reason ->
            assertEquals(
                "a reason's action is the action it accompanies: $reason",
                reason.action == AdaptiveAction.HOLD,
                reason.isHold
            )
        }
    }
}
