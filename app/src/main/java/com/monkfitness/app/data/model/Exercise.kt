package com.monkfitness.app.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
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

enum class FlexibilityTrainingType(@StringRes val labelRes: Int) {
    STRETCHING(R.string.flexibility_type_stretching),
    POSTURE(R.string.flexibility_type_posture),
    BOTH(R.string.flexibility_type_both)
}

enum class Equipment(@StringRes val labelRes: Int) {
    NONE(R.string.equipment_none),
    BAR(R.string.equipment_bar),
    BANDS(R.string.equipment_bands),
    BACKPACK(R.string.equipment_backpack)
}

val postureFocusAreas = listOf(
    ExerciseSubCategory.SHOULDERS,
    ExerciseSubCategory.SPINE,
    ExerciseSubCategory.HIPS
)

val stretchFocusAreas = listOf(
    ExerciseSubCategory.SHOULDERS,
    ExerciseSubCategory.SPINE,
    ExerciseSubCategory.HIPS,
    ExerciseSubCategory.LEGS
)

val flexibilityFocusAreas = listOf(
    ExerciseSubCategory.FULL_BODY,
    ExerciseSubCategory.SHOULDERS,
    ExerciseSubCategory.SPINE,
    ExerciseSubCategory.HIPS,
    ExerciseSubCategory.LEGS
)

val flexibilitySpecificFocusAreas = flexibilityFocusAreas.filterNot { it == ExerciseSubCategory.FULL_BODY }

@Immutable
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
    val subCategory: ExerciseSubCategory = ExerciseSubCategory.FULL_BODY,
    val equipment: Equipment = Equipment.NONE,
    val nameRu: String = "",
    val nameEn: String = "",
    val descriptionRu: String = "",
    val descriptionEn: String = ""
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
