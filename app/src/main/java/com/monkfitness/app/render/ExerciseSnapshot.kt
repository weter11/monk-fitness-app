package com.monkfitness.app.render

import android.graphics.Bitmap
import com.monkfitness.app.animation.SkeletonPose

data class ExerciseSnapshot(
    val frameIndex: Int,
    val progress: Float,
    val bitmap: Bitmap,
    val pose: SkeletonPose? = null
)
