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
    val minReps: Int = reps,
    val maxReps: Int = reps,
    val baseMinReps: Int = minReps,
    val baseMaxReps: Int = maxReps,
    val phase4MinReps: Int = minReps,
    val phase4MaxReps: Int = maxReps,
    val durationSeconds: Int = 0,
    val baseDurationSeconds: Int = durationSeconds,
    val phase4DurationSeconds: Int = durationSeconds,
    val isTimerBased: Boolean = false,
    val category: ExerciseCategory = ExerciseCategory.MOBILITY,
    val subCategory: ExerciseSubCategory = ExerciseSubCategory.FULL_BODY
)

fun Exercise.applyDifficultyAdjustment(adjustment: Int): Exercise {
    val safeAdjustment = adjustment.coerceIn(-2, 2)

    return if (isTimerBased) {
        copy(
            durationSeconds = (durationSeconds + safeAdjustment * 5).coerceAtLeast(5)
        )
    } else {
        val offset = safeAdjustment * 2
        val adjustedMin = (minReps + offset).coerceAtLeast(1)
        val adjustedMax = (maxReps + offset).coerceAtLeast(adjustedMin)
        copy(
            minReps = adjustedMin,
            maxReps = adjustedMax,
            reps = adjustedMax
        )
    }
}
