package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.SessionId
import java.time.Instant

/**
 * The immutable record of what was presented when one session started (§19, §16).
 *
 * The distinction this type exists for is subtle and load-bearing, so it is stated twice:
 *
 * ```text
 * EffectiveWorkout       = what should be presented for a Slot (recomputable, changes as
 *                          adjustments supersede one another)
 * WorkoutSessionSnapshot = what WAS presented when this Session started (frozen forever)
 * ```
 *
 * A session is bound to the program, the revision and the complete snapshot it started under, and it
 * is the only thing the session may generate from afterwards: an edit to the program, a newly
 * computed effective workout or a superseding adjustment cannot retroactively change what the user
 * was shown, and a finished workout is never re-explained against a newer plan. The snapshot is what
 * makes that true, which is also why an adaptive adjustment is described as being *consumed* by
 * snapshot creation (§16): once captured here, a later adjustment supersedes the old one for future
 * slots rather than rewriting this one.
 *
 * [workout] is held as a value, so the frozen presentation is the one that was copied in — including
 * the adjustment ids that were in effect at that moment.
 *
 * @property sessionId the session this snapshot belongs to. A snapshot never outlives its session or
 *   describes another one.
 * @property capturedAt when the session started and this record was taken. The session's own
 *   `startedAt` is the actual start; `workout.plannedFor` is the planned date, so a session started
 *   on one date and completed later preserves both.
 * @property workout the presentation captured at that moment.
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
