package com.monkfitness.app.animation

data class SkeletonDefinition(
    // Anatomical Body Lengths
    val torsoLength: Float,
    val neckLength: Float,

    // Legs
    val thighLength: Float,
    val shinLength: Float,
    val footLength: Float,

    // Arms
    val upperArmLength: Float,
    val forearmLength: Float,

    // Widths
    val shoulderWidth: Float,
    val hipWidth: Float
) {
    companion object {
        val DEFAULT_ADULT = SkeletonDefinition(
            torsoLength = 120f,
            neckLength = 18f,
            thighLength = 112f,
            shinLength = 98f,
            footLength = 35f,
            upperArmLength = 64f,
            forearmLength = 82f,
            shoulderWidth = 42f,
            hipWidth = 22f
        )
    }
}
