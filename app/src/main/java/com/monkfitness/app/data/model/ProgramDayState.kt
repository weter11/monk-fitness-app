package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "program_day_state")
data class ProgramDayState(
    @PrimaryKey
    val programDay: Int,
    val isWorkoutDay: Boolean,
    val isCompleted: Boolean,
    val isMissed: Boolean,
    val completedAt: Long?
)
