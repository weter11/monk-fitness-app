package com.monkfitness.app.animation

import kotlin.math.PI

data class FootDefinition(
    val footLength: Float,
    val heelRatio: Float = 0.29f,
    val toeRatio: Float = 0.71f,
    val minPitch: Float = -45f * PI.toFloat() / 180f,
    val maxPitch: Float = 45f * PI.toFloat() / 180f
)
