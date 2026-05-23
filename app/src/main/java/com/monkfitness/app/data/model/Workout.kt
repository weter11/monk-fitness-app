package com.monkfitness.app.data.model

import com.monkfitness.app.R

data class Workout(
    val id: Int,
    val type: WorkoutType,
    val exercises: List<Exercise>
)

enum class WorkoutType(val nameRes: Int) {
    STRENGTH_A(R.string.wt_strength_a),
    STRENGTH_B(R.string.wt_strength_b),
    MOBILITY(R.string.wt_mobility),
    POSTURE_MOBILITY(R.string.wt_posture_mobility),
    FUNCTIONAL(R.string.wt_functional),
    REST(R.string.wt_rest)
}
