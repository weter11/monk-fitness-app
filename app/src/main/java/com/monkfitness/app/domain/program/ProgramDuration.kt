package com.monkfitness.app.domain.program

import java.time.DayOfWeek

/**
 * How long a revision runs: a fixed number of days, or indefinitely until the user stops (§20, §21).
 *
 * The two forms exist because the two programs genuinely differ in what progress means. A fixed
 * program has a total — `Active 12 of 30 days` is a true statement — while an indefinite one has
 * none, and reporting a fake `N / 30` progress for it is exactly the mistake the architecture
 * forbids. An indefinite program instead reports how long it has been active and how many sessions
 * it has completed.
 *
 * Nothing here schedules anything: the Scheduler owns how a duration turns into slots, including the
 * rolling horizon an indefinite program needs.
 */
sealed interface ProgramDuration {

    /** A program that runs for a known number of calendar days. */
    data class FixedDays(val days: Int) : ProgramDuration {

        init {
            require(days >= 1) { "a fixed program runs for at least one day, was $days" }
        }
    }

    /**
     * A program with no end date.
     *
     * It keeps a bounded planning horizon — the fixed amount of future planning the Scheduler
     * maintains and extends as the user advances — rather than a total length (§20).
     */
    data object Indefinite : ProgramDuration
}

/**
 * When the slots of a revision fall, in the two forms the architecture supports: fixed weekdays, and
 * a deterministic flexible frequency (§20).
 *
 * Fixed weekdays are the user's explicit weekly rhythm. Flexible frequency is a **deterministic**
 * target — the scheduler spreads that many sessions across the week by rule, never by chance, and
 * never by sliding the whole schedule after a missed workout.
 *
 * This type states the input only. Which weekdays a flexible frequency lands on, how a missed
 * opportunity affects the rest of the horizon, and how the horizon is extended are the Scheduler's
 * decisions and are not made here.
 */
sealed interface ProgramSchedule {

    /** The user's fixed weekdays, e.g. Monday/Wednesday/Friday. */
    data class FixedWeekdays(val weekdays: Set<DayOfWeek>) : ProgramSchedule {

        init {
            require(weekdays.isNotEmpty()) {
                "a fixed-weekday schedule names at least one weekday"
            }
        }
    }

    /** A deterministic number of sessions per week, without fixed weekdays. */
    data class FlexiblePerWeek(val sessionsPerWeek: Int) : ProgramSchedule {

        init {
            require(sessionsPerWeek in 1..7) {
                "sessionsPerWeek must be within 1..7, was $sessionsPerWeek"
            }
        }
    }
}
