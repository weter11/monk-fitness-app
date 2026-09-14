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
 * The "Program Completed" gate: the dialog (and the rollover it triggers) is offered at the end
 * of a cycle until `dismissProgramSummary` has stamped the cycle it rolled into ([storedCycle]
 * == [activeCycle] + 1).
 *
 * The stamp is what closes the gate, so the dialog fires exactly once per cycle. Any other
 * stamped value leaves it open — a counter left behind by a boundary crossing, or one written by
 * an older build — so a completed cycle is never silently skipped, and the next stamp repairs
 * the counter.
 *
 * [activeCycle] is the cycle the calendar puts the app in ([resolveCycleAndDay]); the stamp never
 * moves the displayed day. Holding the finished cycle's last day (instead of showing the next
 * cycle's day 1) is what keeps one programme day on exactly one calendar date: day 56 of cycle N
 * and day 1 of cycle N+1 stay on their own dates, and day 1 cannot be credited twice.
 */
fun shouldOfferCycleCompletion(
    programDay: Int,
    isDayCompleted: Boolean,
    activeCycle: Int,
    storedCycle: Int
): Boolean {
    return programDay == TOTAL_PROGRAM_DAYS && isDayCompleted && storedCycle != activeCycle + 1
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
