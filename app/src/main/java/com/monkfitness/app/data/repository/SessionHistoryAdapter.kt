package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionObservationMapper
import com.monkfitness.app.domain.adaptive.SessionSetLog
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.domain.usecase.resolveCycleAndDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Reads persisted workout history and yields the pure [SessionObservation] values the adaptive
 * pipeline consumes — the only component that knows what a stored session *means*.
 *
 * It is an adapter, not a source of truth: it translates, it never decides. Signal calculation,
 * policy, progression and adaptive state belong to later tasks and are deliberately absent here, as
 * are UI models, generator integration and any persistence of adaptive state.
 *
 * ## What a session is built from
 *
 * One observation is one **planned workout opportunity**: a program day that prescribes a workout.
 * A rest day prescribes nothing and yields no observation — it is not training history.
 *
 *  * **session identity** — the calendar position `(cycleNumber, programDay)`. That is the only
 *    stable cross-session identifier persistence exposes: a [com.monkfitness.app.data.model.SetLog]
 *    row carries a calendar date and an exercise id, not a session id, and the day-level
 *    [UserProgress] row is keyed by cycle and day.
 *  * **the plan** — the exercises the presented workout prescribed (see [plannedExercises]).
 *  * **completion** — `UserProgress.isCompleted`, the day-level source.
 *  * **observed work** — the [SessionSetLog] rows recorded on that session's date, one row per
 *    confirmed set.
 *
 * ## Sessions are attributed by calendar date
 *
 * `UserProgress` is keyed by cycle/day and records a completion timestamp, while `set_log` is keyed
 * by calendar date — there is no shared session id. The adapter joins them through the program
 * calendar: a session date resolves to exactly one `(cycleNumber, programDay)` via
 * [resolveCycleAndDay] against [programStartDate], the same function the live app uses, so a date's
 * attribution is what the app itself believed at the time. Persistence cannot prove a set belongs
 * to a session any other way, and this is what makes an observation reconstructable from current
 * data alone.
 *
 * A planned opportunity enters the history when the app left a mark on it — a day-level progress
 * row, a confirmed set, or a [ProgramDayState] the calendar sync recorded as completed or missed. A
 * rest day or a future day that carries none of those is not a session.
 *
 * ## Limitations persistence cannot repair (documented, not fabricated around)
 *
 *  * **`startedAt` is the earliest confirmed-set timestamp.** The session has no explicit start
 *    event in persistence, so a session whose every set was rolled back has no start stamp and is
 *    `NOT_STARTED`. This is an approximation — it cannot be improved without new instrumentation.
 *  * **`finishedAt` is the day-level completion stamp, and only for a completed day.** An abandoned
 *    session leaves no end event in persistence: no `UserProgress` row is written, so there is no
 *    timestamp to report and the observation carries `null` rather than an invented abandonment
 *    time. The policy layer reads that `null` as "the session's end is not established".
 *  * **A set row for an exercise the planned workout does not contain is dropped.** The plan is the
 *    only proof an exercise was prescribed; counting such a row as planned work would invent a plan
 *    persistence cannot show.
 *  * **The plan is the generator's current output for a historical day.** The app persists no
 *    workout snapshot, so an old session's plan cannot be proven against today's library: if the
 *    exercise library or the selection rules change, the plan this adapter reconstructs changes
 *    with it. The generator is deterministic per day, so an unchanged library reproduces the
 *    identical plan — but history is only as stable as the plan source. Task 4+ decides whether a
 *    persisted plan snapshot is required; Task 3 reports the limitation and adds no migration.
 *  * **Multiple sessions on one calendar date collapse into one observation.** Attribution is by
 *    date, so two workouts logged on the same date are reported as one session. The app's own
 *    session model has one workout opportunity per day, so this matches the existing semantics
 *    rather than degrading them.
 *
 * ## Determinism
 *
 * Identical persisted input yields identical output, ordered by cycle then program day ascending.
 * The adapter never writes: it only reads, so it cannot mutate persistence state.
 *
 * @param programStartDate the day the current program started — the existing program/calendar
 *   record (`SettingsManager.programStartDateFlow`), which is how the live app resolves a calendar
 *   date to its cycle and day. Not a per-session value: it is the calendar the observations are
 *   interpreted against.
 */
class SessionHistoryAdapter(
    private val progressDao: ProgressDao,
    private val workoutGenerator: WorkoutGenerator,
    private val programStartDate: LocalDate
) {

    private data class SessionPosition(val cycleNumber: Int, val programDay: Int, val sessionDate: String?)

    /**
     * Every session observation the persisted history can establish, in cycle-then-day ascending
     * order — the oldest session first, which is the order signal calculation consumes.
     */
    suspend fun observations(): List<SessionObservation> = plannedPositions()
        .sortedWith(compareBy({ it.cycleNumber }, { it.programDay }))
        .mapNotNull { position -> observationAt(position) }

    /**
     * The observation for one calendar position, or `null` when persistence establishes no session
     * there (no day-level row, no confirmed set, and no calendar-sync state for it, or the day is a
     * rest day).
     */
    suspend fun observationFor(cycleNumber: Int, programDay: Int): SessionObservation? =
        observations().firstOrNull { it.cycleNumber == cycleNumber && it.programDay == programDay }

    private suspend fun observationAt(position: SessionPosition): SessionObservation? {
        val planned = plannedExercises(position.programDay)
        if (planned.isEmpty()) return null // a rest day prescribes nothing to observe

        val dayProgress = progressDao.getProgressByDay(position.cycleNumber, position.programDay)
        val setLogs = position.sessionDate?.let { date ->
            progressDao.getSetLogsForSessionDate(date).map { row ->
                SessionSetLog(
                    cycleNumber = position.cycleNumber,
                    programDay = position.programDay,
                    exerciseId = row.exerciseId,
                    sessionDate = row.sessionDate,
                    timestamp = row.timestamp,
                    repsCompleted = row.repsCompleted,
                    durationSeconds = row.durationSeconds
                )
            }
        } ?: emptyList()

        return SessionObservationMapper.toObservation(
            cycleNumber = position.cycleNumber,
            programDay = position.programDay,
            plannedExercises = planned,
            sessionDate = position.sessionDate ?: "",
            setLogs = setLogs,
            isCompleted = dayProgress?.isCompleted == true,
            completedAt = dayProgress?.completionDate
        )
    }

    /**
     * Every planned session opportunity the persisted history marks, each with the session date it
     * can be attributed to when one is provable.
     *
     * Three footprints, because a session leaves three different traces:
     *  * a day-level [UserProgress] row — the session ran and its completion state was recorded,
     *    including a completed day whose every set was later rolled back (a session the set rows
     *    alone would forget); its date is derived from its completion timestamp;
     *  * a `set_log` date with at least one confirmed set — the session did work. This is the only
     *    trace a partial or abandoned session leaves, since it never writes a progress row;
     *  * a [ProgramDayState] the calendar sync recorded for a workout day as completed or missed —
     *    a planned day that never happened. Its `isMissed` flag is how the existing calendar marks
     *    a past workout day the user did not complete, which is the `NOT_STARTED` observation
     *    adherence is measured against.
     *
     * A date or state that does not fall on a workout day is dropped here as well, so a rest day
     * with a stray row is never reported as a session.
     */
    private suspend fun plannedPositions(): List<SessionPosition> {
        val progressRows = progressDao.getDayProgressSnapshot()
        val cycles = (progressRows.map { it.cycleNumber } + progressDao.getProgramCycles()).distinct()

        val byPosition = LinkedHashMap<Pair<Int, Int>, SessionPosition>()

        // Confirmed work first, so a date-carrying position wins over a state-only one.
        progressDao.getSessionDates().forEach { rawDate ->
            val sessionDate = rawDate.toLocalDateOrNull() ?: return@forEach
            val (cycleNumber, programDay) = resolveCycleAndDay(programStartDate, sessionDate)
            byPosition.putIfAbsent(cycleNumber to programDay, SessionPosition(cycleNumber, programDay, rawDate))
        }
        progressRows.forEach { progress ->
            byPosition.putIfAbsent(
                progress.cycleNumber to progress.day,
                SessionPosition(progress.cycleNumber, progress.day, progress.sessionDate())
            )
        }
        cycles.flatMap { cycle -> progressDao.getProgramDayStatesSnapshot(cycle) }
            .filter { it.isWorkoutDay && (it.isCompleted || it.isMissed) }
            .forEach { state ->
                byPosition.putIfAbsent(
                    state.cycleNumber to state.programDay,
                    SessionPosition(state.cycleNumber, state.programDay, sessionDate = null)
                )
            }

        return byPosition.values.filter { position ->
            plannedExercises(position.programDay).isNotEmpty()
        }
    }

    /**
     * The exercises the presented workout prescribed for a historical program day, in workout order.
     *
     * The plan comes from the generator's **deterministic output for that day** — the same input
     * always reproduces the same plan — and not from anything regenerated against today's
     * configuration, so the observation describes the workout the user was actually shown as
     * faithfully as persistence allows. This deliberately does not apply the user's current
     * difficulty adjustments or equipment/family filters: those are live configuration that a past
     * session is not proven to have run with, and re-applying them would silently reinterpret old
     * history after a configuration change. The generator's per-day plan is the only plan source the
     * app has; persistence stores no workout snapshot (see the class limitations).
     */
    private fun plannedExercises(programDay: Int) =
        workoutGenerator.generateWorkout(programDay).exercises

    /** The calendar date a day-level row's completion timestamp falls on, if it has one. */
    private fun UserProgress.sessionDate(): String? =
        if (completionDate > 0L) {
            LocalDate.ofEpochDay(completionDate / MILLIS_PER_DAY).format(dateFormatter)
        } else {
            null
        }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(this, dateFormatter) }.getOrNull()

    private companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
