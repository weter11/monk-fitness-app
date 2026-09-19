package com.monkfitness.app.domain.program

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The planning horizon of an indefinite Program, in days (§20).
 *
 * The blueprint states one number for one case — *"Indefinite Program keeps exactly 30 days of future
 * planning and extends it when needed"* — and this constant is that number. It is deliberately not a
 * rule about a **fixed** Program: a revision that runs for a known number of days is planned for the
 * whole of its remaining duration, because its end is a fact the plan can see, while an indefinite
 * one has no end to plan towards and therefore keeps a bounded, rolling window instead.
 *
 * The rule is a *window of dates*, not a count of workouts: 30 days of future planning can hold two
 * opportunities or thirty, depending on the schedule, and nothing here assumes either.
 */
const val PLANNING_HORIZON_DAYS: Int = 30

/**
 * The dates a scheduling pass covers: a first date and a last date, both inclusive.
 *
 * A window is how §20's two duration forms are made comparable. A fixed revision's window is its own
 * remaining run (`anchor … anchor + days - 1`); an indefinite one's is [PLANNING_HORIZON_DAYS] days
 * from the first date it can still be trained. Neither case stores a total, and neither reports a
 * fraction — an indefinite program has no `N / 30` progress to show, and this value gives it no
 * opportunity to be mistaken for one: it says which dates are covered *now* and nothing about how
 * much of a program is left.
 *
 * The window is inclusive at both ends and is never empty: a range whose last date precedes its first
 * is not a small window, it is no window at all, and the Scheduler reports that as an absent window
 * rather than inventing a degenerate one.
 *
 * @property firstDate the first date the pass covers, inclusive.
 * @property lastDate the last date the pass covers, inclusive.
 */
data class ScheduleWindow(
    val firstDate: LocalDate,
    val lastDate: LocalDate
) {

    init {
        require(!lastDate.isBefore(firstDate)) {
            "a window's last date cannot precede its first: first=$firstDate last=$lastDate"
        }
    }

    /** How many calendar days the window covers, both ends included. */
    val dayCount: Int
        get() = (ChronoUnit.DAYS.between(firstDate, lastDate) + 1).toInt()

    /** Whether [date] is one of the dates this window covers. */
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(firstDate) && !date.isAfter(lastDate)

    /** Every date the window covers, in order. The dates themselves, not the opportunities on them. */
    val dates: List<LocalDate>
        get() = (0 until dayCount).map { offset -> firstDate.plusDays(offset.toLong()) }
}
