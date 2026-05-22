package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_progress")
data class UserProgress(
    @PrimaryKey val day: Int,
    val isCompleted: Boolean,
    val completionDate: Long,
    val workoutType: String
)
