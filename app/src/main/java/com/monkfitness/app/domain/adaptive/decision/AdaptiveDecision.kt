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
 * One auditable adaptive decision: what was decided, for what, on what grounds, and how it ended. A
 * **record**, not an instruction that was executed and forgotten.
 *
 * The reason is the one piece of the engine's reasoning that is stored: the trail has to tell "the change
 * was earned and the guard refused it" from "there was not enough history", and the two are the same row
 * shape without it. Signals, thresholds and the policy version stay out, because they are recomputable
 * from the window and the reason is not.
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

    val isApplied: Boolean
        get() = outcome == DecisionOutcome.APPLIED
}
