package com.monkfitness.app.domain.workout

/**
 * What happened to a started session: `IN_PROGRESS → COMPLETED | CANCELLED`.
 *
 * The session survives the screen closing and an app restart because it is persisted runtime state, not UI
 * state. Going back does not cancel it — only the explicit cancel does — and a cancelled session keeps its
 * history rather than deleting it.
 *
 * `CANCELLED` is not a quiet `COMPLETED`: it is not a completed workout, it does not complete its slot, and
 * the work it observed stays recorded as partial exposure. Nothing turns either state into a score.
 *
 * Values are persisted by name.
 */
enum class SessionStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    val isCompleted: Boolean
        get() = this == COMPLETED
}
