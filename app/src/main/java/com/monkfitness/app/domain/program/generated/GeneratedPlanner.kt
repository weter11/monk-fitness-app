package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription

/**
 * The **Generated Planner** (§30 step 10) — a request in, a [GeneratedPlan] out.
 *
 * ### What it composes, and in which order
 *
 * ```text
 * GenerationRequest
 *     ↓ the configuration's eligible focuses, minus the ones nothing usable can train
 * Focus Planner          (§8)      → 1 primary + 0–2 secondary focuses per slot
 *     ↓ one element per assigned focus, chosen for that focus
 * Exercise selection     (§9)      → the exercise that realizes it
 *     ↓
 * GeneratedPlan          a value, not a draft, with no identity and no persistence
 * ```
 *
 * The order is §8's pipeline (Focus Planner → Generator) and it is load-bearing: the plan's
 * *emphasis* is decided before its *content*, so a slot's elements exist to serve focuses the plan
 * has already committed to, rather than a workout's focuses being whatever its exercises happened to
 * imply. That is also what keeps §6 of the stage's brief satisfied — nothing here ever infers a focus
 * from a [com.monkfitness.app.domain.program.ProgramDayType], a category or an exercise's presence in
 * a list; the only source of a focus is the configuration the user stated and the membership the
 * caller supplied.
 *
 * ### What it cannot do, by construction
 *
 * It persists nothing: the whole file has no repository, no DAO, no session, no slot, no date, no
 * clock and no random source, so a generation pass has no way to write anywhere (§33) and no way to
 * be non-deterministic (§9). It invokes no scheduler: a date is not expressible here, so "turning the
 * plan into slots" cannot be moved into it (§20, §30 step 7). It computes no adaptive decision: the
 * only adaptive values it sees are the plain signals on [GenerationPreferences]. And it holds no
 * state: [plan] is a function of its request, so the same request produces the same plan, always.
 */
object GeneratedPlanner {

    /**
     * Plans [request]: the focus assignment of every slot and the elements that realize it.
     *
     * The focuses the request cannot plan are reported in [GeneratedPlan.limitations] and take no part
     * in the allocation — so the plan's own exposure shape is the shape the user's selection and
     * equipment can actually deliver, and a hard constraint is honoured by the plan rather than
     * filtered out of it afterwards.
     */
    fun <E> plan(request: GenerationRequest<E>): GeneratedPlan {
        val plannable = request.plannableFocuses
        val limitations = request.focus.eligibleFocuses.mapNotNull { focus ->
            request.reasonFocusIsNotPlannable(focus)?.let { reason ->
                GenerationLimitation.UnusableFocus(focus, reason)
            }
        }
        if (plannable.isEmpty()) {
            return GeneratedPlan(
                focus = request.focus,
                slots = emptyList(),
                limitations = limitations + GenerationLimitation.NoPlannableFocus
            )
        }

        val assignments = FocusPlanner.allocate(
            focus = request.focus,
            slotCount = request.slotCount,
            plannableFocuses = plannable,
            preferences = request.preferences,
            policy = request.policy
        )

        return GeneratedPlan(
            focus = request.focus,
            slots = slotsOf(request, assignments),
            limitations = limitations
        )
    }

    /** The slots of [assignments], in order, each filled with one element per assigned focus. */
    private fun <E> slotsOf(
        request: GenerationRequest<E>,
        assignments: List<FocusAssignment>
    ): List<GeneratedSlot> =
        assignments.fold(Cycle(emptyList())) { cycle, assignment ->
            Cycle(cycle.slots + cycle.nextSlot(request, assignment))
        }.slots

    /**
     * The plan built so far — the state the selection needs and nothing else.
     *
     * Ordering is read from the slots themselves rather than kept beside them (the counts are derived
     * by [usesOfExercise] and [usesOfFamily]), so the plan and the diversity signal cannot disagree:
     * there is one record of what has been chosen.
     */
    private data class Cycle(val slots: List<GeneratedSlot>) {

        /** The slot that follows this plan, realizing [assignment]'s focuses in their own order. */
        fun <E> nextSlot(request: GenerationRequest<E>, assignment: FocusAssignment): GeneratedSlot {
            val elements = assignment.focuses.map { focus ->
                val chosen = ExerciseSelector.select(
                    request = request,
                    focus = focus,
                    usesThisCycle = usesOfExercise(),
                    familyUsesThisCycle = usesOfFamily()
                ) ?: throw IllegalStateException(
                    "focus $focus was allocated a slot but has no usable exercise: allocation only " +
                        "runs over the focuses GenerationRequest.plannableFocuses reported, and a " +
                        "plannable focus has at least one usable candidate by definition"
                )
                GeneratedElement(
                    focus = focus,
                    exerciseId = chosen.exerciseId,
                    familyId = chosen.familyId,
                    prescription = prescriptionFor(request.policy, chosen.dimension)
                )
            }
            return GeneratedSlot(
                position = slots.size + 1,
                assignment = assignment,
                elements = elements
            )
        }

        /** How many times each exercise this plan has already chosen. */
        fun usesOfExercise(): Map<String, Int> =
            slots.flatMap { it.elements }.groupingBy { it.exerciseId }.eachCount()

        /** How many times each family this plan has already drawn on — §12's family dimension. */
        fun usesOfFamily(): Map<String, Int> =
            slots.flatMap { it.elements }.groupingBy { it.familyId }.eachCount()
    }

    /**
     * The prescription a generated element carries, in the exercise's own dimension (§10).
     *
     * The targets come from the policy and are §10's own two example shapes: this is the plan's own
     * prescription, written per set, and it touches no exercise metadata. The three named dimensions
     * with no subtype are unreachable here — a candidate in one of them is reported unusable rather
     * than prescribed — so the fall-through names the invariant instead of returning something a
     * dimension cannot mean.
     */
    private fun prescriptionFor(
        policy: GenerationPolicy,
        dimension: PrescriptionDimension
    ): Prescription = when (dimension) {
        PrescriptionDimension.REP_BASED -> RepPrescription(policy.repPrescriptionTargets)
        PrescriptionDimension.TIME_BASED -> TimePrescription(policy.timePrescriptionTargets)
        PrescriptionDimension.SET_BASED,
        PrescriptionDimension.DIFFICULTY_BASED,
        PrescriptionDimension.REST_BASED -> throw IllegalStateException(
            "a generated element is never prescribed in $dimension: GenerationRequest.isUsable " +
                "excludes every candidate whose dimension §10 names without implementing"
        )
    }
}
