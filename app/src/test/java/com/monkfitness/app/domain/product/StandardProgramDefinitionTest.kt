package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class StandardProgramDefinitionTest {

    @Test
    fun `standard definition has the product-owned shape`() {
        assertEquals(ProgramMode.MANUAL, StandardProgramDefinition.mode)
        assertEquals(ProgramDuration.FixedDays(30), StandardProgramDefinition.duration)
        assertEquals(
            ProgramSchedule.FixedWeekdays(setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.SATURDAY
            )),
            StandardProgramDefinition.schedule
        )
        assertEquals(
            listOf(ProgramDayType.TRAINING, ProgramDayType.POSTURE_MOBILITY, ProgramDayType.TRAINING, ProgramDayType.TRAINING),
            StandardProgramDefinition.days.map { it.type }
        )
        assertEquals(20, StandardProgramDefinition.days.sumOf { it.exercises.size })
    }

    @Test
    fun `standard definition preserves exact exercise order and prescriptions`() {
        assertEquals(
            listOf("pushups", "squats", "glute_bridge", "bird_dog", "plank"),
            StandardProgramDefinition.days[0].exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf("cat_cow", "bird_dog", "world_greatest_stretch", "pelvic_tilt", "hip_flexor_stretch"),
            StandardProgramDefinition.days[1].exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf("pushups_military", "lunges", "wall_sit", "dead_bug", "side_plank"),
            StandardProgramDefinition.days[2].exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf("pushups_wide", "squats_sumo", "cossack_squat", "bird_dog_reps", "cobra_stretch"),
            StandardProgramDefinition.days[3].exercises.map { it.exerciseId }
        )
        assertEquals(RepPrescription(listOf(8, 8, 8)), StandardProgramDefinition.days[0].exercises[0].prescription)
        assertEquals(TimePrescription(listOf(30, 30, 30)), StandardProgramDefinition.days[0].exercises[4].prescription)
        assertEquals(RepPrescription(listOf(10, 10)), StandardProgramDefinition.days[1].exercises[0].prescription)
        assertEquals(TimePrescription(listOf(30, 30)), StandardProgramDefinition.days[3].exercises[4].prescription)
    }
}
