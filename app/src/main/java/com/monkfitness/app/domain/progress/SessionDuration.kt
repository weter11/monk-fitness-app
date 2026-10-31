package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Duration

/**
 * How long one attempt actually took: `finishedAt - startedAt`, in whole seconds.
 *
 * Actual time, never planned time: a session started at 23:40 and finished at 00:25 the next date lasted
 * 45 minutes whatever dates the two instants fall on. The snapshot's `plannedFor` is a different fact,
 * and replacing the real timestamps with the planned date is the destruction this model forbids.
 */
data class SessionDuration(val sessionId: SessionId, val seconds: Long) {

    init {
        require(seconds >= 0) { "an attempt does not last a negative time, was $seconds" }
    }
}

/**
 * The mean duration of the attempts one aggregation measured. The mean is derived from the count and the
 * total because the interesting case is the empty one: an average of nothing is not `0` seconds, it is
 * no average, and [averageSeconds] is `null` rather than a zero a screen would print as "0m 00s". The
 * count is part of the value for the same reason — an average of two attempts and of two hundred are not
 * the same claim.
 *
 * Which attempts are measured is the caller's scope decision: completed workouts inside the window. A
 * cancelled attempt's elapsed time is a fact about the attempt, not a workout's duration.
 */
data class AverageSessionDuration(val measuredSessions: Int, val totalSeconds: Long) {

    init {
        require(measuredSessions >= 0) { "an average is over zero or more attempts, was $measuredSessions" }
        require(totalSeconds >= 0) { "combined elapsed time is not negative, was $totalSeconds" }
    }

    /** The mean elapsed seconds of one measured attempt, or `null` when nothing was measured. */
    val averageSeconds: Double?
        get() = if (measuredSessions == 0) null else totalSeconds.toDouble() / measuredSessions

    val isMeasured: Boolean
        get() = measuredSessions > 0
}

/**
 * The duration of one attempt, or `null` when it has none yet. A session that has not finished has no
 * duration — not a zero and not "so far": reporting elapsed time as its duration would make the measure
 * change while the user watches it. A finished attempt's elapsed time comes from its two stamps alone.
 */
fun durationOf(session: WorkoutSession): SessionDuration? {
    val finishedAt = session.finishedAt ?: return null
    return SessionDuration(session.sessionId, Duration.between(session.startedAt, finishedAt).seconds)
}
