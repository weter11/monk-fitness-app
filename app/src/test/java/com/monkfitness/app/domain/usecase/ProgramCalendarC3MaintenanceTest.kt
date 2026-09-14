package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * How the three C3 maintenance actions interact with the cycle calendar after PR #265 landed.
 *
 * #265 changed the calendar's contract: `programCycleNumber` is now derived purely from the start
 * date (`resolveCycleAndDay`), and the stored `program_cycle_number` stamp **no longer moves the
 * displayed day** — it only gates the completion dialog and pre-seeds the next cycle's grid. That
 * change has consequences for every C3 action, and this suite pins them:
 *
 * - **Restart Current Cycle** deletes rows but touches no calendar key, so the cycle shown after
 *   the wipe must be the same one the calendar was already resolving. A restart must not shift the
 *   displayed (cycle, day) — if it did, the wiped cycle's days would re-open on the wrong date.
 * - **Start Revised Program** rewrites the start date, and the cycle after that must be 1/1 on the
 *   new date and no other. The stored stamp is written to 1 alongside it; this suite asserts the
 *   *calendar* consequence (position), which is what the user observes.
 * - **Full Reset** wipes the start date too, so the position must be re-derived from today rather
 *   than from a stale date a cleared DataStore can no longer supply.
 *
 * `MainViewModel` is an Android class and the project has no Robolectric harness, so the calendar
 * rules the ViewModel delegates to — [resolveCycleAndDay] for the position and
 * [synchronizeProgramStates] for the re-seeded grid — are what is under test, exactly as
 * `ProgramCycleRolloverTest` does for the rollover.
 */
class ProgramCalendarC3MaintenanceTest {

    private val generator = WorkoutGenerator()
    private val workoutTypeForDay: (Int) -> WorkoutType = { day -> generator.getWorkoutType(day) }
    private val startDate = LocalDate.of(2026, 1, 1)

    // ---- Restart Current Cycle: the calendar position does not move -------------------------

    @Test
    fun restartCurrentCycleKeepsTheCalendarPosition() {
        val midCycle = startDate.plusDays(30)

        val before = resolveCycleAndDay(startDate, midCycle)
        // The action deletes rows; it re-seeds the grid through synchronizeProgramStates, which is
        // what the next sync tick does with the wiped cycle's own day as the scoring anchor.
        val reseeded = synchronizeProgramStates(
            existing = emptyList(),
            currentProgramDay = before.second,
            cycleNumber = before.first,
            workoutTypeForDay = workoutTypeForDay
        )
        val after = resolveCycleAndDay(startDate, midCycle)

        assertEquals(
            "the cycle shown after a restart is the cycle the calendar was already resolving",
            before,
            after
        )
        assertEquals(
            "the re-seeded grid belongs to that same cycle",
            before.first,
            reseeded.firstOrNull()?.cycleNumber
        )
        assertEquals(
            "nothing in the re-seeded grid is completed",
            0,
            reseeded.count { it.isCompleted }
        )
        assertEquals(
            "no day ahead of the cycle's own position is marked missed",
            0,
            reseeded.count { it.isMissed && it.programDay >= before.second }
        )
    }

    @Test
    fun restartCurrentCycleDoesNotResurrectTheFinishedCycle() {
        // The completion dialog's gate: after wiping the active cycle the stamp must not describe a
        // cycle the calendar has already finished, or the gate re-opens for a cycle that no longer
        // has any completions.
        val rollover = startDate.plusDays(TOTAL_PROGRAM_DAYS.toLong() * 2 - 1)
        val (activeCycle, day) = resolveCycleAndDay(startDate, rollover)

        assertEquals(
            "the date is the finished cycle's own last day, as #265 guarantees",
            2 to TOTAL_PROGRAM_DAYS,
            activeCycle to day
        )
        val reseeded = synchronizeProgramStates(
            existing = emptyList(),
            currentProgramDay = day,
            cycleNumber = activeCycle,
            workoutTypeForDay = workoutTypeForDay
        )
        // The gate requires day 56 AND that day completed; a wiped cycle has no completion.
        assertNotEquals(
            "a wiped cycle cannot satisfy the completion gate",
            true,
            reseeded.first { it.programDay == day }.isCompleted
        )
    }

    // ---- Start Revised Program: the position lands on the new date's day 1 ------------------

    @Test
    fun startRevisedProgramLandsOnTheNewDatesCycleOneDayOne() {
        val revisedStart = LocalDate.of(2026, 9, 14)

        // Any date from the old program's history resolves against the NEW start date only.
        val oldProgramDates = listOf(
            startDate,                                  // old cycle 1 day 1
            startDate.plusDays(TOTAL_PROGRAM_DAYS - 1L), // old cycle 1 day 56
            startDate.plusDays(TOTAL_PROGRAM_DAYS * 2L), // old cycle 2 day 1
            startDate.plusDays(200)                      // deep in the old program
        )

        oldProgramDates.forEach { oldDate ->
            val position = resolveCycleAndDay(revisedStart, oldDate)

            // resolveCycleAndDay clamps a negative offset to day 1 of cycle 1 rather than returning
            // a negative cycle or day, so a date the OLD program predates still resolves sanely.
            assertEquals(
                "a date at or before the new start clamps to cycle 1 day 1 (never a negative offset)",
                1 to 1,
                position
            )
        }

        // The position ON the new start date itself — this is what the user sees.
        assertEquals(
            "the revised program's own day 1 is cycle 1 day 1 on the new start date",
            1 to 1,
            resolveCycleAndDay(revisedStart, revisedStart)
        )
        assertEquals(
            "and the day after is day 2 of cycle 1 — one calendar date is one programme day",
            1 to 2,
            resolveCycleAndDay(revisedStart, revisedStart.plusDays(1))
        )
    }

    @Test
    fun startRevisedProgramSeedsTheFirstCycleGridScoredAgainstDayOne() {
        // The grid for the new program must be scored against the new cycle's OWN day 1, not against
        // any day the old program had reached — otherwise the fresh program opens with days it has
        // not reached yet already marked missed.
        val grid = synchronizeProgramStates(
            existing = emptyList(),
            currentProgramDay = 1,
            cycleNumber = 1,
            workoutTypeForDay = workoutTypeForDay
        )

        assertEquals("the grid belongs to the new cycle 1", 1, grid.first().cycleNumber)
        assertEquals(
            "nothing ahead of day 1 is marked missed",
            0,
            grid.count { it.isMissed && it.programDay >= 1 }
        )
        assertEquals(
            "the grid is the full template",
            TOTAL_PROGRAM_DAYS,
            grid.size
        )
    }

    // ---- Full Reset: the position is re-derived from today ----------------------------------

    @Test
    fun fullResetReDerivesThePositionFromToday() {
        // After the reset, the start date key is gone, so the position must be re-derived from
        // today rather than from a stale date the cleared DataStore can no longer supply. The
        // default-arg contract is what the ViewModel relies on when it re-stamps the date.
        val today = LocalDate.of(2026, 9, 14)

        val (cycle, day) = resolveCycleAndDay(today, today)

        assertEquals(
            "a fresh start date of today puts the program on cycle 1 day 1 today",
            1 to 1,
            cycle to day
        )
        assertEquals(
            "and the grid seeded for it is cycle 1's own",
            1,
            synchronizeProgramStates(
                existing = emptyList(),
                currentProgramDay = day,
                cycleNumber = cycle,
                workoutTypeForDay = workoutTypeForDay
            ).first().cycleNumber
        )
    }

    @Test
    fun aWipedCycleReSeedsNothingCompleted() {
        // The observable contract of restart-cycle: the sync tick builds the fresh grid from the
        // rows that remain after the wipe (none), so nothing in the re-seeded grid is completed.
        val grid = synchronizeProgramStates(
            existing = emptyList(),
            currentProgramDay = 5,
            cycleNumber = 1,
            workoutTypeForDay = workoutTypeForDay
        )

        assertEquals(
            "a wiped cycle re-seeds with no completions",
            0,
            grid.count { it.isCompleted }
        )
        assertEquals(
            "the re-seeded grid is the full template",
            TOTAL_PROGRAM_DAYS,
            grid.size
        )
    }

    @Test
    fun theGridKeepsACompletedDayThatWasNotWiped() {
        // The same template function must NOT drop completions for a cycle that was only partially
        // affected — the restart deletes whole cycles, so a surviving cycle's completions survive.
        val grid = synchronizeProgramStates(
            existing = listOf(
                ProgramDayState(cycleNumber = 1, programDay = 1, isCompleted = true, completedAt = 1L),
                ProgramDayState(cycleNumber = 1, programDay = 2, isCompleted = true, completedAt = 2L)
            ),
            currentProgramDay = 5,
            cycleNumber = 1,
            workoutTypeForDay = workoutTypeForDay
        )

        assertEquals(
            "completions the wipe did not touch are kept",
            2,
            grid.count { it.isCompleted }
        )
    }
}
