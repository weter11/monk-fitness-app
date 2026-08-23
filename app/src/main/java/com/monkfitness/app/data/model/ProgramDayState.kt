package com.monkfitness.app.data.model

import androidx.room.Entity

@Entity(
    tableName = "program_day_state",
    primaryKeys = ["cycleNumber", "programDay"],
)
data class ProgramDayState(
    val cycleNumber: Int = 1,
    val programDay: Int,
    val isWorkoutDay: Boolean = false,
    val isCompleted: Boolean = false,
    val isMissed: Boolean = false,
    val completedAt: Long? = null
)

