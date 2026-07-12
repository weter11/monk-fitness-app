package com.monkfitness.app.animation

/**
 * LeverModel represents the computed lever characteristics of the body under a support configuration.
 * Computed dynamically by SupportMath.
 */
data class LeverModel(
    val leverLength: Float,
    val pivotPosition: Vector3
)
