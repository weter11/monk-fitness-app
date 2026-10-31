package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.prescription.Prescription
import java.time.Instant
import java.time.LocalDate

/**
 * One element of an effective workout: what the user will actually be presented with. The same shape as a
 * plan element minus what only matters while the plan is built — no pin, no origin, because presentation
 * is not authoring.
 *
 * [focus] is the plan element's recorded focus, copied through presentation unchanged even when an
 * adjustment swaps the exercise or the prescription: an adjustment changes what is shown, not which focus
 * the element was planned for. `null` means absence, not a default focus.
 */
data class EffectiveExercise(
    val programExerciseId: ProgramExerciseId,
    val exerciseId: String,
    val prescription: Prescription,
    val focus: Focus? = null
) {

    init {
        require(exerciseId.isNotBlank()) { "a presented exercise must name the exercise" }
    }
}

/**
 * What should be presented for one slot, right now: the composition `revision + adjustments`, where the
 * immutable revision says what the plan is and the adjustments say what the adaptive stage wants changed.
 * Adjustments change the presentation, never the revision.
 *
 * It is a computed value — [computedAt] says when — and computing it again after a new adjustment
 * supersedes an old one legitimately produces a different value. That is what separates it from
 * [WorkoutSessionSnapshot]: this answers "what should be presented now", while a snapshot is the immutable
 * record of what was presented when a session started. Nothing here records anything that happened, and a
 * session never re-derives its plan from a newer effective workout. [exercises] is empty for a rest day.
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

    val isAdjusted: Boolean
        get() = appliedAdjustmentIds.isNotEmpty()
}
