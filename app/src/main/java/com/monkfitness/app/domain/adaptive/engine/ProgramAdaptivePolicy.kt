package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * One family's circumstances in one decision window, as the caller maintains them (§15).
 *
 * These are the facts a single window cannot state about itself, and every one of them is supplied
 * rather than derived: the family's position and its adaptation state are stored facts (§23's
 * `FamilyProgressionState`), the qualifying-window counts and the cooldown position are the caller's
 * own record of what previous windows decided, and the recovery counter is what §14's exit gate is
 * measured in.
 *
 * Three of them are deliberately shaped so that an unknown can never masquerade as a value:
 *
 *  * [level] is `null` when the family carries no known position. A family nobody has placed cannot be
 *    progressed or regressed, and the engine holds;
 *  * [qualifyingWindowsSinceLastChange] is `null` when the family has **never** had a progression change.
 *    That is not the same as `0`: the cooldown exists to stop oscillation *after* a change, so the first
 *    earned change goes through with no cooldown to serve;
 *  * nothing here is derived from the window's own signals — a window cannot count itself.
 *
 * @property familyId the family these facts are about.
 * @property level the family's own position in its progression hierarchy, or `null` when it has none.
 * @property state the family's current adaptation state.
 * @property precedingProgressQualifyingWindows consecutive windows **before** this one in which the
 *   progression conditions held.
 * @property precedingRegressQualifyingWindows the same, for the regression conditions.
 * @property precedingRecoveryQualifyingWindows the same, for the recovery-entry pattern.
 * @property qualifyingWindowsSinceLastChange eligible windows since the family's last progression-level
 *   change, or `null` when it has never had one.
 * @property recoveryQualifyingWindows windows completed while the family is in recovery: §14's exit gate.
 * @property restChangeRequested whether a rest adaptation was asked for this window.
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
    val restChangeRequested: Boolean = false
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

/**
 * Everything the policy is allowed to look at (§11, §12): the maintained window facts, the derived
 * signals, and the three qualitative judgements that arrive as **inputs**.
 *
 * The three levels are inputs on purpose. Evidence, confidence and recovery are statements about the
 * history as the layer that owns that history states them, not results this policy computes — and the
 * policy reads them separately, because §12 keeps them separate: how much comparable history exists,
 * how far it can be trusted for this decision, and whether the context calls for caution are three
 * questions with three answers, and there is no composite score anywhere in this type.
 */
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
 * What the policy found in one window, as three separate answers (§15).
 *
 * These are the three questions the policy answers on its own — *did §7's progression conditions hold
 * in this window? did the regression conditions? did §14's reduced-absorption pattern?* — stated as
 * facts rather than left implicit in the decision's `reason`.
 *
 * They exist because a **window cannot count itself.** The confirmation counts, the cooldown position
 * and the recovery exit count are the caller's maintained facts ([ProgramAdaptiveWindow]), and the
 * only component that knows whether this window qualified is the policy. A caller that had to infer
 * `progressQualifying` from the decision's reason would be re-deriving §15 — the reason is one token
 * for one rule, and `PROGRESSION_COOLDOWN` deliberately does not say *which* direction it is holding.
 * §30 step 12's integration reads exactly this value to advance a family's counters, so the
 * alternative (inferring it) would be a second copy of the policy's conditions living outside the
 * policy.
 *
 * The three are independent: a window can qualify for neither, for recovery alone, or (mechanically
 * never) for both directions at once — the trend a progression needs is positive and the one a
 * regression needs is negative.
 *
 * @property progressQualifying §7's progression conditions held here.
 * @property regressQualifying §7's regression conditions held here.
 * @property recoveryQualifying §14's reduced-absorption pattern held here, **before** its own
 *   confirming gate — the fact a caller's recovery-entry count is advanced by.
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

    /** Whether no direction was earned in this window. */
    val isIdle: Boolean
        get() = !progressQualifying && !regressQualifying && !recoveryQualifying
}

/**
 * What the policy decided for one window: the state the family ends in, the action asked for, and the
 * single reason that says why (§15, §22).
 *
 * The action and the reason cannot disagree — the reason names the rule, and each rule belongs to one
 * action — so this value is constructible only in the shapes the vocabulary allows. [verdict] is the
 * window's own reading, and it is held consistent with the reason: a reason that says a direction was
 * earned says so because the conditions for it held.
 */
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

    /** Whether the policy asked for a change to the presentation. */
    val asksForAChange: Boolean
        get() = action != AdaptiveAction.HOLD
}

/**
 * The target Program Adaptive Policy: **one home for every threshold, window and confirmation count**
 * the adaptive stage uses, plus the state machine that applies them (§7 of the brief, §15).
 *
 * Nothing in this file decides adaptation from a literal: the numbers live here as named, documented
 * fields, they are read only by [evaluate] and by the two bucketing helpers the signal layer calls
 * ([ProgramAdaptiveSignalCalculator] asks this policy for its trend minimum and its consistency
 * ratios rather than re-deriving them), and the aggregate load guard reads its tolerances from here as
 * well. A second copy of a threshold would be a second threshold that can disagree with the first.
 *
 * ### The state machine, in priority order
 *
 * ```text
 * 1. recovery entry   strong evidence of unabsorbed load      → RECOVERY (never cooldown-gated)
 * 2. recovery gating  a family in RECOVERY stays until its exit gate is met, and leaves to HOLD
 * 3. rest             an unsupported adaptation is reported, never faked
 * 4. confirmed change the earned direction, if the cooldown has elapsed
 * 5. cooling down     an earned direction the cooldown blocks, reported as itself
 * 6. awaiting         a direction that holds in this window and not yet in the previous one(s)
 * 7. the gates        the direction is there and a gate (evidence, confidence, context, attendance,
 *                     recent load) is not: the gate's own reason
 * 8. the default      insufficient evidence, a measured plateau, or a mixed window
 * ```
 *
 * Step 1 is deliberately first: recovery **outranks** progression, which is what makes it a safety
 * state rather than a preference. Step 2 is what makes recovery exit to `HOLD` and never straight to a
 * progression — a family leaving recovery has to earn its change again, like any other family.
 *
 * ### What the policy is not
 *
 * It is not a medical or physiological model: no recovery score, no readiness percentage, no
 * universal 48-hour rule, and no coefficient that would turn the qualitative levels into numbers. Its
 * thresholds are *decision* counts — how much comparable history, how many confirming windows, how
 * many sets short — and every one of them is stated here as an integer ratio or count.
 *
 * It is deterministic: the same evidence always produces the same decision, whatever order the caller
 * built its collections in, and nothing here reads a clock, a random source, a database or a global.
 */
data class ProgramAdaptivePolicy(
    val version: Int = V1_VERSION,

    // --- how much comparable history a measurement needs ------------------------------------------

    /** Comparable exposures below which no trend is measured at all: fewer than this says nothing. */
    val trendMinimumExposures: Int = 3,

    /** Comparable exposures a progression window needs. */
    val progressMinimumExposures: Int = 4,

    /** Comparable exposures a regression window needs. */
    val regressMinimumExposures: Int = 3,

    /** Sets a regression window must have fallen short by, over and above a negative trend. */
    val regressMinimumShortfallSets: Int = 3,

    // --- attendance over the plan's own opportunities ---------------------------------------------

    /** Completed opportunities at or above this fraction of them is high attendance. */
    val consistencyHighNumerator: Int = 3,
    val consistencyHighDenominator: Int = 4,

    /** ... and at or above this fraction is medium attendance; below it is low. */
    val consistencyMediumNumerator: Int = 1,
    val consistencyMediumDenominator: Int = 2,

    // --- confirmation and cooldown -----------------------------------------------------------------

    val progressConfirmingWindows: Int = 2,
    val regressConfirmingWindows: Int = 2,
    val recoveryEntryConfirmingWindows: Int = 1,

    /** Windows the prolonged reduced-exposure pattern must hold for, on its own, to enter recovery. */
    val recoveryProlongedWindows: Int = 2,

    /** Windows completed in recovery before it exits. */
    val recoveryExitQualifyingWindows: Int = 2,

    /** Eligible windows a progression-level change serves before the family may change level again. */
    val progressionCooldownWindows: Int = 2,

    // --- variant realignment -----------------------------------------------------------------------

    /**
     * Whether the engine may re-point an element to another variant the family declares **at the same
     * level** when the presented one is not in the user's selection.
     */
    val variantRealignmentEnabled: Boolean = true,

    // --- the aggregate load guard's tolerances (§18) ------------------------------------------------

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
     * The deterministic decision for one window (§15).
     *
     * The order of the branches is the policy, and every branch returns a reason that names the rule it
     * applied — so a `HOLD` never arrives without saying which of §15's hold situations it is.
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

    /** The window's own direction, §7's progression conditions, all of them (§15). */
    private fun progressConditionsMet(evidence: ProgramAdaptiveEvidence): Boolean =
        positiveDirection(evidence) &&
            evidence.evidence == EvidenceLevel.STRONG &&
            evidence.confidence != ConfidenceLevel.LOW &&
            evidence.recovery == RecoveryContext.FAVORABLE &&
            evidence.signals.consistency != ProgramConsistency.LOW &&
            !evidence.signals.recentIsAboveBaseline

    /** The window's own direction, §7's regression conditions, all of them (§15). */
    private fun regressConditionsMet(evidence: ProgramAdaptiveEvidence): Boolean =
        negativeDirection(evidence) &&
            (evidence.signals.newerHalf?.shortfallSets ?: 0) >= regressMinimumShortfallSets

    /**
     * Whether the window reads positively on its own: enough comparable exposures, a measured upward
     * trend, and a **recent end** that executed everything it was asked for.
     *
     * The shortfall is read from the newer half rather than from the whole window on purpose. A window
     * is history: a family that fell short weeks ago and completes everything now has an older half that
     * went badly and a recent end that earned its step, and a whole-window shortfall cannot tell those
     * two readings apart. Missing evidence is neither: an unmeasured trend is never positive here.
     */
    private fun positiveDirection(evidence: ProgramAdaptiveEvidence): Boolean =
        evidence.signals.exposure.exposures >= progressMinimumExposures &&
            evidence.signals.trend == ProgramPerformanceTrend.POSITIVE &&
            evidence.signals.newerHalf?.hasShortfall == false

    /**
     * Whether the window reads negatively on its own: enough comparable exposures to state a trend, and
     * a trend that points down. The shortfall a regression additionally needs is checked by
     * [regressConditionsMet], so this predicate answers only the direction question.
     */
    private fun negativeDirection(evidence: ProgramAdaptiveEvidence): Boolean =
        evidence.signals.exposure.exposures >= regressMinimumExposures &&
            evidence.signals.trend == ProgramPerformanceTrend.NEGATIVE

    /**
     * §14's safety path: strong evidence that recent load is not being absorbed.
     *
     * Two documented patterns enter recovery — a recent context above what the plan prescribes
     * *together with* deterioration or reduced exposure, and the prolonged reduced-exposure pattern
     * that stands on its own across [recoveryProlongedWindows] windows. An **idle** window enters
     * neither: inactivity is not failure (§14), and the "reduced exposure" reading is only made about a
     * window in which something was actually observed.
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

    /**
     * §14's reduced-absorption reading of one window, **before** any of its own confirming gates.
     *
     * This is the fact a caller's recovery-entry count is advanced by: whether the window shows load
     * that was not absorbed (a recent context above the plan, together with deterioration or reduced
     * exposure) or the reduced-exposure pattern on its own. [recoveryEntryQualifies] is this reading
     * plus the gates the policy applies to it, so the counter and the entry decision cannot drift
     * apart — they read the same predicate.
     */
    private fun reducedAbsorptionHolds(evidence: ProgramAdaptiveEvidence): Boolean {
        val newer = evidence.signals.newerHalf
        val reducedExposure = newer != null && (newer.hasShortfall || !newer.everyExposureFull)
        val deteriorating = evidence.signals.trend == ProgramPerformanceTrend.NEGATIVE
        val unabsorbedLoad = evidence.signals.recentIsAboveBaseline && (deteriorating || reducedExposure)
        return unabsorbedLoad || (deteriorating && reducedExposure)
    }

    /**
     * Which gate stopped a window whose direction reads positively — the specific reason, in the order
     * the conditions are stated in [progressConditionsMet].
     */
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

    /**
     * Which gate stopped a window whose direction reads negatively: the shortfall §7 requires before a
     * regression can be earned, or the *absence* of evidence rather than its presence.
     */
    private fun regressionGateReason(evidence: ProgramAdaptiveEvidence): ProgramAdaptiveReason = when {
        evidence.signals.exposure.exposures < regressMinimumExposures ->
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE

        evidence.signals.exposure.shortfallSets < regressMinimumShortfallSets ->
            ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE

        else -> ProgramAdaptiveReason.MIXED_EVIDENCE
    }

    /**
     * The default reading, when neither direction stands on its own: nothing measured, a measured
     * plateau, or a window that points two ways at once.
     */
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

        /** Adaptive policy version 1: every threshold at its documented v1 value. */
        val V1: ProgramAdaptivePolicy = ProgramAdaptivePolicy()
    }
}
