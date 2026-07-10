package com.monkfitness.app.animation

data class AnimationState(
    val progress: Float,
    val side: Side,
    val phase: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    val loopIndex: Int = 0,
    val deltaTime: Float = 0f,
    val mirrored: Boolean = false
)
