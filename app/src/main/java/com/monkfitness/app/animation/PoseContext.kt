package com.monkfitness.app.animation

data class PoseContext(
    val progress: Float,
    val side: Side,
    val definition: SkeletonDefinition,
    val deltaTime: Float = 0f,
    val cycleDuration: Float = 3000f,
    val playbackSpeed: Float = 1.0f,
    val mirrored: Boolean = false,
    val phase: Float = 0f,
    val loopIndex: Int = 0
)
