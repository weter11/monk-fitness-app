package com.monkfitness.app.animation

import android.graphics.Bitmap

/**
 * Represents a single rendered snapshot of an exercise frame.
 */
data class ExerciseSnapshot(
    val frameIndex: Int,
    val progress: Float,
    val bitmap: Bitmap
)
