package com.monkfitness.app.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.monkfitness.app.R

enum class MainCategory(@StringRes val nameRes: Int) {
    STRENGTH(R.string.cat_strength),
    MOBILITY(R.string.cat_mobility),
    STRETCHING(R.string.cat_stretching),
    POSTURE(R.string.cat_posture)
}

enum class SubCategory(@StringRes val nameRes: Int) {
    SHOULDERS(R.string.subcat_shoulders),
    SPINE(R.string.subcat_spine),
    HIPS(R.string.subcat_hips),
    LEGS(R.string.subcat_legs),
    CORE(R.string.subcat_core),
    FULL_BODY(R.string.subcat_fullbody)
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
    val mainCategory: MainCategory = MainCategory.STRENGTH,
    val subCategory: SubCategory = SubCategory.FULL_BODY
)
