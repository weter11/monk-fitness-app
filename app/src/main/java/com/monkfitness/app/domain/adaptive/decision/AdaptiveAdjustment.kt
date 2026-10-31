package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant

/**
 * One slot-scoped automatic change: the element as the plan had it, and the element as it should now be
 * presented.
 *
 * This is how the stage changes what the user is about to be shown **without touching the revision**: a
 * revision is immutable history, so an adaptation is a delta that applies to one concrete future slot, is
 * consumed when that slot's session starts, and becomes a real plan change only if the user saves it.
 *
 * An adjustment changes one element and changes something; a superseded adjustment is recorded by id and
 * never rewritten, so the history of what the user was shown stays explainable.
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

    val supersedesAnEarlierAdjustment: Boolean
        get() = supersedesAdjustmentId != null
}
