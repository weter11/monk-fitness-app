package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.SessionId
import java.time.Instant

/**
 * The immutable record of what was presented when one session started — as opposed to an
 * [EffectiveWorkout], which is what should be presented for a slot and is recomputable as adjustments
 * supersede one another.
 *
 * The session is bound to the program, revision and complete snapshot it started under, and the snapshot
 * is the only thing it may generate from afterwards: a program edit, a newly computed effective workout or
 * a superseding adjustment cannot retroactively change what the user was shown, and a finished workout is
 * never re-explained against a newer plan. That is also why an adjustment is described as *consumed* by
 * snapshot creation — once captured here, a later one supersedes the old for future slots rather than
 * rewriting this one.
 *
 * A snapshot never outlives its session or describes another one. [capturedAt] is when the session
 * started, while `workout.plannedFor` is the planned date, so both are preserved.
 */
data class WorkoutSessionSnapshot(
    val sessionId: SessionId,
    val capturedAt: Instant,
    val workout: EffectiveWorkout
) {

    init {
        require(workout.computedAt <= capturedAt) {
            "a snapshot cannot capture a presentation computed after it was taken: " +
                "computedAt=${workout.computedAt} capturedAt=$capturedAt"
        }
    }
}
