package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.SlotId
import java.time.LocalDate

/**
 * The identity source a scheduling pass mints a new opportunity through.
 *
 * The planning decision is a pure function, so the one thing it cannot produce by itself — a fresh
 * [SlotId] — arrives as a parameter. A test supplies a counter it can read in an assertion; production
 * supplies the composition root's [com.monkfitness.app.di.IdGenerator].
 *
 * It mints the *typed* id rather than a string body, because a Slot is the only identity a scheduling
 * pass creates and a general-purpose generator would be a wider seam than the decision needs.
 */
fun interface SlotIdSource {

    /** One fresh slot identity. Callers must treat it as opaque. */
    fun newId(): SlotId
}

/**
 * Why one future opportunity stopped being one.
 *
 * Supersession is a **record**, not a discard: the slot row stays exactly where it was, with its planned
 * date and the revision it came from, and only its status changes. Carrying the reason lets a caller
 * explain to the user *why* an opportunity disappeared, and lets a test assert *which* rule fired
 * instead of matching on a status that three different situations share.
 *
 * Two situations produce it, and they are different facts:
 *
 *  * [THE_REVISION_NO_LONGER_PRESENTS_THE_DATE] — the plan changed under a future date: its weekday left
 *    the schedule, or the date lies outside the revision's own run because it was shortened past it or
 *    the plan now begins later.
 *  * [THE_OPPORTUNITY_PASSED_WHILE_PAUSED] — the date passed while a pause covered it. The user was not
 *    expected to train it (which is why it is not [SlotStatus.MISSED]), and it can no longer be taken
 *    (which is why it is not left open).
 */
enum class SupersessionReason(val message: String) {

    /** The stored revision no longer presents this date: the weekday or the run no longer includes it. */
    THE_REVISION_NO_LONGER_PRESENTS_THE_DATE(
        "the revision no longer presents this date, so the opportunity it planned is superseded (§20)"
    ),

    /** The date passed inside a pause interval, so the opportunity was frozen and then went by. */
    THE_OPPORTUNITY_PASSED_WHILE_PAUSED(
        "the opportunity passed while the Program was paused, so it is not missed — it was not " +
            "expected, and it is superseded (§3, §20)"
    )
}

/** One existing opportunity a pass supersedes, with the rule that superseded it. */
data class SupersededSlot(val slotId: SlotId, val reason: SupersessionReason)

/**
 * Everything a scheduling pass is decided from — and nothing else.
 *
 * The request is the whole input of the decision, and no repository, no clock, no database and no
 * session runtime appears here: that is what makes the decision testable without a device and what makes
 * "let the Scheduler create a Session" structurally impossible rather than merely forbidden.
 *
 * `revision` is **read, never written** — the Scheduler schedules the plan it is given, and a revision is
 * immutable. `anchor` is supplied by the caller because *which* date a program's plan belongs on is a
 * fact about the Program (its actual start, or its planned one) and the Scheduler only decides what
 * follows from it. `asOf` is the date the pass is made on: the first date that can still be trained, the
 * date a passed opportunity is measured against, and the origin of an indefinite program's horizon.
 * `slots` is every slot the Program already has, in any status — the dates already occupied and the
 * opportunities a pass may mark missed or superseded. `pauses` are the program's pause intervals as
 * dates: a paused date is not a training date and is never missed.
 */
data class ScheduleRequest(
    val revision: ProgramRevision,
    val anchor: LocalDate,
    val asOf: LocalDate,
    val slots: List<WorkoutSlot>,
    val pauses: List<PausedInterval>,
    val slotIds: SlotIdSource
)

/**
 * What one scheduling pass decided.
 *
 * The decision is stated as **three disjoint lists plus the window they were made over**, so a caller can
 * see exactly which rows a pass would write before writing them: `create` the opportunities that do not
 * exist yet (all PLANNED), `supersede` the existing ones the revision no longer presents or that passed
 * inside a pause, `miss` the existing ones whose date passed untrained and unpaused.
 *
 * Three properties are structural, not promises:
 *
 *  * **nothing is created for a date that already holds a slot**, whatever that slot's status — so a
 *    pass applied twice writes nothing the second time, and a superseded opportunity is history rather
 *    than a gap to be refilled silently;
 *  * **no created slot carries an amount of work**: there is no field here that could hold a repetition,
 *    a duration or a score, because the opportunity is the whole of it;
 *  * **`create` never names a date in `supersede` or `miss`**, since a created slot is new by definition
 *    and the other two lists name existing rows.
 *
 * `window` is `null` when the revision has no date left — a fixed revision whose run is already over. A
 * pass with a `null` window creates nothing but still reconciles what it finds, which is why the absence
 * of a horizon is reported here instead of refusing the call.
 */
data class SlotPlan(
    val window: ScheduleWindow?,
    val scheduledDates: List<LocalDate>,
    val create: List<WorkoutSlot>,
    val supersede: List<SupersededSlot>,
    val miss: List<SlotId>
) {

    /** Whether the revision has no date left at or after the pass date — a run that is already over. */
    val hasNoFutureDate: Boolean
        get() = window == null

    /** Whether the pass decided nothing at all: no row to write in any of the three lists. */
    val isEmpty: Boolean
        get() = create.isEmpty() && supersede.isEmpty() && miss.isEmpty()

    val createdDates: List<LocalDate>
        get() = create.map { it.plannedFor }
}
