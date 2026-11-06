package com.monkfitness.app.domain.program

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The date arithmetic of the three schedule forms, and the pause/date conversion the schedule needs.
 *
 * Everything here is a *reading* of the schedule vocabulary the domain already has
 * ([ProgramSchedule], [ProgramPause]) into the dates a scheduling pass works with. Nothing here decides
 * anything: which dates exist, which of them a pass covers and what happens to a date that passes are
 * [SlotPlanner]'s decisions, kept apart so the arithmetic can be read and tested on its own.
 *
 * Every form answers one question — *is this date a training date?* — deterministically.
 * [ProgramSchedule.FixedWeekdays] answers by membership; [ProgramSchedule.FlexiblePerWeek] by an even
 * spread over the calendar week, because "three sessions a week, you choose" still means the user
 * delegated the choice to the app, not to chance; [ProgramSchedule.EveryNDays] by the distance from the
 * Program's own anchor date, because an interval that drifts across weeks has no weekday set and no week.
 *
 * The frequency is authoritative as a **count the user stated**, never as a description of the slots
 * that happen to exist: `sessionsPerWeek` is read directly and no function here counts rows. A schedule
 * derived from the current slots would make yesterday's miss change next week's rhythm.
 *
 * The weekly spread is distributed over the calendar week, Monday first. Anchoring it to the program's own
 * start date instead would make the same schedule mean different things for two Programs, and would let
 * a pause move the rhythm. An interval has no such choice: its rhythm is the calendar distance from the
 * anchor, so the anchor is passed in rather than assumed.
 */

/**
 * Whether [date] is one of the training dates [anchor]'s grid presents.
 *
 * The weekday forms ignore [anchor]; an interval is counted from it, so "every other day" starts on the
 * day the Program starts and alternates from there, whatever weekday that is.
 */
fun ProgramSchedule.schedules(date: LocalDate, anchor: LocalDate): Boolean = when (this) {
    is ProgramSchedule.FixedWeekdays -> date.dayOfWeek in weekdays
    is ProgramSchedule.FlexiblePerWeek -> date.dayOfWeek in flexibleSpread(sessionsPerWeek)
    is ProgramSchedule.EveryNDays ->
        !date.isBefore(anchor) &&
            ChronoUnit.DAYS.between(anchor, date) % days == 0L
}

/**
 * The training dates of this schedule from [from] to [to], in order, read against [anchor].
 *
 * Written as a day-by-day walk rather than by stepping the interval, so the three forms share one loop and
 * one answer: a date is a training date or it is not, and the sequence of them is the calendar's. It is
 * also what keeps a month boundary and a year boundary the same arithmetic as any other pair of days —
 * the walk moves by one day at a time and never counts weeks or months.
 */
fun ProgramSchedule.trainingDates(
    from: LocalDate,
    to: LocalDate,
    anchor: LocalDate
): List<LocalDate> = generateSequence(from) { date -> date.plusDays(1) }
    .takeWhile { date -> !date.isAfter(to) }
    .filter { date -> schedules(date, anchor) }
    .toList()

/**
 * The deterministic even spread of [sessionsPerWeek] sessions over a Monday-first week.
 *
 * The `i`-th session lands on the weekday at `floor(i * 7 / sessionsPerWeek)`, counted from Monday. It
 * is a *function of the count alone*: the same count produces the same days in every process, month and
 * Program, and it never depends on how many slots exist, on the current date or on the plan's content. A
 * count of 3 lands on Monday, Wednesday and Friday; a test pins all seven so the table cannot drift.
 *
 * @throws IllegalArgumentException when [sessionsPerWeek] is outside `1..7`.
 */
fun flexibleSpread(sessionsPerWeek: Int): Set<DayOfWeek> {
    require(sessionsPerWeek in 1..7) {
        "a flexible schedule trains between 1 and 7 days a week, was $sessionsPerWeek"
    }
    return (0 until sessionsPerWeek)
        .map { position -> DayOfWeek.of(position * 7 / sessionsPerWeek + 1) }
        .toSet()
}

/**
 * A pause interval expressed in **dates**, which is the form the schedule works in.
 *
 * A [ProgramPause] is stored as instants while a slot is planned for a `LocalDate`, so the two have to
 * meet somewhere. They meet here, once, at the boundary: [ProgramPause.pausedInterval] is the only
 * conversion, and after it the scheduling core compares dates only and needs no clock, no zone and no
 * instant at all.
 *
 * A pause covers **whole days**: the date its interval starts on and the date it ends on are both
 * covered, even when the user paused at 21:00 or resumed at 09:00. A half-covered day would have to
 * answer "was 14:00 still an opportunity?", which is a question about the user's intention rather than
 * about the plan, and a date-based answer is deterministic and explainable.
 *
 * An **open** interval (`lastDate` `null`) covers every date from its start onward: the Scheduler has no
 * way to know which dates belong to the program after it, so it plans none of them.
 */
data class PausedInterval(
    val firstDate: LocalDate,
    val lastDate: LocalDate? = null
) {

    /** Whether this pause is still in effect: an interval with no end covers everything after it. */
    val isOpen: Boolean
        get() = lastDate == null

    /** Whether [date] falls inside this pause. */
    fun covers(date: LocalDate): Boolean =
        !date.isBefore(firstDate) && (lastDate == null || !date.isAfter(lastDate))
}

/**
 * The dates one pause interval covers, read in [zone].
 *
 * The zone is the caller's for the same reason the clock is: a date is a calendar fact about a place, and
 * the layer that owns the clock owns the calendar the user is reading. Nothing in the domain assumes one,
 * so the conversion happens where it is supplied rather than inside the scheduling decision.
 */
fun ProgramPause.pausedInterval(zone: ZoneId): PausedInterval =
    PausedInterval(
        firstDate = startedAt.atZone(zone).toLocalDate(),
        lastDate = endedAt?.atZone(zone)?.toLocalDate()
    )
