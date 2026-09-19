package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import java.time.Instant

/**
 * §16's composition, as two pure functions: which adjustments are still standing for one slot, and what
 * the slot's presentation is once they are applied.
 *
 * ```text
 * ProgramRevision + AdaptiveAdjustment = EffectiveWorkout
 * ```
 *
 * The revision says what the plan is, the adjustments say what the adaptive stage currently wants
 * changed, and the result is what the user is actually presented with. It lives beside the adjustment
 * type because the *change* is the adjustment's own half of that sum, and it is pure — a day, a slot,
 * a chain of adjustments and a moment in, a presentation out — so what a session captures can be
 * measured without a database.
 *
 * ### Why the plan day, and not "the current revision"
 *
 * The presentation of an opportunity is composed from **the revision that opportunity was scheduled
 * from** — the one it names — and not from the Program's current revision. Three facts make that the
 * only composition that is defined:
 *
 *  * a later revision mints **new** day and element identities (`ProgramEditorService`), so a slot's
 *    plan day is not present in a revision saved after it. There is no stored mapping from a slot to a
 *    day of a newer revision, and inventing one (resolving the day by position) would make a slot's
 *    presentation a function of the live revision — the thing §19 forbids for a session and §16 makes
 *    an adjustment, not a plan edit, responsible for;
 *  * an adjustment is expressed against the element it changes — its `before` and `after` are plan
 *    elements — so an adjustment can only be applied to a revision that presents that element. A
 *    revision change is a §6 Save, and a slot that survives one keeps the plan day it named (§20: a
 *    slot is not re-pointed);
 *  * and the alternative is unreachable rather than merely undesirable: `session_snapshot` stores
 *    elements, never a pointer at a day of a newer revision, so nothing downstream could reconstruct
 *    the mapping the composition would have used.
 *
 * The consequence is stated plainly because it is a real one: after a §6 Save, an opportunity that
 * survived keeps presenting the plan day it was scheduled from until the Scheduler supersedes it. That
 * is the schedule's rule (§20) and not this function's: this function composes what one concrete
 * opportunity presents.
 *
 * Neither function reads a clock, a database or a policy, and neither mutates anything: a presentation
 * is computed from the values it is handed, which is what makes "the same opportunity presents the same
 * workout twice" a property of the types rather than a promise.
 */

/**
 * The adjustments of one slot that nothing in its chain supersedes, in the order they were made.
 *
 * §16's supersession is a link between two rows of one slot's chain: a new adjustment names the earlier
 * one it replaces and the earlier one is *not* rewritten, so *"which adjustment is in effect"* is
 * answerable from the rows alone. An adjustment nothing supersedes is the standing one, and a chain can
 * therefore hold several standing adjustments only when they change **different** plan elements — which
 * [presentedWorkout] checks rather than assuming.
 *
 * @throws IllegalArgumentException when the same adjustment is named twice, or when one adjustment
 *   supersedes itself — both are incoherent chains rather than unusual ones.
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
 * What [slot] presents: the elements of [day] in their own order, each replaced by the standing
 * adjustment that changes it, with the adjustment ids that were applied.
 *
 * The rules the value keeps are the ones a session is measured against:
 *
 *  * **order is the plan's.** An adjustment changes one element *in place* (§16: "an adjustment changes
 *    one plan element, not a different one"), so the presentation keeps the day's order and the
 *    snapshot that copies it preserves it too;
 *  * **one element, one adjustment.** Two standing adjustments for the same element would be two
 *    answers to "what is presented", so the chain is refused rather than resolved by picking one;
 *  * **nothing is invented and nothing is dropped.** An element with no standing adjustment is
 *    presented exactly as the revision wrote it, and an adjustment that names an element this day does
 *    not present fails loudly — the caller refuses that opportunity rather than starting a workout that
 *    silently omits a stored change (see `SessionRefusal.StoredAdjustmentIsNotOfThisRevision`).
 *
 * @param computedAt when the presentation was computed. It is the session's own start moment, so the
 *   snapshot it is copied into satisfies the snapshot's `computedAt <= capturedAt` (§19).
 * @throws IllegalArgumentException when two standing adjustments change the same element, or when one
 *   of them names an element [day] does not present.
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
        byElement[element.programExerciseId]?.after
            ?: EffectiveExercise(element.programExerciseId, element.exerciseId, element.prescription)
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
