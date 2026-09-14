package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

const val TOTAL_PROGRAM_DAYS = 56

/**
 * Resolves the (cycleNumber, day) pair for a given calendar date.
 *
 * A cycle is [TOTAL_PROGRAM_DAYS] calendar days starting at [startDate].
 * Day 1 of the program is [startDate] itself; day 56 is start + 55 days;
 * day 1 of cycle N is start + (N-1)*56 days.
 */
fun resolveCycleAndDay(startDate: LocalDate, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
    val daysElapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate, today)
    val safeDaysElapsed = max(0L, daysElapsed).toInt()
    val cycleNumber = safeDaysElapsed / TOTAL_PROGRAM_DAYS + 1
    val day = safeDaysElapsed % TOTAL_PROGRAM_DAYS + 1
    return cycleNumber to day
}

fun calculateProgramDay(startDate: LocalDate, today: LocalDate = LocalDate.now()): Int {
    val daysElapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate, today).toInt()
    return min(TOTAL_PROGRAM_DAYS, max(1, daysElapsed + 1))
}

/**
 * Resolves the (cycleNumber, day) the app is actually ON: the calendar position of [today]
 * reconciled with the cycle the C2 rollover has stamped ([storedCycle]).
 *
 * - [storedCycle] == calendar cycle: the calendar's own day within that cycle.
 * - [storedCycle] is exactly one ahead of the calendar: the rollover has already stamped the
 *   next cycle, so the app is on **day 1 of that cycle**. Reporting [TOTAL_PROGRAM_DAYS] here
 *   offers the finished cycle's last day against the *new* cycle's grid — a phantom,
 *   uncompleted "day 56" that Start Workout can only answer with a recovery session, and whose
 *   completion re-fires the "program completed" rollover (cycle counter ratchet).
 * - [storedCycle] is behind the calendar (the app was closed across a boundary) or more than
 *   one cycle ahead (legacy over-stamped state): the calendar wins.
 */
fun resolveActiveCycleAndDay(
    storedCycle: Int,
    startDate: LocalDate,
    today: LocalDate = LocalDate.now()
): Pair<Int, Int> {
    val (calendarCycle, calendarDay) = resolveCycleAndDay(startDate, today)
    val activeCycle = if (storedCycle > calendarCycle + 1) calendarCycle else max(calendarCycle, storedCycle)
    val day = if (activeCycle > calendarCycle) 1 else calendarDay
    return activeCycle to day
}

/**
 * Rebuilds the 1..56 grid for **one** cycle: every returned row carries [cycleNumber], so a
 * caller that upserts the result (the sync tick, the cycle rollover, the legacy backfill) can
 * only ever write the cycle it is building for.
 *
 * [cycleNumber] defaults to the cycle the [existing] rows already belong to, so distinguishing
 * the active cycle's rows from another cycle's is impossible to forget; callers that build a
 * grid from scratch (rollover, backfill) pass the cycle explicitly.
 */
fun synchronizeProgramStates(
    existing: List<ProgramDayState>,
    currentProgramDay: Int,
    cycleNumber: Int = existing.firstOrNull()?.cycleNumber ?: 1,
    workoutTypeForDay: (Int) -> WorkoutType
): List<ProgramDayState> {
    val byDay = existing.associateBy { it.programDay }
    return (1..TOTAL_PROGRAM_DAYS).map { day ->
        val isWorkoutDay = workoutTypeForDay(day) != WorkoutType.REST
        val current = byDay[day]
        val isMissed = when {
            day >= currentProgramDay -> current?.isMissed ?: false
            !isWorkoutDay -> false
            current?.isCompleted == true -> false
            else -> true
        }
        ProgramDayState(
            cycleNumber = cycleNumber,
            programDay = day,
            isWorkoutDay = isWorkoutDay,
            isCompleted = current?.isCompleted ?: false,
            isMissed = isMissed,
            completedAt = current?.completedAt
        )
    }
}
