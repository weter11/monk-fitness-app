package com.monkfitness.app

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.domain.usecase.TOTAL_PROGRAM_DAYS
import com.monkfitness.app.domain.usecase.calculateProgramDay
import com.monkfitness.app.domain.usecase.resolveCycleAndDay
import com.monkfitness.app.domain.usecase.synchronizeProgramStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgramCalendarTest {

    @Test
    fun calculateProgramDayUsesCalendarOffsetAndCapsAt56() {
        val start = LocalDate.of(2026, 1, 1)

        assertEquals(1, calculateProgramDay(start, start))
        assertEquals(2, calculateProgramDay(start, start.plusDays(1)))
        assertEquals(56, calculateProgramDay(start, start.plusDays(90)))
    }

    @Test
    fun synchronizeProgramStatesMarksMissedWorkoutDaysButNotRestDays() {
        val existing = listOf(
            ProgramDayState(programDay = 1, isWorkoutDay = true, isCompleted = true, isMissed = false, completedAt = 1L),
            ProgramDayState(programDay = 4, isWorkoutDay = false, isCompleted = false, isMissed = false, completedAt = null)
        )

        val synchronized = synchronizeProgramStates(existing, currentProgramDay = 5) { day ->
            when (day) {
                4 -> WorkoutType.REST
                else -> WorkoutType.STRENGTH_A
            }
        }

        assertFalse(synchronized.first { it.programDay == 1 }.isMissed)
        assertTrue(synchronized.first { it.programDay == 2 }.isMissed)
        assertTrue(synchronized.first { it.programDay == 3 }.isMissed)
        assertFalse(synchronized.first { it.programDay == 4 }.isMissed)
        assertFalse(synchronized.first { it.programDay == 5 }.isMissed)
    }

    // ---- C1/C2 cycle rollover contract ----

    @Test
    fun resolveCycleAndDayReturnsFirstCycleWithinFirst56Days() {
        val start = LocalDate.of(2026, 1, 1)

        assertEquals(1 to 1, resolveCycleAndDay(start, start))
        assertEquals(1 to 30, resolveCycleAndDay(start, start.plusDays(29)))
        assertEquals(1 to TOTAL_PROGRAM_DAYS, resolveCycleAndDay(start, start.plusDays(TOTAL_PROGRAM_DAYS - 1L)))
    }

    @Test
    fun resolveCycleAndDayRollsOverToNextCycleAfter56Days() {
        val start = LocalDate.of(2026, 1, 1)
        // Day 57 absolute = day 1 of cycle 2; weekly template stays aligned because
        // the cycle boundary falls on a multiple of 7.
        assertEquals(2 to 1, resolveCycleAndDay(start, start.plusDays(TOTAL_PROGRAM_DAYS.toLong())))
        assertEquals(2 to 8, resolveCycleAndDay(start, start.plusDays(TOTAL_PROGRAM_DAYS + 7L)))
        assertEquals(3 to 1, resolveCycleAndDay(start, start.plusDays(2 * TOTAL_PROGRAM_DAYS.toLong())))
    }
}
