package com.monkfitness.app.animation

data class Bone(
    val parentJoint: Joint,
    val childJoint: Joint,
    val thickness: Float,
    val colorMultiplier: Float = 1.0f
)
