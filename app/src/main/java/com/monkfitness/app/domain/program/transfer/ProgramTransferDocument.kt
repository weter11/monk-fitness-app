package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import java.time.DayOfWeek

/**
 * The transfer model: the Program's transferable definition, as values that are neither the domain's nor
 * the database's.
 *
 * Neither alternative works. Serializing the Room entity would make the format depend on column names,
 * and the schema is not the definition — an entity carries `programId`, `revisionId`, `createdAt` and the
 * lifecycle, none of which may be shared. Serializing the domain value would make the format change
 * whenever the domain does, and the domain's own types carry fields a file must never carry. So this is
 * the allowlist written out: no identity, no timestamp, no lifecycle state, no `ProgramSource`, no
 * runtime fact — and that absence is the design, since a format with no identity to reuse cannot reuse
 * one.
 *
 * `ProgramSource` is deliberately not serialized. Source is provenance, not definition: it says who
 * authored a Program *in this app's storage*, and an imported Program is `IMPORTED` whatever the file
 * came from — so a serialized source would be a field the importer must always overwrite, and a field
 * that is always overwritten exists only to be distrusted. It would also let an exported Standard
 * Program claim to be standard inside the file, inviting exactly the protected copy the format forbids.
 * The importer establishes `ProgramSource.IMPORTED` directly instead.
 *
 * Enum values are carried by name (`MANUAL`, `FIXED_DAYS`, `PUSH`, …), the same representation the app's
 * own converters persist, so a token cannot be right for the database and wrong for a file.
 * [java.time.DayOfWeek] is carried the same way and **in ISO order** (Monday first), because a document
 * is compared byte-for-byte and a set has no order of its own.
 *
 * Positions are absent on purpose: the days *are* their order, so a position would be a second fact that
 * can disagree with the array, and a document could say "day 3" of two days. The list is the order.
 *
 * [name] is always present; [description] may be empty.
 */
data class ProgramTransferDocument(
    val formatVersion: Int,
    val name: String,
    val description: String,
    val revision: RevisionTransfer
) {

    /**
     * Every exercise id this document references, each once, in document order.
     *
     * Derived rather than stored, so it cannot disagree with the days it was read from. It covers **both**
     * sources — the plan's own elements and the revision's [RevisionTransfer.preferredExercises] —
     * because a preferred id the app has never heard of would otherwise slip through the gap between two
     * validated lists, and folding them in means the library is asked **once**. Deduplicated across both,
     * since being in a plan says nothing about whether the id resolves.
     */
    val referencedExerciseIds: List<String>
        get() = (revision.days.flatMap { day -> day.exercises.map { element -> element.exerciseId } } +
            revision.preferredExercises)
            .distinct()
}

/**
 * One revision's transferable definition: its configuration and its plan.
 *
 * [preferredExercises] is the user's **exercise preference**, most preferred first, or empty when they
 * named nothing. It travels because it is a stated input to generation, of the same kind as `focus`:
 * carrying it means the imported Program reaches for the same exercises in the same order, which is the
 * one thing an importer would otherwise silently drop. Like the focus configuration it carries no
 * identity — the import mints a new `programId`, so it cannot claim to be the exporter's.
 */
data class RevisionTransfer(
    val mode: ProgramMode,
    val duration: DurationTransfer,
    val schedule: ScheduleTransfer,
    val focus: FocusTransfer,
    val days: List<DayTransfer>,
    val preferredExercises: List<String> = emptyList()
)

/**
 * How long the revision runs.
 *
 * The two forms are separate values rather than an optional day count, so "indefinite" cannot be read as
 * a program of `0` days and a program of `n` days cannot lose its end.
 */
sealed interface DurationTransfer {

    /** A program that runs for a known number of calendar days. */
    data class FixedDays(val days: Int) : DurationTransfer

    /** A program with no end date; its horizon is the Scheduler's. */
    data object Indefinite : DurationTransfer
}

/**
 * When the revision's slots fall.
 *
 * [FixedWeekdays.weekdays] is held in **ISO order** (Monday first), each weekday at most once, because
 * the domain's own value is a `Set` — which has no order — and a document compared byte-for-byte cannot
 * depend on one.
 */
sealed interface ScheduleTransfer {

    /** The user's fixed weekdays, in ISO order, each once. */
    data class FixedWeekdays(val weekdays: List<DayOfWeek>) : ScheduleTransfer

    /** A deterministic number of sessions per week, without fixed weekdays. */
    data class FlexiblePerWeek(val sessionsPerWeek: Int) : ScheduleTransfer
}

/**
 * The revision's Goals & Focus configuration — the three forms the domain names, each stating exactly
 * what the user stated.
 *
 * A single value with a `goal` field was rejected for the same reason the domain rejects it: a goal that
 * can disagree with the shares beside it is a second source of truth for one fact, and the domain's
 * `FocusPlan` derives the goal from the form. The forms here are therefore the forms there.
 */
sealed interface FocusTransfer {

    /** Every focus is eligible; no share is stated. */
    data object Balanced : FocusTransfer

    /** The named focuses, in the vocabulary's canonical order, each once; no share is stated. */
    data class Focused(val focuses: List<Focus>) : FocusTransfer

    /** One share per focus, in the vocabulary's canonical order, summing to 100%. */
    data class Custom(val allocations: List<FocusShare>) : FocusTransfer
}

/** One focus's share of a [FocusTransfer.Custom] configuration, in whole percent. */
data class FocusShare(val focus: Focus, val percent: Int)

/**
 * One day of the revision's plan.
 *
 * [name] is `null` for an unnamed day and is **omitted** from the document rather than written as
 * `null`: the format admits one representation per value, and "no label" is one value. A `REST` day
 * carries no exercises.
 */
data class DayTransfer(
    val type: ProgramDayType,
    val name: String?,
    val exercises: List<ExerciseTransfer>
)

/**
 * One occurrence of one exercise in a day's plan — its transferable content only.
 *
 * Repetition is an ordinary plan fact, not a conflict: the same [exerciseId] may appear several times in
 * one day, and the occurrences are distinguished by their **position and content**. Nothing here
 * identifies an occurrence, so nothing here can be re-used as an identity on import.
 *
 * @property isPinned whether the occurrence is exempt from automatic change.
 */
data class ExerciseTransfer(
    val exerciseId: String,
    val prescription: PrescriptionTransfer,
    val origin: ProgramExerciseOrigin,
    val isPinned: Boolean
)

/**
 * What one occurrence prescribes, per set.
 *
 * The dimension is carried as a token and the targets as the whole per-set list, because `12 / 10 / 8 /
 * 6` is not the prescription `10 / 10 / 10 / 10` and a "uniform with overrides" spelling would be a
 * lossy representation of the same plan. Each target is positive, in set order.
 */
data class PrescriptionTransfer(
    val dimension: PrescriptionDimension,
    val perSetTargets: List<Int>
)

fun transferOf(duration: ProgramDuration): DurationTransfer = when (duration) {
    is ProgramDuration.FixedDays -> DurationTransfer.FixedDays(duration.days)
    ProgramDuration.Indefinite -> DurationTransfer.Indefinite
}

/** The transferable definition of [schedule], with a fixed weekday set written in ISO order. */
fun transferOf(schedule: ProgramSchedule): ScheduleTransfer = when (schedule) {
    is ProgramSchedule.FixedWeekdays ->
        ScheduleTransfer.FixedWeekdays(schedule.weekdays.sortedBy { day -> day.value })
    is ProgramSchedule.FlexiblePerWeek ->
        ScheduleTransfer.FlexiblePerWeek(schedule.sessionsPerWeek)
}
