package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "posture_session_progress")
data class PostureSessionProgress(
    @PrimaryKey val day: Int,
    val isCompleted: Boolean,
    val completionDate: Long,
    val focusArea: String
)
