package com.monkfitness.app

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.domain.usecase.TOTAL_PROGRAM_DAYS
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.domain.usecase.resolveActiveCycleAndDay
import com.monkfitness.app.domain.usecase.resolveCycleAndDay
import com.monkfitness.app.domain.usecase.synchronizeProgramStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The cycle-boundary contract: **cycle N day 56 -> cycle N+1 day 1**, for every cycle (1->2,
 * 2->3, 3->4 ...), exercised along the path the app actually walks:
 *
 * 1. the user completes day 56 of cycle N -> `MainViewModel.showProgramSummary` fires,
 * 2. the "program completed" dialog is dismissed -> `MainViewModel.dismissProgramSummary`
 *    stamps `program_cycle_number = N+1` and seeds cycle N+1's grid,
 * 3. the Home screen offers `MainViewModel.currentProgramDay`,
 * 4. `Start Workout` generates the workout for that day
 *    (`MainViewModel.getWorkoutForDay` -> [WorkoutGenerator]),
 * 5. the sync tick rebuilds the active cycle's grid
 *    (`MainViewModel.syncProgramDayStates` -> [synchronizeProgramStates]).
 *
 * `MainViewModel` is an Android class and this project has no Robolectric harness, so the
 * boundary rule ([resolveActiveCycleAndDay]) and the grid rebuild ([synchronizeProgramStates])
 * are the production functions under test here; the ViewModel delegates to exactly these.
 */
class ProgramCycleRolloverTest {

    private val generator = WorkoutGenerator()
    private val startDate = LocalDate.of(2026, 1, 1)
    private val workoutTypeForDay: (Int) -> WorkoutType = { day -> generator.getWorkoutType(day) }

    /** The last calendar day of [cycle] — the day its completion dialog appears on. */
    private fun rolloverDay(cycle: Int): LocalDate =
        startDate.plusDays(TOTAL_PROGRAM_DAYS.toLong() * cycle - 1)

    /** The first calendar day of [cycle] as the calendar sees it. */
    private fun firstCalendarDayOf(cycle: Int): LocalDate =
        startDate.plusDays(TOTAL_PROGRAM_DAYS.toLong() * (cycle - 1))

    /** 1..3 covers the first rollover, cycle 2 -> 3 and cycle 3 -> 4. */
    private val cyclesUnderTest = listOf(1, 2, 3)

    // ---- the boundary position -----------------------------------------------------------------

    @Test
    fun rolloverLandsOnTheFirstDayOfTheNewCycle() {
        cyclesUnderTest.forEach { finished ->
            val day = rolloverDay(finished)

            // before the stamp: the finished cycle's own last day
            assertEquals(
                "cycle $finished on its own day 56 must stay on day 56",
                finished to TOTAL_PROGRAM_DAYS,
                resolveActiveCycleAndDay(storedCycle = finished, startDate = startDate, today = day)
            )

            // after the stamp: day 1 of the next cycle — NOT the finished cycle's day 56
            // re-offered against the new cycle's grid (the phantom that broke Start Workout)
            assertEquals(
                "the rollover must land on cycle ${finished + 1} day 1",
                finished + 1 to 1,
                resolveActiveCycleAndDay(storedCycle = finished + 1, startDate = startDate, today = day)
            )
        }
    }

    @Test
    fun theCalendarDayAfterTheRolloverIsAlsoDayOneOfTheNewCycle() {
        cyclesUnderTest.forEach { finished ->
            val next = firstCalendarDayOf(finished + 1)

            assertEquals(
                "the calendar itself agrees the next day is cycle ${finished + 1} day 1",
                finished + 1 to 1,
                resolveCycleAndDay(startDate, next)
            )
            assertEquals(
                "and the app is on the same day for the whole of it",
                finished + 1 to 1,
                resolveActiveCycleAndDay(storedCycle = finished + 1, startDate = startDate, today = next)
            )
        }
    }

    @Test
    fun completionGateIsClosedAfterTheRolloverSoTheRolloverCannotRatchet() {
        cyclesUnderTest.forEach { finished ->
            val (cycle, day) =
                resolveActiveCycleAndDay(storedCycle = finished + 1, startDate = startDate, today = rolloverDay(finished))

            // showProgramSummary == (currentProgramDay == TOTAL_PROGRAM_DAYS && completed)
            assertNotEquals(
                "cycle $cycle must not sit on day $TOTAL_PROGRAM_DAYS right after the rollover: " +
                    "completing that phantom day re-fires the rollover and pins the app",
                TOTAL_PROGRAM_DAYS,
                day
            )
        }
    }

    @Test
    fun startWorkoutAfterTheRolloverOffersARealWorkout() {
        cyclesUnderTest.forEach { finished ->
            val (cycle, day) =
                resolveActiveCycleAndDay(storedCycle = finished + 1, startDate = startDate, today = rolloverDay(finished))
            val workout = generator.generateWorkout(day)

            assertNotEquals(
                "Start Workout in cycle $cycle must not open the recovery screen",
                WorkoutType.REST,
                workout.type
            )
            assertFalse("Start Workout in cycle $cycle must have exercises", workout.exercises.isEmpty())
            assertEquals(
                "exercise ids must stay unique (they are LazyColumn keys)",
                workout.exercises.size,
                workout.exercises.map { it.id }.distinct().size
            )
        }
    }

    // ---- the grid the rollover seeds ----------------------------------------------------------

    @Test
    fun theRolloverSeedsTheNewCycleGridCleanAndStampedForThatCycle() {
        cyclesUnderTest.forEach { finished ->
            val nextCycle = finished + 1
            val (cycle, day) =
                resolveActiveCycleAndDay(storedCycle = nextCycle, startDate = startDate, today = rolloverDay(finished))

            val seeded = synchronizeProgramStates(
                existing = emptyList(),
                currentProgramDay = day,
                cycleNumber = cycle,
                workoutTypeForDay = workoutTypeForDay
            )

            assertEquals(TOTAL_PROGRAM_DAYS, seeded.size)
            assertTrue(
                "every seeded row must belong to cycle $nextCycle, not to cycle 1",
                seeded.all { it.cycleNumber == cycle }
            )
            assertEquals(
                "a cycle that starts today must not open with missed days",
                0,
                seeded.count { it.isMissed }
            )
            assertEquals(0, seeded.count { it.isCompleted })

            val firstDay = seeded.first { it.programDay == 1 }
            assertTrue(firstDay.isWorkoutDay)
            assertFalse(firstDay.isCompleted)
            assertFalse(firstDay.isMissed)
        }
    }

    @Test
    fun rebuildingTheActiveCycleGridNeverReStampsAnotherCycle() {
        // Cycle 2's grid as the DAO hands it back on the sync tick: day 1 already completed.
        val cycleTwoRows = (1..TOTAL_PROGRAM_DAYS).map { programDay ->
            ProgramDayState(
                cycleNumber = 2,
                programDay = programDay,
                isWorkoutDay = generator.getWorkoutType(programDay) != WorkoutType.REST,
                isCompleted = programDay == 1,
                isMissed = false,
                completedAt = if (programDay == 1) 1_000L else null
            )
        }

        val rebuilt = synchronizeProgramStates(
            existing = cycleTwoRows,
            currentProgramDay = 2,
            cycleNumber = 2,
            workoutTypeForDay = workoutTypeForDay
        )

        assertTrue(
            "the sync tick must write cycle 2's grid back onto cycle 2 — re-stamping it onto " +
                "cycle 1 overwrites the finished cycle's history",
            rebuilt.all { it.cycleNumber == 2 }
        )
        assertTrue(
            "the completed day keeps its completion inside its own cycle",
            rebuilt.first { it.programDay == 1 }.isCompleted
        )

        // and the cycle-1 path is unchanged: a cycle-1 rebuild stays on cycle 1
        val cycleOneRebuild = synchronizeProgramStates(
            existing = cycleTwoRows.map { it.copy(cycleNumber = 1) },
            currentProgramDay = 2,
            workoutTypeForDay = workoutTypeForDay
        )
        assertTrue(cycleOneRebuild.all { it.cycleNumber == 1 })
        assertTrue(cycleOneRebuild.first { it.programDay == 1 }.isCompleted)
    }

    @Test
    fun theBackfillSeedsTheCycleTheUserIsOnWithoutPhantomMisses() {
        // Legacy device: no grid for the cycle it is standing in (cycle 2, day 1).
        val today = firstCalendarDayOf(2)
        val (cycle, day) = resolveActiveCycleAndDay(storedCycle = 1, startDate = startDate, today = today)
        assertEquals(2 to 1, cycle to day)

        val backfilled = synchronizeProgramStates(
            existing = emptyList(),
            currentProgramDay = day,
            cycleNumber = cycle,
            workoutTypeForDay = workoutTypeForDay
        )

        assertTrue(backfilled.all { it.cycleNumber == 2 })
        assertEquals("backfilling cycle 2 must not pre-mark its days missed", 0, backfilled.count { it.isMissed })
        assertEquals(40, backfilled.count { it.isWorkoutDay })
        assertEquals(16, backfilled.count { !it.isWorkoutDay })
    }

    // ---- legacy states ------------------------------------------------------------------------

    @Test
    fun aStoredCycleBeyondTheRolloverWindowFallsBackToTheCalendar() {
        // Cycle 3, day 40 — mid-cycle, so there is no rollover window at all.
        val today = startDate.plusDays(2L * TOTAL_PROGRAM_DAYS + 39)
        assertEquals(3 to 40, resolveCycleAndDay(startDate, today))

        // the one-cycle-ahead window the rollover creates: day 1 of the stamped cycle
        assertEquals(
            4 to 1,
            resolveActiveCycleAndDay(storedCycle = 4, startDate = startDate, today = today)
        )
        // a legacy over-stamped counter (the pre-fix rollover ratchet): the calendar wins, so
        // the app resumes on its real day instead of freezing on a phantom cycle
        assertEquals(
            3 to 40,
            resolveActiveCycleAndDay(storedCycle = 7, startDate = startDate, today = today)
        )
        // and a counter left behind by a boundary crossing still tracks the calendar
        assertEquals(
            3 to 40,
            resolveActiveCycleAndDay(storedCycle = 2, startDate = startDate, today = today)
        )
    }
}
