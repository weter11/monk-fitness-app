package com.monkfitness.app.data.model

data class ProgramStatistics(
    val totalWorkoutsCompleted: Int,
    val totalMissed: Int,
    val totalSets: Int,
    val totalReps: Int,
    val totalTimerSeconds: Int,
    val totalExercisesCompleted: Int,
    val totalPersonalRecords: Int,
    val completionPercentage: Int
)

data class ProgramStatisticsSnapshot(
    val totalWorkoutsCompleted: Int,
    val totalMissed: Int,
    val totalSets: Int,
    val totalReps: Int,
    val totalTimerSeconds: Int,
    val totalExercisesCompleted: Int,
    val totalWorkoutDays: Int
)
