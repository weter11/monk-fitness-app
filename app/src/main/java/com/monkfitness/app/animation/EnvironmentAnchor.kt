package com.monkfitness.app.animation

enum class EnvironmentAnchorType {
    FLOOR,
    BAR,
    WALL,
    BENCH,
    PARALLEL_BARS,
    RINGS
}

data class EnvironmentAnchor(
    val id: String,
    val type: EnvironmentAnchorType,
    val worldPosition: Vector3,
    val orientation: JointRotation? = null
)
