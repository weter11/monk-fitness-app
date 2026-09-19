package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant

/**
 * One auditable adaptive decision: what was decided, for what, on what grounds, and how it ended (§15,
 * §16, §18, §22).
 *
 * A decision is a **record**, not an instruction that was executed and forgotten. Everything needed to
 * explain it afterwards is part of the value — the action, the target, the evidence and confidence it
 * was made on, the recovery context it was made in, and the outcome — which is what the debug view of
 * a program shows as the decision/adjustment chain and what the decision-history table stores. The
 * reasoning that produced it, however, is not here: no signals, no thresholds and no policy version,
 * because the policy is a pure component whose rules are tested on their own and whose output is this
 * value.
 *
 * The reason *is* stored, and it is the one piece of the engine's reasoning that is: §22's decision
 * trail has to be able to tell *"the change was earned and the load guard refused it"* from *"there was
 * not enough history to say"*, and the two are the same row shape — one slot, one target, one action,
 * one outcome — without it. Signals, thresholds and the policy version stay out, because they are the
 * component's own business and are recomputable from the window; the reason is the decision's answer and
 * is not. `ProgramAdaptiveReason` is a stable domain token and not user-facing text (§25).
 *
 * The two ends of a decision are held consistent, so an incoherent record is not constructible:
 *
 *  * an `APPLIED` decision produced exactly one adjustment, and a `NOT_APPLIED` one produced none —
 *    the guard's filtered-out decisions stay in the history with that outcome rather than vanishing;
 *  * `HOLD` changes nothing, so it is never `APPLIED`;
 *  * a decision is about one slot of one revision, never about a program in the abstract: adaptations
 *    are applied to concrete future slots and are consumed when the session snapshot is taken.
 *
 * @property decisionId identity of this decision.
 * @property programId the Program it belongs to.
 * @property revisionId the revision in effect. A decision never creates a revision (§16).
 * @property slotId the slot the change applies to.
 * @property target what it changes, and at what scope.
 * @property action what it asks for.
 * @property outcome whether the change was applied or filtered out.
 * @property evidence how much comparable history justified it.
 * @property confidence how far that history could be trusted.
 * @property recovery the recovery context it was made in.
 * @property decidedAt when it was decided.
 * @property adjustmentId the adjustment it produced, present exactly when it was applied.
 * @property reason the single rule that answered for this decision, when one is known. It is `null`
 *   only for a decision that no engine produced (a value a caller assembled itself) and for a row
 *   written before §30 step 12 stored the reason; it is never guessed at on read.
 */
data class AdaptiveDecision(
    val decisionId: DecisionId,
    val programId: ProgramId,
    val revisionId: RevisionId,
    val slotId: SlotId,
    val target: AdaptiveTarget,
    val action: AdaptiveAction,
    val outcome: DecisionOutcome,
    val evidence: EvidenceLevel,
    val confidence: ConfidenceLevel,
    val recovery: RecoveryContext,
    val decidedAt: Instant,
    val adjustmentId: AdjustmentId? = null,
    val reason: ProgramAdaptiveReason? = null
) {

    init {
        require((outcome == DecisionOutcome.APPLIED) == (adjustmentId != null)) {
            "an APPLIED decision produced exactly one adjustment and a NOT_APPLIED decision " +
                "produced none: outcome=$outcome adjustmentId=$adjustmentId"
        }
        require(outcome != DecisionOutcome.APPLIED || action != AdaptiveAction.HOLD) {
            "HOLD changes nothing, so it is never an applied decision"
        }
    }

    /** Whether this decision changed the presentation of its slot. */
    val isApplied: Boolean
        get() = outcome == DecisionOutcome.APPLIED
}
