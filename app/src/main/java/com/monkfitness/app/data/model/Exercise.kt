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
    /**
     * Marks an entry that is an engine validation reference pose rather than a
     * trainable exercise. Test poses live in the catalog (so they appear in the
     * exercise browser / search and open in the standard viewer) but are
     * excluded from workout generation, recommendations, statistics,
     * progression and achievements. See poses_for_tests / VALIDATION_REPORT.
     */
    val isTestPose: Boolean = false,
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
    // Training Styles
    SHAOLIN("shaolin", "Shaolin"),
    CALISTHENICS("calisthenics", "Calisthenics"),
    FUNCTIONAL_FITNESS("functional_fitness", "Functional Fitness"),
    BODYWEIGHT_STRENGTH("bodyweight_strength", "Bodyweight Strength"),
    MOBILITY("mobility", "Mobility"),
    STRETCHING("stretching", "Stretching"),

    // Special Programs
    HYPERLORDOSIS("hyperlordosis", "Hyperlordosis"),
    POSTURE_CORRECTION("posture_correction", "Posture Correction"),
    NECK("neck", "Neck"),
    LOWER_BACK("lower_back", "Lower Back"),
    SHOULDERS("shoulders", "Shoulders"),
    KNEES("knees", "Knees"),
    BALANCE("balance", "Balance"),
    SENIOR("senior", "Senior"),
    REHABILITATION("rehabilitation", "Rehabilitation")
}

data class ExerciseCategoryGroup(
    val title: String,
    val categories: List<ExerciseCategoryFilter>
)

val exerciseCategoryGroups = listOf(
    ExerciseCategoryGroup(
        title = "Training Styles",
        categories = listOf(
            ExerciseCategoryFilter.SHAOLIN,
            ExerciseCategoryFilter.CALISTHENICS,
            ExerciseCategoryFilter.FUNCTIONAL_FITNESS,
            ExerciseCategoryFilter.BODYWEIGHT_STRENGTH,
            ExerciseCategoryFilter.MOBILITY,
            ExerciseCategoryFilter.STRETCHING
        )
    ),
    ExerciseCategoryGroup(
        title = "Special Programs",
        categories = listOf(
            ExerciseCategoryFilter.HYPERLORDOSIS,
            ExerciseCategoryFilter.POSTURE_CORRECTION,
            ExerciseCategoryFilter.NECK,
            ExerciseCategoryFilter.LOWER_BACK,
            ExerciseCategoryFilter.SHOULDERS,
            ExerciseCategoryFilter.KNEES,
            ExerciseCategoryFilter.BALANCE,
            ExerciseCategoryFilter.SENIOR,
            ExerciseCategoryFilter.REHABILITATION
        )
    )
)

val exerciseToFamiliesMap: Map<String, Set<ExerciseCategoryFilter>> = mapOf(
    "pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.FUNCTIONAL_FITNESS),
    "pushups_wide" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "pushups_military" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "pushups_knee" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "decline_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "diamond_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "squats" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "squats_sumo" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "squats_jump" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "deep_squat" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "pullups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "pullups_chin" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "pullups_neutral" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "pullups_wide" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "hang" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "plank" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "side_plank" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "lunges" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "lunges_reverse" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "lunges_side" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "step_ups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "rows" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "glute_bridge" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "dead_bug" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "leg_raises" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "pelvic_tilt" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.HYPERLORDOSIS, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "couch_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.HYPERLORDOSIS),
    "hip_flexor_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.HYPERLORDOSIS),
    "cat_cow" to setOf(ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.LOWER_BACK),
    "cobra_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "bird_dog" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "bird_dog_reps" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "world_greatest_stretch" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.MOBILITY),
    "thoracic_rotations" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "thoracic_extension" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "burpees" to setOf(ExerciseCategoryFilter.FUNCTIONAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "mountain_climbers" to setOf(ExerciseCategoryFilter.FUNCTIONAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "kettlebell_swing" to setOf(ExerciseCategoryFilter.FUNCTIONAL_FITNESS, ExerciseCategoryFilter.CALISTHENICS),
    "face_pull" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "band_pull_aparts" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "y_t_raises" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "scapular_retraction_hold" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "reverse_snow_angels" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "scapular_pullups" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "wall_slides" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.SHOULDERS),
    "shoulder_cars" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.SHOULDERS),
    "lat_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.SHOULDERS),
    "hip_cars" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "ninety_ninety_hips" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "piriformis_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING),
    "ankle_mobility" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.KNEES),
    "calf_stretch" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.KNEES),
    "dips" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "pike_pushups" to setOf(ExerciseCategoryFilter.CALISTHENICS, ExerciseCategoryFilter.BODYWEIGHT_STRENGTH),
    "wall_sit" to setOf(ExerciseCategoryFilter.BODYWEIGHT_STRENGTH, ExerciseCategoryFilter.CALISTHENICS),
    "chin_tucks" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.NECK),
    "neck_circles" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.NECK, ExerciseCategoryFilter.MOBILITY),
    "jumping_jacks" to setOf(ExerciseCategoryFilter.FUNCTIONAL_FITNESS, ExerciseCategoryFilter.SHAOLIN),
    "horse_stance" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.BALANCE, ExerciseCategoryFilter.POSTURE_CORRECTION),
    "superman" to setOf(ExerciseCategoryFilter.POSTURE_CORRECTION, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "child_pose" to setOf(ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.REHABILITATION, ExerciseCategoryFilter.LOWER_BACK),
    "test_middle_split" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.POSTURE),
    "test_pike_sit" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.POSTURE),
    "test_deep_overhead_squat" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.POSTURE),
    "test_dead_hang" to setOf(ExerciseCategoryFilter.MOBILITY, ExerciseCategoryFilter.STRETCHING, ExerciseCategoryFilter.POSTURE),
    "arm_circles" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "hip_circles" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "leg_swings" to setOf(ExerciseCategoryFilter.SHAOLIN, ExerciseCategoryFilter.MOBILITY),
    "hamstring_stretch" to setOf(ExerciseCategoryFilter.STRETCHING)
)
