package com.monkfitness.app.data.model

import androidx.room.Entity

@Entity(
    tableName = "posture_session_progress",
    primaryKeys = ["cycleNumber", "day"],
)
data class PostureSessionProgress(
    val cycleNumber: Int = 1,
    val day: Int,
    val isCompleted: Boolean = false,
    val completionDate: Long = 0L,
    val focusArea: String = ""
)

