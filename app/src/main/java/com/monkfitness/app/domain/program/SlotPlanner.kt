package com.monkfitness.app.domain.program

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The Scheduler's decision, as a pure function of a [ScheduleRequest] — §30 step 7, §20.
 *
 * ### What the Scheduler owns, in one sentence
 *
 * It turns a saved revision's plan into **planned opportunities**: which dates the revision trains on
 * over a bounded window, which of those dates do not have an opportunity yet, which existing
 * opportunities the revision no longer presents, and which existing opportunities passed. It decides
 * *timing*, and it owns nothing else.
 *
 * ### The invariants it is built around, and how each is structural
 *
 * These are the blueprint's own rules (§12, §20, §33); each is enforced by the shape of the code
 * rather than by a check that could be forgotten.
 *
 * | rule | how it holds here |
 * | --- | --- |
 * | the Scheduler schedules **slots, never Sessions** | there is no session type in this file, in [ScheduleRequest] or in [SlotPlan]; a pass can only answer with slots |
 * | it never creates a `WorkoutSession` | the decision produces [SlotPlan.create] as `WorkoutSlot` values, and nothing here can name a session |
 * | a Slot is an **opportunity**, not an amount of work | every created value is a `WorkoutSlot`, which has no repetitions, duration or score field to fill (§12) |
 * | `MISSED` is not zero performance | missing writes a status; no value is recorded for the missed opportunity, because there is nothing to record |
 * | a missed slot does **not** slide the schedule | the plan day a date presents is `ordinal(date) mod days`, where the ordinal counts *scheduled dates from the anchor* — never completions, never attempts, never misses. A miss changes no other date and no other day |
 * | existing planned dates are **preserved** | [SlotPlan.create] skips every date the Program already has a slot on, whatever its status; an existing row is never rewritten, re-pointed or deleted |
 * | fixed weekdays are deterministic | membership in the user's own set (§20) |
 * | `FlexiblePerWeek.sessionsPerWeek` is **authoritative** | the count is read from the revision and spread by [flexibleSpread]; no function here counts existing slots, so a schedule can never be derived from its own history |
 * | the same inputs produce the same slots | the pass reads no clock, no random source and no mutable state: `plan(request)` is a function of `request` alone, and [ScheduleRequest.slotIds] is the only thing that varies (identity, not structure — §26) |
 * | the Scheduler does not recreate plan elements | a created slot *names* an existing `ProgramDay` of the revision; the plan's days are cycled, and no day or exercise is ever built here |
 * | the Scheduler does not invoke Adaptive policy | no adaptive type appears; a scheduling pass reads the plan, not performance |
 * | the Scheduler does not rewrite an immutable Revision | the revision is read-only input; [SlotPlan] carries no revision row to write |
 * | the Scheduler makes no UI decision | it returns a value; nothing here knows what a screen wants |
 *
 * ### The three reconciliations it performs
 *
 * **Missed.** An opportunity whose date passed while the program was *not* paused was an expectation
 * the user did not meet. It becomes `MISSED`, and that is the whole record: no zero-work session is
 * invented for it (§12), the slot keeps its date, and no other date moves. The comparison is
 * `plannedFor < asOf`, so *today's* opportunity is still open — a date is not missed at midnight; it is
 * missed once the day is over.
 *
 * **Superseded by the revision.** §20 says *future* incompatible slots become `SUPERSEDED`, and
 * "future" is the operative word: a slot that has already passed was an expectation under the plan
 * that was live then, so a later edit does not retroactively cancel it — it is missed or it was
 * trained. A slot that is still ahead becomes superseded when the revision in front of us no longer
 * presents its date:
 *
 * ```text
 * the date's weekday is not a day this revision trains on   → superseded
 * the date lies outside this revision's own run             → superseded
 * otherwise                                                 → untouched
 * ```
 *
 * The second line is the revision's **extent**, not the planning window: a fixed revision runs to
 * `anchor + days - 1`, and an indefinite one runs indefinitely. Shortening a program therefore
 * supersedes the dates beyond its new end, while switching from a fixed program to an indefinite one
 * supersedes nothing — the far dates are still dates the revision presents, they are simply beyond
 * today's horizon.
 *
 * This rule **replaces** nothing wholesale. It is not a regeneration: every future slot whose date the
 * revision still presents is left exactly as it is — same identity, same date, same plan day, same
 * status — and only the dates the revision genuinely cannot present are superseded. It is also
 * *idempotent*: after one pass no future slot fails the test, so a second pass supersedes nothing.
 *
 * Note what is deliberately **not** part of it: the plan's *content*. Changing what Wednesday's
 * workout contains creates a new revision, and this rule preserves every Wednesday, because the
 * revision still presents Wednesdays. The opportunity is the date; what is trained on it is the
 * revision's business (§16, §19 — a session snapshots the presentation it captured).
 *
 * **Superseded by a pause.** §3 freezes a paused Program's *"active program time and missed-opportunity
 * logic"*, and §20's slot-state prose names a pause among the reasons a slot is superseded rather than
 * missed. Both are honoured here, in the two directions that keep the plan intact:
 *
 *  * a date covered by a pause is **not a training date** — the plan does not advance through a pause,
 *    which is what freezing active program time means: the plan days that would have fallen inside the
 *    pause fall after it instead;
 *  * an opportunity that falls **ahead of the user** inside a pause is **left open**, not superseded:
 *    the pause may end before it, the user may take it, and destroying a future opportunity because
 *    today is paused would leave a gap the plan could never refill. A pause is a fact about now, not a
 *    cancellation of what is coming;
 *  * an opportunity whose date **passed** inside a pause is superseded rather than missed, because the
 *    user was not expected to train on a frozen day (§20: *"superseded is not missed: the user was not
 *    expected to train it"*) and an open opportunity is by definition still ahead ([WorkoutSlot.isOpen]).
 */
object SlotPlanner {

    /**
     * Decides one scheduling pass. Pure: no clock, no storage, no state — `request` in, [SlotPlan] out.
     *
     * The order of the work is the order of the questions, and each answer is independent of the
     * others: the window first (how far the plan reaches), then the plan's dates inside it, then the
     * opportunities that are missing from those dates, and finally what the program's existing
     * opportunities have become. Nothing in the second half reads the first half's creations, which is
     * why a pass applied to a tree it has already applied to decides nothing new.
     */
    fun plan(request: ScheduleRequest): SlotPlan {
        val revision = request.revision
        val weekdays = revision.schedule.scheduledWeekdays()
        val anchor = request.anchor

        // The first date that can still be trained: the plan's own beginning, or today if the plan is
        // already under way. A pass never plans an opportunity in the past — an opportunity nobody can
        // take is not an opportunity, and it would be born missed.
        val windowStart = if (anchor.isAfter(request.asOf)) anchor else request.asOf

        val windowEnd = when (val duration = revision.duration) {
            // A fixed revision is planned to its own end: its end is a fact the plan can see.
            is ProgramDuration.FixedDays -> anchor.plusDays((duration.days - 1).toLong())
            // An indefinite revision keeps §20's bounded horizon and extends it on the next pass.
            ProgramDuration.Indefinite -> windowStart.plusDays((PLANNING_HORIZON_DAYS - 1).toLong())
        }

        val window = if (windowEnd.isBefore(windowStart)) null else ScheduleWindow(windowStart, windowEnd)

        // Every date from the plan's anchor to the end of the window that the revision trains on and
        // that no pause covers. The *whole* walk from the anchor matters even though only its tail is
        // planned: the index of a date in this sequence is the ordinal of the plan day it presents, and
        // the ordinal must not restart when the window start moves forward a day.
        val datesFromAnchor = if (window == null) {
            emptyList()
        } else {
            planDates(anchor, window.lastDate, weekdays, request.pauses)
        }
        val scheduledDates = if (window == null) {
            emptyList()
        } else {
            datesFromAnchor.filter { window.contains(it) }
        }

        val occupied = request.slots.map { slot -> slot.plannedFor }.toSet()
        val create = if (window == null) {
            emptyList()
        } else {
            // The ordinal is the date's place in the walk **from the anchor**, so a window that starts
            // later than the plan's first day does not restart the cycle: the plan day a date presents
            // is a property of the calendar and of the pauses before it, never of when the pass was made.
            datesFromAnchor.mapIndexedNotNull { ordinal, date ->
                if (!window.contains(date) || date in occupied) {
                    // Either the date is behind the user (the walk starts at the anchor, which may be
                    // before the window), or it already holds an opportunity — trained, missed,
                    // superseded or still open. One date holds one slot, so the Scheduler adds nothing
                    // and overwrites nothing (§20).
                    null
                } else {
                    WorkoutSlot(
                        slotId = request.slotIds.newId(),
                        programId = revision.programId,
                        revisionId = revision.revisionId,
                        programDayId = revision.days[ordinal % revision.days.size].programDayId,
                        plannedFor = date,
                        status = SlotStatus.PLANNED
                    )
                }
            }
        }

        val planned = request.slots.filter { slot -> slot.status == SlotStatus.PLANNED }

        val supersede = planned.mapNotNull { slot ->
            when {
                // Ahead of the user, and the revision in front of us cannot present that date.
                !slot.plannedFor.isBefore(request.asOf) &&
                    !presents(revision, slot.plannedFor, anchor, weekdays) ->
                    SupersededSlot(
                        slot.slotId,
                        SupersessionReason.THE_REVISION_NO_LONGER_PRESENTS_THE_DATE
                    )

                // Behind the user, and the plan was frozen when it went by.
                slot.plannedFor.isBefore(request.asOf) && coveredBy(request.pauses, slot.plannedFor) ->
                    SupersededSlot(
                        slot.slotId,
                        SupersessionReason.THE_OPPORTUNITY_PASSED_WHILE_PAUSED
                    )

                else -> null
            }
        }

        val miss = planned
            .filter { slot ->
                slot.plannedFor.isBefore(request.asOf) &&
                    !coveredBy(request.pauses, slot.plannedFor)
            }
            .map { slot -> slot.slotId }

        return SlotPlan(
            window = window,
            scheduledDates = scheduledDates,
            create = create,
            supersede = supersede,
            miss = miss
        )
    }

    /**
     * The dates [from] to [to] that [weekdays] trains on and that no pause covers, in order.
     *
     * Paused dates are **excluded rather than skipped over**, which is the mechanism behind "a pause
     * freezes active program time": because the sequence is what the plan days are counted along, the
     * plan does not advance while the program is paused — the days that would have fallen inside the
     * pause fall after it, and the user's pause costs them no plan day.
     *
     * @param from the plan's anchor, inclusive.
     * @param to the last date of the pass's window, inclusive.
     * @param weekdays the days the revision trains on.
     * @param pauses the intervals to exclude; an open one excludes every date from its start onward.
     */
    private fun planDates(
        from: LocalDate,
        to: LocalDate,
        weekdays: Set<DayOfWeek>,
        pauses: List<PausedInterval>
    ): List<LocalDate> =
        generateSequence(from) { date -> date.plusDays(1) }
            .takeWhile { date -> !date.isAfter(to) }
            .filter { date -> date.dayOfWeek in weekdays }
            .filter { date -> !coveredBy(pauses, date) }
            .toList()

    /** Whether any pause interval in [pauses] covers [date]. */
    private fun coveredBy(pauses: List<PausedInterval>, date: LocalDate): Boolean =
        pauses.any { pause -> pause.covers(date) }

    /**
     * Whether [revision] still presents [date]: the weekday is one it trains on, and the date lies
     * within its own run — from the plan's [anchor] to its end, if it has one.
     *
     * The run's **end** is the revision's own, never the planning window's: an indefinite revision has
     * no end at all, so a date beyond today's 30-day horizon is still presented by it, and is
     * preserved rather than superseded by horizon arithmetic. The horizon is how far ahead the plan is
     * *written down*, not how far it *reaches*.
     */
    private fun presents(
        revision: ProgramRevision,
        date: LocalDate,
        anchor: LocalDate,
        weekdays: Set<DayOfWeek>
    ): Boolean {
        if (date.dayOfWeek !in weekdays) return false
        if (date.isBefore(anchor)) return false
        val end = (revision.duration as? ProgramDuration.FixedDays)
            ?.let { duration -> anchor.plusDays((duration.days - 1).toLong()) }
        return end == null || !date.isAfter(end)
    }
}
