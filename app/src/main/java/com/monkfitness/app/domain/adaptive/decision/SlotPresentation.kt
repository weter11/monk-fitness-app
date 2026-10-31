package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import java.time.Instant

/**
 * The composition as two pure functions: which adjustments are still standing for one slot, and what the
 * slot presents once they are applied.
 *
 * The presentation is composed from **the revision the opportunity was scheduled from**, not the
 * Program's current revision: a later revision mints new day and element identities, so a slot's plan day
 * is not present in it, and resolving the day by position would make the presentation a function of the
 * live revision. Neither function reads a clock, a database or a policy.
 */

/**
 * The adjustments nothing in the slot's chain supersedes, in the order they were made. Supersession is a
 * link: the earlier row is *not* rewritten, so "which adjustment is in effect" is answerable from the
 * rows alone. Several standing adjustments are legal only when they change **different** elements, which
 * [presentedWorkout] checks rather than assumes.
 */
fun standingAdjustments(adjustments: List<AdaptiveAdjustment>): List<AdaptiveAdjustment> {
    require(adjustments.map { it.adjustmentId }.toSet().size == adjustments.size) {
        "an adjustment appears once in a slot's chain: " +
            "${adjustments.map { it.adjustmentId.value }}"
    }
    val superseded = adjustments.mapNotNull { it.supersedesAdjustmentId }.toSet()
    return adjustments.filterNot { it.adjustmentId in superseded }
}

/**
 * What [slot] presents: the elements of [day] in their own order, each replaced by the standing adjustment
 * that changes it.
 *
 * Order is the plan's — an adjustment changes one element *in place*. Two standing adjustments for the
 * same element are two answers to "what is presented", so the chain is refused instead of resolved, and
 * an adjustment naming an element this day does not present fails loudly rather than starting a workout
 * that silently omits a stored change.
 */
fun presentedWorkout(
    day: ProgramDay,
    slot: WorkoutSlot,
    standing: List<AdaptiveAdjustment>,
    computedAt: Instant
): EffectiveWorkout {
    require(standing.map { it.after.programExerciseId }.toSet().size == standing.size) {
        "one element of a plan day is presented once: " +
            "${standing.map { it.after.programExerciseId.value }}"
    }
    val elementIds = day.exercises.map { it.programExerciseId }.toSet()
    val unknown = standing.firstOrNull { it.after.programExerciseId !in elementIds }
    require(unknown == null) {
        "an adjustment of this opportunity must change an element its revision presents: adjustment " +
            "'${unknown?.adjustmentId?.value}' changes '${unknown?.after?.programExerciseId?.value}'"
    }

    val byElement = standing.associateBy { it.after.programExerciseId }
    val presented = day.exercises.map { element ->
        // The **day's** element is the authority for the focus even when an adjustment supplies the
        // exercise or prescription: the adjustment's `after` carries no focus, so reading it here would
        // freeze an absent focus into the snapshot forever.
        val adjusted = byElement[element.programExerciseId]?.after
        EffectiveExercise(
            programExerciseId = element.programExerciseId,
            exerciseId = adjusted?.exerciseId ?: element.exerciseId,
            prescription = adjusted?.prescription ?: element.prescription,
            focus = element.focus
        )
    }
    return EffectiveWorkout(
        slotId = slot.slotId,
        programId = slot.programId,
        revisionId = slot.revisionId,
        plannedFor = slot.plannedFor,
        computedAt = computedAt,
        exercises = presented,
        appliedAdjustmentIds = presented.mapNotNull { byElement[it.programExerciseId]?.adjustmentId }
    )
}
