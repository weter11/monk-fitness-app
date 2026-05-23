package com.monkfitness.app.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.monkfitness.app.R

enum class ExerciseCategory(@StringRes val labelRes: Int) {
    STRENGTH(R.string.category_strength),
    MOBILITY(R.string.category_mobility),
    STRETCHING(R.string.category_stretching),
    POSTURE(R.string.category_posture)
}

enum class ExerciseSubCategory(@StringRes val labelRes: Int) {
    SHOULDERS(R.string.subcategory_shoulders),
    SPINE(R.string.subcategory_spine),
    HIPS(R.string.subcategory_hips),
    LEGS(R.string.subcategory_legs),
    CORE(R.string.subcategory_core),
    FULL_BODY(R.string.subcategory_full_body)
}

data class Exercise(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val techniqueRes: Int,
    @StringRes val stepsRes: Int = 0,
    @StringRes val mistakesRes: Int = 0,
    @DrawableRes val imageRes: Int,
    val sets: Int,
    val reps: Int,
    val durationSeconds: Int = 0,
    val isTimerBased: Boolean = false,
    val category: ExerciseCategory = ExerciseCategory.MOBILITY,
    val subCategory: ExerciseSubCategory = ExerciseSubCategory.FULL_BODY
)
