package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate

/**
 * One attempt as history reads it.
 *
 * History is a list of attempts, not of planned days: an opportunity never attempted has no entry,
 * while one attempted twice has two. Each entry says what the session's own rows say:
 *
 *  * [performedSets] and [exposedExercises] are reported for every status, because exposure is a fact
 *    about work that happened and not a reward for a status — a cancelled attempt that confirmed four
 *    sets keeps those four sets, and reading it as "nothing happened" would be that erasure;
 *  * [status] is kept beside them so a reader can tell a completed workout from a cancelled one without
 *    the exposure having been filtered on the way in; `CANCELLED` is never converted to `COMPLETED`;
 *  * [skippedExercises] counts occurrences the user explicitly skipped — they observed nothing, which is
 *    why they are counted separately rather than as zero-valued work.
 *
 * `plannedFor` is read from the attempt's own snapshot, never a live re-reading of the plan, and
 * `finishedAt`/`duration` are `null` only while the attempt is running.
 */
data class HistoryItem(
    val sessionId: SessionId,
    val programId: ProgramId,
    val slotId: SlotId,
    val plannedFor: LocalDate,
    val status: SessionStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val duration: SessionDuration?,
    val performedSets: Int,
    val exposedExercises: Int,
    val skippedExercises: Int
) {

    init {
        require(performedSets >= 0) { "a count of confirmed sets is not negative, was $performedSets" }
        require(exposedExercises >= 0) { "a count of exercised occurrence is not negative, was $exposedExercises" }
        require(skippedExercises >= 0) { "a count of skipped occurrence is not negative, was $skippedExercises" }
        require((status == SessionStatus.IN_PROGRESS) == (finishedAt == null)) {
            "an attempt still running has not finished and a finished attempt is not running: " +
                "status=$status finishedAt=$finishedAt"
        }
        require(duration == null || duration.sessionId == sessionId) {
            "the duration of a history entry belongs to that entry's own attempt: " +
                "entry=$sessionId duration=${duration?.sessionId}"
        }
        require(status != SessionStatus.IN_PROGRESS || duration == null) {
            "an attempt that is still running has no duration yet"
        }
    }

    val isCompleted: Boolean
        get() = status.isCompleted

    /** Whether the attempt recorded any work at all, whatever its status. */
    val hasExposure: Boolean
        get() = performedSets > 0
}

/**
 * The only place a [HistoryItem] is built, from the session's own facts and nothing else — a second
 * construction site would be a second definition of exposure.
 */
fun historyItemOf(session: WorkoutSession): HistoryItem = HistoryItem(
    sessionId = session.sessionId,
    programId = session.programId,
    slotId = session.slotId,
    plannedFor = session.snapshot.workout.plannedFor,
    status = session.status,
    startedAt = session.startedAt,
    finishedAt = session.finishedAt,
    duration = durationOf(session),
    performedSets = session.exercises.sumOf { it.results.size },
    exposedExercises = session.exercises.count { it.results.isNotEmpty() },
    skippedExercises = session.exercises.count { it.skipped }
)
