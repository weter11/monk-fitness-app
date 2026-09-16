package com.monkfitness.app.domain.adaptive

/**
 * Bucketed performance trend, as the signal layer hands it over. The buckets are produced through
 * [AdaptivePolicy.trendOf] so the threshold that decides them lives with the rest of the policy.
 */
enum class PerformanceTrend {
    POSITIVE,
    STABLE,
    NEGATIVE
}

/**
 * Bucketed recent program load: the last seven-day workload compared with the seven days before it.
 * [NORMAL] is the neutral bucket; [HIGH] blocks normal progression and, with deterioration, is what
 * can switch a family into RECOVERY. This is program workload, not a physiological measurement.
 */
enum class RecentLoadBucket {
    NORMAL,
    ELEVATED,
    HIGH
}

/**
 * Bucketed attendance over the latest planned workout opportunities. The buckets are deliberately
 * coarse — the signal says "attended consistently", "attended about half the time" or "attended
 * inconsistently", never a statistical claim about the user. Produced through
 * [AdaptivePolicy.consistencyOf] so the two ratios live with the rest of the policy.
 */
enum class ConsistencyBucket {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Everything the policy is allowed to look at: already-normalized signal values plus the window,
 * cooldown and recovery evidence the caller maintains.
 *
 * The policy performs no history queries and infers nothing. In particular it never derives a
 * qualifying-window count, a recovery session count or a cooldown position from [eligibleSessionCount]
 * — those are supplied explicitly, so a decision is reproducible from its own inputs.
 *
 * Counts are conservative by default (no prior qualifying window, no recovery sessions), which can
 * only push a decision toward HOLD. [eligibleSessionsSinceLastProgressionChange] has no default on
 * purpose: "no progression change recorded yet" must never be assumed silently, because that would
 * let an unknown cooldown position expand the program.
 */
data class AdaptiveEvidence(
    /** The adaptation state currently held by the caller for this family. */
    val currentState: AdaptiveState = AdaptiveState.HOLD,

    /** Eligible sessions in the observation window, counted the same way the signals were. */
    val eligibleSessionCount: Int = 0,

    /** Weighted exposure score over the recent eligible sessions, normalized to `0..1`. */
    val exposureScore: Double = 0.0,

    /** Attendance over the adherence window, normalized to `0..1`. */
    val adherence: Double = 0.0,

    val performanceTrend: PerformanceTrend = PerformanceTrend.STABLE,

    val recentLoadBucket: RecentLoadBucket = RecentLoadBucket.NORMAL,

    /** Consecutive decision windows *before* this one in which the PROGRESS conditions held. */
    val precedingProgressQualifyingWindows: Int = 0,

    /** Consecutive decision windows *before* this one in which the REGRESS conditions held. */
    val precedingRegressQualifyingWindows: Int = 0,

    /** Consecutive decision windows *before* this one that qualified as high risk. */
    val precedingHighRiskWindows: Int = 0,

    /** Qualifying sessions completed while in RECOVERY: the count its exit gate is measured in. */
    val recoveryQualifyingSessions: Int = 0,

    /**
     * Eligible sessions since the last progression-level change for this family, or `null` when the
     * family has never had one. `null` is deliberately not the same as `0`: the cooldown applies
     * only *after* an actual change, so the first confirmed change is never blocked by it.
     */
    val eligibleSessionsSinceLastProgressionChange: Int?
) {

    init {
        require(eligibleSessionCount >= 0) { "eligibleSessionCount must be >= 0, was $eligibleSessionCount" }
        require(exposureScore in 0.0..1.0) { "exposureScore must be normalized to 0..1, was $exposureScore" }
        require(adherence in 0.0..1.0) { "adherence must be normalized to 0..1, was $adherence" }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingHighRiskWindows >= 0) {
            "precedingHighRiskWindows must be >= 0, was $precedingHighRiskWindows"
        }
        require(recoveryQualifyingSessions >= 0) {
            "recoveryQualifyingSessions must be >= 0, was $recoveryQualifyingSessions"
        }
        require(
            eligibleSessionsSinceLastProgressionChange == null ||
                eligibleSessionsSinceLastProgressionChange >= 0
        ) {
            "eligibleSessionsSinceLastProgressionChange must be null or >= 0, was $eligibleSessionsSinceLastProgressionChange"
        }
    }
}

/**
 * The single home of every v1 threshold, window and confirmation count, plus the state machine that
 * applies them. Nothing in this file decides adaptation from a literal: the numbers live here and
 * are read only by [evaluate] and by the two bucket helpers, which the signal layer (a later stage)
 * calls instead of re-deriving its own thresholds — now three: [trendOf], [loadBucketOf] and
 * [consistencyOf].
 *
 * Transition order in [evaluate], highest priority first:
 *
 *  1. **high risk** → RECOVERY. High recent load with a negative trend or strongly reduced exposure
 *     ([AdaptiveReasonCode.HIGH_LOAD_DETERIORATION]), or the prolonged low-exposure pattern
 *     ([AdaptiveReasonCode.RECOVERY]), after [recoveryEntryConfirmingWindows] qualifying window
 *     (one by default). This is the safety path: it is never blocked by the progression cooldown.
 *  2. **RECOVERY gating** — while the state reported by the caller is RECOVERY, the family stays
 *     there until [recoveryExitQualifyingSessions] qualifying sessions have accumulated, then leaves
 *     to HOLD. It never leaves directly to PROGRESS; a later window may progress normally.
 *  3. **confirmed PROGRESS** → PROGRESS, when the progress conditions hold in
 *     [progressConfirmingWindows] consecutive windows and the progression cooldown has elapsed.
 *  4. **confirmed REGRESS** → REGRESS, the same way, with [regressConfirmingWindows].
 *  5. **confirmed but cooling down** → HOLD with [AdaptiveReasonCode.PROGRESSION_COOLDOWN]. A
 *     confirmed condition that is only blocked by the cooldown is reported as such rather than
 *     silently as insufficient evidence.
 *  6. **everything else** → HOLD with [AdaptiveReasonCode.INSUFFICIENT_EVIDENCE].
 *
 * The cooldown is read symmetrically: [AdaptiveEvidence.eligibleSessionsSinceLastProgressionChange]
 * counts eligible sessions since the family's last progression-*level* change, in either direction,
 * because [progressionCooldownEligibleSessions] exists to stop oscillation, not to stop corrections.
 * It applies only *after* a change: a family with no recorded change (`null`) has nothing to cool
 * down from, so its first confirmed change goes through.
 *
 * PROGRESS and REGRESS are orders for the current window, not stored memory: a caller that holds
 * PROGRESS in `currentState` gains nothing from it, and must supply the qualifying-window evidence
 * again. Only RECOVERY is re-emitted from the caller's state, because its exit gate is counted in
 * qualifying sessions.
 */
data class AdaptivePolicy(
    val version: Int = V1_VERSION,

    /** Eligible sessions the exposure score is weighted over. */
    val eligibleSessionWindow: Int = 6,

    /** Calendar days the adherence ratio is measured over. */
    val adherenceWindowDays: Int = 14,

    /** Planned workout opportunities the consistency bucket is measured over. */
    val consistencyOpportunityWindow: Int = 8,

    val progressMinimumEligibleSessions: Int = 4,
    val progressMinimumExposureScore: Double = 0.80,
    val progressMinimumAdherence: Double = 0.70,
    val regressMinimumEligibleSessions: Int = 3,

    /** The regress rule is strictly below this exposure score. */
    val regressMaximumExposureScore: Double = 0.70,

    /** "Strongly reduced" exposure: with a negative trend this is the prolonged high-risk pattern. */
    val recoveryStrongLowExposureScore: Double = 0.60,

    val performancePositiveThreshold: Double = 0.05,
    val performanceNegativeThreshold: Double = -0.05,

    /** `recent7 / previous7` at or below this ratio is NORMAL. */
    val recentLoadNormalMaxRatio: Double = 1.10,

    /** ... and at or below this ratio is ELEVATED; above it is HIGH. */
    val recentLoadElevatedMaxRatio: Double = 1.20,

    val progressConfirmingWindows: Int = 2,
    val regressConfirmingWindows: Int = 2,
    val recoveryEntryConfirmingWindows: Int = 1,
    val recoveryProlongedHighRiskWindows: Int = 2,
    val recoveryExitQualifyingSessions: Int = 2,
    val progressionCooldownEligibleSessions: Int = 2,

    /** Planned-work fraction that makes a session a meaningful start when no exercise was completed. */
    val adherenceMeaningfulStartMinWorkRatio: Double = 0.10,

    /** Attendance at or above this fraction of the consistency opportunities is [ConsistencyBucket.HIGH]. */
    val consistencyHighMinRatio: Double = 0.75,

    /** ... and at or above this fraction is [ConsistencyBucket.MEDIUM]; below it is LOW. */
    val consistencyMediumMinRatio: Double = 0.50,

    /** Meaningful exposures of one exercise/family the performance trend is measured over. */
    val performanceTrendExposures: Int = 5,

    /** Fewer exposures than this claim no trend at all: the bucket stays STABLE. */
    val performanceTrendMinimumExposures: Int = 3,

    /** Consecutive newest eligible sessions without completion that make a poor completion streak. */
    val poorCompletionStreakSessions: Int = 2,

    /** Program days per load window: the recent one, and the preceding one it is compared against. */
    val recentLoadWindowDays: Int = 7
) {

    /** Buckets a raw trend value through [performancePositiveThreshold]/[performanceNegativeThreshold]. */
    fun trendOf(trend: Double): PerformanceTrend = when {
        trend >= performancePositiveThreshold -> PerformanceTrend.POSITIVE
        trend <= performanceNegativeThreshold -> PerformanceTrend.NEGATIVE
        else -> PerformanceTrend.STABLE
    }

    /** Buckets a raw `recent7 / previous7` load ratio through the two load thresholds. */
    fun loadBucketOf(loadRatio: Double): RecentLoadBucket = when {
        loadRatio <= recentLoadNormalMaxRatio -> RecentLoadBucket.NORMAL
        loadRatio <= recentLoadElevatedMaxRatio -> RecentLoadBucket.ELEVATED
        else -> RecentLoadBucket.HIGH
    }

    /** Buckets an attendance ratio through [consistencyHighMinRatio]/[consistencyMediumMinRatio]. */
    fun consistencyOf(attendanceRatio: Double): ConsistencyBucket = when {
        attendanceRatio >= consistencyHighMinRatio -> ConsistencyBucket.HIGH
        attendanceRatio >= consistencyMediumMinRatio -> ConsistencyBucket.MEDIUM
        else -> ConsistencyBucket.LOW
    }

    /** The deterministic policy decision for one decision window. */
    fun evaluate(evidence: AdaptiveEvidence): AdaptiveDecision {
        val recoveryReason = recoveryEntryReason(evidence)
        val progressConfirmed = progressConditionsMet(evidence) &&
            evidence.precedingProgressQualifyingWindows + 1 >= progressConfirmingWindows
        val regressConfirmed = regressConditionsMet(evidence) &&
            evidence.precedingRegressQualifyingWindows + 1 >= regressConfirmingWindows
        // A family that never had a progression-level change has nothing to cool down from.
        val cooldownElapsed = evidence.eligibleSessionsSinceLastProgressionChange == null ||
            evidence.eligibleSessionsSinceLastProgressionChange >= progressionCooldownEligibleSessions

        val state: AdaptiveState
        val reason: AdaptiveReasonCode

        when {
            recoveryReason != null -> {
                state = AdaptiveState.RECOVERY
                reason = recoveryReason
            }

            evidence.currentState == AdaptiveState.RECOVERY -> when {
                evidence.recoveryQualifyingSessions >= recoveryExitQualifyingSessions -> {
                    state = AdaptiveState.HOLD
                    reason = AdaptiveReasonCode.RECOVERY
                }

                else -> {
                    state = AdaptiveState.RECOVERY
                    reason = AdaptiveReasonCode.RECOVERY
                }
            }

            progressConfirmed && cooldownElapsed -> {
                state = AdaptiveState.PROGRESS
                reason = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE
            }

            regressConfirmed && cooldownElapsed -> {
                state = AdaptiveState.REGRESS
                reason = AdaptiveReasonCode.SUSTAINED_DECLINE
            }

            progressConfirmed || regressConfirmed -> {
                state = AdaptiveState.HOLD
                reason = AdaptiveReasonCode.PROGRESSION_COOLDOWN
            }

            else -> {
                state = AdaptiveState.HOLD
                reason = AdaptiveReasonCode.INSUFFICIENT_EVIDENCE
            }
        }

        return AdaptiveDecision(
            state = state,
            previousState = evidence.currentState,
            actions = listOf(AdaptiveAction.of(state)),
            reasonCode = reason,
            policyVersion = version
        )
    }

    private fun progressConditionsMet(evidence: AdaptiveEvidence): Boolean =
        evidence.eligibleSessionCount >= progressMinimumEligibleSessions &&
            evidence.exposureScore >= progressMinimumExposureScore &&
            evidence.adherence >= progressMinimumAdherence &&
            evidence.performanceTrend != PerformanceTrend.NEGATIVE &&
            evidence.recentLoadBucket != RecentLoadBucket.HIGH

    private fun regressConditionsMet(evidence: AdaptiveEvidence): Boolean =
        evidence.eligibleSessionCount >= regressMinimumEligibleSessions &&
            evidence.exposureScore < regressMaximumExposureScore &&
            evidence.performanceTrend == PerformanceTrend.NEGATIVE

    /**
     * The reason RECOVERY is entered for this window, or `null` when the window is not high risk.
     *
     * The high-load path keeps [AdaptiveReasonCode.HIGH_LOAD_DETERIORATION]; the prolonged
     * low-exposure pattern (strong low exposure plus a negative trend, with no HIGH load
     * involved) reports [AdaptiveReasonCode.RECOVERY], so the audit trail does not claim a load
     * spike that the evidence never showed.
     */
    private fun recoveryEntryReason(evidence: AdaptiveEvidence): AdaptiveReasonCode? {
        val loadAndDeterioration = evidence.recentLoadBucket == RecentLoadBucket.HIGH &&
            (
                evidence.performanceTrend == PerformanceTrend.NEGATIVE ||
                    evidence.exposureScore < recoveryStrongLowExposureScore
                )
        val prolongedPattern = evidence.exposureScore < recoveryStrongLowExposureScore &&
            evidence.performanceTrend == PerformanceTrend.NEGATIVE &&
            evidence.precedingHighRiskWindows + 1 >= recoveryProlongedHighRiskWindows

        val qualifyingWindow = loadAndDeterioration || prolongedPattern
        if (!qualifyingWindow || evidence.precedingHighRiskWindows + 1 < recoveryEntryConfirmingWindows) {
            return null
        }

        return if (loadAndDeterioration) {
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION
        } else {
            AdaptiveReasonCode.RECOVERY
        }
    }

    init {
        require(version >= 1) { "version must be >= 1, was $version" }
        require(progressConfirmingWindows >= 1 && regressConfirmingWindows >= 1) {
            "confirmation windows must be >= 1, were progress=$progressConfirmingWindows regress=$regressConfirmingWindows"
        }
        require(recoveryEntryConfirmingWindows >= 1) {
            "recoveryEntryConfirmingWindows must be >= 1, was $recoveryEntryConfirmingWindows"
        }
        require(recoveryProlongedHighRiskWindows >= 1) {
            "recoveryProlongedHighRiskWindows must be >= 1, was $recoveryProlongedHighRiskWindows"
        }
        require(recoveryExitQualifyingSessions >= 1) {
            "recoveryExitQualifyingSessions must be >= 1, was $recoveryExitQualifyingSessions"
        }
        require(progressionCooldownEligibleSessions >= 0) {
            "progressionCooldownEligibleSessions must be >= 0, was $progressionCooldownEligibleSessions"
        }
        require(regressMinimumEligibleSessions >= 1 && progressMinimumEligibleSessions >= 1) {
            "minimum eligible session counts must be >= 1"
        }
        require(progressMinimumExposureScore in 0.0..1.0) {
            "progressMinimumExposureScore must be normalized to 0..1, was $progressMinimumExposureScore"
        }
        require(regressMaximumExposureScore in 0.0..1.0) {
            "regressMaximumExposureScore must be normalized to 0..1, was $regressMaximumExposureScore"
        }
        require(recoveryStrongLowExposureScore in 0.0..1.0) {
            "recoveryStrongLowExposureScore must be normalized to 0..1, was $recoveryStrongLowExposureScore"
        }
        require(progressMinimumAdherence in 0.0..1.0) {
            "progressMinimumAdherence must be normalized to 0..1, was $progressMinimumAdherence"
        }
        require(performanceNegativeThreshold <= performancePositiveThreshold) {
            "performanceNegativeThreshold must not exceed performancePositiveThreshold"
        }
        require(recentLoadNormalMaxRatio <= recentLoadElevatedMaxRatio) {
            "recentLoadNormalMaxRatio must not exceed recentLoadElevatedMaxRatio"
        }
        require(consistencyMediumMinRatio <= consistencyHighMinRatio) {
            "consistencyMediumMinRatio must not exceed consistencyHighMinRatio"
        }
        require(adherenceMeaningfulStartMinWorkRatio in 0.0..1.0) {
            "adherenceMeaningfulStartMinWorkRatio must be normalized to 0..1, was $adherenceMeaningfulStartMinWorkRatio"
        }
        require(performanceTrendMinimumExposures >= 1) {
            "performanceTrendMinimumExposures must be >= 1, was $performanceTrendMinimumExposures"
        }
        require(performanceTrendExposures >= performanceTrendMinimumExposures) {
            "performanceTrendExposures must be >= performanceTrendMinimumExposures, were " +
                "exposures=$performanceTrendExposures minimum=$performanceTrendMinimumExposures"
        }
        require(poorCompletionStreakSessions >= 1) {
            "poorCompletionStreakSessions must be >= 1, was $poorCompletionStreakSessions"
        }
        require(recentLoadWindowDays >= 1) {
            "recentLoadWindowDays must be >= 1, was $recentLoadWindowDays"
        }
    }

    companion object {
        const val V1_VERSION: Int = 1

        /** Adaptive policy version 1: every parameter at its documented v1 value. */
        val V1: AdaptivePolicy = AdaptivePolicy()
    }
}