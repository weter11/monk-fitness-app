package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import java.time.DayOfWeek

/**
 * Product-owned, immutable definition of the built-in Standard Program.
 *
 * This value contains no persistence identities and is deliberately independent of generators,
 * planners, adaptive policy, and the legacy runtime. A bootstrap maps it to target Program rows.
 */
object StandardProgramDefinition {
    val mode: ProgramMode = ProgramMode.MANUAL
    val duration: ProgramDuration = ProgramDuration.FixedDays(30)
    val schedule: ProgramSchedule = ProgramSchedule.FixedWeekdays(
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
    )

    val days: List<StandardProgramDay> = listOf(
        StandardProgramDay(
            type = ProgramDayType.TRAINING,
            name = "Full Body A",
            exercises = listOf(
                exercise("pushups", RepPrescription(listOf(8, 8, 8))),
                exercise("squats", RepPrescription(listOf(12, 12, 12))),
                exercise("glute_bridge", RepPrescription(listOf(12, 12, 12))),
                exercise("bird_dog", RepPrescription(listOf(8, 8, 8))),
                exercise("plank", TimePrescription(listOf(30, 30, 30)))
            )
        ),
        StandardProgramDay(
            type = ProgramDayType.POSTURE_MOBILITY,
            name = "Mobility + posture",
            exercises = listOf(
                exercise("cat_cow", RepPrescription(listOf(10, 10))),
                exercise("bird_dog", RepPrescription(listOf(8, 8))),
                exercise("world_greatest_stretch", RepPrescription(listOf(4, 4))),
                exercise("pelvic_tilt", RepPrescription(listOf(12, 12))),
                exercise("hip_flexor_stretch", TimePrescription(listOf(30, 30)))
            )
        ),
        StandardProgramDay(
            type = ProgramDayType.TRAINING,
            name = "Full Body B",
            exercises = listOf(
                exercise("pushups_military", RepPrescription(listOf(6, 6, 6))),
                exercise("lunges", RepPrescription(listOf(8, 8, 8))),
                exercise("wall_sit", TimePrescription(listOf(30, 30, 30))),
                exercise("dead_bug", RepPrescription(listOf(10, 10, 10))),
                exercise("side_plank", TimePrescription(listOf(30, 30)))
            )
        ),
        StandardProgramDay(
            type = ProgramDayType.TRAINING,
            name = "Full Body C",
            exercises = listOf(
                exercise("pushups_wide", RepPrescription(listOf(5, 5, 5))),
                exercise("squats_sumo", RepPrescription(listOf(10, 10, 10))),
                exercise("cossack_squat", RepPrescription(listOf(6, 6, 6))),
                exercise("bird_dog_reps", RepPrescription(listOf(8, 8, 8))),
                exercise("cobra_stretch", TimePrescription(listOf(30, 30)))
            )
        )
    )

    private fun exercise(exerciseId: String, prescription: Prescription) =
        StandardProgramExercise(exerciseId, prescription)
}

data class StandardProgramDay(
    val type: ProgramDayType,
    val name: String,
    val exercises: List<StandardProgramExercise>
)

data class StandardProgramExercise(
    val exerciseId: String,
    val prescription: Prescription
)
