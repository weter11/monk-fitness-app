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
 * The **transfer model**: the Program's transferable definition, as values that are not the domain's
 * and not the database's (§2, §25).
 *
 * ### Why a third model
 *
 * §25 separates `JSON Transfer DTO ⇄ Import/Export Mapper ⇄ Domain`, and both of the alternatives to
 * this file are forbidden by §2 in one sentence each:
 *
 * ```text
 * serialize the Room entity        the format would depend on column names, and §23 says the schema is
 *                                  not the definition: an entity carries `programId`, `revisionId`,
 *                                  `createdAt` and the lifecycle, none of which may be shared
 * serialize the domain value       the format would then change whenever the domain does — a new field
 *                                  on ProgramRevision would silently change every exported file — and
 *                                  the domain's own type would carry fields the format must never
 *                                  carry (a revision's identity, a day's identity, a Program's source)
 * ```
 *
 * So this is the *allowlist*, written out: exactly the facts §2 names as transferable — the Program's
 * definition and configuration, its revision's structure, its days, its exercises, its prescriptions,
 * its focus/goals, its mode, its schedule, its duration, its pinning and its authorship — and nothing
 * else. There is no identity field anywhere in this hierarchy, no timestamp, no lifecycle state, no
 * `ProgramSource` and no runtime fact, and that absence is the design rather than an omission: §3's
 * *"import must mint a completely new graph"* is unrepresentable as a violation when the format has no
 * identity to reuse, and §15/§16's prohibitions are unrepresentable when the format has no
 * vocabulary for adaptive state, sessions, sets or progress.
 *
 * ### `ProgramSource` is deliberately **not** in the format
 *
 * §2 asks for this decision to be made explicitly. It is not serialized, for three reasons that point
 * the same way:
 *
 *  1. **source is provenance, not definition.** `STANDARD` / `USER` / `IMPORTED` says who authored a
 *     Program *in this app's storage* — it is what the copy-before-edit rule is derived from (§4) and
 *     what a UI explains with. It is not part of "what this Program plans", which is what a transfer
 *     carries;
 *  2. **the importing side must not believe it.** §5's invariant is that *an imported Program is never
 *     treated as the original Program or as the built-in Standard Program*, and §9/§10 require the
 *     imported copy to be `IMPORTED` whatever the file came from. A serialized source would be a field
 *     the importer would have to *overwrite*, and a field that is always overwritten is a field that
 *     exists only to be distrusted;
 *  3. **an exported Standard Program would otherwise claim to be standard.** §10 exports the Standard
 *     Program through the same format as any other, and a `STANDARD` token inside the file would invite
 *     exactly the reading §10 forbids — a copy that stays protected and therefore cannot be edited or
 *     deleted.
 *
 * The importer therefore *establishes* the source directly (`ProgramSource.IMPORTED`, §9), which is the
 * alternative §9 names: *"unless inspection proves the source field is intentionally not serialized and
 * the constructor can establish this directly"*.
 *
 * ### The tokens are the domain's own names
 *
 * Enum values are carried by name (`MANUAL`, `FIXED_DAYS`, `PUSH`, …), which is the same representation
 * the app's own converters persist — one vocabulary for the mode, the day type, the origin, the
 * dimension, the goal and the focus, so a token cannot be right for the database and wrong for a file.
 * [java.time.DayOfWeek] is carried the same way, and **in ISO order** (Monday first), because a document
 * is compared byte-for-byte and a set has no order of its own (§11's determinism).
 *
 * ### The shapes
 *
 * ```text
 * ProgramTransferDocument      formatVersion, name, description, revision
 *   RevisionTransfer           mode, duration, schedule, focus, days
 *     DurationTransfer         FIXED_DAYS(days) | INDEFINITE
 *     ScheduleTransfer         FIXED_WEEKDAYS(weekdays) | FLEXIBLE_PER_WEEK(sessionsPerWeek)
 *     FocusTransfer            BALANCED | FOCUSED(focuses) | CUSTOM(allocations)
 *       FocusShare             focus, percent
 *     DayTransfer              type, name?, exercises
 *       ExerciseTransfer       exerciseId, prescription, origin, pinned
 *         PrescriptionTransfer dimension, perSetTargets
 * ```
 *
 * Positions are absent on purpose, and this is the one place the format's shape carries a rule: the
 * days *are* their order (§6 numbers a revision's days `1..n` in the order they are held), so a
 * position would be a second fact that can disagree with the array, and a document could then say
 * "day 3" of two days. The list is the order.
 *
 * @property formatVersion the version of the format the document is written in (§5).
 * @property name the Program's user-facing name. A Program always has one.
 * @property description the Program's user-facing description; may be empty.
 * @property revision the transferable definition of the Program's current configuration (§17).
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
     * This is the list §5's *exerciseId validation* step asks the Exercise Library about — the
     * document's own references, not a plan's rows — and it is derived rather than stored so that it
     * cannot disagree with the days it was read from.
     */
    val referencedExerciseIds: List<String>
        get() = revision.days
            .flatMap { day -> day.exercises.map { element -> element.exerciseId } }
            .distinct()
}

/** One revision's transferable definition: its configuration and its plan (§17). */
data class RevisionTransfer(
    val mode: ProgramMode,
    val duration: DurationTransfer,
    val schedule: ScheduleTransfer,
    val focus: FocusTransfer,
    val days: List<DayTransfer>
)

/**
 * How long the revision runs (§20).
 *
 * The two forms are separate values rather than an optional day count, so "indefinite" cannot be read
 * as a program of `0` days and a program of `n` days cannot lose its end.
 */
sealed interface DurationTransfer {

    /** A program that runs for a known number of calendar days. */
    data class FixedDays(val days: Int) : DurationTransfer

    /** A program with no end date; its horizon is the Scheduler's (§20). */
    data object Indefinite : DurationTransfer
}

/**
 * When the revision's slots fall (§20).
 *
 * [FixedWeekdays.weekdays] is held in **ISO order** (Monday first) with each weekday at most once,
 * because the domain's own value is a `Set` — which has no order — and a document that is compared
 * byte-for-byte cannot depend on one.
 */
sealed interface ScheduleTransfer {

    /** The user's fixed weekdays, in ISO order, each once. */
    data class FixedWeekdays(val weekdays: List<DayOfWeek>) : ScheduleTransfer

    /** A deterministic number of sessions per week, without fixed weekdays. */
    data class FlexiblePerWeek(val sessionsPerWeek: Int) : ScheduleTransfer
}

/**
 * The revision's Goals & Focus configuration (§8) — the three forms §8 names, each stating exactly
 * what the user stated.
 *
 * A single value with a `goal` field was rejected for the same reason the domain rejects it: a goal
 * that can disagree with the shares beside it is a second source of truth for one fact, and the
 * domain's `FocusPlan` derives the goal from the form. The forms here are therefore the forms there.
 */
sealed interface FocusTransfer {

    /** Every focus is eligible; no share is stated. */
    data object Balanced : FocusTransfer

    /** The named focuses, in the vocabulary's canonical order, each once; no share is stated. */
    data class Focused(val focuses: List<Focus>) : FocusTransfer

    /** One share per focus, in the vocabulary's canonical order, summing to 100%. */
    data class Custom(val allocations: List<FocusShare>) : FocusTransfer
}

/** One focus's share of a [FocusTransfer.Custom] configuration, in whole percent (§8). */
data class FocusShare(val focus: Focus, val percent: Int)

/**
 * One day of the revision's plan.
 *
 * [name] is `null` for an unnamed day and is **omitted** from the document rather than written as
 * `null`: §11's determinism admits one representation per value, and "no label" is one value.
 *
 * @property type what the day is for. A `REST` day carries no exercises (§20).
 * @property name the day's optional label, or `null`.
 * @property exercises the day's ordered plan elements.
 */
data class DayTransfer(
    val type: ProgramDayType,
    val name: String?,
    val exercises: List<ExerciseTransfer>
)

/**
 * One occurrence of one exercise in a day's plan — its transferable content only.
 *
 * Repetition is an ordinary plan fact, not a conflict: the same [exerciseId] may appear several times
 * in one day, and the occurrences are distinguished by their **position and content**, which is what
 * §21's *"repeated exercise occurrences remain distinct by order/content even though identities are
 * excluded"* asks for. Nothing here identifies an occurrence, so nothing here can be re-used as an
 * identity on import (§3).
 *
 * @property exerciseId the Exercise Library key, opaque to the format and validated on import (§5).
 * @property prescription what the occurrence asks for, per set, in its dimension (§10).
 * @property origin who authored it — the generator or the user (§7).
 * @property pinned whether it is exempt from automatic change (§7).
 */
data class ExerciseTransfer(
    val exerciseId: String,
    val prescription: PrescriptionTransfer,
    val origin: ProgramExerciseOrigin,
    val isPinned: Boolean
)

/**
 * What one occurrence prescribes, per set (§10).
 *
 * The dimension is carried as a token and the targets as the whole per-set list, because `12 / 10 / 8 /
 * 6` is not the prescription `10 / 10 / 10 / 10` (§10) and a "uniform with overrides" spelling would be
 * a lossy representation of the same plan.
 *
 * @property dimension the dimension the targets are stated in (§10).
 * @property perSetTargets one target per set, in set order, each positive.
 */
data class PrescriptionTransfer(
    val dimension: PrescriptionDimension,
    val perSetTargets: List<Int>
)

// ------------------------------------------------------------------------------------------------
// The two mappings from a domain value to this model, kept here so the allowlist has one home.
// ------------------------------------------------------------------------------------------------

/** The transferable definition of [duration] (§20). */
fun transferOf(duration: ProgramDuration): DurationTransfer = when (duration) {
    is ProgramDuration.FixedDays -> DurationTransfer.FixedDays(duration.days)
    ProgramDuration.Indefinite -> DurationTransfer.Indefinite
}

/** The transferable definition of [schedule] (§20), with a fixed weekday set written in ISO order. */
fun transferOf(schedule: ProgramSchedule): ScheduleTransfer = when (schedule) {
    is ProgramSchedule.FixedWeekdays ->
        ScheduleTransfer.FixedWeekdays(schedule.weekdays.sortedBy { day -> day.value })
    is ProgramSchedule.FlexiblePerWeek ->
        ScheduleTransfer.FlexiblePerWeek(schedule.sessionsPerWeek)
}
