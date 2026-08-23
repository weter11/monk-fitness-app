package com.monkfitness.app.data.model

import androidx.room.Entity

@Entity(
    tableName = "user_progress",
    primaryKeys = ["cycleNumber", "day"],
)
data class UserProgress(
    val cycleNumber: Int = 1,
    val day: Int,
    val isCompleted: Boolean = false,
    val completionDate: Long = 0L,
    val workoutType: String = ""
)

