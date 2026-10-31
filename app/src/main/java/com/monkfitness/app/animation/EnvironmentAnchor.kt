package com.monkfitness.app.animation

enum class EnvironmentAnchorType {
    FLOOR,
    BAR,
    WALL,
    BENCH,
    PARALLEL_BARS,
    RINGS
}

/** A fixed object in the world that a pose can attach to or refer to. */
data class EnvironmentAnchor(
    val id: String,
    val type: EnvironmentAnchorType,
    val worldPosition: Vector3,
    val orientation: JointRotation? = null
)
