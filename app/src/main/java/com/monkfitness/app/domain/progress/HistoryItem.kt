package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate

/**
 * One attempt as history reads it (§19, §21's *recent history*).
 *
 * History is a list of **attempts**, not of planned days: an opportunity that was never attempted has no
 * entry (there is no session to describe, and inventing an empty one is the same defect as reading a
 * missed opportunity as a workout), while an opportunity attempted twice has two. What each entry says is
 * what the session's own rows say — its status, its actual timestamps, and its **exposure**:
 *
 *  * [performedSets] and [exposedExercises] are the recorded sets and the occurrences that hold at least
 *    one — §12's partial exposure. They are reported for **every** status, because exposure is a fact
 *    about work that happened and not a reward for a status: a cancelled attempt that confirmed four sets
 *    keeps those four sets (§12: *"a cancelled Session may still contain partial Exposure"*), and reading
 *    that attempt as "nothing happened" would be exactly the erasure the blueprint forbids;
 *  * [status] is kept beside them so a reader can tell a completed workout from a cancelled one without
 *    the exposure having been filtered on the way in. `CANCELLED` is not a quiet `COMPLETED` (§19) and
 *    nothing here converts one into the other;
 *  * [skippedExercises] counts the occurrences the user explicitly skipped — they observed nothing, which
 *    is why they are counted separately rather than as zero-valued work (§12).
 *
 * @property sessionId identity of the attempt.
 * @property programId the Program it belongs to.
 * @property slotId the opportunity it attempts.
 * @property plannedFor the date the opportunity was planned for, read from the attempt's own snapshot —
 *   the presentation it started under, never a live re-reading of the plan (§19).
 * @property status in progress, completed or cancelled.
 * @property startedAt when it actually started.
 * @property finishedAt when it actually ended, or `null` while it is running.
 * @property duration how long it actually took, or `null` while it is running.
 * @property performedSets how many sets it confirmed.
 * @property exposedExercises how many of its occurrences hold at least one confirmed set.
 * @property skippedExercises how many of its occurrences the user explicitly skipped.
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

    /** Whether the attempt ended as a finished workout. */
    val isCompleted: Boolean
        get() = status.isCompleted

    /** Whether the attempt recorded any work at all, whatever its status (§12's partial exposure). */
    val hasExposure: Boolean
        get() = performedSets > 0
}

/**
 * One attempt as history reads it, computed from the session's own facts and from nothing else.
 *
 * It exists as a function rather than as a block inside the calculator so that the *only* place a
 * [HistoryItem] is built is the place that says what each of its fields means; a second construction site
 * would be a second definition of exposure.
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
