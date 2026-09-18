package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.Prescription
import java.time.Instant
import java.time.LocalDate

/**
 * One element of an effective workout: what the user will actually be presented with.
 *
 * It is the same shape as a plan element, minus everything that only matters while the plan is being
 * *built* — there is no pin and no origin here, because presentation is not authoring. What is left is
 * what the session screen needs: which plan element this is, which exercise, and what to ask for.
 *
 * @property programExerciseId the plan element this presentation comes from.
 * @property exerciseId the library key to present.
 * @property prescription what to ask for, per set — the revision's prescription, or the one an
 *   adaptive adjustment put in its place.
 */
data class EffectiveExercise(
    val programExerciseId: ProgramExerciseId,
    val exerciseId: String,
    val prescription: Prescription
) {

    init {
        require(exerciseId.isNotBlank()) { "a presented exercise must name the exercise" }
    }
}

/**
 * What should be presented for one slot, right now.
 *
 * This is the composition the architecture names as `ProgramRevision + AdaptiveAdjustment =
 * EffectiveWorkout` (§16): the immutable revision says what the plan is, the adjustments say what the
 * adaptive stage currently wants changed, and this value is the result of applying them. It is a
 * **computed** value — [computedAt] says when it was computed, and computing it again after a new
 * adjustment supersedes an old one legitimately produces a different value.
 *
 * That is precisely what separates it from [WorkoutSessionSnapshot]: an effective workout answers
 * "what should be presented now", and a snapshot is the immutable record of what *was* presented when
 * a session started. Nothing here is a record of anything that happened, and a session never
 * re-derives its plan from a newer effective workout.
 *
 * @property slotId the opportunity this presentation is for.
 * @property programId the Program it belongs to.
 * @property revisionId the revision it was composed from. Adjustments change the presentation, never
 *   the revision.
 * @property plannedFor the date the slot was planned for.
 * @property computedAt when this presentation was computed.
 * @property exercises the presentation, in plan order. Empty for a rest day.
 * @property appliedAdjustmentIds the adjustments that were applied, in application order.
 */
data class EffectiveWorkout(
    val slotId: SlotId,
    val programId: ProgramId,
    val revisionId: RevisionId,
    val plannedFor: LocalDate,
    val computedAt: Instant,
    val exercises: List<EffectiveExercise>,
    val appliedAdjustmentIds: List<AdjustmentId> = emptyList()
) {

    init {
        require(exercises.map { it.programExerciseId }.toSet().size == exercises.size) {
            "a plan element is presented at most once in one workout"
        }
        require(appliedAdjustmentIds.toSet().size == appliedAdjustmentIds.size) {
            "an adjustment is applied at most once"
        }
    }

    /** Whether any adaptive adjustment changed this presentation. */
    val isAdjusted: Boolean
        get() = appliedAdjustmentIds.isNotEmpty()
}
