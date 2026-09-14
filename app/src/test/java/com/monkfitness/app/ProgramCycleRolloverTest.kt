package com.monkfitness.app

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.domain.usecase.TOTAL_PROGRAM_DAYS
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.domain.usecase.resolveCycleAndDay
import com.monkfitness.app.domain.usecase.shouldOfferCycleCompletion
import com.monkfitness.app.domain.usecase.synchronizeProgramStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The cycle-boundary contract, exercised along the path the app walks:
 *
 * 1. Home reads `MainViewModel.currentProgramDay` -> `Start Workout` generates the workout for
 *    that day (`MainViewModel.getWorkoutForDay` -> [WorkoutGenerator]),
 * 2. completing day 56 of cycle N offers the "Program Completed" dialog
 *    (`MainViewModel.showProgramSummary` -> [shouldOfferCycleCompletion]),
 * 3. dismissing it runs the rollover (`MainViewModel.dismissProgramSummary`: seed cycle N+1's
 *    grid, stamp `program_cycle_number = N+1`),
 * 4. the sync tick rebuilds the active cycle's grid
 *    (`MainViewModel.syncProgramDayStates` -> [synchronizeProgramStates]).
 *
 * `MainViewModel` is an Android class and this project has no Robolectric harness, so the
 * boundary rules the ViewModel delegates to — [resolveCycleAndDay] for the position,
 * [shouldOfferCycleCompletion] for the rollover gate, [synchronizeProgramStates] for the grid —
 * are what is under test here.
 *
 * Boundary invariant under test: **one programme day lives on exactly one calendar date**.
 * Cycle N day 56 is the last date of cycle N; cycle N+1 day 1 is the next date and no other; the
 * stamped cycle number never moves the displayed day (it only gates the dialog and pre-seeds the
 * grid). An earlier revision started cycle N+1 the moment the rollover ran, which put day 1 on
 * two consecutive dates (the rollover date and its own) and demoted the real day 1 to a
 * no-credit repeat — `dayOneLivesOnExactlyOneCalendarDatePerCycle` guards against that.
 */
class ProgramCycleRolloverTest {

    private val generator = WorkoutGenerator()
    private val startDate = LocalDate.of(2026, 1, 1)
    private val workoutTypeForDay: (Int) -> WorkoutType = { day -> generator.getWorkoutType(day) }

    /** 1..3 covers the first rollover, cycle 2 -> 3 and cycle 3 -> 4. */
    private val cyclesUnderTest = listOf(1, 2, 3)

    /** The last calendar date of [cycle] — the date its completion dialog appears on. */
    private fun rolloverDate(cycle: Int): LocalDate =
        startDate.plusDays(TOTAL_PROGRAM_DAYS.toLong() * cycle - 1)

    /** The one and only calendar date of cycle [cycle] day 1. */
    private fun firstDateOfCycle(cycle: Int): LocalDate =
        startDate.plusDays(TOTAL_PROGRAM_DAYS.toLong() * (cycle - 1))

    // ---- one calendar date == one programme day ------------------------------------------------

    @Test
    fun theRolloverDateIsStillTheFinishedCyclesDay56() {
        cyclesUnderTest.forEach { finished ->
            assertEquals(
                "the date the rollover runs on is cycle $finished's own last day",
                finished to TOTAL_PROGRAM_DAYS,
                resolveCycleAndDay(startDate, rolloverDate(finished))
            )
        }
    }

    @Test
    fun dayOneLivesOnExactlyOneCalendarDatePerCycle() {
        cyclesUnderTest.forEach { cycle ->
            assertEquals(
                "cycle $cycle day 1 is the date its cycle starts",
                cycle to 1,
                resolveCycleAndDay(startDate, firstDateOfCycle(cycle))
            )
            if (cycle > 1) {
                assertEquals(
                    "and the date before it is the previous cycle's last day",
                    cycle - 1 to TOTAL_PROGRAM_DAYS,
                    resolveCycleAndDay(startDate, firstDateOfCycle(cycle).minusDays(1))
                )
            }
        }

        // bijection over three whole cycles: every date has one (cycle, day), every (cycle, day)
        // has one date — nothing is offered twice, nothing is skipped
        val dates = (0 until 3 * TOTAL_PROGRAM_DAYS).map { startDate.plusDays(it.toLong()) }
        val positions = dates.map { resolveCycleAndDay(startDate, it) }
        assertEquals(dates.size, positions.distinct().size)
        assertEquals(
            "the programme days run 1..$TOTAL_PROGRAM_DAYS within every cycle",
            (1..3).flatMap { cycle -> (1..TOTAL_PROGRAM_DAYS).map { cycle to it } },
            positions
        )
    }

    @Test
    fun startWorkoutOnTheNewCyclesFirstDateOffersARealWorkout() {
        (2..4).forEach { cycle ->
            val (resolvedCycle, day) = resolveCycleAndDay(startDate, firstDateOfCycle(cycle))
            assertEquals(cycle to 1, resolvedCycle to day)

            val workout = generator.generateWorkout(day)
            assertNotEquals(
                "Start Workout on cycle $cycle day 1 must not open the recovery screen",
                WorkoutType.REST,
                workout.type
            )
            assertFalse("Start Workout on cycle $cycle day 1 must have exercises", workout.exercises.isEmpty())
            assertEquals(
                "exercise ids must stay unique (they are LazyColumn keys)",
                workout.exercises.size,
                workout.exercises.map { it.id }.distinct().size
            )
        }
    }

    // ---- the completion gate -------------------------------------------------------------------

    @Test
    fun theCompletionGateFiresOncePerCycle() {
        cyclesUnderTest.forEach { cycle ->
            assertTrue(
                "cycle $cycle's completed last day offers the dialog",
                shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, cycle, storedCycle = cycle)
            )
            assertFalse(
                "and the rollover's own stamp closes it — the dialog must not re-fire",
                shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, cycle, storedCycle = cycle + 1)
            )
        }
    }

    @Test
    fun aStaleStampedCycleNeverSwallowsACompletedCycle() {
        // a counter left behind by a boundary crossing (or an older build): the gate stays open,
        // so the finished cycle is announced and the next stamp repairs the counter
        assertTrue(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, activeCycle = 3, storedCycle = 1))
        assertTrue(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, activeCycle = 3, storedCycle = 2))
        // a legacy over-stamped counter behaves the same way
        assertTrue(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, activeCycle = 3, storedCycle = 7))
    }

    @Test
    fun theGateOnlyOpensOnACompletedLastDay() {
        assertFalse(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, false, activeCycle = 1, storedCycle = 1))
        assertFalse(shouldOfferCycleCompletion(1, true, activeCycle = 1, storedCycle = 1))
        assertFalse(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS - 1, true, activeCycle = 1, storedCycle = 1))
        assertTrue(shouldOfferCycleCompletion(TOTAL_PROGRAM_DAYS, true, activeCycle = 2, storedCycle = 2))
    }

    // ---- the grid the rollover seeds -----------------------------------------------------------

    @Test
    fun theRolloverSeedsTheNewCycleGridCleanAndStampedForThatCycle() {
        cyclesUnderTest.forEach { finished ->
            val nextCycle = finished + 1
            // the rollover runs on the finished cycle's last date and seeds against the new
            // cycle's own day 1 — the day that cycle actually starts on
            val (activeCycle, _) = resolveCycleAndDay(startDate, rolloverDate(finished))
            assertEquals(finished, activeCycle)

            val seeded = synchronizeProgramStates(
                existing = emptyList(),
                currentProgramDay = 1,
                cycleNumber = nextCycle,
                workoutTypeForDay = workoutTypeForDay
            )

            assertEquals(TOTAL_PROGRAM_DAYS, seeded.size)
            assertTrue(
                "every seeded row must belong to cycle $nextCycle, not to cycle 1",
                seeded.all { it.cycleNumber == nextCycle }
            )
            assertEquals(
                "a cycle that has not started yet must not open with missed days",
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
        val today = firstDateOfCycle(2)
        val (cycle, day) = resolveCycleAndDay(startDate, today)
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
}
