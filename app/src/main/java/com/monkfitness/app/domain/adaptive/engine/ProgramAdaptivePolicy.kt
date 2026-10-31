package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import java.time.Duration
import java.time.LocalDate

/**
 * Gap facts handed to the policy. [daysAway] measures the stretch before this window's own session,
 * so it is not derivable from [lastConfirmedSetOn]: that already includes the session being judged.
 */
data class ProgramGapFacts(
    val lastConfirmedSetOn: LocalDate? = null,
    val daysAway: Long? = null,
    val usualInterval: Duration? = null
) {

    companion object {

        val NONE: ProgramGapFacts = ProgramGapFacts()
    }
}

/**
 * One family's circumstances in one decision window. [qualifyingWindowsSinceLastChange] is `null`, not
 * `0`, when no change has ever happened: the cooldown only exists to stop oscillation after a change.
 */
data class ProgramAdaptiveWindow(
    val familyId: String,
    val level: Int? = null,
    val state: AdaptiveState = AdaptiveState.HOLD,
    val precedingProgressQualifyingWindows: Int = 0,
    val precedingRegressQualifyingWindows: Int = 0,
    val precedingRecoveryQualifyingWindows: Int = 0,
    val qualifyingWindowsSinceLastChange: Int? = null,
    val recoveryQualifyingWindows: Int = 0,
    val restChangeRequested: Boolean = false,
    val gap: ProgramGapFacts = ProgramGapFacts.NONE
) {

    init {
        require(familyId.isNotBlank()) { "a decision window must name its family" }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingRecoveryQualifyingWindows >= 0) {
            "precedingRecoveryQualifyingWindows must be >= 0, was $precedingRecoveryQualifyingWindows"
        }
        require(recoveryQualifyingWindows >= 0) {
            "recoveryQualifyingWindows must be >= 0, was $recoveryQualifyingWindows"
        }
        require(qualifyingWindowsSinceLastChange == null || qualifyingWindowsSinceLastChange >= 0) {
            "qualifyingWindowsSinceLastChange must be null or >= 0, was " +
                "$qualifyingWindowsSinceLastChange"
        }
    }
}

/** The maintained window facts, the derived signals, and three qualitative inputs. */
data class ProgramAdaptiveEvidence(
    val window: ProgramAdaptiveWindow,
    val signals: ProgramAdaptiveSignals,
    val evidence: EvidenceLevel,
    val confidence: ConfidenceLevel,
    val recovery: RecoveryContext
) {

    init {
        require(window.familyId == signals.familyId) {
            "the window and its signals are about one family: window=${window.familyId} " +
                "signals=${signals.familyId}"
        }
    }
}

/**
 * What the policy read in one window. A window cannot count itself, so this is the only place the
 * qualifying facts exist; the caller advances its confirmation counters from them.
 */
data class ProgramWindowVerdict(
    val progressQualifying: Boolean = false,
    val regressQualifying: Boolean = false,
    val recoveryQualifying: Boolean = false
) {

    init {
        require(!(progressQualifying && regressQualifying)) {
            "a window's trend is either positive or negative: the two directions are mutually exclusive"
        }
    }

    val isIdle: Boolean
        get() = !progressQualifying && !regressQualifying && !recoveryQualifying
}

data class ProgramPolicyDecision(
    val state: AdaptiveState,
    val action: AdaptiveAction,
    val reason: ProgramAdaptiveReason,
    val verdict: ProgramWindowVerdict = ProgramWindowVerdict()
) {

    init {
        require(action == reason.action) {
            "a decision's action is the action its reason accompanies: action=$action reason=$reason"
        }
        require(reason != ProgramAdaptiveReason.SUSTAINED_POSITIVE || verdict.progressQualifying) {
            "a sustained positive change was reached from a window that qualified for progression"
        }
        require(reason != ProgramAdaptiveReason.SUSTAINED_NEGATIVE || verdict.regressQualifying) {
            "a sustained negative change was reached from a window that qualified for regression"
        }
        require(reason != ProgramAdaptiveReason.RECOVERY_ENTERED || verdict.recoveryQualifying) {
            "entering recovery was reached from a window whose reduced-absorption pattern held"
        }
    }

    val asksForAChange: Boolean
        get() = action != AdaptiveAction.HOLD
}

/**
 * One home for every threshold the adaptive stage uses, plus the state machine that applies them.
 *
 * Branch order is the policy: recovery outranks progression, a family leaving recovery must earn its
 * change again, and a return from a gap pre-empts the confirmation rules whose evidence predates it.
 */
data class ProgramAdaptivePolicy(
    val version: Int = V1_VERSION,

    val trendMinimumExposures: Int = 3,

    val progressMinimumExposures: Int = 4,

    val regressMinimumExposures: Int = 3,

    val regressMinimumShortfallSets: Int = 3,

    /** Completed opportunities at or above this fraction of them is high attendance. */
    val consistencyHighNumerator: Int = 3,
    val consistencyHighDenominator: Int = 4,

    /** ... and at or above this fraction is medium attendance; below it is low. */
    val consistencyMediumNumerator: Int = 1,
    val consistencyMediumDenominator: Int = 2,

    val progressConfirmingWindows: Int = 2,
    val regressConfirmingWindows: Int = 2,
    val recoveryEntryConfirmingWindows: Int = 1,

    val recoveryProlongedWindows: Int = 2,

    val recoveryExitQualifyingWindows: Int = 2,

    val progressionCooldownWindows: Int = 2,

/** States a habit over the last N sessions; a count of sessions, not of days. */
    val usualIntervalSessions: Int = 5,

/**
 * Used when the history is too short to state one. A *usual interval*, not a second baseline: every
 * threshold below is stated as a multiple of it, so the fallback feeds the same arithmetic.
 */
    val usualIntervalFallbackDays: Int = 3,

/**
 * More than this many of the user's own intervals is a return, not a missed session: two missed
 * sessions are exactly three, so the strict compare is the first gap skipping cannot explain.
 */
    val returnGapUsualIntervalMultiple: Int = 3,

/**
 * A floor for frequent trainers, in calendar days: the unit the user experiences the absence in.
 */
    val returnGapMinimumDays: Int = 10,

/**
 * A share of the exercise's **range width**, not a repetition count: the range is the only span the
 * adaptive layer knows the exercise's difficulty in, and it is stated in the exercise's own unit.
 */
    val returnGapReductionNumerator: Int = 1,
    val returnGapReductionDenominator: Int = 2,

/**
 * Where the share stops growing: a year away is not three times the adjustment of four months, and
 * the range's floor is reached by the clamp instead.
 */
    val returnGapFullReductionIntervals: Int = 6,

/** May re-point an element to another variant of the same level the user selected. */
    val variantRealignmentEnabled: Boolean = true,

    /** An automatic change may add at most this fraction of the baseline's own **sets**. */
    val guardAllowedSetIncreaseNumerator: Int = 0,
    val guardAllowedSetIncreaseDenominator: Int = 1,

    /** ... at most this fraction of the baseline's own repetitions (or seconds). */
    val guardAllowedAmountIncreaseNumerator: Int = 0,
    val guardAllowedAmountIncreaseDenominator: Int = 1,

    /** ... at most this fraction of the baseline's own working seconds. */
    val guardAllowedWorkIncreaseNumerator: Int = 0,
    val guardAllowedWorkIncreaseDenominator: Int = 1,

    /** How many of the family's own declared levels one automatic change may travel. */
    val guardAllowedLevelSteps: Int = 1,

    /** Rest may not shorten below this fraction of the baseline's rest: the guard never asks for less. */
    val guardAllowedRestDecreaseNumerator: Int = 1,
    val guardAllowedRestDecreaseDenominator: Int = 1
) {

/**
 * The deterministic decision for one window. Branch order is the policy, and every branch returns a
 * reason naming the rule it applied, so a `HOLD` always says which situation it is.
 */
    fun evaluate(evidence: ProgramAdaptiveEvidence): ProgramPolicyDecision {
        val window = evidence.window
        val verdict = ProgramWindowVerdict(
            progressQualifying = progressConditionsMet(evidence),
            regressQualifying = regressConditionsMet(evidence),
            recoveryQualifying = reducedAbsorptionHolds(evidence)
        )

        if (recoveryEntryQualifies(evidence)) {
            return holding(verdict, AdaptiveState.RECOVERY, ProgramAdaptiveReason.RECOVERY_ENTERED)
        }

        if (window.state == AdaptiveState.RECOVERY) {
            val exitMet = window.recoveryQualifyingWindows >= recoveryExitQualifyingWindows
            return if (exitMet) {
                holding(verdict, AdaptiveState.HOLD, ProgramAdaptiveReason.RECOVERY_EXITED)
            } else {
                holding(verdict, AdaptiveState.RECOVERY, ProgramAdaptiveReason.RECOVERY_HELD)
            }
        }

        // Return after a gap sits before every confirmation rule: the evidence those rules would count
        // was earned *before* the gap, and pre-gap evidence must not confirm anything on the way back
        // in. The window's own verdict is withheld so the caller's counters do not advance.
        if (returnAfterGapHolds(window.gap)) {
            return changing(
                ProgramWindowVerdict(),
                AdaptiveState.REGRESS,
                ProgramAdaptiveReason.RETURN_AFTER_GAP
            )
        }

        if (window.restChangeRequested) {
            return holding(verdict, AdaptiveState.HOLD, ProgramAdaptiveReason.REST_CHANGE_UNSUPPORTED)
        }

        val cooldownElapsed = window.qualifyingWindowsSinceLastChange == null ||
            window.qualifyingWindowsSinceLastChange >= progressionCooldownWindows
        val progressHolds = verdict.progressQualifying
        val regressHolds = verdict.regressQualifying
        val progressConfirmed =
            progressHolds && window.precedingProgressQualifyingWindows + 1 >= progressConfirmingWindows
        val regressConfirmed =
            regressHolds && window.precedingRegressQualifyingWindows + 1 >= regressConfirmingWindows

        if (progressConfirmed && cooldownElapsed) {
            return changing(verdict, AdaptiveState.PROGRESS, ProgramAdaptiveReason.SUSTAINED_POSITIVE)
        }
        if (regressConfirmed && cooldownElapsed) {
            return changing(verdict, AdaptiveState.REGRESS, ProgramAdaptiveReason.SUSTAINED_NEGATIVE)
        }
        if (progressConfirmed || regressConfirmed) {
            // The direction was earned; only the cooldown stands in its way, and saying so is the
            // difference between "the program is holding you back" and "we do not know".
            return holding(verdict, AdaptiveState.HOLD, ProgramAdaptiveReason.PROGRESSION_COOLDOWN)
        }
        if (progressHolds || regressHolds) {
            return holding(verdict, AdaptiveState.HOLD, ProgramAdaptiveReason.AWAITING_CONFIRMATION)
        }
        if (positiveDirection(evidence)) {
            return holding(verdict, AdaptiveState.HOLD, progressionGateReason(evidence))
        }
        if (negativeDirection(evidence)) {
            return holding(verdict, AdaptiveState.HOLD, regressionGateReason(evidence))
        }
        return holding(verdict, AdaptiveState.HOLD, defaultHoldReason(evidence))
    }

    private fun progressConditionsMet(evidence: ProgramAdaptiveEvidence): Boolean =
        positiveDirection(evidence) &&
            evidence.evidence == EvidenceLevel.STRONG &&
            evidence.confidence != ConfidenceLevel.LOW &&
            evidence.recovery == RecoveryContext.FAVORABLE &&
            evidence.signals.consistency != ProgramConsistency.LOW &&
            !evidence.signals.recentIsAboveBaseline

    private fun regressConditionsMet(evidence: ProgramAdaptiveEvidence): Boolean =
        negativeDirection(evidence) &&
            (evidence.signals.newerHalf?.shortfallSets ?: 0) >= regressMinimumShortfallSets

    /**
     * The shortfall is read from the newer half on purpose: a whole-window shortfall cannot tell a
     * family that fell short weeks ago from one whose recent end earned its step.
     */
    private fun positiveDirection(evidence: ProgramAdaptiveEvidence): Boolean =
        evidence.signals.exposure.exposures >= progressMinimumExposures &&
            evidence.signals.trend == ProgramPerformanceTrend.POSITIVE &&
            evidence.signals.newerHalf?.hasShortfall == false

    private fun negativeDirection(evidence: ProgramAdaptiveEvidence): Boolean =
        evidence.signals.exposure.exposures >= regressMinimumExposures &&
            evidence.signals.trend == ProgramPerformanceTrend.NEGATIVE

    /**
     * An **idle** window enters neither pattern: inactivity is not failure, and the reduced-exposure
     * reading is only made about a window in which something was actually observed.
     */
    private fun recoveryEntryQualifies(evidence: ProgramAdaptiveEvidence): Boolean {
        val newer = evidence.signals.newerHalf
        val reducedExposure = newer != null && (newer.hasShortfall || !newer.everyExposureFull)
        val deteriorating = evidence.signals.trend == ProgramPerformanceTrend.NEGATIVE
        val unabsorbedLoad = evidence.signals.recentIsAboveBaseline && (deteriorating || reducedExposure)
        val prolonged = deteriorating && reducedExposure &&
            evidence.window.precedingRecoveryQualifyingWindows + 1 >= recoveryProlongedWindows
        if (!unabsorbedLoad && !prolonged) return false
        return evidence.window.precedingRecoveryQualifyingWindows + 1 >= recoveryEntryConfirmingWindows
    }

    /** The reading a recovery-entry count is advanced by: [recoveryEntryQualifies] is this plus its gates. */
    private fun reducedAbsorptionHolds(evidence: ProgramAdaptiveEvidence): Boolean {
        val newer = evidence.signals.newerHalf
        val reducedExposure = newer != null && (newer.hasShortfall || !newer.everyExposureFull)
        val deteriorating = evidence.signals.trend == ProgramPerformanceTrend.NEGATIVE
        val unabsorbedLoad = evidence.signals.recentIsAboveBaseline && (deteriorating || reducedExposure)
        return unabsorbedLoad || (deteriorating && reducedExposure)
    }

    /**
     * Rounded up, the conservative direction for both rules that read it: the trigger is a multiple of
     * it, so a larger usual interval asks for a longer absence before a return is claimed.
     */
    fun usualIntervalDaysOf(gap: ProgramGapFacts): Long = gap.usualInterval
        ?.let { interval -> (interval.seconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY }
        ?.coerceAtLeast(1L)
        ?: usualIntervalFallbackDays.toLong()

    /**
     * The strict compare keeps a missed session out of this rule: one missed session is two intervals,
     * two are exactly three, and neither is *more* than the multiple.
     */
    fun returnAfterGapHolds(gap: ProgramGapFacts): Boolean {
        val daysAway = gap.daysAway ?: return false
        if (daysAway < returnGapMinimumDays) return false
        return daysAway > returnGapUsualIntervalMultiple * usualIntervalDaysOf(gap)
    }

    /**
     * The base is what was **performed**, not what was prescribed: a user already falling short comes
     * back to their own last amount, lowered, rather than to a prescription they were not completing.
     *
     * @param performed the amounts to lower, one per presented set.
     * @param range the exercise's own span, read for its width only.
     */
    fun returnAfterGapTargets(performed: List<Int>, range: IntRange, gap: ProgramGapFacts): List<Int> {
        if (performed.isEmpty()) return performed
        val intervals = minOf(
            (gap.daysAway ?: 0L) / usualIntervalDaysOf(gap),
            returnGapFullReductionIntervals.toLong()
        )
        val width = (range.last - range.first).coerceAtLeast(0).toLong()
        val reduction = width * returnGapReductionNumerator * intervals /
            (returnGapReductionDenominator.toLong() * returnGapFullReductionIntervals)
        return performed.map { amount -> (amount - reduction).toInt() }
    }

    private fun progressionGateReason(evidence: ProgramAdaptiveEvidence): ProgramAdaptiveReason = when {
        evidence.signals.exposure.exposures < progressMinimumExposures ->
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE

        evidence.evidence != EvidenceLevel.STRONG -> ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE
        evidence.confidence == ConfidenceLevel.LOW -> ProgramAdaptiveReason.LOW_CONFIDENCE
        evidence.signals.recentIsAboveBaseline -> ProgramAdaptiveReason.RECENT_LOAD_ELEVATED
        evidence.recovery != RecoveryContext.FAVORABLE -> ProgramAdaptiveReason.CAUTIOUS_CONTEXT
        evidence.signals.consistency == ProgramConsistency.LOW ->
            ProgramAdaptiveReason.INCONSISTENT_ATTENDANCE

        // Every gate above is clear, so this is a window whose reading is not as positive as the
        // caller of this function believed: it is reported as the mixed window it is.
        else -> ProgramAdaptiveReason.MIXED_EVIDENCE
    }

    private fun regressionGateReason(evidence: ProgramAdaptiveEvidence): ProgramAdaptiveReason = when {
        evidence.signals.exposure.exposures < regressMinimumExposures ->
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE

        evidence.signals.exposure.shortfallSets < regressMinimumShortfallSets ->
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE

        else -> ProgramAdaptiveReason.MIXED_EVIDENCE
    }

    private fun defaultHoldReason(evidence: ProgramAdaptiveEvidence): ProgramAdaptiveReason = when {
        evidence.signals.trend == null -> ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE
        evidence.evidence == EvidenceLevel.INSUFFICIENT -> ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE
        evidence.signals.trend == ProgramPerformanceTrend.STABLE &&
            !evidence.signals.exposure.hasShortfall -> ProgramAdaptiveReason.STABLE_PLATEAU

        else -> ProgramAdaptiveReason.MIXED_EVIDENCE
    }

    private fun holding(
        verdict: ProgramWindowVerdict,
        state: AdaptiveState,
        reason: ProgramAdaptiveReason
    ): ProgramPolicyDecision =
        ProgramPolicyDecision(state = state, action = AdaptiveAction.HOLD, reason = reason, verdict = verdict)

    private fun changing(
        verdict: ProgramWindowVerdict,
        state: AdaptiveState,
        reason: ProgramAdaptiveReason
    ): ProgramPolicyDecision =
        ProgramPolicyDecision(
            state = state,
            action = reason.action,
            reason = reason,
            verdict = verdict
        )

    init {
        require(version >= 1) { "version must be >= 1, was $version" }
        require(trendMinimumExposures >= 2) {
            "a trend needs two halves to compare, so at least two exposures, was $trendMinimumExposures"
        }
        require(progressMinimumExposures >= 1 && regressMinimumExposures >= 1) {
            "the minimum comparable exposures must be >= 1, were progress=$progressMinimumExposures " +
                "regress=$regressMinimumExposures"
        }
        require(regressMinimumShortfallSets >= 1) {
            "a regression needs a measured shortfall, so at least one set, was $regressMinimumShortfallSets"
        }
        require(progressConfirmingWindows >= 1 && regressConfirmingWindows >= 1) {
            "confirmation windows must be >= 1, were progress=$progressConfirmingWindows " +
                "regress=$regressConfirmingWindows"
        }
        require(recoveryEntryConfirmingWindows >= 1) {
            "recoveryEntryConfirmingWindows must be >= 1, was $recoveryEntryConfirmingWindows"
        }
        require(recoveryProlongedWindows >= 1) {
            "recoveryProlongedWindows must be >= 1, was $recoveryProlongedWindows"
        }
        require(recoveryExitQualifyingWindows >= 1) {
            "recoveryExitQualifyingWindows must be >= 1, was $recoveryExitQualifyingWindows"
        }
        require(progressionCooldownWindows >= 0) {
            "progressionCooldownWindows must be >= 0, was $progressionCooldownWindows"
        }
        require(usualIntervalSessions >= 3) {
            "three sessions are two intervals, which is the fewest that can state a habit at all, was " +
                "$usualIntervalSessions"
        }
        require(usualIntervalFallbackDays >= 1) {
            "a fallback usual interval is at least one day, was $usualIntervalFallbackDays"
        }
        require(returnGapUsualIntervalMultiple >= 1) {
            "a return is longer than at least one usual interval, was $returnGapUsualIntervalMultiple"
        }
        require(returnGapMinimumDays >= 0) {
            "the minimum gap in days must be >= 0, was $returnGapMinimumDays"
        }
        require(
            returnGapReductionNumerator >= 0 &&
                returnGapReductionDenominator >= 1 &&
                returnGapReductionNumerator <= returnGapReductionDenominator
        ) {
            "the share of the range a return takes away must be a fraction within 0..1, was " +
                "$returnGapReductionNumerator/$returnGapReductionDenominator"
        }
        require(returnGapFullReductionIntervals >= 1) {
            "the reduction grows over at least one interval, was $returnGapFullReductionIntervals"
        }
        require(consistencyMediumDenominator >= 1 && consistencyHighDenominator >= 1) {
            "consistency ratios need a positive denominator, were " +
                "high=$consistencyHighNumerator/$consistencyHighDenominator " +
                "medium=$consistencyMediumNumerator/$consistencyMediumDenominator"
        }
        require(consistencyMediumNumerator <= consistencyMediumDenominator) {
            "a medium attendance fraction is within 0..1, was " +
                "$consistencyMediumNumerator/$consistencyMediumDenominator"
        }
        require(consistencyHighNumerator <= consistencyHighDenominator) {
            "a high attendance fraction is within 0..1, was " +
                "$consistencyHighNumerator/$consistencyHighDenominator"
        }
        require(
            consistencyMediumNumerator * consistencyHighDenominator <=
                consistencyHighNumerator * consistencyMediumDenominator
        ) {
            "the high attendance fraction must not be below the medium one, were " +
                "$consistencyHighNumerator/$consistencyHighDenominator and " +
                "$consistencyMediumNumerator/$consistencyMediumDenominator"
        }
        require(guardAllowedSetIncreaseNumerator >= 0 && guardAllowedSetIncreaseDenominator >= 1) {
            "the guard's set tolerance must be a non-negative fraction, was " +
                "$guardAllowedSetIncreaseNumerator/$guardAllowedSetIncreaseDenominator"
        }
        require(guardAllowedAmountIncreaseNumerator >= 0 && guardAllowedAmountIncreaseDenominator >= 1) {
            "the guard's amount tolerance must be a non-negative fraction, was " +
                "$guardAllowedAmountIncreaseNumerator/$guardAllowedAmountIncreaseDenominator"
        }
        require(guardAllowedWorkIncreaseNumerator >= 0 && guardAllowedWorkIncreaseDenominator >= 1) {
            "the guard's work tolerance must be a non-negative fraction, was " +
                "$guardAllowedWorkIncreaseNumerator/$guardAllowedWorkIncreaseDenominator"
        }
        require(guardAllowedLevelSteps >= 0) {
            "the guard's level tolerance must be >= 0, was $guardAllowedLevelSteps"
        }
        require(
            guardAllowedRestDecreaseNumerator >= 0 &&
                guardAllowedRestDecreaseDenominator >= 1 &&
                guardAllowedRestDecreaseNumerator <= guardAllowedRestDecreaseDenominator
        ) {
            "the rest floor must be a fraction within 0..1, was " +
                "$guardAllowedRestDecreaseNumerator/$guardAllowedRestDecreaseDenominator"
        }
    }

    companion object {

        const val V1_VERSION: Int = 1

        private const val SECONDS_PER_DAY: Long = 86_400L

        val V1: ProgramAdaptivePolicy = ProgramAdaptivePolicy()
    }
}
