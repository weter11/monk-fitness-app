package com.monkfitness.app.data.model

data class Workout(
    val id: Int,
    val type: WorkoutType,
    val exercises: List<Exercise>
)

enum class WorkoutType {
    STRENGTH_A,
    STRENGTH_B,
    MOBILITY,
    FUNCTIONAL,
    REST
}
