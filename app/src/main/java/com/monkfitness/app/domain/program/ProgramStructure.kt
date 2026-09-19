package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.prescription.Prescription

/**
 * A plan's **content**, with every identity stripped out.
 *
 * The question this value exists to answer is the one §6 makes load-bearing: *did the user's edit
 * change the structure?* Renaming, describing, selecting, starting, pausing, resuming, finishing,
 * archiving and planning a start date must create **no** revision, while mode, duration, frequency,
 * weekdays, schedule, exercise selection, the plan, ordering, prescriptions, rest and pinning must
 * create one. Those two sets are only distinguishable if "the structure" is a comparable value, so
 * this is that value — and identity is deliberately not part of it.
 *
 * Identity is excluded for a reason that is easy to get wrong. A *draft* addresses its days and its
 * plan elements by id (that is how an edit says which day to remove), so a draft opened from a
 * revision carries that revision's identities as its working handles — while a **saved** revision
 * never reuses them: a new revision's plan is new rows with new identities (§6, §23). A comparison
 * that included identity would therefore report a difference on every save that re-identifies an
 * unchanged plan, turning the one case that must create nothing into a revision.
 *
 * What is compared, then:
 *
 * ```text
 * mode, duration, schedule      the revision's own configuration
 * days, in order                each day's type and name
 * plan elements, in order       each element's exercise, per-set prescription, authorship, pin
 * ```
 *
 * Positions are not stored: the lists *are* the order, so a re-ordered plan differs while a
 * renumbered-but-otherwise-identical one does not.
 */
data class ProgramStructure(
    val mode: ProgramMode,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    val days: List<StructuredDay>
) {

    /** The names of this structure's days, in order. */
    val dayCount: Int
        get() = days.size

    /** How many of this structure's plan elements prescribe work, counting repeats separately. */
    val exerciseCount: Int
        get() = days.sumOf { it.exercises.size }

    /** How many sets the whole structure prescribes, across every occurrence and every dimension. */
    val setCount: Int
        get() = days.sumOf { day -> day.exercises.sumOf { it.prescription.setCount } }

    companion object {

        /** The structure of one plan, in day order, with the day and element identities dropped. */
        fun of(
            mode: ProgramMode,
            duration: ProgramDuration,
            schedule: ProgramSchedule,
            days: List<ProgramDay>
        ): ProgramStructure = ProgramStructure(
            mode = mode,
            duration = duration,
            schedule = schedule,
            days = days.map { StructuredDay.of(it) }
        )
    }
}

/** One day of a [ProgramStructure]: what the day is, what it is called, and what it plans. */
data class StructuredDay(
    val type: ProgramDayType,
    val name: String?,
    val exercises: List<StructuredExercise>
) {

    /** Whether this day prescribes work — a rest day is the one type that prescribes nothing (§20). */
    val isWorkDay: Boolean
        get() = type != ProgramDayType.REST

    companion object {

        fun of(day: ProgramDay): StructuredDay =
            StructuredDay(
                type = day.type,
                name = day.name,
                exercises = day.exercises.map { StructuredExercise.of(it) }
            )
    }
}

/**
 * One plan element of a [ProgramStructure].
 *
 * [origin] is content, not bookkeeping: whether the generator or the user authored an element is what
 * a regenerate pass keeps or replaces (§7), so a change of authorship *is* a change of the plan. The
 * prescription is carried whole — per set, in its dimension — because a plan that says `12 / 10 / 8 /
 * 6` is not the plan that says `10 / 10 / 10 / 10` (§10).
 */
data class StructuredExercise(
    val exerciseId: String,
    val prescription: Prescription,
    val origin: ProgramExerciseOrigin,
    val isPinned: Boolean
) {

    companion object {

        fun of(element: ProgramExercise): StructuredExercise =
            StructuredExercise(
                exerciseId = element.exerciseId,
                prescription = element.prescription,
                origin = element.origin,
                isPinned = element.isPinned
            )
    }
}

/** The structure of the plan a saved revision describes. */
val ProgramRevision.structure: ProgramStructure
    get() = ProgramStructure.of(mode, duration, schedule, days)

/** The structure of the plan a draft is currently holding — what `Save` would persist (§7). */
val ProgramEditorDraft.structure: ProgramStructure
    get() = ProgramStructure.of(mode, duration, schedule, days)

/**
 * A dimension of the structure that can differ between two plans.
 *
 * The vocabulary is §6's list of what creates a revision, grouped the way a user would change it:
 * the three configuration facts have a value of their own, and the plan is read as days (what the
 * days *are*), exercises (which exercises, in which order, authored by whom), prescriptions (what
 * each element asks for per set) and pinning (which elements are exempt from automatic change).
 *
 * It is deliberately *not* a diff: the aspects are ordered by this declaration and each appears at
 * most once, because "which dimensions changed" is what a Review screen shows and a user can act on.
 */
enum class ProgramStructureAspect {

    /** The mode: manual or generated (§2). Switching it is an explicit structural change. */
    MODE,

    /** How long the revision runs (§20). */
    DURATION,

    /** The weekly rhythm: fixed weekdays or a deterministic frequency (§20). */
    SCHEDULE,

    /** The days themselves — how many, in what order, of which type, under which names. */
    DAYS,

    /** Exercise selection and ordering, including who authored each element (§7, §9). */
    EXERCISES,

    /** What each element prescribes per set (§10). */
    PRESCRIPTIONS,

    /** Which elements are exempt from automatic change (§7). */
    PINNING
}

/**
 * The dimensions in which this structure differs from [base], in a fixed order.
 *
 * `isEmpty()` is the editor's "nothing structural changed" test, and it is what makes a no-op Save a
 * no-op (§6: *no-op Save creates none*). An empty result is not a failure: it is the answer that a
 * draft the user opened and closed without touching the plan produces.
 */
fun ProgramStructure.differencesFrom(base: ProgramStructure): List<ProgramStructureAspect> =
    ProgramStructureAspect.entries.filter { aspect -> differsFrom(base, aspect) }

private fun ProgramStructure.differsFrom(
    base: ProgramStructure,
    aspect: ProgramStructureAspect
): Boolean = when (aspect) {
    ProgramStructureAspect.MODE -> mode != base.mode
    ProgramStructureAspect.DURATION -> duration != base.duration
    ProgramStructureAspect.SCHEDULE -> schedule != base.schedule
    ProgramStructureAspect.DAYS -> dayLabels != base.dayLabels
    ProgramStructureAspect.EXERCISES -> selection != base.selection
    ProgramStructureAspect.PRESCRIPTIONS -> prescriptions != base.prescriptions
    ProgramStructureAspect.PINNING -> pinning != base.pinning
}

/**
 * Each day as (type, name), in order — the day list as a comparable value.
 *
 * The four readouts below are flattened over the whole plan, in plan order, rather than nested per
 * day: a day boundary is a *part* of the day list (which is [ProgramStructureAspect.DAYS]'s own
 * dimension), not a dimension of its own, so adding a rest day changes the day list and nothing else,
 * while re-ordering the elements of a day changes the exercise order as a user would expect.
 */
private val ProgramStructure.dayLabels: List<Pair<ProgramDayType, String?>>
    get() = days.map { it.type to it.name }

/** The plan's elements as (exercise, author), in plan order — selection and ordering as a value. */
private val ProgramStructure.selection: List<Pair<String, ProgramExerciseOrigin>>
    get() = days.flatMap { day -> day.exercises.map { it.exerciseId to it.origin } }

/** The plan's per-set prescriptions, in plan order. */
private val ProgramStructure.prescriptions: List<Prescription>
    get() = days.flatMap { day -> day.exercises.map { it.prescription } }

/** The plan's pin flags, in plan order. */
private val ProgramStructure.pinning: List<Boolean>
    get() = days.flatMap { day -> day.exercises.map { it.isPinned } }
