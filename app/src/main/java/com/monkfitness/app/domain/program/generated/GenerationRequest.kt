package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.ExerciseMetadata
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule

/**
 * One exercise the planner **may** choose, as the caller states it.
 *
 * The planner does not own an exercise catalogue and must not grow one, so the caller supplies a view
 * of the library it already has: [metadata] is the app's own [ExerciseMetadata] value, and this type
 * adds only the two facts a *planner* needs that value does not carry.
 *
 * [focuses] is stated by the caller rather than inferred, because the library's own classification is
 * not expressible here and where metadata cannot support a focus decision honestly the answer is an
 * explicit input boundary, never a hidden classification rule. **An empty set is refused**: an exercise
 * whose focuses are unknown is not "maybe everything", it is an exercise this request did not classify.
 *
 * [dimension] is the primary progression dimension a generated prescription is written in. The planner
 * prescribes in the exercise's own dimension, so it is told which one that is and never guesses from a
 * name or a category; the three dimensions the blueprint names and does not implement are representable
 * here and make the exercise *unusable for generation* — reported, never prescribed as something they
 * are not.
 *
 * Planning never mutates it: the value is read-only input, and no library definition is touched by
 * generating a plan.
 */
data class GenerationCandidate<E>(
    val metadata: ExerciseMetadata<E>,
    val focuses: Set<Focus>,
    val dimension: PrescriptionDimension
) {

    init {
        require(focuses.isNotEmpty()) {
            "a candidate the planner may choose states the focuses '${metadata.id}' trains; the " +
                "focus membership of an exercise is an input, not something the planner infers (§6, §8)"
        }
    }

    /** The library key of this exercise, opaque to the planner. */
    val exerciseId: String
        get() = metadata.id

    /** The family this exercise belongs to — selection's family membership axis. */
    val familyId: String
        get() = metadata.familyId

    /** The equipment this exercise cannot be performed without — a hard execution constraint. */
    val requiredEquipment: Set<E>
        get() = metadata.requiredEquipment

    /** Whether this exercise trains [focus]. */
    fun trains(focus: Focus): Boolean = focus in focuses

    /** The focuses this exercise trains, in the vocabulary's canonical order. */
    val focusesInOrder: List<Focus>
        get() = FocusPlan.canonical(focuses)
}

/**
 * The **plain signals** generation may take as input, and the boundary it stops at.
 *
 * Everything here is something the caller already knows; nothing here is computed by the planner. The
 * planner may consume these values and may not derive them — no evidence calculation, no progression
 * policy, no recovery policy and no adaptive adjustment appears in this stage, and the fields below are
 * its only access to anything adaptive.
 *
 * The maps are read as lookups per focus and the "most recent first" lists by index, never by iteration
 * order, so a caller that builds them in a different order gets a different plan only when it states a
 * different fact.
 *
 * @property userPreferredExerciseIds the exercises **the user** asked for, most preferred first. User
 *   choice outranks everything below it, including an adaptive preference — the request states both, so
 *   their order is a fact of the request rather than an assumption about who is right.
 * @property adaptivePreferredExerciseIds the exercises the adaptive layer would prefer, most preferred
 *   first. A plain input: this stage neither calculates nor interprets it.
 * @property recentExerciseIds the exercises used most recently, most recent first. An exercise absent
 *   from the list has not been used recently, which is a fact about the list and not a missing value.
 * @property recentExposureByFocus how much exposure each focus has recently had, in **assignments** —
 *   the unit the planner itself allocates. A focus's entry is the weight it already carried, not a
 *   count of workouts, so one workout that trained two focuses contributes to two entries. The planner
 *   adds its own assignments to this figure; it never derives the figure.
 * @property recentLoadByFocus how many sets of each focus were recently performed — a **count of sets
 *   the caller supplied**, not a scalar load score, and nothing in the domain converts it into one or
 *   compares two focuses' values with each other. Used only as the soft penalty described on
 *   [GenerationPolicy.recentLoadThreshold].
 * @property recovery the recovery context generation is planned in, reused rather than restated.
 */
data class GenerationPreferences(
    val userPreferredExerciseIds: List<String> = emptyList(),
    val adaptivePreferredExerciseIds: List<String> = emptyList(),
    val recentExerciseIds: List<String> = emptyList(),
    val recentExposureByFocus: Map<Focus, Int> = emptyMap(),
    val recentLoadByFocus: Map<Focus, Int> = emptyMap(),
    val recovery: RecoveryContext = RecoveryContext.UNKNOWN
) {

    init {
        val preferred = userPreferredExerciseIds + adaptivePreferredExerciseIds
        require(preferred.distinct().size == preferred.size) {
            "an exercise is preferred once: 'user choice' and 'adaptive preference' are two levels of " +
                "§9's order, so the same exercise may not be stated as both — " +
                "userPreferredExerciseIds=$userPreferredExerciseIds " +
                "adaptivePreferredExerciseIds=$adaptivePreferredExerciseIds"
        }
        require(recentExerciseIds.distinct().size == recentExerciseIds.size) {
            "'most recent first' is an order, so an exercise appears at most once: $recentExerciseIds"
        }
        require(recentExposureByFocus.values.all { it >= 0 }) {
            "exposure is a count of assignments and cannot be negative: $recentExposureByFocus"
        }
        require(recentLoadByFocus.values.all { it >= 0 }) {
            "recent load is a count of sets and cannot be negative: $recentLoadByFocus"
        }
    }

    companion object {

        /** No preference, no signal, no recovery context — a plan built from the configuration alone. */
        val NONE: GenerationPreferences = GenerationPreferences()
    }
}

/**
 * Everything the Generated Planner is given: the whole **pure input**.
 *
 * The list of what is *not* here is the contract: no Room, DAO, repository or ViewModel; no Android,
 * Context, resources or library port; no Clock, Random, mutable state or legacy generator. The domain
 * keeps Android and persistence out, identity and time are injectable so a pure engine can stay pure,
 * and generation forbids an uncontrolled random source — and none of those could be expressed by this
 * type even if a later edit wanted it to: the request holds values, and the only port the generator
 * needs — a draft identity source — is held by the editor that builds the draft.
 *
 * It states **what** to plan ([focus], [schedule], [duration]) and **from what** — the candidates the
 * caller allows, the equipment the user has, and the plain signals above. It carries no date: the plan
 * is a plan, not a calendar, and which dates it lands on stays the Scheduler's decision.
 *
 * @property candidates the exercises the caller allows. A candidate the equipment cannot support is
 *   still listed — the planner reports it instead of choosing it, so a hard constraint is never
 *   silently violated.
 * @property availableEquipment the equipment the user has. A candidate whose requirements are not a
 *   subset of this set is never selected, whatever else prefers it.
 */
data class GenerationRequest<E>(
    val focus: FocusPlan,
    val schedule: ProgramSchedule,
    val duration: ProgramDuration,
    val candidates: List<GenerationCandidate<E>>,
    val availableEquipment: Set<E>,
    val preferences: GenerationPreferences = GenerationPreferences.NONE,
    val policy: GenerationPolicy = GenerationPolicy.DEFAULT
) {

    init {
        require(candidates.isNotEmpty()) {
            "generation is planned from a library view: a request with no candidates plans nothing"
        }
        require(candidates.map { it.exerciseId }.distinct().size == candidates.size) {
            "the library view names each exercise once, found " +
                candidates.groupingBy { it.exerciseId }.eachCount().filterValues { it > 1 }.keys
        }
    }

    /** Whether [candidate] may be selected at all: equipment available, dimension prescribable. */
    fun isUsable(candidate: GenerationCandidate<E>): Boolean =
        availableEquipment.containsAll(candidate.requiredEquipment) && prescribes(candidate.dimension)

    /** The candidates that may be selected for [focus], in the request's own order. */
    fun usableCandidatesFor(focus: Focus): List<GenerationCandidate<E>> =
        candidates.filter { it.trains(focus) && isUsable(it) }

    /**
     * The focuses this request can actually plan, in the vocabulary's canonical order.
     *
     * A focus is plannable when at least one candidate trains it *and* may be selected: an exercise the
     * equipment forbids, or one prescribed in a dimension the domain does not implement, cannot put a
     * focus on a plan. Allocation runs over this set, so a hard constraint is honoured by the plan's own
     * shape rather than by a filter applied after it.
     */
    val plannableFocuses: List<Focus>
        get() = FocusPlan.canonical(
            focus.eligibleFocuses.filter { candidateFocus -> usableCandidatesFor(candidateFocus).isNotEmpty() }
        )

    /** Why [focus] cannot be planned by this request, or `null` when it can. */
    fun reasonFocusIsNotPlannable(focus: Focus): FocusUnusableReason? {
        if (usableCandidatesFor(focus).isNotEmpty()) return null
        val training = candidates.filter { it.trains(focus) }
        return when {
            training.isEmpty() -> FocusUnusableReason.NO_EXERCISE_TRAINS_THE_FOCUS
            training.none { prescribes(it.dimension) } ->
                FocusUnusableReason.PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED
            else -> FocusUnusableReason.EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT
        }
    }

    /**
     * The number of slots the plan covers: the weekly rhythm's slots, times the whole weeks the duration
     * covers.
     *
     * A fixed Program uses its own length in whole weeks (at least [GenerationPolicy.minimumCycleWeeks],
     * so no second calendar-duration interpretation is invented), and an indefinite one uses the
     * planning horizon's whole weeks. The result is a count of **plan days**, not of dates: which dates
     * those days land on remains the Scheduler's decision, and nothing here reads a calendar, a clock or
     * a weekday.
     */
    val slotCount: Int
        get() = sessionsPerWeek * cycleWeeks

    /** How many training opportunities one week of this schedule holds. */
    val sessionsPerWeek: Int
        get() = when (schedule) {
            is ProgramSchedule.FixedWeekdays -> schedule.weekdays.size
            is ProgramSchedule.FlexiblePerWeek -> schedule.sessionsPerWeek
        }

    /** How many whole weeks the plan covers, from the Program's own duration or the horizon. */
    val cycleWeeks: Int
        get() = when (duration) {
            is ProgramDuration.FixedDays -> maxOf(policy.minimumCycleWeeks, duration.days / 7)
            ProgramDuration.Indefinite -> policy.indefiniteCycleWeeks
        }

    companion object {

        /**
         * Whether a generated element may be prescribed in [dimension].
         *
         * Only the two dimensions with a prescription subtype are prescribable. The other three are
         * *representable and unimplemented* — a hand-authored plan may state them, and a generator may
         * not invent an algorithm for them — so a candidate in one of them is reported as unusable
         * rather than prescribed as something the domain cannot mean.
         */
        fun prescribes(dimension: PrescriptionDimension): Boolean = when (dimension) {
            PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED -> true
            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED -> false
        }
    }
}
