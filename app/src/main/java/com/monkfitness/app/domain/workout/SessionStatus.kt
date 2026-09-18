package com.monkfitness.app.domain.workout

/**
 * What happened to a started session (§19):
 *
 * ```text IN_PROGRESS → COMPLETED | CANCELLED
 * ```
 *
 * A session is created once, when the user starts it, and it survives the screen closing, the process
 * being recreated, an app restart and a reboot — because it is persisted runtime state, not UI state.
 * Closing the screen or going back does **not** cancel it; only the explicit cancel does, and a
 * cancelled session keeps its history rather than deleting it.
 *
 * `CANCELLED` is not a quiet `COMPLETED`: a cancelled session is not a completed workout, it does not
 * complete its slot, and the work it did observe stays recorded as partial exposure. Nothing in the
 * domain turns either state into a score.
 *
 * Values are persisted by name.
 */
enum class SessionStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /** Whether the session reached its end as a finished workout. */
    val isCompleted: Boolean
        get() = this == COMPLETED
}
