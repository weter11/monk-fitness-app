package com.monkfitness.app.animation

import android.graphics.Bitmap

data class ExerciseSnapshot(
    val frameIndex: Int,
    val progress: Float,
    val bitmap: Bitmap,
    val pose: SkeletonPose? = null
)
