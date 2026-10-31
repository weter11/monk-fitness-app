package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * Why the guard filtered a change out. There is no third reason saying "take some away": the guard
 * exists to stop compounding, not to prescribe.
 */
enum class ProgramGuardReason {

    EXCESSIVE_AUTOMATIC_INCREASE,

    RECENT_LOAD_UNMET_OPPORTUNITY
}

/**
 * What the guard said about one change. The third value is not an error: only an *increase* is guarded,
 * so a hold or a regression is simply not the guard's business, and it never invents a regression in
 * place of a filtered progression.
 *
 * A channel that could not be compared blocks nothing and approves nothing.
 */
sealed interface ProgramGuardVerdict {

    /** The scope the guard compared at — the decision's own scope, never a broader one. */
    val scope: AdaptiveScope

    data class NotGuarded(
        override val scope: AdaptiveScope,
        val action: AdaptiveAction
    ) : ProgramGuardVerdict

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
 * The aggregate load guard, as a pure component: would this automatic change compound beyond what the
 * policy allows, given what the user has recently been carrying?
 *
 * Every rule is per channel and nothing is totalled or converted — sets, repetitions and seconds never
 * become one number — and rest is a bound the guard can refuse a shortening against, never a proposal.
 * The recent-context rule is the second side: a delta that looks safe alone is cumulative once the
 * recent past already meets the plan and still carries an opportunity that produced nothing.
 */
object ProgramAggregateLoadGuard {

    fun guard(
        action: AdaptiveAction,
        baselineLoad: LoadProfile,
        candidateLoad: LoadProfile,
        recentBaselineLoad: LoadProfile? = null,
        recentLoad: LoadProfile? = null,
        policy: ProgramAdaptivePolicy,
        authoredCeiling: LoadProfile? = null
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

        val exceeded = delta.comparable.filter { compared -> exceeds(compared, policy, authoredCeiling) }
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

    fun compare(baselineLoad: LoadProfile, candidateLoad: LoadProfile): ProgramLoadComparison =
        ProgramLoadComparison.of(baselineLoad, candidateLoad)

    /**
     * Each channel carries its own direction: the volume, working-time and level rules bound what an
     * adaptation may **add**, the rest rule bounds what it may **take away**, and a channel that did not
     * move is never a violation. Every comparison is exact integer arithmetic, with no division or
     * rounding.
     *
     * [authoredCeiling] is the exercise's own span, and it replaces the volume tolerances: a change that
     * stays inside the range the catalogue authored is bounded by that content, not by a share of the
     * baseline — which is what lets a prescription climb within its range at all.
     */
    private fun exceeds(
        compared: ProgramChannelComparison.Compared,
        policy: ProgramAdaptivePolicy,
        authoredCeiling: LoadProfile?
    ): Boolean = when (compared.channel) {
        ProgramLoadChannel.VOLUME_SETS -> compared.increases && (
            ceilingOf(compared.channel, authoredCeiling)?.let { ceiling -> compared.candidate > ceiling }
                ?: exceedsIncrease(
                    compared,
                    policy.guardAllowedSetIncreaseNumerator,
                    policy.guardAllowedSetIncreaseDenominator
                )
            )

        ProgramLoadChannel.VOLUME_REPETITIONS,
        ProgramLoadChannel.VOLUME_SECONDS -> compared.increases && (
            ceilingOf(compared.channel, authoredCeiling)?.let { ceiling -> compared.candidate > ceiling }
                ?: exceedsIncrease(
                    compared,
                    policy.guardAllowedAmountIncreaseNumerator,
                    policy.guardAllowedAmountIncreaseDenominator
                )
            )

        // The relation has already established that a single declared step exists; this is the
        // guard's own independent bound on how far one decision may travel.
        ProgramLoadChannel.INTENSITY_LEVEL ->
            compared.increases && compared.delta > policy.guardAllowedLevelSteps

        ProgramLoadChannel.DENSITY_WORKING_SECONDS -> compared.increases && exceedsIncrease(
            compared, policy.guardAllowedWorkIncreaseNumerator, policy.guardAllowedWorkIncreaseDenominator
        )

        // Rest may not shorten below the policy's own floor. A rest that stayed equal or grew is never
        // a violation, and a change that lengthens rest is not this stage's to make.
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

    /** The authored ceiling for [channel], or `null` when the ceiling says nothing about it. */
    private fun ceilingOf(channel: ProgramLoadChannel, ceiling: LoadProfile?): Int? =
        if (ceiling == null) {
            null
        } else {
            when (channel) {
                ProgramLoadChannel.VOLUME_SETS -> ceiling.volume.sets
                ProgramLoadChannel.VOLUME_REPETITIONS -> ceiling.volume.repetitions
                ProgramLoadChannel.VOLUME_SECONDS -> ceiling.volume.durationSeconds
                ProgramLoadChannel.DENSITY_WORKING_SECONDS -> ceiling.density.workingSeconds
                else -> null
            }
        }

    /** `candidate ≤ baseline + baseline · numerator/denominator`, in exact integers. */
    private fun exceedsIncrease(
        compared: ProgramChannelComparison.Compared,
        numerator: Int,
        denominator: Int
    ): Boolean = compared.candidate * denominator > compared.baseline * (denominator + numerator)
}
