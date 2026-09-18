package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant

/**
 * One slot-scoped automatic change: the element as the plan had it, and the element as it now should be
 * presented (§16).
 *
 * Adjustments are how the adaptive stage changes what the user is about to be shown **without touching
 * the revision**. That is the whole reason this type exists separately from the plan: a revision is
 * immutable history, so an adaptation cannot be an edit to it; it is a delta that applies to one
 * concrete future slot, is consumed when that slot's session is started, and becomes a real plan change
 * only if the user saves it.
 *
 * Four rules are enforced here rather than left to the caller:
 *
 *  * an adjustment changes one plan element — the identity of the presentation before and after must
 *    be the same occurrence, so an adjustment cannot quietly swap in a different element;
 *  * an adjustment changes something. A decision that would change nothing is `NothingToChange`, which
 *    is an expected result and not an adjustment (§28);
 *  * an adjustment may supersede an earlier one for the same slot, and the chain is recorded by id —
 *    the superseded adjustment is not rewritten, so the history of what the user was shown stays
 *    explainable;
 *  * an adjustment does not outlive its usefulness: once the session snapshot is taken, the effective
 *    presentation is frozen there.
 *
 * @property adjustmentId identity of this adjustment.
 * @property decisionId the decision that produced it.
 * @property slotId the slot it applies to.
 * @property before the element as the revision (plus any still-standing adjustments) presented it.
 * @property after the element as it should now be presented.
 * @property createdAt when it was produced.
 * @property supersedesAdjustmentId the adjustment it replaces for this slot, or `null`.
 */
data class AdaptiveAdjustment(
    val adjustmentId: AdjustmentId,
    val decisionId: DecisionId,
    val slotId: SlotId,
    val before: EffectiveExercise,
    val after: EffectiveExercise,
    val createdAt: Instant,
    val supersedesAdjustmentId: AdjustmentId? = null
) {

    init {
        require(before.programExerciseId == after.programExerciseId) {
            "an adjustment changes one presented element, not a different one: " +
                "before=${before.programExerciseId} after=${after.programExerciseId}"
        }
        require(before != after) {
            "an adjustment that changes nothing is NothingToChange, not an adjustment"
        }
        require(supersedesAdjustmentId != adjustmentId) {
            "an adjustment cannot supersede itself"
        }
    }

    /** Whether this adjustment replaces an earlier one for the same slot. */
    val supersedesAnEarlierAdjustment: Boolean
        get() = supersedesAdjustmentId != null
}
