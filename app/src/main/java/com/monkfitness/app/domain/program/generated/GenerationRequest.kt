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
 * The planner does not own an exercise catalogue and must not grow one (§10, §25), so the caller
 * supplies a view of the library it already has. The view is deliberately **composition, not a
 * second catalogue**: [metadata] is the app's own [ExerciseMetadata] value — canonical id, family,
 * training domain, body region and required equipment, with the equipment type left generic so the
 * domain never re-declares that vocabulary — and this type adds only the two facts a *planner* needs
 * and that value does not carry:
 *
 *  * [focuses] — the focuses the exercise trains, stated by the caller. §8's Focus Planner has to
 *    allocate exposure over the focus vocabulary and §9's selection has to respect *focus/family
 *    membership*, and the library's own classification is not expressible here. It is passed in
 *    explicitly rather than inferred: §6 of the stage's brief is explicit that where metadata is
 *    insufficient to support a focus decision honestly, the answer is an explicit input boundary,
 *    never a hidden classification rule. **An empty set is refused**: an exercise whose focuses are
 *    unknown is not "maybe everything", it is an exercise this request did not classify.
 *  * [dimension] — the primary progression dimension a generated prescription is written in (§10).
 *    The planner prescribes in the exercise's own dimension, so it is told which one that is; it
 *    never guesses from a name or a category. The three dimensions §10 names and does not implement
 *    are representable here and make the exercise *unusable for generation* — reported, never
 *    prescribed as something they are not.
 *
 * Nothing here is mutated by planning: the value is read-only input, and no library definition is
 * touched by generating a plan (§10).
 *
 * @property metadata the app's own metadata for this exercise.
 * @property focuses the focuses this exercise trains, at least one.
 * @property dimension the dimension a generated prescription for this exercise is written in.
 * @param E the caller's equipment vocabulary — the domain never names an equipment value.
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

    /** The library key of this exercise, opaque to the planner (§10). */
    val exerciseId: String
        get() = metadata.id

    /** The family this exercise belongs to — §9's family membership axis. */
    val familyId: String
        get() = metadata.familyId

    /** The equipment this exercise cannot be performed without (§9's hard execution constraint). */
    val requiredEquipment: Set<E>
        get() = metadata.requiredEquipment

    /** Whether this exercise trains [focus]. */
    fun trains(focus: Focus): Boolean = focus in focuses

    /** The focuses this exercise trains, in the vocabulary's canonical order. */
    val focusesInOrder: List<Focus>
        get() = FocusPlan.canonical(focuses)
}

/**
 * The **plain signals** generation may take as input — and the boundary §30 step 10 stops at.
 *
 * Everything here is something the caller already knows; nothing here is computed by the planner.
 * That is the whole point of the type: §8 lets the Focus Planner *consider* recent exposure, recent
 * load, adaptive signals and recovery, and §30 step 10 is **before** the Adaptive Engine exists, so
 * the planner may consume these values and may not derive them. No evidence calculation, no
 * progression policy, no recovery policy, no adaptive decision and no adjustment appears anywhere in
 * this stage (§30 steps 11–12), and this boundary is what makes that mechanical: the planner's only
 * access to anything adaptive is the fields below.
 *
 * Four of the five lists and maps are ordered or keyed by the *vocabulary*, never by insertion:
 * [recentExposureByFocus] and [recentLoadByFocus] are read as counts per focus (a map lookup, never
 * an iteration whose order could rank anything), and the "most recent first" lists are read by index
 * — so a caller that builds them in a different order gets a different plan only when it states a
 * different fact.
 *
 * @property userPreferredExerciseIds the exercises **the user** asked for, most preferred first.
 *   §9's first level: user choice outranks everything below it, including an adaptive preference
 *   (the request states both, so their order is a fact of the request rather than an assumption).
 * @property adaptivePreferredExerciseIds the exercises the adaptive layer would prefer, most
 *   preferred first — §9's *"adaptive preference"* level. A plain input: this stage neither
 *   calculates nor interprets it.
 * @property recentExerciseIds the exercises used most recently, most recent first — §9's
 *   *"recency"* axis. An exercise absent from the list has not been used recently, which is a fact
 *   about the list and not a missing value.
 * @property recentExposureByFocus how much exposure each focus has recently had, in **assignments**
 *   — the unit the planner itself allocates. §8's *"weighted exposure, not simple workout counts"*:
 *   a focus's entry is the weight that focus already carried, not a count of workouts, so one
 *   workout that trained two focuses contributes to two entries. The planner adds its own
 *   assignments to this figure; it never derives the figure.
 * @property recentLoadByFocus how many sets of each focus were recently performed — §8's *"recent
 *   load"*, and §17's prohibition honoured: this is a **count of sets the caller supplied**, not a
 *   scalar load score, and nothing in the domain converts it into one or compares two focuses'
 *   values with each other. Used only as the soft penalty described on
 *   [GenerationPolicy.recentLoadThreshold].
 * @property recovery the recovery context generation is planned in (§14's own vocabulary, reused
 *   rather than restated).
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
 * Everything the Generated Planner is given: §30 step 10's **pure input**.
 *
 * The list of what is *not* here is the contract:
 *
 * ```text
 * no Room        no DAO          no repository      no ViewModel
 * no Android     no Context      no resources       no library port
 * no Clock       no Random       no mutable state   no legacy WorkoutGenerator
 * ```
 *
 * §25 keeps Android and persistence out of the domain, §26 makes identity and time injectable so a
 * pure engine can stay pure, and §9 forbids an uncontrolled random source in generation. Not one of
 * those things could be expressed by this type even if a later edit wanted it to: the request holds
 * values, and the only ports the generator needs — a draft identity source — are held by the editor
 * that builds the draft, not by the plan.
 *
 * The request states **what** to plan ([focus], [schedule], [duration]) and **from what** — the
 * candidates the caller allows, the equipment the user has, and the plain signals above. It carries
 * no date: the plan is a plan, not a calendar, and which dates it lands on stays the Scheduler's
 * decision (§20, §30 step 7).
 *
 * @property focus the Goals & Focus configuration the plan is built for (§8).
 * @property schedule the weekly rhythm, which fixes how many slots a week holds (§20).
 * @property duration how long the Program runs, which fixes how many whole weeks the plan covers.
 * @property candidates the exercises the caller allows: the user's selection, with its metadata and
 *   focus membership. A candidate the equipment cannot support is still listed — the planner reports
 *   it instead of choosing it (§9's *"hard execution constraints must never be silently violated"*).
 * @property availableEquipment the equipment the user has. A candidate whose requirements are not a
 *   subset of this set is never selected, whatever else prefers it.
 * @property preferences the plain signals above; empty by default.
 * @property policy the explicit numbers generation uses ([GenerationPolicy]).
 * @param E the caller's equipment vocabulary.
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
     * A focus is plannable when at least one candidate trains it *and* may be selected: an exercise
     * the equipment forbids, or one prescribed in a dimension the domain does not implement, cannot
     * put a focus on a plan. Allocation runs over this set, so a hard constraint is honoured by the
     * plan's own shape rather than by a filter applied after it.
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
     * The number of slots the plan covers: the weekly rhythm's slots, times the whole weeks the
     * duration covers.
     *
     * A fixed Program uses its own length in whole weeks (at least [GenerationPolicy.minimumCycleWeeks]
     * — §9 of the stage's brief: *"for fixed Programs, do not invent a second calendar-duration
     * interpretation"*), and an indefinite one uses the §20 planning horizon's whole weeks. The
     * result is a count of **plan days**, not of dates: which dates those days land on remains the
     * Scheduler's decision, and nothing here reads a calendar, a clock or a weekday.
     */
    val slotCount: Int
        get() = sessionsPerWeek * cycleWeeks

    /** How many training opportunities one week of this schedule holds (§20). */
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
         * Only the two dimensions §10 gives a subtype are prescribable. The other three are
         * *representable and unimplemented* — a hand-authored plan may state them, and a generator
         * may not invent an algorithm for them — so a candidate in one of them is reported as
         * unusable rather than prescribed as something the domain cannot mean.
         */
        fun prescribes(dimension: PrescriptionDimension): Boolean = when (dimension) {
            PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED -> true
            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED -> false
        }
    }
}
