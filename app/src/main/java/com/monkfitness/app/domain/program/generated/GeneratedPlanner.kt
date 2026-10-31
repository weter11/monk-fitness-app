package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription

/**
 * The Generated Planner: a request in, a [GeneratedPlan] out — the Focus Planner's assignments first,
 * then one element per assigned focus, chosen for that focus.
 *
 * The order is load-bearing: the plan's *emphasis* is decided before its *content*, so a slot's
 * elements exist to serve focuses the plan has already committed to, rather than a workout's focuses
 * being whatever its exercises happened to imply. Nothing here infers a focus from a
 * [com.monkfitness.app.domain.program.ProgramDayType], a category or an exercise's presence in a list;
 * the only source of a focus is the configuration the user stated and the membership the caller
 * supplied.
 *
 * It persists nothing and invokes no scheduler: the whole file holds no repository, DAO, session, slot,
 * date, clock or random source, so a generation pass cannot write anywhere, cannot be
 * non-deterministic, and cannot turn the plan into dates. The only adaptive values it sees are the
 * plain signals on [GenerationPreferences], and [plan] is a function of its request, so the same
 * request produces the same plan, always.
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
                    prescription = prescriptionFor(request.policy, chosen)
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

        /** How many times each family this plan has already drawn on — family load is its own dimension. */
        fun usesOfFamily(): Map<String, Int> =
            slots.flatMap { it.elements }.groupingBy { it.familyId }.eachCount()
    }

    /**
     * The prescription a generated element carries, in the exercise's own dimension.
     *
     * When the candidate's catalogue metadata states a progression range, the element prescribes the
     * catalogue's own set count, uniformly, at that range's **lower bound** — a push-up starts at
     * `3 × 6`, a plank at `3 × 30 s`, an exercise the catalogue defines with two sets at two. The range
     * is the exercise's full progression span (`baseMin..phase4Max`, see
     * `Exercise.toConfigurationMetadata`), so its first value is where the exercise begins rather than
     * where a generator's fixed shape happens to start: the policy's `12 / 10 / 8 / 6` and
     * `30s / 30s / 45s` describe targets for *one* plan, and applying them to every exercise regardless
     * of that exercise's own definition is exactly what this avoids.
     *
     * [GenerationPolicy]'s two lists are the **fallback**, not the rule: a candidate whose caller states
     * no range (a hand-stated library view, a fixture) still has to be prescribed something, and a
     * caller that wants the canonical shapes verbatim states no range.
     *
     * The three named dimensions with no subtype are unreachable here — a candidate in one of them is
     * reported unusable rather than prescribed — so the fall-through names the invariant instead of
     * returning something a dimension cannot mean.
     */
    private fun prescriptionFor(
        policy: GenerationPolicy,
        candidate: GenerationCandidate<*>
    ): Prescription = when (candidate.dimension) {
        PrescriptionDimension.REP_BASED ->
            candidate.metadata.repRange
                ?.let { range -> RepPrescription.uniform(candidate.metadata.sets, range.first) }
                ?: RepPrescription(policy.repPrescriptionTargets)
        PrescriptionDimension.TIME_BASED ->
            candidate.metadata.timeRange
                ?.let { range -> TimePrescription.uniform(candidate.metadata.sets, range.first) }
                ?: TimePrescription(policy.timePrescriptionTargets)
        PrescriptionDimension.SET_BASED,
        PrescriptionDimension.DIFFICULTY_BASED,
        PrescriptionDimension.REST_BASED -> throw IllegalStateException(
            "a generated element is never prescribed in ${candidate.dimension}: " +
                "GenerationRequest.isUsable excludes every candidate whose dimension §10 names " +
                "without implementing"
        )
    }
}
