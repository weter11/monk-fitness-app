package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * Why the aggregate load guard filtered an automatic change out (§18).
 *
 * Two reasons, and no third that would say *"this is too much, take some away"*: the guard exists to
 * stop compounding, not to prescribe.
 */
enum class ProgramGuardReason {

    /**
     * The candidate session asks for more than the automatic change is allowed to add on at least one
     * channel it can be compared on: the delta is past its policy tolerance.
     */
    EXCESSIVE_AUTOMATIC_INCREASE,

    /**
     * The recent context is at or above what the plan already prescribes on every channel that can be
     * compared, **and** it still carries an opportunity that produced nothing — so the load already
     * asked for has not been absorbed, and adding more compounds rather than progresses.
     */
    RECENT_LOAD_UNMET_OPPORTUNITY
}

/**
 * What the guard said about one automatic change (§18).
 *
 * The verdict is deliberately three-valued, and the third value is not an error:
 *
 *  * [NotGuarded] — the change is one the guard does not guard at all. A `HOLD` changes nothing and a
 *    `REGRESS` lowers the load, so neither can compound; only an *increase* is guarded. The guard never
 *    invents a `REGRESS` in place of a filtered `PROGRESS`, and the one way to say that is that its
 *    verdicts are only ever *approved*, *filtered*, or *not its business*;
 *  * [Approved] — the candidate stayed inside every tolerance that could be applied. The comparisons it
 *    was reached from travel with it, so what the guard saw is auditable rather than asserted;
 *  * [Filtered] — a bounded change was refused. The decision that carried it is recorded as
 *    `NOT_APPLIED` (§18) and the channels that produced the refusal travel with the verdict.
 *
 * A channel that could not be compared blocks nothing and approves nothing: it cannot be evidence of
 * excess, so an incomparable dimension is reported in [Filtered.delta] / [Approved.delta] and takes no
 * part in the verdict.
 */
sealed interface ProgramGuardVerdict {

    /** The scope the guard compared at — the decision's own scope, never a broader one. */
    val scope: AdaptiveScope

    /** A change the guard does not guard: nothing is added, or load is taken away. */
    data class NotGuarded(
        override val scope: AdaptiveScope,
        val action: AdaptiveAction
    ) : ProgramGuardVerdict

    /** An automatic increase that stayed inside every tolerance the guard could apply. */
    data class Approved(
        override val scope: AdaptiveScope,
        val delta: ProgramLoadComparison,
        val recentContext: ProgramLoadComparison?
    ) : ProgramGuardVerdict {

        init {
            require(delta.scope == scope) {
                "the guard approves at the scope it compared: scope=$scope delta=${delta.scope}"
            }
        }
    }

    /** An automatic increase the guard refused. The change becomes a `HOLD`. */
    data class Filtered(
        override val scope: AdaptiveScope,
        val reason: ProgramGuardReason,
        val exceeded: List<ProgramChannelComparison.Compared>,
        val delta: ProgramLoadComparison,
        val recentContext: ProgramLoadComparison?
    ) : ProgramGuardVerdict {

        init {
            require(delta.scope == scope) {
                "the guard filters at the scope it compared: scope=$scope delta=${delta.scope}"
            }
            require(reason != ProgramGuardReason.EXCESSIVE_AUTOMATIC_INCREASE || exceeded.isNotEmpty()) {
                "a refusal for an excessive increase names the channels that exceeded their " +
                    "tolerance: $reason"
            }
            require(exceeded.all { it.channel in delta.comparable.map { compared -> compared.channel } }) {
                "the channels a refusal names are channels the guard compared: " +
                    "${exceeded.map { it.channel }} against ${delta.comparable.map { it.channel }}"
            }
        }
    }
}

/**
 * §18's aggregate load guard, as a pure component.
 *
 * ```text
 * baseline session  vs  candidate session
 *         +  recent context
 * ```
 *
 * The guard answers one question, at one scope: *would this automatic change compound beyond what the
 * policy allows, given what the user has recently been carrying?* Everything it needs is an argument —
 * the baseline profile, the candidate the change produces, the recent context, and the policy whose
 * tolerances every rule below reads — and it returns a verdict. It reads no clock, no storage, no
 * history and no exercise metadata, and it changes nothing.
 *
 * ### The rules, and why they are per channel
 *
 *  * **volume** — sets, repetitions and seconds are separate channels, so an automatic change may not
 *    add repetitions *or* sets *or* seconds past the policy's own tolerance. No conversion between the
 *    three exists anywhere, and none is invented to make one rule out of them (§17);
 *  * **intensity** — an automatic change may move a family by at most the policy's declared number of
 *    steps. The relation has already established that a single declared step exists (§15); this is the
 *    guard's own independent bound on how far one decision may travel;
 *  * **density** — working time may not lengthen past its tolerance, and rest may not shorten below
 *    its own floor. Rest is the dimension `CHANGE_REST` would need and that the domain cannot yet
 *    express (§10), so the guard's rest rule is a *bound*, never a proposal: it can refuse a change
 *    that shortens rest, and it never asks for one;
 *  * **exposure/context** — an automatic change may never *add* an opportunity. Exposure is the plan's
 *    and the calendar's, and §33 forbids adaptive logic from silently changing duration, frequency or
 *    schedule, so the only direction this channel can move on its own is zero;
 *  * **recent context** — the second rule, stated on the two sides the snapshot supplies: when the
 *    recent past met or exceeded everything the plan prescribes *and* still has an opportunity that
 *    produced no work, an automatic increase is refused. This is the "baseline + adaptive delta +
 *    recent context" reading of §18 — the delta alone would look safe, and the context is what makes it
 *    cumulative.
 *
 * ### What it will not do
 *
 * It does not total the dimensions, does not convert between units, does not compare profiles stated at
 * different scopes, does not compare a repetition count against a duration, does not compare a family's
 * level against another family's, and never returns a `REGRESS`. Its tie-breaking is the comparison's
 * own canonical channel order, so the same three profiles always produce the same verdict and the same
 * violation list — the same order included.
 */
object ProgramAggregateLoadGuard {

    /**
     * The guard's verdict for one automatic change.
     *
     * @param action what the change asks for. A `HOLD` or a `REGRESS` is not guarded at all.
     * @param baselineLoad the element as the plan currently presents it.
     * @param candidateLoad the element as the change would present it.
     * @param recentBaselineLoad the baseline side of the recent context, or `null` when the caller has
     *   none — a context that was not supplied is missing, not zero.
     * @param recentLoad the recent past, or `null` when the caller has none.
     * @param policy the policy whose tolerances the rules below read. The guard holds no number of its
     *   own: a second copy of a threshold is a second threshold.
     */
    fun guard(
        action: AdaptiveAction,
        baselineLoad: LoadProfile,
        candidateLoad: LoadProfile,
        recentBaselineLoad: LoadProfile? = null,
        recentLoad: LoadProfile? = null,
        policy: ProgramAdaptivePolicy
    ): ProgramGuardVerdict {
        val scope = baselineLoad.scope
        if (action == AdaptiveAction.HOLD || action == AdaptiveAction.REGRESS ||
            action == AdaptiveAction.CHANGE_REST
        ) {
            return ProgramGuardVerdict.NotGuarded(scope, action)
        }

        val delta = ProgramLoadComparison.of(baselineLoad, candidateLoad)
        val recentContext = if (recentBaselineLoad != null && recentLoad != null) {
            ProgramLoadComparison.of(recentBaselineLoad, recentLoad)
        } else {
            null
        }

        val exceeded = delta.comparable.filter { compared -> exceeds(compared, policy) }
        if (exceeded.isNotEmpty()) {
            return ProgramGuardVerdict.Filtered(
                scope = scope,
                reason = ProgramGuardReason.EXCESSIVE_AUTOMATIC_INCREASE,
                exceeded = exceeded,
                delta = delta,
                recentContext = recentContext
            )
        }

        if (recentContext != null && recentLoad != null &&
            recentContext.candidateIsAtOrAboveBaselineEverywhere &&
            recentLoad.exposure.opportunities > recentLoad.exposure.completedOpportunities
        ) {
            return ProgramGuardVerdict.Filtered(
                scope = scope,
                reason = ProgramGuardReason.RECENT_LOAD_UNMET_OPPORTUNITY,
                exceeded = emptyList(),
                delta = delta,
                recentContext = recentContext
            )
        }

        return ProgramGuardVerdict.Approved(scope = scope, delta = delta, recentContext = recentContext)
    }

    /** The channel-by-channel comparison the guard is stated on, exposed for a caller to inspect. */
    fun compare(baselineLoad: LoadProfile, candidateLoad: LoadProfile): ProgramLoadComparison =
        ProgramLoadComparison.of(baselineLoad, candidateLoad)

    /**
     * Whether one compared channel of an automatic change is past its tolerance.
     *
     * Each channel carries its own direction: the volume, working-time and level rules bound what an
     * adaptation may **add**, the rest rule bounds what it may **take away** (rest is the dimension
     * `CHANGE_REST` would need and that the domain cannot yet express, so the guard's rest rule can
     * refuse a shortening and never proposes one), and a channel that did not move is never a violation.
     * Every comparison is exact integer arithmetic — `candidate * denominator` against
     * `baseline * (denominator + numerator)`, or the rest floor the other way round — with no division,
     * no rounding and no floating point.
     */
    private fun exceeds(
        compared: ProgramChannelComparison.Compared,
        policy: ProgramAdaptivePolicy
    ): Boolean = when (compared.channel) {
        ProgramLoadChannel.VOLUME_SETS -> compared.increases && exceedsIncrease(
            compared, policy.guardAllowedSetIncreaseNumerator, policy.guardAllowedSetIncreaseDenominator
        )

        ProgramLoadChannel.VOLUME_REPETITIONS,
        ProgramLoadChannel.VOLUME_SECONDS -> compared.increases && exceedsIncrease(
            compared, policy.guardAllowedAmountIncreaseNumerator, policy.guardAllowedAmountIncreaseDenominator
        )

        // The relation has already established that a single declared step exists (§15); this is the
        // guard's own independent bound on how far one decision may travel.
        ProgramLoadChannel.INTENSITY_LEVEL ->
            compared.increases && compared.delta > policy.guardAllowedLevelSteps

        ProgramLoadChannel.DENSITY_WORKING_SECONDS -> compared.increases && exceedsIncrease(
            compared, policy.guardAllowedWorkIncreaseNumerator, policy.guardAllowedWorkIncreaseDenominator
        )

        // Rest may not shorten below the policy's own floor. A rest that stayed equal or grew is never
        // a violation, and a change that lengthens rest is not this stage's to make (§10).
        ProgramLoadChannel.DENSITY_REST_SECONDS ->
            compared.decreases &&
                compared.candidate * policy.guardAllowedRestDecreaseDenominator <
                compared.baseline * policy.guardAllowedRestDecreaseNumerator

        // Exposure is context, not workload, and an automatic change may never add an opportunity.
        ProgramLoadChannel.EXPOSURE_OPPORTUNITIES -> compared.increases

        // A larger completed count is not a larger prescription: an opportunity that produced work is
        // not load the adaptive stage added.
        ProgramLoadChannel.EXPOSURE_COMPLETED -> false
    }

    /** `candidate ≤ baseline + baseline · numerator/denominator`, in exact integers. */
    private fun exceedsIncrease(
        compared: ProgramChannelComparison.Compared,
        numerator: Int,
        denominator: Int
    ): Boolean = compared.candidate * denominator > compared.baseline * (denominator + numerator)
}
