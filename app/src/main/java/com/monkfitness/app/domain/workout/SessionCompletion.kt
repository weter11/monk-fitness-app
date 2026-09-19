package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * What the adaptive stage decided about one completion — the third leg of §27's completion transaction,
 * as a value the caller hands in.
 *
 * §27's `Complete Workout → Session + Slot + Adaptive state + Decisions + Adjustments` is one unit of
 * work, and the two halves of it are owned by different layers: the session and the opportunity are the
 * runtime's, and the adaptive state, the decisions and the adjustments are the adaptive stage's (§30
 * steps 11–12). This type is the seam between them, and it is a **value** so that the runtime never has to
 * invent one.
 *
 * ### The three shapes, and what each writes
 *
 * ```text
 * NothingDecided          nothing at all: no window was evaluated, so no state is advanced
 * WindowEvaluated(state)  the family's own state after a window that decided nothing (§23)
 * Decided(decision, …)    the audit half: a decision and, when it applied one, its adjustment
 * ```
 *
 * The distinction between the first two is the one §12 makes: a completion whose adaptive pass could not
 * be built (no eligible future slot, a manual plan, a ladder that is not declared) writes *nothing*
 * adaptive, while a pass that evaluated a window advances the family's own bookkeeping even when the
 * engine held. The distinction between the last two is §18's: a decision that was filtered out by the
 * aggregate load guard is *recorded with `NOT_APPLIED`* rather than dropped, so an adaptive stage with a
 * decision that applied nothing hands over [Decided] with an adjustment-less `NOT_APPLIED` decision and it
 * is stored.
 *
 * ### The state leg
 *
 * §27 lists **adaptive state** in the completion unit, and §11 decided that the family's window
 * bookkeeping is state the engine takes as an input rather than a fact any decision row records — so the
 * family's current state travels with the completion and is written in the same transaction as the
 * session it followed. An `APPLIED` decision always carries it: an applied change moved the family to a
 * variant, and a change whose family position was not written would be a change nothing could explain
 * afterwards.
 */
sealed interface AdaptiveCompletion {

    /**
     * The adaptive stage evaluated **no window** for this completion.
     *
     * No family state, no decision and no adjustment is written, and the completion reports that
     * explicitly ([AdaptiveOutcome.NothingDecided]) rather than fabricating a `NOT_APPLIED` decision, its
     * target, its action, its evidence and its confidence: a decision is a record of something that was
     * decided (§16) and this runtime decides none.
     */
    data object NothingDecided : AdaptiveCompletion

    /**
     * One window was evaluated and the engine held: the family's own state after it is written, and
     * nothing else.
     *
     * [familyState] is the single current state of one family in one revision (§23's own primary key),
     * carrying the confirmation counts, the cooldown position and the recovery exit count this window
     * advanced. It is not a decision record, which is why the completion writes no decision row with it.
     */
    data class WindowEvaluated(
        val familyState: FamilyProgressionState
    ) : AdaptiveCompletion

    /**
     * The adaptive stage made one decision, and applied it when it applied one.
     *
     * [adjustment] is present exactly when the decision is `APPLIED` — the pairing the adaptive persistence
     * contract itself enforces — and absent for a decision that was filtered out. The decision must be
     * about a **future** opportunity of this completion's Program and revision, which the completion
     * checks: the target opportunity is where the adjustment will be consumed, and recording a change
     * against the opportunity that was just completed would file it under a history nothing can apply it
     * to (§4, §16).
     *
     * @property familyState the family's state after the window, written in the same unit. Required for an
     *   applied decision: the change and the position it moved the family to are one fact.
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
