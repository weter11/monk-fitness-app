package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for AdaptivePolicy v1 and its state machine.
 *
 * Every test drives the policy from explicit evidence only: the policy must never infer a
 * qualifying window, a cooldown position or a recovery session count that the caller did not
 * supply. Reason codes and actions are asserted alongside the state, because a decision is only
 * auditable if all three agree.
 */
class AdaptivePolicyTest {

    private val policy = AdaptivePolicy.V1

    // ---------------------------------------------------------------- vocabulary and centralization

    @Test
    fun policyV1CentralizesEveryThresholdAndWindow() {
        assertEquals(1, policy.version)
        assertEquals(6, policy.eligibleSessionWindow)
        assertEquals(14, policy.adherenceWindowDays)
        assertEquals(8, policy.consistencyOpportunityWindow)
        assertEquals(4, policy.progressMinimumEligibleSessions)
        assertEquals(0.80, policy.progressMinimumExposureScore, 0.0)
        assertEquals(0.70, policy.progressMinimumAdherence, 0.0)
        assertEquals(3, policy.regressMinimumEligibleSessions)
        assertEquals(0.70, policy.regressMaximumExposureScore, 0.0)
        assertEquals(0.60, policy.recoveryStrongLowExposureScore, 0.0)
        assertEquals(0.05, policy.performancePositiveThreshold, 0.0)
        assertEquals(-0.05, policy.performanceNegativeThreshold, 0.0)
        assertEquals(1.10, policy.recentLoadNormalMaxRatio, 0.0)
        assertEquals(1.20, policy.recentLoadElevatedMaxRatio, 0.0)
        assertEquals(2, policy.progressConfirmingWindows)
        assertEquals(2, policy.regressConfirmingWindows)
        assertEquals(1, policy.recoveryEntryConfirmingWindows)
        assertEquals(2, policy.recoveryProlongedHighRiskWindows)
        assertEquals(2, policy.recoveryExitQualifyingSessions)
        assertEquals(2, policy.progressionCooldownEligibleSessions)
    }

    @Test
    fun policyClassifiesTrendsThroughItsOwnThresholds() {
        assertEquals(PerformanceTrend.POSITIVE, policy.trendOf(0.05))
        assertEquals(PerformanceTrend.POSITIVE, policy.trendOf(0.4))
        assertEquals(PerformanceTrend.STABLE, policy.trendOf(0.049))
        assertEquals(PerformanceTrend.STABLE, policy.trendOf(0.0))
        assertEquals(PerformanceTrend.STABLE, policy.trendOf(-0.049))
        assertEquals(PerformanceTrend.NEGATIVE, policy.trendOf(-0.05))
        assertEquals(PerformanceTrend.NEGATIVE, policy.trendOf(-0.4))
    }

    @Test
    fun policyBucketsRecentLoadThroughItsOwnThresholds() {
        assertEquals(RecentLoadBucket.NORMAL, policy.loadBucketOf(0.5))
        assertEquals(RecentLoadBucket.NORMAL, policy.loadBucketOf(1.10))
        assertEquals(RecentLoadBucket.ELEVATED, policy.loadBucketOf(1.1000001))
        assertEquals(RecentLoadBucket.ELEVATED, policy.loadBucketOf(1.20))
        assertEquals(RecentLoadBucket.HIGH, policy.loadBucketOf(1.2000001))
        assertEquals(RecentLoadBucket.HIGH, policy.loadBucketOf(1.21))
        assertEquals(RecentLoadBucket.HIGH, policy.loadBucketOf(3.0))
    }

    @Test
    fun reasonCodeVocabularyIsStable() {
        // These names are the stable domain contract later stages map to UI/localization text.
        assertEquals(
            listOf(
                "INSUFFICIENT_EVIDENCE",
                "SUSTAINED_POSITIVE_PERFORMANCE",
                "SUSTAINED_DECLINE",
                "HIGH_LOAD_DETERIORATION",
                "RECOVERY",
                "PROGRESSION_COOLDOWN",
                "CUSTOM_CONFIGURATION_LIMITATION"
            ),
            AdaptiveReasonCode.entries.map { it.name }
        )
        assertEquals(
            listOf("HOLD", "PROGRESS", "REGRESS", "RECOVERY"),
            AdaptiveState.entries.map { it.name }
        )
    }

    @Test
    fun customConfigurationLimitationIsRepresentableButNotEmittedByV1() {
        // Reserved for the custom-configuration stage: the model can carry it, v1 decisions do not.
        val emitted = listOf(
            defaults(),
            progressReady(),
            regressReady(),
            highRisk()
        ).map { policy.evaluate(it).reasonCode }
        assertEquals(false, emitted.contains(AdaptiveReasonCode.CUSTOM_CONFIGURATION_LIMITATION))
    }

    @Test
    fun everyStateCarriesTheActionItOrders() {
        assertEquals(AdaptiveAction.MAINTAIN_STIMULUS, AdaptiveAction.of(AdaptiveState.HOLD))
        assertEquals(AdaptiveAction.INCREASE_STIMULUS, AdaptiveAction.of(AdaptiveState.PROGRESS))
        assertEquals(AdaptiveAction.REDUCE_STIMULUS, AdaptiveAction.of(AdaptiveState.REGRESS))
        assertEquals(AdaptiveAction.RECOVERY_LOAD, AdaptiveAction.of(AdaptiveState.RECOVERY))
    }

    // ------------------------------------------------------------------------- state machine rules

    @Test
    fun insufficientEvidenceHolds() {
        assertDecision(AdaptiveState.HOLD, AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, defaults())
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(eligibleSessionCount = 0, exposureScore = 0.0, adherence = 0.0)
        )
    }

    @Test
    fun oneGoodWorkoutDoesNotProgress() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(eligibleSessionCount = 1, precedingProgressQualifyingWindows = 0)
        )
    }

    @Test
    fun oneGoodWorkoutInAnEarlierWindowStillWaitsForTheSecond() {
        // The first qualifying window is not confirmation: nothing is inferred from the past.
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(precedingProgressQualifyingWindows = 0)
        )
    }

    @Test
    fun oneBadWorkoutDoesNotRegress() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(eligibleSessionCount = 1, precedingRegressQualifyingWindows = 0)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(precedingRegressQualifyingWindows = 0)
        )
    }

    @Test
    fun sustainedPositiveEvidenceProgresses() {
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady()
        )
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(
                performanceTrend = PerformanceTrend.POSITIVE,
                precedingProgressQualifyingWindows = 4
            )
        )
    }

    @Test
    fun sustainedNegativeEvidenceRegresses() {
        assertDecision(
            AdaptiveState.REGRESS,
            AdaptiveReasonCode.SUSTAINED_DECLINE,
            regressReady()
        )
    }

    @Test
    fun highLoadDeteriorationEntersRecoveryAfterOneWindow() {
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            highRisk(performanceTrend = PerformanceTrend.NEGATIVE)
        )
        // Strongly reduced exposure under high load is the second half of the same condition.
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            highRisk(performanceTrend = PerformanceTrend.STABLE, exposureScore = 0.45)
        )
    }

    @Test
    fun prolongedHighRiskPatternEntersRecovery() {
        // The documented second entry path: strong low exposure plus declining performance,
        // sustained across two high-risk windows. No load spike is required.
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            progressReady(
                exposureScore = 0.55,
                performanceTrend = PerformanceTrend.NEGATIVE,
                precedingHighRiskWindows = 1
            )
        )
        // One such window is not yet "prolonged": a single weak session must not switch to recovery.
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(
                exposureScore = 0.55,
                performanceTrend = PerformanceTrend.NEGATIVE,
                precedingHighRiskWindows = 0,
                precedingProgressQualifyingWindows = 0
            )
        )
    }

    @Test
    fun recoveryExitsOnlyAfterTwoQualifyingSessions() {
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.RECOVERY,
            progressReady(
                currentState = AdaptiveState.RECOVERY,
                recoveryQualifyingSessions = 1
            )
        )
        assertEquals(
            AdaptiveState.HOLD,
            policy.evaluate(
                progressReady(
                    currentState = AdaptiveState.RECOVERY,
                    recoveryQualifyingSessions = 2
                )
            ).state
        )
        assertEquals(
            AdaptiveReasonCode.RECOVERY,
            policy.evaluate(
                progressReady(
                    currentState = AdaptiveState.RECOVERY,
                    recoveryQualifyingSessions = 2
                )
            ).reasonCode
        )
        // A renewed high-risk window keeps recovery gating even when the count is already there.
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            highRisk(currentState = AdaptiveState.RECOVERY, recoveryQualifyingSessions = 2)
        )
    }

    @Test
    fun recoveryNeverBecomesProgressDirectly() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.RECOVERY,
            progressReady(
                currentState = AdaptiveState.RECOVERY,
                recoveryQualifyingSessions = 2,
                precedingProgressQualifyingWindows = 9,
                performanceTrend = PerformanceTrend.POSITIVE
            )
        )
        // Same evidence, then a following window with the same qualifying history may progress.
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(
                currentState = AdaptiveState.HOLD,
                precedingProgressQualifyingWindows = 1,
                performanceTrend = PerformanceTrend.POSITIVE
            )
        )
    }

    @Test
    fun highLoadDeteriorationIsNotAvailableAsNormalProgress() {
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            highRisk(
                currentState = AdaptiveState.PROGRESS,
                performanceTrend = PerformanceTrend.NEGATIVE,
                exposureScore = 0.95,
                eligibleSessionsSinceLastProgressionChange = 9
            )
        )
    }

    // ---------------------------------------------------------- gating, thresholds and determinism

    @Test
    fun progressionCooldownBlocksAnotherProgressionChange() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.PROGRESSION_COOLDOWN,
            progressReady(eligibleSessionsSinceLastProgressionChange = 0)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.PROGRESSION_COOLDOWN,
            progressReady(eligibleSessionsSinceLastProgressionChange = 1)
        )
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(eligibleSessionsSinceLastProgressionChange = 2)
        )
    }

    @Test
    fun progressionCooldownGatesRegressionChangesToo() {
        // A progression-level change in either direction restarts the same cooldown.
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.PROGRESSION_COOLDOWN,
            regressReady(eligibleSessionsSinceLastProgressionChange = 0)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.PROGRESSION_COOLDOWN,
            regressReady(eligibleSessionsSinceLastProgressionChange = 1)
        )
        assertDecision(
            AdaptiveState.REGRESS,
            AdaptiveReasonCode.SUSTAINED_DECLINE,
            regressReady(eligibleSessionsSinceLastProgressionChange = 2)
        )
    }

    @Test
    fun recoveryEntryIsNotCooldownGated() {
        // Recovery is the safety path: an expansion cooldown must never delay it.
        assertDecision(
            AdaptiveState.RECOVERY,
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            highRisk(eligibleSessionsSinceLastProgressionChange = 0)
        )
    }

    @Test
    fun highLoadPreventsNormalProgress() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(
                recentLoadBucket = RecentLoadBucket.HIGH,
                precedingProgressQualifyingWindows = 5
            )
        )
    }

    @Test
    fun elevatedLoadDoesNotPreventProgress() {
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(recentLoadBucket = RecentLoadBucket.ELEVATED)
        )
    }

    @Test
    fun negativeTrendPreventsProgress() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(
                performanceTrend = PerformanceTrend.NEGATIVE,
                precedingProgressQualifyingWindows = 3
            )
        )
    }

    @Test
    fun positiveTrendAloneDoesNotProgress() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(
                performanceTrend = PerformanceTrend.POSITIVE,
                exposureScore = 0.79,
                precedingProgressQualifyingWindows = 3
            )
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(
                performanceTrend = PerformanceTrend.POSITIVE,
                adherence = 0.69,
                precedingProgressQualifyingWindows = 3
            )
        )
    }

    @Test
    fun progressExposureBoundaryIsInclusive() {
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(exposureScore = 0.80)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(exposureScore = 0.7999)
        )
    }

    @Test
    fun progressAdherenceBoundaryIsInclusive() {
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(adherence = 0.70)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(adherence = 0.6999)
        )
    }

    @Test
    fun progressEligibleSessionBoundaryIsFour() {
        assertDecision(
            AdaptiveState.PROGRESS,
            AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            progressReady(eligibleSessionCount = 4)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(eligibleSessionCount = 3)
        )
    }

    @Test
    fun regressExposureBoundaryIsExclusive() {
        // Exactly 0.70 is not a regression: the rule is strictly below the threshold.
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(exposureScore = 0.70)
        )
        assertDecision(
            AdaptiveState.REGRESS,
            AdaptiveReasonCode.SUSTAINED_DECLINE,
            regressReady(exposureScore = 0.6999)
        )
    }

    @Test
    fun regressRequiresANegativeTrend() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(exposureScore = 0.50, performanceTrend = PerformanceTrend.STABLE)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(exposureScore = 0.50, performanceTrend = PerformanceTrend.POSITIVE)
        )
    }

    @Test
    fun regressEligibleSessionBoundaryIsThree() {
        assertDecision(
            AdaptiveState.REGRESS,
            AdaptiveReasonCode.SUSTAINED_DECLINE,
            regressReady(eligibleSessionCount = 3)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(eligibleSessionCount = 2)
        )
    }

    @Test
    fun theCurrentProgressStateIsNotProofOfAnotherProgression() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            progressReady(currentState = AdaptiveState.PROGRESS, precedingProgressQualifyingWindows = 0)
        )
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.PROGRESSION_COOLDOWN,
            progressReady(
                currentState = AdaptiveState.PROGRESS,
                eligibleSessionsSinceLastProgressionChange = 1
            )
        )
    }

    @Test
    fun aStoredRegressionStateIsReconfirmedFromEvidenceOnly() {
        assertDecision(
            AdaptiveState.HOLD,
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            regressReady(currentState = AdaptiveState.REGRESS, precedingRegressQualifyingWindows = 0)
        )
        assertDecision(
            AdaptiveState.REGRESS,
            AdaptiveReasonCode.SUSTAINED_DECLINE,
            regressReady(currentState = AdaptiveState.REGRESS)
        )
    }

    @Test
    fun policyVersionIsCarriedIntoEveryDecision() {
        val evidences = listOf(defaults(), progressReady(), regressReady(), highRisk())
        evidences.forEach { evidence ->
            assertEquals(1, policy.evaluate(evidence).policyVersion)
        }
        // A future policy version stamps its own version rather than a hardcoded one.
        val futurePolicy = AdaptivePolicy(version = 7)
        assertEquals(7, futurePolicy.evaluate(progressReady()).policyVersion)
    }

    @Test
    fun identicalInputsProduceIdenticalDecisions() {
        val evidence = progressReady()
        val first = policy.evaluate(evidence)
        val second = policy.evaluate(evidence)
        assertEquals(first, second)
        assertEquals(first, AdaptivePolicy.V1.evaluate(evidence))
        // Equality is by value: the decision is a plain auditable snapshot.
        assertEquals(first, first.copy())
    }

    @Test
    fun identicalInputsProduceIdenticalDecisionsAcrossTheWholeEvidenceSpace() {
        val evidences = listOf(
            defaults(),
            progressReady(),
            progressReady(exposureScore = 0.80, adherence = 0.70, eligibleSessionCount = 4),
            progressReady(eligibleSessionCount = 3),
            regressReady(),
            regressReady(exposureScore = 0.70),
            regressReady(eligibleSessionsSinceLastProgressionChange = 1),
            highRisk(),
            highRisk(currentState = AdaptiveState.RECOVERY, recoveryQualifyingSessions = 2),
            progressReady(currentState = AdaptiveState.RECOVERY, recoveryQualifyingSessions = 1),
            progressReady(recentLoadBucket = RecentLoadBucket.HIGH),
            progressReady(performanceTrend = PerformanceTrend.NEGATIVE)
        )
        // Every listed evidence re-evaluates to the same decision on a repeat run.
        evidences.forEach { evidence ->
            val expected = policy.evaluate(evidence)
            assertEquals(expected, policy.evaluate(evidence))
            assertEquals(expected, AdaptivePolicy.V1.evaluate(evidence))
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun defaults() = AdaptiveEvidence(
        eligibleSessionsSinceLastProgressionChange = 2
    )

    private fun progressReady(
        currentState: AdaptiveState = AdaptiveState.HOLD,
        eligibleSessionCount: Int = 4,
        exposureScore: Double = 0.85,
        adherence: Double = 0.80,
        performanceTrend: PerformanceTrend = PerformanceTrend.STABLE,
        recentLoadBucket: RecentLoadBucket = RecentLoadBucket.NORMAL,
        precedingProgressQualifyingWindows: Int = 1,
        precedingRegressQualifyingWindows: Int = 0,
        precedingHighRiskWindows: Int = 0,
        recoveryQualifyingSessions: Int = 0,
        eligibleSessionsSinceLastProgressionChange: Int = 2
    ) = AdaptiveEvidence(
        currentState = currentState,
        eligibleSessionCount = eligibleSessionCount,
        exposureScore = exposureScore,
        adherence = adherence,
        performanceTrend = performanceTrend,
        recentLoadBucket = recentLoadBucket,
        precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
        precedingHighRiskWindows = precedingHighRiskWindows,
        recoveryQualifyingSessions = recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange
    )

    private fun regressReady(
        currentState: AdaptiveState = AdaptiveState.HOLD,
        eligibleSessionCount: Int = 3,
        exposureScore: Double = 0.55,
        adherence: Double = 0.50,
        performanceTrend: PerformanceTrend = PerformanceTrend.NEGATIVE,
        recentLoadBucket: RecentLoadBucket = RecentLoadBucket.NORMAL,
        precedingProgressQualifyingWindows: Int = 0,
        precedingRegressQualifyingWindows: Int = 1,
        precedingHighRiskWindows: Int = 0,
        recoveryQualifyingSessions: Int = 0,
        eligibleSessionsSinceLastProgressionChange: Int = 2
    ) = progressReady(
        currentState = currentState,
        eligibleSessionCount = eligibleSessionCount,
        exposureScore = exposureScore,
        adherence = adherence,
        performanceTrend = performanceTrend,
        recentLoadBucket = recentLoadBucket,
        precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
        precedingHighRiskWindows = precedingHighRiskWindows,
        recoveryQualifyingSessions = recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange
    )

    private fun highRisk(
        currentState: AdaptiveState = AdaptiveState.HOLD,
        performanceTrend: PerformanceTrend = PerformanceTrend.NEGATIVE,
        exposureScore: Double = 0.85,
        precedingHighRiskWindows: Int = 0,
        recoveryQualifyingSessions: Int = 0,
        eligibleSessionsSinceLastProgressionChange: Int = 2
    ) = progressReady(
        currentState = currentState,
        exposureScore = exposureScore,
        performanceTrend = performanceTrend,
        recentLoadBucket = RecentLoadBucket.HIGH,
        precedingHighRiskWindows = precedingHighRiskWindows,
        recoveryQualifyingSessions = recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange
    )

    private fun assertDecision(
        expectedState: AdaptiveState,
        expectedReason: AdaptiveReasonCode,
        evidence: AdaptiveEvidence
    ) {
        val decision = policy.evaluate(evidence)
        assertEquals("state", expectedState, decision.state)
        assertEquals("reason", expectedReason, decision.reasonCode)
        assertEquals("actions", listOf(AdaptiveAction.of(expectedState)), decision.actions)
        assertEquals("policyVersion", 1, decision.policyVersion)
    }
}