package com.monkfitness.app.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class Exercise(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val techniqueRes: Int,
    @DrawableRes val imageRes: Int,
    val sets: Int,
    val reps: Int,
    val durationSeconds: Int = 0,
    val isTimerBased: Boolean = false
)
