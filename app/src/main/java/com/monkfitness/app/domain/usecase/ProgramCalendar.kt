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

fun synchronizeProgramStates(
    existing: List<ProgramDayState>,
    currentProgramDay: Int,
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
            programDay = day,
            isWorkoutDay = isWorkoutDay,
            isCompleted = current?.isCompleted ?: false,
            isMissed = isMissed,
            completedAt = current?.completedAt
        )
    }
}
