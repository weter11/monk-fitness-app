package com.monkfitness.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "set_log")
data class SetLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val repsCompleted: Int,
    val durationSeconds: Int,
    val timestamp: Long,
    val sessionDate: String
)

@Immutable
data class VolumeHistoryPoint(
    val sessionDate: String,
    val totalReps: Int
)

@Immutable
data class WorkoutFrequencyPoint(
    val weekLabel: String,
    val sessionCount: Int
)
