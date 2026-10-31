package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * What the adaptive stage decided about one completion, as a value the caller hands in — the seam between
 * the runtime (session and opportunity) and the adaptive stage (state, decisions, adjustments), so the
 * runtime never has to invent one.
 *
 * The three shapes differ where it matters: [NothingDecided] writes nothing at all, because no window was
 * evaluated; [WindowEvaluated] advances the family's own bookkeeping even when the engine held; and
 * [Decided] stores a decision filtered out by the aggregate load guard as `NOT_APPLIED` rather than
 * dropping it.
 *
 * The family's state travels with the completion and is written in the same transaction as the session.
 * An `APPLIED` decision always carries it: an applied change moved the family to a variant, and a change
 * whose family position was not written could not be explained afterwards.
 */
sealed interface AdaptiveCompletion {

    /**
     * No window was evaluated: no family state, no decision and no adjustment is written, and the
     * completion reports that explicitly rather than fabricating a `NOT_APPLIED` decision with its target,
     * action, evidence and confidence — a decision records something that was decided, and nothing was.
     */
    data object NothingDecided : AdaptiveCompletion

    /**
     * One window was evaluated and the engine held: the family's own state after it is written, and nothing
     * else. [familyState] is the single current state of one family in one revision, carrying the
     * confirmation counts, cooldown position and recovery exit count this window advanced — not a decision
     * record, which is why no decision row is written with it.
     */
    data class WindowEvaluated(
        val familyState: FamilyProgressionState
    ) : AdaptiveCompletion

    /**
     * One decision, and its adjustment when it applied one. [adjustment] is present exactly when the
     * decision is `APPLIED`, and absent for one that was filtered out. The decision must be about a future
     * opportunity of this completion's Program and revision: the target is where the adjustment is
     * consumed, and filing a change against the just-completed opportunity would file it under a history
     * nothing can apply it to.
     */
    data class Decided(
        val decision: AdaptiveDecision,
        val adjustment: AdaptiveAdjustment? = null,
        val familyState: FamilyProgressionState? = null
    ) : AdaptiveCompletion {

        init {
            require((decision.outcome == DecisionOutcome.APPLIED) == (adjustment != null)) {
                "an applied decision produced exactly one adjustment and a not-applied one produced " +
                    "none: outcome=${decision.outcome} adjustment=${adjustment?.adjustmentId?.value}"
            }
            require(decision.outcome != DecisionOutcome.APPLIED || familyState != null) {
                "an applied change travels with the family's state after the window (§27's 'adaptive " +
                    "state' leg): decision='${decision.decisionId.value}'"
            }
            require(adjustment == null || adjustment.decisionId == decision.decisionId) {
                "an adjustment belongs to the decision that produced it"
            }
            require(familyState == null || familyState.familyId.isNotBlank()) {
                "the state leg names the family it is about"
            }
        }
    }
}

/** What one completion wrote of the adaptive half, as a value a caller can present. */
sealed interface AdaptiveOutcome {

    /** Nothing adaptive was written, because no window was evaluated. */
    data object NothingDecided : AdaptiveOutcome

    /**
     * One decision was stored — with the adjustment it produced, or alone when it was filtered out
     * ([adjustmentId] is `null` then).
     */
    data class Stored(val decisionId: DecisionId, val adjustmentId: AdjustmentId?) : AdaptiveOutcome
}

/**
 * One finished workout: the session as it now stands, the opportunity it took, and what the completion
 * wrote of the adaptive half.
 *
 * [session] and [slot] are read back from storage after the unit of work, not the values the caller handed
 * in, so what is reported is what a process restart would load. [adaptive] says explicitly whether that
 * unit included an adaptive row, which is what makes "a completion that applied nothing"
 * distinguishable from "a completion whose adaptive write was lost".
 */
data class SessionCompletion(
    val session: WorkoutSession,
    val slot: WorkoutSlot,
    val adaptive: AdaptiveOutcome
) {

    val recordedAnAdaptiveDecision: Boolean
        get() = adaptive is AdaptiveOutcome.Stored
}
