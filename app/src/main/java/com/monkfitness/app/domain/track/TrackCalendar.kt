package com.monkfitness.app.domain.track

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The daily-track calendar: a 56-day repeating cadence owned by the retained daily tracks and by nothing
 * else — the optional posture/mobility track and the nutrition planner's phase, neither of which is a
 * Program.
 *
 * The arithmetic is deliberately the shipped one: day 1 is [anchor] itself, day 56 is `anchor + 55`, and
 * day 1 of the next cycle is `anchor + 56`. Keeping it verbatim makes the migration of the tracked rows
 * a rename rather than a reinterpretation.
 *
 * It is a pure calendar: no clock is read, no store is touched, and a track's start date is handed in as
 * a value.
 */
object TrackCalendar {

    const val TRACK_DAYS: Int = 56

    /**
     * The `(trackCycle, trackDay)` pair [date] falls on, counting from [anchor].
     *
     * Dates before [anchor] are clamped to the first day of the first cycle rather than producing a
     * cycle 0: a track started today has no yesterday, and inventing one would let a caller write a row
     * for a cycle that never existed.
     */
    fun cycleAndDay(anchor: LocalDate, date: LocalDate): Pair<Int, Int> {
        val elapsed = max(0L, ChronoUnit.DAYS.between(anchor, date)).toInt()
        return (elapsed / TRACK_DAYS + 1) to (elapsed % TRACK_DAYS + 1)
    }

    /**
     * The 1-based day of the track [date] falls on, **capped at [TRACK_DAYS]** rather than rolling over.
     *
     * This is the nutrition phase's reading: a meal cycle planned after the track's first 56 days keeps
     * planning from day 56 instead of restarting at day 1, which is the behaviour the shipped nutrition
     * planner already had.
     */
    fun cappedDay(anchor: LocalDate, date: LocalDate): Int {
        val elapsed = ChronoUnit.DAYS.between(anchor, date).toInt()
        return min(TRACK_DAYS, max(1, elapsed + 1))
    }
}
