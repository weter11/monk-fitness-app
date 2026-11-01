package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.workout.AdaptiveCompletion

/**
 * What one adaptive pass produced for one family: a sealed hierarchy rather than a nullable decision,
 * because "nothing to adapt", "the guard refused" and "no window was evaluated" write different things.
 *
 * A pass produces one of these per family the completed session exposed, and the completion transaction
 * writes their [completion] legs as one unit.
 *
 * [NothingToAdapt] still carries the family's state, and that is arithmetic the policy states: a
 * direction is confirmed only on **consecutive** qualifying windows, so the counters must advance
 * through the windows that changed nothing. The state row is not a decision record.
 * [CannotBuildAdaptiveRequest] is the other side of that line: nothing at all is written.
 */
sealed interface AdaptiveIntegrationOutcome {

    /** What the completion transaction writes of the adaptive half. */
    val completion: AdaptiveCompletion

    /** The family this outcome is about; `null` for a gap that names the opportunity rather than a family. */
    val familyId: String?

    val isApplied: Boolean
        get() = this is AdaptiveApplied

    /** A change was resolved **and applied**: the decision, the adjustment and the family's state after it. */
    data class AdaptiveApplied(
        val decision: AdaptiveDecision,
        val adjustment: AdaptiveAdjustment,
        val familyState: FamilyProgressionState,
        val reason: ProgramAdaptiveReason
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason.isChange) {
                "an applied change is answered by one of the three change reasons: reason=$reason"
            }
            require(decision.reason == reason) {
                "the decision the completion records carries the reason that produced it: " +
                    "decision=${decision.reason} reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.Decided(decision, adjustment, familyState)

        override val familyId: String
            get() = familyState.familyId
    }

    /**
     * A change was resolved and the aggregate load guard **refused** it. The decision is kept as
     * `NOT_APPLIED` so a filtered decision remains recorded, while no adjustment is written because no
     * change happened.
     */
    data class AdaptiveFiltered(
        val decision: AdaptiveDecision,
        val familyState: FamilyProgressionState,
        val reason: ProgramAdaptiveReason
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason == ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD) {
                "the only decision this integration keeps as NOT_APPLIED is one the aggregate load " +
                    "guard refused (§18): reason=$reason"
            }
            require(decision.reason == reason) {
                "the decision the completion records carries the reason that produced it: " +
                    "decision=${decision.reason} reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.Decided(decision, adjustment = null, familyState = familyState)

        override val familyId: String
            get() = familyState.familyId
    }

    /**
     * The engine held. [reason] is deliberately **not** persisted: a hold is a reading of one window that
     * the same window recomputes identically, and a row per completed workout would bury the two decision
     * shapes that are required.
     */
    data class NothingToAdapt(
        val reason: ProgramAdaptiveReason,
        val familyState: FamilyProgressionState?
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason.isHold) {
                "a window that changed nothing is answered by one of the hold reasons: reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = familyState?.let { AdaptiveCompletion.WindowEvaluated(it) }
                ?: AdaptiveCompletion.NothingDecided

        override val familyId: String?
            get() = familyState?.familyId
    }

    /**
     * The window was read for a family the target opportunity does not present: there is no element to
     * attach an adjustment to, so the bookkeeping advances and nothing else is written.
     */
    data class WindowOnly(
        override val familyId: String,
        val familyState: FamilyProgressionState?,
        val reason: ProgramAdaptiveReason
    ) : AdaptiveIntegrationOutcome {

        init {
            require(familyId.isNotBlank()) { "a window is read for a named family" }
        }

        override val completion: AdaptiveCompletion
            get() = familyState?.let { AdaptiveCompletion.WindowEvaluated(it) }
                ?: AdaptiveCompletion.NothingToAdapt(familyId)
    }

    data class CannotBuildAdaptiveRequest(val gap: AdaptiveInputGap) : AdaptiveIntegrationOutcome {

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.NothingDecided

        override val familyId: String?
            get() = null
    }
}
