package com.monkfitness.app.domain.track

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The **daily-track calendar**: a 56-day repeating cadence, owned by the retained daily tracks and by
 * nothing else.
 *
 * Two features of this app are daily practices that run on a 56-day rhythm and are **not** Programs:
 *
 *  * the optional **posture / mobility** track, whose completed sessions are stored one per track day;
 *  * the **nutrition** planner's phase, which offsets the meal rotation and the training/rest pattern
 *    of a generated meal cycle.
 *
 * Before the Program System was the only program architecture, both read their day out of the shipped
 * 56-day program's calendar (`resolveCycleAndDay` / `calculateProgramDay`). §30 step 15 retired that
 * program — its cycle, its day states and its tables — but the two daily tracks are unrelated global
 * features (§4) and keep the rhythm they always had. What moved is *ownership*: the arithmetic below
 * belongs to the tracks, and the retired program's vocabulary (`cycleNumber`, `programDay` as
 * ownership) does not appear in it at all.
 *
 * The arithmetic is unchanged, deliberately: day 1 is [anchor] itself, day 56 is `anchor + 55`, and day
 * 1 of the next track cycle is `anchor + 56`. Porting it verbatim is what makes the migration of the
 * tracked rows a rename rather than a reinterpretation.
 *
 * It is a **pure calendar**: no clock is read here, no store is touched, and a caller decides which
 * date "today" is. A track's own start date is handed in as a value.
 */
object TrackCalendar {

    /** The length of one track cycle, in days. */
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
