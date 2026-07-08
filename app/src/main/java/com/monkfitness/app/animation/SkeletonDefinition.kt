package com.monkfitness.app.animation

data class SkeletonDefinition(
    // Body
    val torsoLength: Float,
    val neckLength: Float,
    val headRadius: Float,

    // Legs
    val thighLength: Float,
    val shinLength: Float,
    val footLength: Float,

    // Arms
    val upperArmLength: Float,
    val forearmLength: Float,

    // Widths
    val shoulderWidth: Float,
    val hipWidth: Float,

    // Rendering
    val torsoChestDepth: Float,
    val torsoHipDepth: Float,

    // Bone thickness
    val upperArmThickness: Float,
    val forearmThickness: Float,
    val thighThickness: Float,
    val shinThickness: Float,
    val neckThickness: Float
) {
    companion object {
        val DEFAULT_ADULT = SkeletonDefinition(
            torsoLength = 120f,
            neckLength = 18f,
            headRadius = 18f,
            thighLength = 112f,
            shinLength = 98f,
            footLength = 35f,
            upperArmLength = 64f,
            forearmLength = 82f,
            shoulderWidth = 42f,
            hipWidth = 22f,
            torsoChestDepth = 22f,
            torsoHipDepth = 12f,
            upperArmThickness = 16f,
            forearmThickness = 13f,
            thighThickness = 21f,
            shinThickness = 16f,
            neckThickness = 12f
        )
    }
}
