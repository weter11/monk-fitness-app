package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.model.ProgramRevisionEntity
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule

/**
 * The plan rows ⇄ the revision aggregate: `program_revision` + `program_day` + `program_exercise`.
 *
 * The revision is three tables and one immutable value, so the two directions are not symmetric and
 * the asymmetry is deliberate:
 *
 *  * **loading** assembles rows a repository has already read, in the revision's own order (day
 *    position, then element position). It sorts by the stored positions instead of trusting the order
 *    rows arrived in, and it refuses to drop one: an element whose day is not part of the revision is
 *    a broken plan, not a row to skip.
 *  * **saving** derives the rows from the value: a day's position is the day's own, and an element's
 *    position is its place in the day's ordered list, because the domain's plan element is an
 *    occurrence *in an order* and carries no position of its own (§9). Saving a loaded revision
 *    therefore reproduces its positions, and a stored gap in element positions is not a domain
 *    violation — the order is what the domain preserves, and nothing is renumbered on a load.
 *
 * Two structural facts are stored as a discriminator plus its payload (§20) and are mapped
 * explicitly: a revision runs for a fixed number of days or indefinitely, and its slots fall on fixed
 * weekdays or at a deterministic weekly frequency. Neither form is collapsed into the other — an
 * indefinite program has no length, and reporting a fake `N / 30` for it is what §21 forbids.
 *
 * A stored prescription in one of the three dimensions §10 names but does not implement
 * (`SET_BASED`, `DIFFICULTY_BASED`, `REST_BASED`) **cannot be loaded**: those dimensions are
 * representable in the schema and have no domain subtype yet, so there is no value to produce.
 * Inventing one, or reading it as a repetition prescription, would change what the plan says. It
 * fails loudly and names the row instead.
 *
 * ### Both schedule forms cross the boundary losslessly
 *
 * The deterministic sessions-per-week frequency of `ProgramSchedule.FlexiblePerWeek` is revision content
 * (§6 lists frequency and preferred weekdays among the changes that create a revision), so it is stored
 * beside its discriminator: the version-8 → version-9 correction added
 * `program_revision.scheduleSessionsPerWeek`, and both directions map it —
 *
 *  * fixed weekdays store their day set and **no** frequency (`null`);
 *  * a flexible frequency stores the number it runs at, and a stored flexible revision with no frequency
 *    is invalid persisted data that fails loudly here, exactly as a weekday schedule with no weekdays
 *    does. Nothing is dropped, derived or defaulted in either direction.
 *
 * The gap this mapper used to refuse in both directions is closed by
 * `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md`; `PlanMapperTest` pins the round trip value by value.
 */

/** The three stored tables of one revision, as a repository writes them. */
internal data class RevisionRows(
    val revision: ProgramRevisionEntity,
    val days: List<ProgramDayEntity>,
    val exercises: List<ProgramExerciseEntity>
)

/**
 * The domain revision of one stored revision row, its day rows and its element rows.
 *
 * @throws IllegalArgumentException when an element row names a day the revision does not contain —
 *   dropping it would silently shorten a plan — when the schedule is a flexible frequency (see the
 *   file's gap note), or when the assembled value violates a domain invariant (a revision with no
 *   days, days not numbered `1..n`, a repeated day identity, a prescription in an unimplemented
 *   dimension).
 */
internal fun revisionDomain(
    revision: ProgramRevisionEntity,
    dayRows: List<ProgramDayEntity>,
    exerciseRows: List<ProgramExerciseEntity>
): ProgramRevision {
    val orderedDays = dayRows.sortedBy { it.position }
    val dayIds = orderedDays.map { it.programDayId }.toSet()
    val strayElement = exerciseRows.firstOrNull { it.programDayId !in dayIds }
    require(strayElement == null) {
        "every plan element of revision '${revision.revisionId}' must belong to one of its days; " +
            "'${strayElement?.programExerciseId}' names day '${strayElement?.programDayId}'"
    }

    val elementsByDay = exerciseRows.groupBy { it.programDayId }
    return ProgramRevision(
        revisionId = RevisionId(revision.revisionId),
        programId = ProgramId(revision.programId),
        revisionNumber = revision.revisionNumber,
        mode = storedToken(revision.mode, ProgramMode.entries, "program_revision.mode"),
        duration = durationOf(revision),
        schedule = scheduleOf(revision),
        days = orderedDays.map { day ->
            day.toDomain(
                elementsByDay[day.programDayId].orEmpty()
                    .sortedBy { it.position }
                    .map { it.toDomain() }
            )
        },
        createdAt = storedInstant("program_revision.createdAt", revision.createdAt),
        focus = focusOf(revision)
    )
}

/** The stored rows of one revision, in the order a repository inserts them. */
internal fun ProgramRevision.toRows(): RevisionRows = RevisionRows(
    revision = toEntity(),
    days = days.map { it.toEntity(revisionId) },
    exercises = days.flatMap { day ->
        day.exercises.mapIndexed { index, element -> element.toEntity(day.programDayId, index + 1) }
    }
)

/** One revision's scalar row. */
internal fun ProgramRevision.toEntity(): ProgramRevisionEntity = ProgramRevisionEntity(
    revisionId = revisionId.value,
    programId = programId.value,
    revisionNumber = revisionNumber,
    mode = mode.name,
    durationType = durationTypeOf(duration),
    durationDays = (duration as? ProgramDuration.FixedDays)?.days,
    scheduleType = scheduleTypeOf(schedule),
    scheduleWeekdays = (schedule as? ProgramSchedule.FixedWeekdays)?.weekdays,
    scheduleSessionsPerWeek = (schedule as? ProgramSchedule.FlexiblePerWeek)?.sessionsPerWeek,
    createdAt = storedMilliseconds(createdAt),
    focusGoal = focus.goal.name,
    focusTargets = focus.storedFocusTargets()
)

/** The duration one revision row stores. */
private fun durationOf(revision: ProgramRevisionEntity): ProgramDuration =
    when (revision.durationType) {
        ProgramRevisionEntity.FIXED_DAYS -> ProgramDuration.FixedDays(
            requireNotNull(revision.durationDays) {
                "a ${ProgramRevisionEntity.FIXED_DAYS} revision names how many days it runs: " +
                    "'${revision.revisionId}' stores no durationDays"
            }
        )
        ProgramRevisionEntity.INDEFINITE -> ProgramDuration.Indefinite
        else -> throw IllegalArgumentException(
            "a stored program_revision.durationType must be one of " +
                "${listOf(ProgramRevisionEntity.FIXED_DAYS, ProgramRevisionEntity.INDEFINITE)}, " +
                "was '${revision.durationType}' in '${revision.revisionId}'"
        )
    }

/** The schedule one revision row stores. */
private fun scheduleOf(revision: ProgramRevisionEntity): ProgramSchedule =
    when (revision.scheduleType) {
        ProgramRevisionEntity.FIXED_WEEKDAYS -> ProgramSchedule.FixedWeekdays(
            requireNotNull(revision.scheduleWeekdays) {
                "a ${ProgramRevisionEntity.FIXED_WEEKDAYS} schedule names its weekdays: " +
                    "'${revision.revisionId}' stores none"
            }
        )
        ProgramRevisionEntity.FLEXIBLE_PER_WEEK -> ProgramSchedule.FlexiblePerWeek(
            requireNotNull(revision.scheduleSessionsPerWeek) {
                "a ${ProgramRevisionEntity.FLEXIBLE_PER_WEEK} schedule stores the sessions-per-week " +
                    "frequency it runs at: revision '${revision.revisionId}' stores none (§20)"
            }
        )
        else -> throw IllegalArgumentException(
            "a stored program_revision.scheduleType must be one of " +
                "${listOf(ProgramRevisionEntity.FIXED_WEEKDAYS, ProgramRevisionEntity.FLEXIBLE_PER_WEEK)}, " +
                "was '${revision.scheduleType}' in '${revision.revisionId}'"
        )
    }

/** The stored discriminator of one duration. */
private fun durationTypeOf(duration: ProgramDuration): String =
    when (duration) {
        is ProgramDuration.FixedDays -> ProgramRevisionEntity.FIXED_DAYS
        ProgramDuration.Indefinite -> ProgramRevisionEntity.INDEFINITE
    }

/** The stored discriminator of one schedule. */
private fun scheduleTypeOf(schedule: ProgramSchedule): String = when (schedule) {
    is ProgramSchedule.FixedWeekdays -> ProgramRevisionEntity.FIXED_WEEKDAYS
    is ProgramSchedule.FlexiblePerWeek -> ProgramRevisionEntity.FLEXIBLE_PER_WEEK
}

/**
 * The **Goals & Focus** configuration one revision row stores (§8) — the third discriminator-plus-
 * payload pair on `program_revision`, after the duration and the schedule.
 *
 * ```text
 * focusGoal      ''      ''       BALANCED   FOCUSED          CUSTOM
 * focusTargets   null    null     null        PUSH,PULL        PUSH:40,LEGS:60
 * ```
 *
 * The `null` goal is read as [FocusPlan.Balanced], and that reading is faithful rather than a
 * default: a revision written before Goals & Focus existed was built for the whole vocabulary with no
 * share stated, which is exactly what `BALANCED` means. Nothing is invented for it, and the column
 * carries no database default either — the mapper is the only place the decision is made, and it is
 * made once.
 *
 * Reading is **strict in both directions**, like the two pairs above it:
 *
 *  * a stored goal token that is not a [Goal] member is invalid persisted data, not a configuration;
 *  * a `FOCUSED` row whose targets are missing, blank or not focus names is invalid;
 *  * a `CUSTOM` row whose share tokens are not `NAME:percent`, or whose percentages do not sum to
 *    [FocusPlan.FULL_ALLOCATION], is invalid — §8's rule is the domain's own, so the domain's own
 *    constructor states it, and a stored 90% share fails here rather than silently loading as
 *    something the user did not ask for.
 *
 * The order of the stored targets is the domain's canonical order, so a loaded configuration is
 * value-equal to the one that was saved and a re-saved revision is a byte-identical row.
 */
private fun focusOf(revision: ProgramRevisionEntity): FocusPlan {
    val goalToken = revision.focusGoal ?: return FocusPlan.Balanced
    return when (storedToken(goalToken, Goal.entries, "program_revision.focusGoal")) {
        Goal.BALANCED -> FocusPlan.Balanced
        Goal.FOCUSED -> FocusPlan.focused(focusesOf(revision))
        Goal.CUSTOM -> FocusPlan.custom(sharesOf(revision))
    }
}

/** The focuses a `FOCUSED` revision names, read as a configuration in canonical order. */
private fun focusesOf(revision: ProgramRevisionEntity): List<Focus> =
    storedFocusTargets(revision).map { token ->
        storedToken(token, Focus.entries, "program_revision.focusTargets")
    }

/** The shares a `CUSTOM` revision states, read as a configuration that must sum to 100% (§8). */
private fun sharesOf(revision: ProgramRevisionEntity): List<FocusAllocation> =
    storedFocusTargets(revision).map { token ->
        val parts = token.split(SHARE_SEPARATOR)
        require(parts.size == 2) {
            "a stored custom focus share is 'NAME$SHARE_SEPARATOR<percent>', was '$token' in " +
                "revision '${revision.revisionId}' (§8)"
        }
        val percent = parts[1].trim().toIntOrNull() ?: throw IllegalArgumentException(
            "a stored custom focus share states a whole percentage, was '${parts[1]}' in " +
                "revision '${revision.revisionId}' (§8)"
        )
        FocusAllocation(
            focus = storedToken(parts[0].trim(), Focus.entries, "program_revision.focusTargets"),
            percent = percent
        )
    }

/**
 * The tokens a revision's configuration states, or a refusal naming the row.
 *
 * The stored form is a comma-separated list of opaque tokens — `PUSH` for a `FOCUSED` configuration
 * and `PUSH:40` for a `CUSTOM` one — which is deterministic because the domain holds a configuration
 * in canonical order and refuses one that is not. The format is deliberately owned here rather than
 * by a `ProgramTypeConverters` method: Room already declares a `List<String> ⇄ String` conversion for
 * the session snapshot's applied adjustments, and this column's tokens carry a discriminator-dependent
 * meaning that only the mapper knows.
 */
private fun storedFocusTargets(revision: ProgramRevisionEntity): List<String> {
    val stored = requireNotNull(revision.focusTargets) {
        "revision '${revision.revisionId}' states '${revision.focusGoal}' but stores no focus " +
            "targets: a configuration that names focuses states which ones (§8)"
    }
    val tokens = stored.split(TARGET_SEPARATOR).map { it.trim() }
    require(tokens.isNotEmpty() && tokens.none { it.isEmpty() }) {
        "revision '${revision.revisionId}' stores focus targets that are not a list of tokens: " +
            "'$stored' (§8)"
    }
    return tokens
}

/**
 * The stored targets of a configuration, or `null` for [FocusPlan.Balanced].
 *
 * A focus name for a `FOCUSED` configuration and `NAME:percent` for a `CUSTOM` one — two shapes in
 * one converted column, told apart by the discriminator on the same row, exactly as `perSetTargets`
 * holds repetitions for one dimension and seconds for another (§10).
 */
private fun FocusPlan.storedFocusTargets(): String? = when (this) {
    FocusPlan.Balanced -> null
    is FocusPlan.Focused -> focuses.joinToString(TARGET_SEPARATOR) { it.name }
    is FocusPlan.Custom ->
        allocations.joinToString(TARGET_SEPARATOR) { "${it.focus.name}$SHARE_SEPARATOR${it.percent}" }
}

/** The separator between two stored focus targets. */
private const val TARGET_SEPARATOR: String = ","

/** The separator between a focus's name and its share in a stored `CUSTOM` target. */
private const val SHARE_SEPARATOR: String = ":"

/** One stored plan day with its ordered elements. */
internal fun ProgramDayEntity.toDomain(elements: List<ProgramExercise>): ProgramDay = ProgramDay(
    programDayId = ProgramDayId(programDayId),
    position = position,
    type = storedToken(type, ProgramDayType.entries, "program_day.type"),
    name = name,
    exercises = elements
)

/** One plan day's row, owned by [revisionId]. */
internal fun ProgramDay.toEntity(revisionId: RevisionId): ProgramDayEntity = ProgramDayEntity(
    programDayId = programDayId.value,
    revisionId = revisionId.value,
    position = position,
    type = type.name,
    name = name
)

/** One stored plan element. */
internal fun ProgramExerciseEntity.toDomain(): ProgramExercise = ProgramExercise(
    programExerciseId = ProgramExerciseId(programExerciseId),
    exerciseId = exerciseId,
    prescription = prescriptionOf(
        prescriptionDimension,
        perSetTargets,
        "program_exercise '$programExerciseId'"
    ),
    origin = storedToken(origin, ProgramExerciseOrigin.entries, "program_exercise.origin"),
    isPinned = isPinned
)

/** One plan element's row, owned by [programDayId] and holding place [position] in that day. */
internal fun ProgramExercise.toEntity(programDayId: ProgramDayId, position: Int): ProgramExerciseEntity =
    ProgramExerciseEntity(
        programExerciseId = programExerciseId.value,
        programDayId = programDayId.value,
        position = position,
        exerciseId = exerciseId,
        prescriptionDimension = prescription.dimension.name,
        perSetTargets = prescription.perSetTargets,
        origin = origin.name,
        isPinned = isPinned
    )

/**
 * The prescription one stored dimension token and per-set target list describe.
 *
 * @throws IllegalArgumentException when the token is not one of the five §10 dimensions, when the
 *   dimension is one of the three with no domain subtype yet, or when the domain constructor refuses
 *   the targets (an empty list, a target that is not positive) — a stored prescription the domain
 *   cannot state is invalid persisted data.
 */
internal fun prescriptionOf(
    dimensionToken: String,
    perSetTargets: List<Int>,
    subject: String
): Prescription = when (
    val dimension = storedToken(dimensionToken, PrescriptionDimension.entries, "$subject prescriptionDimension")
) {
    PrescriptionDimension.REP_BASED -> RepPrescription(perSetTargets)
    PrescriptionDimension.TIME_BASED -> TimePrescription(perSetTargets)
    PrescriptionDimension.SET_BASED,
    PrescriptionDimension.DIFFICULTY_BASED,
    PrescriptionDimension.REST_BASED -> throw IllegalArgumentException(
        "a stored $subject is prescribed in $dimension, which §10 names and the domain does not " +
            "implement yet: the row cannot be loaded without inventing semantics for that dimension"
    )
}
