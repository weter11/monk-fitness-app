package com.monkfitness.app.animation

/**
 * EnvironmentAnchorType represents types of world objects.
 */
enum class EnvironmentAnchorType {
    FLOOR,
    BAR,
    WALL,
    BENCH,
    PARALLEL_BARS,
    RINGS
}

/**
 * EnvironmentAnchor describes fixed objects in the world for poses to attach/refer to.
 */
data class EnvironmentAnchor(
    val id: String,
    val type: EnvironmentAnchorType,
    val worldPosition: Vector3,
    val orientation: JointRotation? = null
)
