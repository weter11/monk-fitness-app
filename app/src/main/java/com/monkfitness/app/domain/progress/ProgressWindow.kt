package com.monkfitness.app.domain.progress

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * An inclusive range of **calendar dates** — the only span a rate-like measure is computed over (§21).
 *
 * Frequency is the measure that makes this type necessary: "how often did I train" is a question about a
 * span, and a rate that is not told the span it was computed over cannot be interpreted (twelve workouts
 * is a disciplined month or a lost year). So the span is a value rather than an implicit "recently", and
 * it is always explicit in the result that used it ([TrainingFrequency.window],
 * [TrainingProgress.window]).
 *
 * Two things are stated here because they are the contract every date-bounded calculation inherits:
 *
 *  * the range is **inclusive at both ends** — `2026-09-01..2026-09-07` is seven calendar dates, and the
 *    weekday the window happens to start on is irrelevant to its length;
 *  * a window is a range of *dates*, not of instants. A session's actual `startedAt` is an instant, and
 *    the calendar date it falls on is read in one explicit zone (the calculator's), never in the
 *    process's default zone by accident — see [ProgressCalculator].
 */
data class ProgressWindow(val from: LocalDate, val to: LocalDate) {

    init {
        require(!to.isBefore(from)) { "a window does not run backwards: from=$from to=$to" }
    }

    /** How many calendar dates the window spans, both ends included. Never zero. */
    val calendarDays: Int
        get() = ChronoUnit.DAYS.between(from, to).toInt() + 1

    /** Whether [date] lies inside the window. */
    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)

    companion object {

        /**
         * The [days] calendar dates ending at [endingOn], both ends included — `ofLastDays(28, d)` is
         * `d-27..d`.
         *
         * It is a named constructor rather than a calculation inside a measure so the caller owns the
         * "today" it is relative to: the use case reads the injected clock once and hands the resulting
         * window down, and every calculation below stays a function of its arguments (§26).
         */
        fun ofLastDays(days: Int, endingOn: LocalDate): ProgressWindow {
            require(days >= 1) { "a window spans at least one calendar date, was $days" }
            return ProgressWindow(endingOn.minusDays((days - 1).toLong()), endingOn)
        }
    }
}
