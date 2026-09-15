package com.monkfitness.app.domain.adaptive

/**
 * How a program session ended, in the only three states the adaptive pipeline distinguishes.
 *
 *  * [NOT_STARTED] — the day's session was never opened: no start stamp, no actual work, no
 *    completed exercise. It is the "planned opportunity that did not happen" observation, and it is
 *    what adherence/consistency are measured against.
 *  * [PARTIAL] — the session started and some work was observed, but the workout completion
 *    condition was not satisfied (the user stopped early, or abandoned it). An abandoned workout is
 *    a [PARTIAL] observation whose finished stamp is set; a [PARTIAL] observation without one is
 *    still in progress. Abandonment is not a separate field at this layer — it is read from the
 *    stamps.
 *  * [COMPLETED] — the workout completion condition was satisfied. This does NOT mean every
 *    prescribed set was performed: a completed session may still carry exposure well below `1.0`.
 *
 * Deliberately carries no user-facing strings and no Android/Room types: the mapping from stored
 * program state to an outcome belongs to the session-history adapter, not to the domain model.
 */
enum class SessionOutcome {
    NOT_STARTED,
    PARTIAL,
    COMPLETED
}