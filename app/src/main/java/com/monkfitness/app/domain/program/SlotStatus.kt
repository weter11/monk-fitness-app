package com.monkfitness.app.domain.program

/**
 * What happened to one planned opportunity to train (§20):
 *
 * ```text PLANNED → COMPLETED | MISSED | SUPERSEDED
 * ```
 *
 * The four states are different facts and must not be folded into one another:
 *
 *  * `PLANNED` — the opportunity is still ahead of the user;
 *  * `COMPLETED` — a session for it was completed;
 *  * `MISSED` — the opportunity passed. A missed slot says **nothing** about performance: it is not
 *    a workout that scored zero, it is a workout that did not happen, and the program does not slide
 *    the whole schedule after it;
 *  * `SUPERSEDED` — the slot was replaced by a later plan (an edit, a pause, a schedule change)
 *    before it was trained. Superseded is not missed: the user was not expected to train it.
 *
 * Values are persisted by name.
 */
enum class SlotStatus {
    PLANNED,
    COMPLETED,
    MISSED,
    SUPERSEDED
}
