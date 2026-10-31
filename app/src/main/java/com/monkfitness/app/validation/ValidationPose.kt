package com.monkfitness.app.validation

import androidx.annotation.StringRes
import com.monkfitness.app.R
import com.monkfitness.app.animation.PoseBuilder

/**
 * A single static reference pose used purely for engine validation. NOT an exercise: it is a frozen
 * skeleton snapshot rendered through the shared animation pipeline, and it never participates in
 * workouts, statistics, progression, recommendations or any training logic.
 */
data class ValidationPose(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val techniqueRes: Int,
    val builder: PoseBuilder,
    val alternating: Boolean = false,
    val purposeRes: List<Int> = emptyList()
)
