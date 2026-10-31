package com.monkfitness.app.domain.progress

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * An inclusive range of calendar dates — the only span a rate-like measure is computed over. A rate that
 * is not told its span cannot be interpreted (twelve workouts is a disciplined month or a lost year), so
 * the span is a value rather than an implicit "recently" and is explicit in every result that used it.
 *
 * Inclusive at both ends — `2026-09-01..2026-09-07` is seven calendar dates — and a range of dates, not
 * instants: a session's `startedAt` is read in one explicit zone (the calculator's), never in the
 * process default by accident.
 */
data class ProgressWindow(val from: LocalDate, val to: LocalDate) {

    init {
        require(!to.isBefore(from)) { "a window does not run backwards: from=$from to=$to" }
    }

    val calendarDays: Int
        get() = ChronoUnit.DAYS.between(from, to).toInt() + 1

    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)

    companion object {

        /**
         * The [days] calendar dates ending at [endingOn], both ends included — `ofLastDays(28, d)` is
         * `d-27..d`. A named constructor so the caller owns the "today" it is relative to: the use case
         * reads the injected clock once and every calculation stays a function of its arguments.
         */
        fun ofLastDays(days: Int, endingOn: LocalDate): ProgressWindow {
            require(days >= 1) { "a window spans at least one calendar date, was $days" }
            return ProgressWindow(endingOn.minusDays((days - 1).toLong()), endingOn)
        }
    }
}
