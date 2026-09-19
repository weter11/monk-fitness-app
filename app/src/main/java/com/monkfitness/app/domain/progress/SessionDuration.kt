package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Duration

/**
 * How long one attempt actually took: `finishedAt - startedAt`, in whole seconds (§19, §21).
 *
 * **Actual** time, never planned time: a session started at 23:40 and finished at 00:25 the next
 * calendar date lasted 45 minutes, and it lasted 45 minutes whatever dates the two instants fall on.
 * The snapshot's `plannedFor` is a different fact and lives in the snapshot (see [HistoryItem.plannedFor])
 * — replacing the real timestamps with the planned date is precisely the destruction §19 forbids.
 *
 * @property sessionId the attempt this duration belongs to.
 * @property seconds the elapsed time between its start and its end.
 */
data class SessionDuration(val sessionId: SessionId, val seconds: Long) {

    init {
        require(seconds >= 0) { "an attempt does not last a negative time, was $seconds" }
    }
}

/**
 * The mean duration of the attempts one aggregation measured (§21's *average Session duration*).
 *
 * It is `measuredSessions`-and-`totalSeconds` first, and the mean is a **derived** property, because the
 * interesting case is the empty one: an average of nothing is not `0` seconds, it is *no average*, and
 * [averageSeconds] is `null` rather than a zero that a screen would happily print as
 * "0m 00s average". The same care is why the count of measurements is part of the value: an average of
 * two attempts and an average of two hundred are not the same claim.
 *
 * Which attempts are measured is the caller's scope decision, and [ProgressCalculator] documents it:
 * completed workouts inside the window. A cancelled attempt's elapsed time is a fact about the attempt
 * (it has its own [SessionDuration] in history) but it is not a workout's duration, and an attempt still
 * running has no duration at all.
 *
 * @property measuredSessions how many attempts the mean is over.
 * @property totalSeconds their combined elapsed seconds.
 */
data class AverageSessionDuration(val measuredSessions: Int, val totalSeconds: Long) {

    init {
        require(measuredSessions >= 0) { "an average is over zero or more attempts, was $measuredSessions" }
        require(totalSeconds >= 0) { "combined elapsed time is not negative, was $totalSeconds" }
    }

    /** The mean elapsed seconds of one measured attempt, or `null` when nothing was measured. */
    val averageSeconds: Double?
        get() = if (measuredSessions == 0) null else totalSeconds.toDouble() / measuredSessions

    /** Whether at least one attempt was measured, so an average exists. */
    val isMeasured: Boolean
        get() = measuredSessions > 0
}

/**
 * The duration of one attempt, or `null` when it has none yet.
 *
 * A session that has not finished has no duration — not a zero, and not "so far": a running workout is
 * still running, and reporting the time elapsed so far as its duration would make the measure change
 * while the user watches it (§19: the session is persisted runtime state, not a stopwatch the reader
 * owns). The elapsed time of a finished attempt comes from the two stamps it stored and from nothing
 * else.
 */
fun durationOf(session: WorkoutSession): SessionDuration? {
    val finishedAt = session.finishedAt ?: return null
    return SessionDuration(session.sessionId, Duration.between(session.startedAt, finishedAt).seconds)
}
