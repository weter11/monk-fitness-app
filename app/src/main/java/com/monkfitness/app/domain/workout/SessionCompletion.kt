package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * What the adaptive stage decided about one completion — the third leg of §27's completion transaction,
 * as a value the caller hands in.
 *
 * §27's `Complete Workout → Session + Slot + Adaptive state + Decisions + Adjustments` is one unit of
 * work, and the two halves of it are owned by different layers: the session and the opportunity are the
 * runtime's, the decision and its adjustment are the adaptive stage's (§30 steps 11–12). This type is
 * the seam between them, and it is a **value** so that the runtime never has to invent one.
 *
 * The distinction it carries is the one §18 makes: a decision that was filtered out by the aggregate
 * load guard is *recorded with `NOT_APPLIED`* rather than dropped, so an adaptive stage with a decision
 * that applied nothing hands over [Decided] with an adjustment-less `NOT_APPLIED` decision and it is
 * stored — while an adaptive stage with **no decision at all** hands over [NothingDecided] and the
 * completion persists no adaptive row. Neither case is a decision the runtime made up, which is the
 * whole reason the choice is the caller's.
 */
sealed interface AdaptiveCompletion {

    /**
     * The adaptive stage decided nothing about this completion.
     *
     * No decision and no adjustment is written, and the completion reports that explicitly
     * ([AdaptiveOutcome.NothingDecided]) rather than fabricating a `NOT_APPLIED` decision, its target,
     * its action, its evidence and its confidence: a decision is a record of something that was decided
     * (§16) and this runtime decides none.
     */
    data object NothingDecided : AdaptiveCompletion

    /**
     * The adaptive stage made one decision, and applied it when it applied one.
     *
     * [adjustment] is present exactly when the decision is `APPLIED` — the pairing the adaptive
     * persistence contract itself enforces — and absent for a decision that was filtered out. The
     * decision must be about **this** opportunity, which the completion checks: the decision, the
     * session and the slot it writes all describe one fact.
     */
    data class Decided(
        val decision: AdaptiveDecision,
        val adjustment: AdaptiveAdjustment? = null
    ) : AdaptiveCompletion
}

/** What one completion wrote of the adaptive half, as a value a caller can present. */
sealed interface AdaptiveOutcome {

    /** Nothing adaptive was written, because the adaptive stage decided nothing ([AdaptiveCompletion.NothingDecided]). */
    data object NothingDecided : AdaptiveOutcome

    /**
     * One decision was stored — with the adjustment it produced, or alone when it was filtered out.
     *
     * @property decisionId the stored decision.
     * @property adjustmentId the stored adjustment, or `null` for a decision that applied none.
     */
    data class Stored(val decisionId: DecisionId, val adjustmentId: AdjustmentId?) : AdaptiveOutcome
}

/**
 * One finished workout: the session as it now stands, the opportunity it took, and what the completion
 * wrote of the adaptive half.
 *
 * Both [session] and [slot] are **read back from storage** after the unit of work, not the values the
 * caller handed in, so what the completion reports is what a process restart would load (§19). The
 * three of them are one fact and are produced by the one transaction that wrote them; [adaptive] says
 * explicitly whether that unit included an adaptive row, which is what makes *"a completion that
 * applied nothing"* distinguishable from *"a completion whose adaptive write was lost"*.
 */
data class SessionCompletion(
    val session: WorkoutSession,
    val slot: WorkoutSlot,
    val adaptive: AdaptiveOutcome
) {

    /** Whether this completion also recorded an adaptive decision. */
    val recordedAnAdaptiveDecision: Boolean
        get() = adaptive is AdaptiveOutcome.Stored
}
