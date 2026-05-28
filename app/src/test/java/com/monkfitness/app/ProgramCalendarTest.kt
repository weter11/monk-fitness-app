package com.monkfitness.app

import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.domain.usecase.calculateProgramDay
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
}
