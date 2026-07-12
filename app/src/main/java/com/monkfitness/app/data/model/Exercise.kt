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
    FULL_BODY(R.string.subcategory_full_body),
    HYPERLORDOSIS(R.string.subcategory_hyperlordosis)
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
    ExerciseSubCategory.HIPS,
    ExerciseSubCategory.CORE,
    ExerciseSubCategory.HYPERLORDOSIS
)

val stretchFocusAreas = listOf(
    ExerciseSubCategory.SHOULDERS,
    ExerciseSubCategory.SPINE,
    ExerciseSubCategory.HIPS,
    ExerciseSubCategory.LEGS,
    ExerciseSubCategory.CORE,
    ExerciseSubCategory.HYPERLORDOSIS
)

val flexibilityFocusAreas = listOf(
    ExerciseSubCategory.FULL_BODY,
    ExerciseSubCategory.SHOULDERS,
    ExerciseSubCategory.SPINE,
    ExerciseSubCategory.HIPS,
    ExerciseSubCategory.LEGS,
    ExerciseSubCategory.CORE,
    ExerciseSubCategory.HYPERLORDOSIS
)

val flexibilitySpecificFocusAreas = flexibilityFocusAreas.filterNot { it == ExerciseSubCategory.FULL_BODY }

@Immutable
data class ExerciseFamily(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int
)

@Immutable
data class Exercise(
    val id: String,
    val familyId: String,
    val animationId: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val techniqueRes: Int,
    @StringRes val stepsRes: Int = 0,
    @StringRes val mistakesRes: Int = 0,
    @DrawableRes val imageRes: Int?,
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
    val requiredEquipment: Set<Equipment> = emptySet(),
    val nameRu: String = "",
    val nameEn: String = "",
    val nameUk: String = "",
    val descriptionRu: String = "",
    val descriptionEn: String = "",
    val descriptionUk: String = ""
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

enum class ExerciseCategoryFilter(val key: String, val displayName: String) {
    SHAOLIN("shaolin", "Shaolin"),
    CALISTHENICS("calisthenics", "Calisthenics"),
    POSTURE("posture", "Posture"),
    HYPERLORDOSIS("hyperlordosis", "Hyperlordosis"),
    SPINE_REHABILITATION("spine_rehab", "Spine Rehabilitation"),
    MOBILITY("mobility", "Mobility"),
    STRETCHING("stretching", "Stretching"),
    BALANCE("balance", "Balance"),
    FLEXIBILITY("flexibility", "Flexibility"),
    GENERAL_FITNESS("general_fitness", "General Fitness")
}

val exerciseToFamiliesMap: Map<String, Set<ExerciseCategoryFilter>> = mapOf(
    "pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "pushups_wide" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "pushups_military" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "pushups_knee" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "decline_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "diamond_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "squats" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "squats_sumo" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "squats_jump" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "deep_squat" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.FLEXIBILITY),
    "pullups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.POSTURE),
    "pullups_chin" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.POSTURE),
    "pullups_neutral" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.POSTURE),
    "pullups_wide" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.POSTURE),
    "hang" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.POSTURE),
    "plank" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "side_plank" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "lunges" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "lunges_reverse" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "lunges_side" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "step_ups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "rows" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "glute_bridge" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "dead_bug" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "leg_raises" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "pelvic_tilt" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "couch_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY, ExerciseCategoryFilter.HYPERLORDOSIS),
    "hip_flexor_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY, ExerciseCategoryFilter.HYPERLORDOSIS),
    "cat_cow" to setOf(ExerciseCategoryFilter.SPINE_REHABILITATION, ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.FLEXIBILITY),
    "cobra_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "bird_dog" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "bird_dog_reps" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "world_greatest_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.FLEXIBILITY),
    "thoracic_rotations" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "thoracic_extension" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "burpees" to setOf(ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "mountain_climbers" to setOf(ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "kettlebell_swing" to setOf(ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "face_pull" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "band_pull_aparts" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "y_t_raises" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "scapular_retraction_hold" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.GENERAL_FITNESS),
    "reverse_snow_angels" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.MOBILITY),
    "scapular_pullups" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.MOBILITY),
    "wall_slides" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.MOBILITY),
    "shoulder_cars" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY),
    "lat_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY),
    "hip_cars" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY),
    "ninety_ninety_hips" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY),
    "piriformis_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY),
    "ankle_mobility" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "calf_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "dips" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "pike_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.GENERAL_FITNESS),
    "wall_sit" to setOf(ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "chin_tucks" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.SPINE_REHABILITATION, ExerciseCategoryFilter.MOBILITY),
    "neck_circles" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.SPINE_REHABILITATION, ExerciseCategoryFilter.MOBILITY),
    "jumping_jacks" to setOf(ExerciseCategoryFilter.GENERAL_FITNESS, ExerciseCategoryFilter.SHAOLIN),
    "horse_stance" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.BALANCE, ExerciseCategoryFilter.POSTURE),
    "superman" to setOf(ExerciseCategoryFilter.POSTURE, ExerciseCategoryFilter.SPINE_REHABILITATION, ExerciseCategoryFilter.GENERAL_FITNESS),
    "child_pose" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY, ExerciseCategoryFilter.SPINE_REHABILITATION),
    "arm_circles" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "hip_circles" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "leg_swings" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "hamstring_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.FLEXIBILITY)
)
