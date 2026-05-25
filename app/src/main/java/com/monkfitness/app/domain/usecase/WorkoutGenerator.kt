package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import kotlin.math.roundToInt
import kotlin.random.Random

class WorkoutGenerator {

    private data class WorkoutSelectionRule(
        val count: Int,
        val preferredMatch: (Exercise) -> Boolean,
        val fallbackMatch: (Exercise) -> Boolean = preferredMatch
    )

    private val allExercises = listOf(
        baseRepExercise("pushups", R.string.ex_pushups, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, lottieRes = R.raw.military_push_ups, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("decline_pushups", R.string.ex_decline_pushups, R.string.ex_decline_pushups_desc, R.string.ex_decline_pushups_tech, R.string.ex_decline_pushups_steps, R.string.ex_decline_pushups_mistakes, imageRes = R.drawable.push_up, lottieRes = R.raw.military_push_ups, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("diamond_pushups", R.string.ex_diamond_pushups, R.string.ex_diamond_pushups_desc, R.string.ex_diamond_pushups_tech, R.string.ex_diamond_pushups_steps, R.string.ex_diamond_pushups_mistakes, imageRes = R.drawable.push_up, lottieRes = R.raw.military_push_ups, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("squats", R.string.ex_squats, R.string.ex_squats_desc, R.string.ex_squats_tech, R.string.ex_squats_steps, R.string.ex_squats_mistakes, imageRes = R.drawable.squat, lottieRes = R.raw.squat, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("pullups", R.string.ex_pullups, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, lottieRes = R.raw.pull_ups, sets = 3, baseMinReps = 3, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseTimerExercise("plank", R.string.ex_plank, R.string.ex_plank_desc, R.string.ex_plank_tech, R.string.ex_plank_steps, R.string.ex_plank_mistakes, imageRes = R.drawable.plank, lottieRes = R.raw.exercise_pulse, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("dips", R.string.ex_dips, R.string.ex_dips_desc, R.string.ex_dips_tech, R.string.ex_dips_steps, R.string.ex_dips_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("lunges", R.string.ex_lunges, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("step_ups", R.string.ex_step_ups, R.string.ex_step_ups_desc, R.string.ex_step_ups_tech, R.string.ex_step_ups_steps, R.string.ex_step_ups_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("rows", R.string.ex_rows, R.string.ex_rows_desc, R.string.ex_rows_tech, R.string.ex_rows_steps, R.string.ex_rows_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("glute_bridge", R.string.ex_glute_bridge, R.string.ex_glute_bridge_desc, R.string.ex_glute_bridge_tech, R.string.ex_glute_bridge_steps, R.string.ex_glute_bridge_mistakes, imageRes = R.drawable.glute_bridge, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.HIPS),
        baseRepExercise("cat_cow", R.string.ex_cat_cow, R.string.ex_cat_cow_desc, R.string.ex_cat_cow_tech, R.string.ex_cat_cow_steps, R.string.ex_cat_cow_mistakes, sets = 2, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 16, phase4MaxReps = 20, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("bird_dog", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, imageRes = R.drawable.bird_dog, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 14, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("world_greatest_stretch", R.string.ex_stretch, R.string.ex_stretch_desc, R.string.ex_stretch_tech, R.string.ex_stretch_steps, R.string.ex_stretch_mistakes, sets = 2, baseMinReps = 4, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("burpees", R.string.ex_burpees, R.string.ex_burpees_desc, R.string.ex_burpees_tech, R.string.ex_burpees_steps, R.string.ex_burpees_mistakes, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("mountain_climbers", R.string.ex_climbers, R.string.ex_climbers_desc, R.string.ex_climbers_tech, R.string.ex_climbers_steps, R.string.ex_climbers_mistakes, sets = 3, baseMinReps = 12, baseMaxReps = 16, phase4MinReps = 18, phase4MaxReps = 28, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("kettlebell_swing", R.string.ex_kb_swing, R.string.ex_kb_swing_desc, R.string.ex_kb_swing_tech, R.string.ex_kb_swing_steps, R.string.ex_kb_swing_mistakes, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 16, phase4MaxReps = 22, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY, requiredEquipment = setOf(Equipment.BACKPACK)),
        baseRepExercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.string.ex_face_pull_steps, R.string.ex_face_pull_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 24, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BANDS)),
        baseRepExercise("scapular_pullups", R.string.ex_scapular_pullups, R.string.ex_scapular_pullups_desc, R.string.ex_scapular_pullups_tech, R.string.ex_scapular_pullups_steps, R.string.ex_scapular_pullups_mistakes, imageRes = R.drawable.pull_up, lottieRes = R.raw.pull_ups, sets = 3, baseMinReps = 4, baseMaxReps = 6, phase4MinReps = 7, phase4MaxReps = 10, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("reverse_snow_angels", R.string.ex_reverse_snow_angels, R.string.ex_reverse_snow_angels_desc, R.string.ex_reverse_snow_angels_tech, R.string.ex_reverse_snow_angels_steps, R.string.ex_reverse_snow_angels_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("band_pull_aparts", R.string.ex_band_pull_aparts, R.string.ex_band_pull_aparts_desc, R.string.ex_band_pull_aparts_tech, R.string.ex_band_pull_aparts_steps, R.string.ex_band_pull_aparts_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BANDS)),
        baseRepExercise("thoracic_rotations", R.string.ex_thoracic_rotations, R.string.ex_thoracic_rotations_desc, R.string.ex_thoracic_rotations_tech, R.string.ex_thoracic_rotations_steps, R.string.ex_thoracic_rotations_mistakes, imageRes = R.drawable.bird_dog, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("shoulder_cars", R.string.ex_shoulder_cars, R.string.ex_shoulder_cars_desc, R.string.ex_shoulder_cars_tech, R.string.ex_shoulder_cars_steps, R.string.ex_shoulder_cars_mistakes, sets = 2, baseMinReps = 4, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 8, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("cobra_stretch", R.string.ex_cobra_stretch, R.string.ex_cobra_stretch_desc, R.string.ex_cobra_stretch_tech, R.string.ex_cobra_stretch_steps, R.string.ex_cobra_stretch_mistakes, imageRes = R.drawable.plank, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
        baseTimerExercise("child_pose", R.string.ex_child_pose, R.string.ex_child_pose_desc, R.string.ex_child_pose_tech, R.string.ex_child_pose_steps, R.string.ex_child_pose_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, baseDurationSeconds = 45, phase4DurationSeconds = 90, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("thoracic_extension", R.string.ex_thoracic_extension, R.string.ex_thoracic_extension_desc, R.string.ex_thoracic_extension_tech, R.string.ex_thoracic_extension_steps, R.string.ex_thoracic_extension_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseTimerExercise("hip_flexor_stretch", R.string.ex_hip_flexor_stretch, R.string.ex_hip_flexor_stretch_desc, R.string.ex_hip_flexor_stretch_tech, R.string.ex_hip_flexor_stretch_steps, R.string.ex_hip_flexor_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),
        baseRepExercise("hip_cars", R.string.ex_hip_cars, R.string.ex_hip_cars_desc, R.string.ex_hip_cars_tech, R.string.ex_hip_cars_steps, R.string.ex_hip_cars_mistakes, sets = 2, baseMinReps = 3, baseMaxReps = 4, phase4MinReps = 5, phase4MaxReps = 6, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseRepExercise("ninety_ninety_hips", R.string.ex_ninety_ninety_hips, R.string.ex_ninety_ninety_hips_desc, R.string.ex_ninety_ninety_hips_tech, R.string.ex_ninety_ninety_hips_steps, R.string.ex_ninety_ninety_hips_mistakes, imageRes = R.drawable.squat, sets = 2, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 14, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseTimerExercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.string.ex_deep_squat_steps, R.string.ex_deep_squat_mistakes, imageRes = R.drawable.squat, lottieRes = R.raw.squat, sets = 2, baseDurationSeconds = 45, phase4DurationSeconds = 90, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("ankle_mobility", R.string.ex_ankle_mobility, R.string.ex_ankle_mobility_desc, R.string.ex_ankle_mobility_tech, R.string.ex_ankle_mobility_steps, R.string.ex_ankle_mobility_mistakes, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("hamstring_stretch", R.string.ex_hamstring_stretch, R.string.ex_hamstring_stretch_desc, R.string.ex_hamstring_stretch_tech, R.string.ex_hamstring_stretch_steps, R.string.ex_hamstring_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, imageRes = R.drawable.pull_up, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 90, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("superman", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.string.ex_superman_steps, R.string.ex_superman_mistakes, imageRes = R.drawable.plank, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("pike_pushups", R.string.ex_pike_pushups, R.string.ex_pike_pushups_desc, R.string.ex_pike_pushups_tech, R.string.ex_pike_pushups_steps, R.string.ex_pike_pushups_mistakes, imageRes = R.drawable.pike_pushup, lottieRes = R.raw.military_push_ups, sets = 3, baseMinReps = 4, baseMaxReps = 6, phase4MinReps = 7, phase4MaxReps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("wall_sit", R.string.ex_wall_sit, R.string.ex_wall_sit_desc, R.string.ex_wall_sit_tech, R.string.ex_wall_sit_steps, R.string.ex_wall_sit_mistakes, imageRes = R.drawable.horse_stance, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("chin_tucks", R.string.ex_chin_tucks, R.string.ex_chin_tucks_desc, R.string.ex_chin_tucks_tech, R.string.ex_chin_tucks_steps, R.string.ex_chin_tucks_mistakes, sets = 2, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("y_t_raises", R.string.ex_y_t_raises, R.string.ex_y_t_raises_desc, R.string.ex_y_t_raises_tech, R.string.ex_y_t_raises_steps, R.string.ex_y_t_raises_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("scapular_retraction_hold", R.string.ex_scapular_retraction_hold, R.string.ex_scapular_retraction_hold_desc, R.string.ex_scapular_retraction_hold_tech, R.string.ex_scapular_retraction_hold_steps, R.string.ex_scapular_retraction_hold_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("horse_stance", R.string.ex_horse_stance, R.string.ex_horse_stance_desc, R.string.ex_horse_stance_tech, R.string.ex_horse_stance_steps, R.string.ex_horse_stance_mistakes, imageRes = R.drawable.horse_stance, lottieRes = R.raw.squat, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 90, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("calf_stretch", R.string.ex_calf_stretch, R.string.ex_calf_stretch_desc, R.string.ex_calf_stretch_tech, R.string.ex_calf_stretch_steps, R.string.ex_calf_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("lat_stretch", R.string.ex_lat_stretch, R.string.ex_lat_stretch_desc, R.string.ex_lat_stretch_tech, R.string.ex_lat_stretch_steps, R.string.ex_lat_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("piriformis_stretch", R.string.ex_piriformis_stretch, R.string.ex_piriformis_stretch_desc, R.string.ex_piriformis_stretch_tech, R.string.ex_piriformis_stretch_steps, R.string.ex_piriformis_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),
        baseTimerExercise("neck_circles", R.string.ex_neck_circles, R.string.ex_neck_circles, R.string.ex_neck_circles, imageRes = R.drawable.ic_exercise_placeholder, lottieRes = R.raw.exercise_pulse, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseTimerExercise("arm_circles", R.string.ex_arm_circles, R.string.ex_arm_circles, R.string.ex_arm_circles, imageRes = R.drawable.ic_exercise_placeholder, lottieRes = R.raw.exercise_pulse, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("hip_circles", R.string.ex_hip_circles, R.string.ex_hip_circles, R.string.ex_hip_circles, imageRes = R.drawable.ic_exercise_placeholder, lottieRes = R.raw.exercise_pulse, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseTimerExercise("leg_swings", R.string.ex_leg_swings, R.string.ex_leg_swings, R.string.ex_leg_swings, imageRes = R.drawable.ic_exercise_placeholder, lottieRes = R.raw.exercise_pulse, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("jumping_jacks", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, imageRes = R.drawable.ic_exercise_placeholder, lottieRes = R.raw.exercise_pulse, sets = 1, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY)
    )

    private val exercisesById = allExercises.associateBy { it.id }

    fun getWorkoutType(day: Int): WorkoutType {
        val safeDay = if (day in 1..56) day else 1
        return when ((safeDay - 1) % 7) {
            0 -> WorkoutType.STRENGTH_A
            1 -> WorkoutType.MOBILITY
            2 -> WorkoutType.STRENGTH_B
            3 -> WorkoutType.REST
            4 -> WorkoutType.FUNCTIONAL
            5 -> WorkoutType.MOBILITY
            else -> WorkoutType.REST
        }
    }

    fun generateWorkout(
        day: Int,
        flexibilityTrainingType: FlexibilityTrainingType = FlexibilityTrainingType.BOTH,
        focusAreas: Set<ExerciseSubCategory> = setOf(ExerciseSubCategory.FULL_BODY),
        availableEquipment: Set<Equipment> = Equipment.entries.toSet()
    ): Workout {
        val safeDay = if (day in 1..56) day else 1
        val week = ((safeDay - 1) / 7) + 1
        val phase = (((week - 1) / 2) + 1).coerceIn(1, 4)
        val type = getWorkoutType(safeDay)

        return Workout(
            id = safeDay,
            type = type,
            exercises = getExercisesForType(type, phase, safeDay, flexibilityTrainingType, focusAreas, availableEquipment)
        )
    }

    fun generatePostureMobilityWorkout(
        day: Int,
        flexibilityTrainingType: FlexibilityTrainingType = FlexibilityTrainingType.BOTH,
        focusAreas: Set<ExerciseSubCategory> = setOf(ExerciseSubCategory.FULL_BODY),
        availableEquipment: Set<Equipment> = Equipment.entries.toSet()
    ): Workout {
        val safeDay = if (day in 1..56) day else 1
        val week = ((safeDay - 1) / 7) + 1
        val phase = (((week - 1) / 2) + 1).coerceIn(1, 4)

        return Workout(
            id = safeDay,
            type = WorkoutType.POSTURE_MOBILITY,
            exercises = filterExercisesForAvailableEquipment(
                exercises = selectFlexibilityExercises(
                    count = 4,
                    phase = phase,
                    daySeed = safeDay,
                    trainingType = flexibilityTrainingType,
                    focusAreas = focusAreas
                ),
                availableEquipment = availableEquipment,
                phase = phase,
                random = Random(safeDay * 10_000 + WorkoutType.POSTURE_MOBILITY.ordinal)
            )
        )
    }

    private fun getExercisesForType(
        type: WorkoutType,
        phase: Int,
        daySeed: Int,
        flexibilityTrainingType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>,
        availableEquipment: Set<Equipment>
    ): List<Exercise> {
        return try {
            val selectionRules = when (type) {
                WorkoutType.STRENGTH_A -> listOf(
                    subCategoryRule(ExerciseSubCategory.LEGS, preferredCategories = setOf(ExerciseCategory.STRENGTH)),
                    subCategoryRule(ExerciseSubCategory.CORE, preferredCategories = setOf(ExerciseCategory.STRENGTH, ExerciseCategory.MOBILITY)),
                    subCategoryRule(ExerciseSubCategory.FULL_BODY, preferredCategories = setOf(ExerciseCategory.STRENGTH)),
                    postureRule(preferredSubCategories = setOf(ExerciseSubCategory.SPINE, ExerciseSubCategory.SHOULDERS))
                )
                WorkoutType.STRENGTH_B -> listOf(
                    subCategoryRule(ExerciseSubCategory.SHOULDERS, preferredCategories = setOf(ExerciseCategory.STRENGTH)),
                    postureRule(preferredSubCategories = setOf(ExerciseSubCategory.SPINE, ExerciseSubCategory.SHOULDERS)),
                    subCategoryRule(ExerciseSubCategory.CORE, preferredCategories = setOf(ExerciseCategory.STRENGTH, ExerciseCategory.MOBILITY))
                )
                WorkoutType.MOBILITY -> return filterExercisesForAvailableEquipment(
                    exercises = selectFlexibilityExercises(
                        count = 3,
                        phase = phase,
                        daySeed = daySeed,
                        trainingType = flexibilityTrainingType,
                        focusAreas = focusAreas
                    ),
                    availableEquipment = availableEquipment,
                    phase = phase,
                    random = Random(daySeed * 10_000 + type.ordinal)
                )
                WorkoutType.FUNCTIONAL -> listOf(
                    subCategoryRule(
                        ExerciseSubCategory.FULL_BODY,
                        count = 3,
                        preferredCategories = setOf(ExerciseCategory.STRENGTH, ExerciseCategory.MOBILITY)
                    )
                )
                WorkoutType.POSTURE_MOBILITY -> return filterExercisesForAvailableEquipment(
                    exercises = selectFlexibilityExercises(
                        count = 4,
                        phase = phase,
                        daySeed = daySeed,
                        trainingType = flexibilityTrainingType,
                        focusAreas = focusAreas
                    ),
                    availableEquipment = availableEquipment,
                    phase = phase,
                    random = Random(daySeed * 10_000 + type.ordinal)
                )
                WorkoutType.REST -> return emptyList()
            }

            filterExercisesForAvailableEquipment(
                exercises = selectExercises(selectionRules, phase, Random(daySeed * 1_000 + type.ordinal)),
                availableEquipment = availableEquipment,
                phase = phase,
                random = Random(daySeed * 10_000 + type.ordinal)
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun selectFlexibilityExercises(
        count: Int,
        phase: Int,
        daySeed: Int,
        trainingType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): List<Exercise> {
        val normalizedFocusAreas = normalizeFlexibilityFocusAreas(focusAreas)
        val prioritizedAreas = buildFlexibilityAreaSequence(count, normalizedFocusAreas, daySeed)
        val selectedIds = mutableSetOf<String>()

        return prioritizedAreas.mapIndexed { index, area ->
            val exercise = pickFlexibilityExercise(
                desiredArea = area,
                preferPosture = trainingType == FlexibilityTrainingType.BOTH && (daySeed + index) % 2 == 0,
                trainingType = trainingType,
                prioritizedFocusAreas = normalizedFocusAreas,
                selectedIds = selectedIds,
                random = Random(daySeed * 1_000 + index)
            )
            selectedIds += exercise.id
            phasedExercise(exercise, phase)
        }
    }

    private fun normalizeFlexibilityFocusAreas(focusAreas: Set<ExerciseSubCategory>): List<ExerciseSubCategory> {
        val selectedAreas = focusAreas
            .filter { it in flexibilitySpecificFocusAreas }
            .distinct()

        return if (ExerciseSubCategory.FULL_BODY in focusAreas || selectedAreas.isEmpty()) {
            flexibilitySpecificFocusAreas
        } else {
            selectedAreas
        }
    }

    private fun buildFlexibilityAreaSequence(
        count: Int,
        focusAreas: List<ExerciseSubCategory>,
        daySeed: Int
    ): List<ExerciseSubCategory> {
        if (focusAreas.size == flexibilitySpecificFocusAreas.size) {
            return cycleAreas(flexibilitySpecificFocusAreas, count, daySeed)
        }

        val prioritizedCount = (count * 0.7).roundToInt().coerceIn(1, count)
        val maintenanceCount = (count - prioritizedCount).coerceAtLeast(0)
        val maintenanceAreas = flexibilitySpecificFocusAreas.filterNot { it in focusAreas }.ifEmpty { focusAreas }

        return buildList {
            addAll(cycleAreas(focusAreas, prioritizedCount, daySeed))
            addAll(cycleAreas(maintenanceAreas, maintenanceCount, daySeed + focusAreas.size))
        }
    }

    private fun cycleAreas(
        areas: List<ExerciseSubCategory>,
        count: Int,
        daySeed: Int
    ): List<ExerciseSubCategory> {
        if (count <= 0 || areas.isEmpty()) return emptyList()

        val startIndex = daySeed.mod(areas.size)
        return List(count) { index -> areas[(startIndex + index) % areas.size] }
    }

    private fun pickFlexibilityExercise(
        desiredArea: ExerciseSubCategory,
        preferPosture: Boolean,
        trainingType: FlexibilityTrainingType,
        prioritizedFocusAreas: List<ExerciseSubCategory>,
        selectedIds: Set<String>,
        random: Random
    ): Exercise {
        val candidates = allExercises.filter { exercise ->
            exercise.id !in selectedIds && exercise.matchesTrainingType(trainingType)
        }

        fun select(match: (Exercise) -> Boolean): Exercise? = candidates.filter(match).randomOrNull(random)

        return select { it.subCategory == desiredArea && it.matchesPreferredGroup(trainingType, preferPosture) }
            ?: select { it.subCategory == desiredArea }
            ?: select { it.subCategory in prioritizedFocusAreas && it.matchesPreferredGroup(trainingType, preferPosture) }
            ?: select { it.subCategory in prioritizedFocusAreas }
            ?: select { it.matchesPreferredGroup(trainingType, preferPosture) }
            ?: checkNotNull(candidates.randomOrNull(random)) { "No flexibility exercise matches selection rule" }
    }

    private fun Exercise.matchesTrainingType(trainingType: FlexibilityTrainingType): Boolean {
        return when (trainingType) {
            FlexibilityTrainingType.STRETCHING -> category == ExerciseCategory.STRETCHING || category == ExerciseCategory.MOBILITY
            FlexibilityTrainingType.POSTURE -> category == ExerciseCategory.POSTURE
            FlexibilityTrainingType.BOTH -> category == ExerciseCategory.STRETCHING ||
                category == ExerciseCategory.MOBILITY ||
                category == ExerciseCategory.POSTURE
        }
    }

    private fun Exercise.matchesPreferredGroup(
        trainingType: FlexibilityTrainingType,
        preferPosture: Boolean
    ): Boolean {
        return when (trainingType) {
            FlexibilityTrainingType.STRETCHING -> category == ExerciseCategory.STRETCHING || category == ExerciseCategory.MOBILITY
            FlexibilityTrainingType.POSTURE -> category == ExerciseCategory.POSTURE
            FlexibilityTrainingType.BOTH -> if (preferPosture) {
                category == ExerciseCategory.POSTURE
            } else {
                category == ExerciseCategory.STRETCHING || category == ExerciseCategory.MOBILITY
            }
        }
    }

    private fun postureRule(
        count: Int = 1,
        preferredSubCategories: Set<ExerciseSubCategory>
    ): WorkoutSelectionRule {
        return categoryRule(
            count = count,
            preferredCategories = setOf(ExerciseCategory.POSTURE, ExerciseCategory.MOBILITY),
            preferredSubCategories = preferredSubCategories,
            fallbackSubCategories = preferredSubCategories + ExerciseSubCategory.SPINE
        )
    }

    private fun subCategoryRule(
        subCategory: ExerciseSubCategory,
        count: Int = 1,
        preferredCategories: Set<ExerciseCategory> = emptySet()
    ): WorkoutSelectionRule {
        val fallbackMatch: (Exercise) -> Boolean = { it.subCategory == subCategory }
        val preferredMatch: (Exercise) -> Boolean = { exercise ->
            exercise.subCategory == subCategory &&
                (preferredCategories.isEmpty() || exercise.category in preferredCategories)
        }

        return WorkoutSelectionRule(
            count = count,
            preferredMatch = preferredMatch,
            fallbackMatch = fallbackMatch
        )
    }

    private fun categoryRule(
        count: Int = 1,
        preferredCategories: Set<ExerciseCategory>,
        preferredSubCategories: Set<ExerciseSubCategory>,
        fallbackSubCategories: Set<ExerciseSubCategory> = preferredSubCategories
    ): WorkoutSelectionRule {
        val preferredMatch: (Exercise) -> Boolean = { exercise ->
            exercise.category in preferredCategories && exercise.subCategory in preferredSubCategories
        }
        val fallbackMatch: (Exercise) -> Boolean = { exercise ->
            exercise.subCategory in fallbackSubCategories
        }

        return WorkoutSelectionRule(
            count = count,
            preferredMatch = preferredMatch,
            fallbackMatch = fallbackMatch
        )
    }

    private fun selectExercises(
        selectionRules: List<WorkoutSelectionRule>,
        phase: Int,
        random: Random
    ): List<Exercise> {
        val selectedIds = mutableSetOf<String>()

        return buildList {
            selectionRules.forEach { rule ->
                repeat(rule.count) {
                    val exercise = pickExercise(rule, selectedIds, random)
                    selectedIds += exercise.id
                    add(phasedExercise(exercise, phase))
                }
            }
        }
    }

    private fun pickExercise(
        rule: WorkoutSelectionRule,
        selectedIds: Set<String>,
        random: Random
    ): Exercise {
        val preferredMatches = allExercises.filter { it.id !in selectedIds && rule.preferredMatch(it) }
        val fallbackMatches = allExercises.filter { it.id !in selectedIds && rule.fallbackMatch(it) }

        val candidates = preferredMatches.ifEmpty { fallbackMatches }
        return checkNotNull(candidates.randomOrNull(random)) { "No exercise matches selection rule" }
    }

    private fun phasedExercise(exercise: Exercise, phase: Int): Exercise {
        return if (exercise.isTimerBased) {
            exercise.copy(
                reps = 1,
                minReps = 0,
                maxReps = 0,
                durationSeconds = interpolatePhaseValue(exercise.baseDurationSeconds, exercise.phase4DurationSeconds, phase),
                isTimerBased = true
            )
        } else {
            val minReps = interpolatePhaseValue(exercise.baseMinReps, exercise.phase4MinReps, phase)
            val maxReps = interpolatePhaseValue(exercise.baseMaxReps, exercise.phase4MaxReps, phase).coerceAtLeast(minReps)
            exercise.copy(
                minReps = minReps,
                maxReps = maxReps,
                reps = maxReps,
                durationSeconds = 0,
                isTimerBased = false
            )
        }
    }

    private fun interpolatePhaseValue(start: Int, end: Int, phase: Int): Int {
        if (phase <= 1) return start
        if (phase >= 4) return end

        val progress = (phase - 1) / 3.0
        return (start + (end - start) * progress).roundToInt()
    }

    private fun baseRepExercise(
        id: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        imageRes: Int? = null,
        lottieRes: Int? = null,
        sets: Int,
        baseMinReps: Int,
        baseMaxReps: Int,
        phase4MinReps: Int,
        phase4MaxReps: Int,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory,
        requiredEquipment: Set<Equipment> = emptySet()
    ): Exercise {
        val currentMaxReps = baseMaxReps.coerceAtLeast(baseMinReps)
        return Exercise(
            id = id,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
            lottieRes = lottieRes,
            sets = sets,
            reps = currentMaxReps,
            minReps = baseMinReps,
            maxReps = currentMaxReps,
            baseMinReps = baseMinReps,
            baseMaxReps = currentMaxReps,
            phase4MinReps = phase4MinReps.coerceAtLeast(baseMinReps),
            phase4MaxReps = phase4MaxReps.coerceAtLeast(phase4MinReps),
            durationSeconds = 0,
            baseDurationSeconds = 0,
            phase4DurationSeconds = 0,
            isTimerBased = false,
            category = category,
            subCategory = subCategory,
            requiredEquipment = normalizeRequiredEquipment(requiredEquipment)
        )
    }

    private fun baseTimerExercise(
        id: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        imageRes: Int? = null,
        lottieRes: Int? = null,
        sets: Int,
        baseDurationSeconds: Int,
        phase4DurationSeconds: Int,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory,
        requiredEquipment: Set<Equipment> = emptySet()
    ): Exercise {
        return Exercise(
            id = id,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
            lottieRes = lottieRes,
            sets = sets,
            reps = 1,
            minReps = 0,
            maxReps = 0,
            baseMinReps = 0,
            baseMaxReps = 0,
            phase4MinReps = 0,
            phase4MaxReps = 0,
            durationSeconds = baseDurationSeconds,
            baseDurationSeconds = baseDurationSeconds,
            phase4DurationSeconds = phase4DurationSeconds.coerceAtLeast(baseDurationSeconds),
            isTimerBased = true,
            category = category,
            subCategory = subCategory,
            requiredEquipment = normalizeRequiredEquipment(requiredEquipment)
        )
    }

    private fun requireExercise(id: String): Exercise {
        return checkNotNull(exercisesById[id]) { "Unknown exercise id: $id" }
    }

    fun getExerciseLibrary(
        availableEquipment: Set<Equipment> = Equipment.entries.toSet()
    ): List<Exercise> {
        val normalizedEquipment = normalizeAvailableEquipment(availableEquipment)
        return allExercises.filter { it.isAccessibleWith(normalizedEquipment) }
    }

    private fun filterExercisesForAvailableEquipment(
        exercises: List<Exercise>,
        availableEquipment: Set<Equipment>,
        phase: Int,
        random: Random
    ): List<Exercise> {
        if (exercises.isEmpty()) return emptyList()

        val normalizedEquipment = normalizeAvailableEquipment(availableEquipment)
        val selectedIds = mutableSetOf<String>()
        val resolvedExercises = mutableListOf<Exercise>()
        val unresolvedProfiles = mutableListOf<Exercise>()

        exercises.forEach { exercise ->
            val resolved = when {
                exercise.id !in selectedIds && exercise.isAccessibleWith(normalizedEquipment) -> exercise
                else -> findReplacementExercise(exercise, selectedIds, normalizedEquipment, phase, random)
            }

            if (resolved != null) {
                selectedIds += resolved.id
                resolvedExercises += resolved
            } else {
                unresolvedProfiles += exercise
            }
        }

        unresolvedProfiles.forEach { exercise ->
            val fallback = findBodyweightReplacement(exercise, selectedIds, phase, random) ?: return@forEach
            selectedIds += fallback.id
            resolvedExercises += fallback
        }

        return if (resolvedExercises.isNotEmpty()) {
            resolvedExercises
        } else {
            buildBodyweightFallback(exercises, selectedIds, phase, random)
        }
    }

    private fun findReplacementExercise(
        target: Exercise,
        selectedIds: Set<String>,
        availableEquipment: Set<Equipment>,
        phase: Int,
        random: Random
    ): Exercise? {
        val accessibleCandidates = allExercises.filter { candidate ->
            candidate.id !in selectedIds && candidate.isAccessibleWith(availableEquipment)
        }
        val bodyweightCandidates = accessibleCandidates.filter { it.requiresNoEquipment() }

        return pickCandidate(
            random,
            listOf(
                accessibleCandidates.filter { it.category == target.category && it.subCategory == target.subCategory },
                accessibleCandidates.filter { it.category == target.category },
                accessibleCandidates.filter { it.subCategory == target.subCategory },
                bodyweightCandidates.filter { it.category == target.category && it.subCategory == target.subCategory },
                bodyweightCandidates.filter { it.category == target.category },
                bodyweightCandidates
            )
        )?.let { phasedExercise(it, phase) }
    }

    private fun findBodyweightReplacement(
        target: Exercise,
        selectedIds: Set<String>,
        phase: Int,
        random: Random
    ): Exercise? {
        val bodyweightCandidates = allExercises.filter { candidate ->
            candidate.id !in selectedIds && candidate.requiresNoEquipment()
        }

        return pickCandidate(
            random,
            listOf(
                bodyweightCandidates.filter { it.category == target.category && it.subCategory == target.subCategory },
                bodyweightCandidates.filter { it.category == target.category },
                bodyweightCandidates.filter { it.subCategory == target.subCategory },
                bodyweightCandidates
            )
        )?.let { phasedExercise(it, phase) }
    }

    private fun buildBodyweightFallback(
        exercises: List<Exercise>,
        selectedIds: Set<String>,
        phase: Int,
        random: Random
    ): List<Exercise> {
        val mutableSelectedIds = selectedIds.toMutableSet()
        val fallback = mutableListOf<Exercise>()

        exercises.forEach { exercise ->
            val replacement = findBodyweightReplacement(exercise, mutableSelectedIds, phase, random) ?: return@forEach
            mutableSelectedIds += replacement.id
            fallback += replacement
        }

        if (fallback.isNotEmpty()) {
            return fallback
        }

        return allExercises
            .asSequence()
            .filter { it.requiresNoEquipment() }
            .filter { it.id !in mutableSelectedIds }
            .map { phasedExercise(it, phase) }
            .take(exercises.size.coerceAtLeast(1))
            .toList()
    }

    private fun pickCandidate(
        random: Random,
        groups: List<List<Exercise>>
    ): Exercise? {
        return groups.firstNotNullOfOrNull { candidates ->
            candidates.distinctBy { it.id }.randomOrNull(random)
        }
    }

    private fun normalizeAvailableEquipment(availableEquipment: Set<Equipment>): Set<Equipment> {
        return availableEquipment
            .filter { it != Equipment.NONE }
            .toSet() + Equipment.NONE
    }

    private fun normalizeRequiredEquipment(requiredEquipment: Set<Equipment>): Set<Equipment> {
        return requiredEquipment
            .filter { it != Equipment.NONE }
            .toSet()
    }

    internal fun Exercise.requiresNoEquipment(): Boolean {
        return normalizeRequiredEquipment(requiredEquipment).isEmpty()
    }

    internal fun Exercise.isAccessibleWith(availableEquipment: Set<Equipment>): Boolean {
        val normalizedAvailableEquipment = normalizeAvailableEquipment(availableEquipment) - Equipment.NONE
        return normalizeRequiredEquipment(requiredEquipment).all { it in normalizedAvailableEquipment }
    }

    fun getPostureExercises(): List<Exercise> {
        return listOf(
            requireExercise("hang").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true),
            requireExercise("face_pull").copy(sets = 3, reps = 15, minReps = 15, maxReps = 15, durationSeconds = 0, isTimerBased = false),
            requireExercise("bird_dog").copy(sets = 3, reps = 12, minReps = 12, maxReps = 12, durationSeconds = 0, isTimerBased = false),
            requireExercise("superman").copy(sets = 3, reps = 12, minReps = 12, maxReps = 12),
            requireExercise("wall_slides").copy(sets = 3, reps = 15, minReps = 15, maxReps = 15),
            requireExercise("deep_squat").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true),
            requireExercise("pike_pushups").copy(sets = 3, reps = 8, minReps = 8, maxReps = 8, durationSeconds = 0, isTimerBased = false),
            requireExercise("horse_stance").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true)
        )
    }

    fun getWarmupExercises(): List<Exercise> {
        return listOf(
            requireExercise("neck_circles").copy(sets = 1, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 30, isTimerBased = true),
            requireExercise("arm_circles").copy(sets = 1, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 30, isTimerBased = true),
            requireExercise("hip_circles").copy(sets = 1, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 30, isTimerBased = true),
            requireExercise("leg_swings").copy(sets = 1, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 30, isTimerBased = true),
            requireExercise("jumping_jacks").copy(sets = 1, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true)
        )
    }
}
