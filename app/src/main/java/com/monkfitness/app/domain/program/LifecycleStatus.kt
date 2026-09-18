package com.monkfitness.app.domain.program

/**
 * Where a Program is in its own life (§3):
 *
 * ```text
 * NOT_STARTED → RUNNING ↕ PAUSED → COMPLETED
 * ```
 *
 * Three rules this vocabulary exists to keep straight:
 *
 *  * a planned start date does not start anything — a Program is `RUNNING` only once the user
 *    starts it and `actualStartDate` says when that happened, which is why it is a fact and not a
 *    plan;
 *  * `PAUSED` freezes active program time and missed-opportunity logic rather than moving the plan;
 *  * `COMPLETED` is terminal: it cannot be resumed directly, and picking what runs next is the
 *    user's explicit choice.
 *
 * **Archiving is not a lifecycle state.** It is a separate stamp (`archivedAt`) that retains all
 * history and stops future planning, so an archived Program keeps reporting the lifecycle it reached
 * (§3, §29). Values are persisted by name.
 */
enum class LifecycleStatus {
    NOT_STARTED,
    RUNNING,
    PAUSED,
    COMPLETED;

    /** Whether this lifecycle is over — a completed Program is not resumable (§3). */
    val isTerminal: Boolean
        get() = this == COMPLETED
}
