package com.monkfitness.app.domain.program

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The Scheduler's decision, as a pure function of a [ScheduleRequest].
 *
 * It decides *timing* and owns nothing else: which dates a revision trains on over a bounded window,
 * which of those have no opportunity yet, which existing opportunities the revision no longer
 * presents, and which passed. The invariants are structural, not checked:
 *
 *  * it schedules **slots, never Sessions** — no session type is reachable from this file,
 *    [ScheduleRequest] or [SlotPlan], and a pass can only answer with slots;
 *  * a slot is an **opportunity**, not an amount of work — `WorkoutSlot` has no repetitions, duration
 *    or score field to fill;
 *  * a missed slot writes a status and nothing else, and does **not** slide the schedule: the plan day
 *    a date presents is `ordinal(date) mod days`, the ordinal counting *scheduled dates from the
 *    anchor* — never completions, attempts or misses;
 *  * existing planned dates are **preserved**: [SlotPlan.create] skips every date the Program already
 *    holds a slot on, whatever its status, and no row is rewritten, re-pointed or deleted;
 *  * `FlexiblePerWeek.sessionsPerWeek` is **authoritative** and read from the revision — nothing here
 *    counts existing slots, so a schedule can never be derived from its own history;
 *  * the same inputs produce the same slots: no clock, no random source, no mutable state, and
 *    [ScheduleRequest.slotIds] is the only thing that varies (identity, not structure);
 *  * a created slot *names* an existing `ProgramDay`; no day or exercise is built here, no adaptive
 *    policy is invoked, and the revision is read-only input.
 *
 * **Missed.** An opportunity whose date passed while the program was *not* paused was an expectation
 * the user did not meet. It becomes `MISSED`, and that is the whole record: no zero-work session is
 * invented for it, the slot keeps its date, and no other date moves. The comparison is
 * `plannedFor < asOf`, so *today's* opportunity is still open — a date is not missed at midnight, it
 * is missed once the day is over.
 *
 * **Superseded by the revision.** Only *future* slots become `SUPERSEDED`: a slot that has already
 * passed was an expectation under the plan that was live then, so a later edit does not retroactively
 * cancel it — it is missed or it was trained. A slot still ahead is superseded when the revision no
 * longer presents its date: its weekday is not one this revision trains on, or the date lies outside
 * this revision's own run. That run is the revision's **extent**, not the planning window — a fixed
 * revision runs to `anchor + days - 1`, an indefinite one runs indefinitely — so shortening a program
 * supersedes the dates beyond its new end while switching to an indefinite one supersedes nothing,
 * the far dates simply lying beyond today's horizon.
 *
 * This rule regenerates nothing: every future slot whose date the revision still presents keeps its
 * identity, date, plan day and status, and a second pass supersedes nothing. Plan *content* is not
 * part of it either — changing what Wednesday's workout contains creates a new revision and preserves
 * every Wednesday, because the revision still presents Wednesdays. The opportunity is the date; what
 * is trained on it is the revision's business.
 *
 * **Superseded by a pause.** A pause freezes active program time and missed-opportunity logic, so a
 * date it covers is **not a training date**: the plan does not advance through the pause, and the plan
 * days that would have fallen inside it fall after it instead. An opportunity that falls **ahead of
 * the user** inside a pause is left **open**, not superseded — the pause may end before it and
 * destroying it would leave a gap the plan could never refill — while one whose date **passed** inside
 * a pause is superseded rather than missed, because the user was not expected to train on a frozen
 * day.
 */
object SlotPlanner {

    /**
     * Decides one scheduling pass. Pure: no clock, no storage, no state — `request` in, [SlotPlan] out.
     *
     * The order of the work is the order of the questions and each answer is independent of the
     * others; nothing in the second half reads the first half's creations, which is why a pass applied
     * to a tree it has already applied to decides nothing new.
     */
    fun plan(request: ScheduleRequest): SlotPlan {
        val revision = request.revision
        val weekdays = revision.schedule.scheduledWeekdays()
        val anchor = request.anchor

        // The first date that can still be trained: the plan's own beginning, or today if the plan is
        // already under way. A pass never plans an opportunity in the past — it would be born missed.
        val windowStart = if (anchor.isAfter(request.asOf)) anchor else request.asOf

        val windowEnd = when (val duration = revision.duration) {
            // A fixed revision is planned to its own end: a fact the plan can see.
            is ProgramDuration.FixedDays -> anchor.plusDays((duration.days - 1).toLong())
            // An indefinite revision keeps the bounded horizon and extends it on the next pass.
            ProgramDuration.Indefinite -> windowStart.plusDays((PLANNING_HORIZON_DAYS - 1).toLong())
        }

        val window = if (windowEnd.isBefore(windowStart)) null else ScheduleWindow(windowStart, windowEnd)

        // Every date from the anchor to the end of the window that the revision trains on. The *whole*
        // walk matters even though only its tail is planned: a date's index in this sequence is the
        // ordinal of the plan day it presents, and the ordinal must not restart when the window start
        // moves forward a day.
        //
        // The walk is deliberately **pause-free**, and that is the invariant rather than an omission: a
        // pause stops *planning* and freezes missed-opportunity logic, but it does not renumber the
        // plan. The plan day a date presents is a property of the calendar — its ordinal among the
        // revision's dates from the anchor — so passes made before and after a pause agree about the
        // same date. Excluding paused dates here would shift the cycle for every date after the pause
        // and make the plan's days disagree with the slots already persisted around the interval.
        val datesFromAnchor = if (window == null) {
            emptyList()
        } else {
            planDates(anchor, window.lastDate, weekdays)
        }

        // The dates a pass may create an opportunity on: the revision's dates inside the window that no
        // pause covers. A paused date is not a date the user is expected to train on, so nothing is
        // planned there — the walk above still *counts* it, so the plan keeps its place on the calendar.
        val scheduledDates = if (window == null) {
            emptyList()
        } else {
            datesFromAnchor.filter { date ->
                window.contains(date) && !coveredBy(request.pauses, date)
            }
        }

        val occupied = request.slots.map { slot -> slot.plannedFor }.toSet()
        val create = if (window == null) {
            emptyList()
        } else {
            // The ordinal is the date's place in the walk **from the anchor**, so a window starting
            // later than the plan's first day does not restart the cycle: the plan day a date presents
            // is a property of the calendar, never of when the pass was made nor of pauses before it.
            datesFromAnchor.mapIndexedNotNull { ordinal, date ->
                if (!window.contains(date) || coveredBy(request.pauses, date) || date in occupied) {
                    // Behind the user, inside a pause (planned by nobody), or already holding an
                    // opportunity — trained, missed, superseded or still open. One date holds one slot,
                    // so nothing is added and nothing is overwritten.
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
     * The dates [from] to [to] that [weekdays] trains on, in order.
     *
     * **No pause is consulted here, and that is the rule.** This walk is what the plan's days are
     * counted along, so excluding a paused date would renumber every date after the pause: the plan day
     * a date presents would depend on *when* the pass was made rather than on the calendar, and slots
     * already persisted around the interval would disagree about the cycle. Which dates a pass may
     * actually *create* on is decided by [SlotPlanner.plan]'s own filter, where a pause does belong.
     */
    private fun planDates(
        from: LocalDate,
        to: LocalDate,
        weekdays: Set<DayOfWeek>
    ): List<LocalDate> =
        generateSequence(from) { date -> date.plusDays(1) }
            .takeWhile { date -> !date.isAfter(to) }
            .filter { date -> date.dayOfWeek in weekdays }
            .toList()

    private fun coveredBy(pauses: List<PausedInterval>, date: LocalDate): Boolean =
        pauses.any { pause -> pause.covers(date) }

    /**
     * Whether [revision] still presents [date]: the weekday is one it trains on, and the date lies
     * within its own run — from the plan's [anchor] to its end, if it has one.
     *
     * The run's **end** is the revision's own, never the planning window's: an indefinite revision has
     * no end, so a date beyond today's horizon is still presented by it and is preserved rather than
     * superseded by horizon arithmetic. The horizon is how far ahead the plan is *written down*, not how
     * far it *reaches*.
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
