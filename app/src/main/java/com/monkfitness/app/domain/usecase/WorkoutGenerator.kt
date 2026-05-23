package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.WorkoutType

class WorkoutGenerator {

    fun generateWorkout(day: Int): Workout {
        val safeDay = if (day in 1..56) day else 1
        val week = ((safeDay - 1) / 7) + 1
        val dayOfWeek = (safeDay - 1) % 7
        val phase = (((week - 1) / 2) + 1).coerceIn(1, 4)

        val type = when (dayOfWeek) {
            0 -> WorkoutType.STRENGTH_A
            1 -> WorkoutType.MOBILITY
            2 -> WorkoutType.STRENGTH_B
            3 -> WorkoutType.REST
            4 -> WorkoutType.FUNCTIONAL
            5 -> WorkoutType.MOBILITY
            else -> WorkoutType.REST
        }

        return Workout(
            id = safeDay,
            type = type,
            exercises = getExercisesForType(type, phase)
        )
    }

    private fun getExercisesForType(type: WorkoutType, phase: Int): List<Exercise> {
        return try {
            when (type) {
                WorkoutType.STRENGTH_A -> listOf(
                    createExercise("pushups", R.string.ex_pushups, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_pushups_desc, techRes = R.string.ex_pushups_tech, stepsRes = R.string.ex_pushups_steps, mistakesRes = R.string.ex_pushups_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
                    createExercise("squats", R.string.ex_squats, phase, baseReps = 12, baseSets = 3, imageRes = R.drawable.squat, descRes = R.string.ex_squats_desc, techRes = R.string.ex_squats_tech, stepsRes = R.string.ex_squats_steps, mistakesRes = R.string.ex_squats_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
                    createExercise("pullups", R.string.ex_pullups, phase, baseReps = 5, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_pullups_desc, techRes = R.string.ex_pullups_tech, stepsRes = R.string.ex_pullups_steps, mistakesRes = R.string.ex_pullups_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
                    createExercise("plank", R.string.ex_plank, phase, baseReps = 1, baseSets = 3, isTimer = true, baseDuration = 30, imageRes = R.drawable.plank, descRes = R.string.ex_plank_desc, techRes = R.string.ex_plank_tech, stepsRes = R.string.ex_plank_steps, mistakesRes = R.string.ex_plank_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE)
                )
                WorkoutType.STRENGTH_B -> listOf(
                    createExercise("dips", R.string.ex_dips, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_dips_desc, techRes = R.string.ex_dips_tech, stepsRes = R.string.ex_dips_steps, mistakesRes = R.string.ex_dips_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
                    createExercise("lunges", R.string.ex_lunges, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.lunges, descRes = R.string.ex_lunges_desc, techRes = R.string.ex_lunges_tech, stepsRes = R.string.ex_lunges_steps, mistakesRes = R.string.ex_lunges_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
                    createExercise("rows", R.string.ex_rows, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_rows_desc, techRes = R.string.ex_rows_tech, stepsRes = R.string.ex_rows_steps, mistakesRes = R.string.ex_rows_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
                    createExercise("glute_bridge", R.string.ex_glute_bridge, phase, baseReps = 15, baseSets = 3, imageRes = R.drawable.glute_bridge, descRes = R.string.ex_glute_bridge_desc, techRes = R.string.ex_glute_bridge_tech, stepsRes = R.string.ex_glute_bridge_steps, mistakesRes = R.string.ex_glute_bridge_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.HIPS)
                )
                WorkoutType.MOBILITY -> listOf(
                    createExercise("cat_cow", R.string.ex_cat_cow, phase, baseReps = 15, baseSets = 2, descRes = R.string.ex_cat_cow_desc, techRes = R.string.ex_cat_cow_tech, stepsRes = R.string.ex_cat_cow_steps, mistakesRes = R.string.ex_cat_cow_mistakes, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
                    createExercise("bird_dog", R.string.ex_bird_dog, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.bird_dog, descRes = R.string.ex_bird_dog_desc, techRes = R.string.ex_bird_dog_tech, stepsRes = R.string.ex_bird_dog_steps, mistakesRes = R.string.ex_bird_dog_mistakes, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.CORE),
                    createExercise("world_greatest_stretch", R.string.ex_stretch, phase, baseReps = 5, baseSets = 2, descRes = R.string.ex_stretch_desc, techRes = R.string.ex_stretch_tech, stepsRes = R.string.ex_stretch_steps, mistakesRes = R.string.ex_stretch_mistakes, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY)
                )
                WorkoutType.FUNCTIONAL -> listOf(
                    createExercise("burpees", R.string.ex_burpees, phase, baseReps = 10, baseSets = 3, descRes = R.string.ex_burpees_desc, techRes = R.string.ex_burpees_tech, stepsRes = R.string.ex_burpees_steps, mistakesRes = R.string.ex_burpees_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
                    createExercise("mountain_climbers", R.string.ex_climbers, phase, baseReps = 20, baseSets = 3, descRes = R.string.ex_climbers_desc, techRes = R.string.ex_climbers_tech, stepsRes = R.string.ex_climbers_steps, mistakesRes = R.string.ex_climbers_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
                    createExercise("kettlebell_swing", R.string.ex_kb_swing, phase, baseReps = 15, baseSets = 3, descRes = R.string.ex_kb_swing_desc, techRes = R.string.ex_kb_swing_tech, stepsRes = R.string.ex_kb_swing_steps, mistakesRes = R.string.ex_kb_swing_mistakes, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY)
                )
                WorkoutType.REST -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createExercise(
        id: String,
        nameRes: Int,
        phase: Int,
        baseReps: Int,
        baseSets: Int,
        isTimer: Boolean = false,
        baseDuration: Int = 0,
        imageRes: Int = R.drawable.ic_exercise_placeholder,
        descRes: Int = R.string.description,
        techRes: Int = R.string.technique,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory
    ): Exercise {
        val sets = baseSets + (phase - 1)
        val reps = baseReps + (phase - 1) * 2
        val duration = baseDuration + (phase - 1) * 15

        return Exercise(
            id = id,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
            sets = sets,
            reps = if (isTimer) 1 else reps,
            durationSeconds = duration,
            isTimerBased = isTimer,
            category = category,
            subCategory = subCategory
        )
    }

    private fun libraryExercise(
        id: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int,
        mistakesRes: Int,
        imageRes: Int = R.drawable.ic_exercise_placeholder,
        sets: Int = 3,
        reps: Int = 10,
        durationSeconds: Int = 0,
        isTimerBased: Boolean = false,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory
    ): Exercise {
        return Exercise(
            id = id,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
            sets = sets,
            reps = reps,
            durationSeconds = durationSeconds,
            isTimerBased = isTimerBased,
            category = category,
            subCategory = subCategory
        )
    }

    fun getExerciseLibrary(): List<Exercise> {
        return listOf(
            libraryExercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.string.ex_face_pull_steps, R.string.ex_face_pull_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 15, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
            libraryExercise("scapular_pullups", R.string.ex_scapular_pullups, R.string.ex_scapular_pullups_desc, R.string.ex_scapular_pullups_tech, R.string.ex_scapular_pullups_steps, R.string.ex_scapular_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, reps = 8, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
            libraryExercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 12, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
            libraryExercise("reverse_snow_angels", R.string.ex_reverse_snow_angels, R.string.ex_reverse_snow_angels_desc, R.string.ex_reverse_snow_angels_tech, R.string.ex_reverse_snow_angels_steps, R.string.ex_reverse_snow_angels_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 12, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
            libraryExercise("band_pull_aparts", R.string.ex_band_pull_aparts, R.string.ex_band_pull_aparts_desc, R.string.ex_band_pull_aparts_tech, R.string.ex_band_pull_aparts_steps, R.string.ex_band_pull_aparts_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 15, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
            libraryExercise("thoracic_rotations", R.string.ex_thoracic_rotations, R.string.ex_thoracic_rotations_desc, R.string.ex_thoracic_rotations_tech, R.string.ex_thoracic_rotations_steps, R.string.ex_thoracic_rotations_mistakes, imageRes = R.drawable.bird_dog, sets = 2, reps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
            libraryExercise("cat_cow", R.string.ex_cat_cow, R.string.ex_cat_cow_desc, R.string.ex_cat_cow_tech, R.string.ex_cat_cow_steps, R.string.ex_cat_cow_mistakes, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
            libraryExercise("cobra_stretch", R.string.ex_cobra_stretch, R.string.ex_cobra_stretch_desc, R.string.ex_cobra_stretch_tech, R.string.ex_cobra_stretch_steps, R.string.ex_cobra_stretch_mistakes, imageRes = R.drawable.plank, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
            libraryExercise("child_pose", R.string.ex_child_pose, R.string.ex_child_pose_desc, R.string.ex_child_pose_tech, R.string.ex_child_pose_steps, R.string.ex_child_pose_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
            libraryExercise("thoracic_extension", R.string.ex_thoracic_extension, R.string.ex_thoracic_extension_desc, R.string.ex_thoracic_extension_tech, R.string.ex_thoracic_extension_steps, R.string.ex_thoracic_extension_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, reps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
            libraryExercise("hip_flexor_stretch", R.string.ex_hip_flexor_stretch, R.string.ex_hip_flexor_stretch_desc, R.string.ex_hip_flexor_stretch_tech, R.string.ex_hip_flexor_stretch_steps, R.string.ex_hip_flexor_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),
            libraryExercise("ninety_ninety_hips", R.string.ex_ninety_ninety_hips, R.string.ex_ninety_ninety_hips_desc, R.string.ex_ninety_ninety_hips_tech, R.string.ex_ninety_ninety_hips_steps, R.string.ex_ninety_ninety_hips_mistakes, imageRes = R.drawable.squat, sets = 2, reps = 8, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
            libraryExercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.string.ex_deep_squat_steps, R.string.ex_deep_squat_mistakes, imageRes = R.drawable.squat, sets = 2, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
            libraryExercise("hamstring_stretch", R.string.ex_hamstring_stretch, R.string.ex_hamstring_stretch_desc, R.string.ex_hamstring_stretch_tech, R.string.ex_hamstring_stretch_steps, R.string.ex_hamstring_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),
            libraryExercise("glute_bridge", R.string.ex_glute_bridge, R.string.ex_glute_bridge_desc, R.string.ex_glute_bridge_tech, R.string.ex_glute_bridge_steps, R.string.ex_glute_bridge_mistakes, imageRes = R.drawable.glute_bridge, sets = 3, reps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.HIPS),
            libraryExercise("world_greatest_stretch", R.string.ex_stretch, R.string.ex_stretch_desc, R.string.ex_stretch_tech, R.string.ex_stretch_steps, R.string.ex_stretch_mistakes, sets = 2, reps = 5, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY),
            libraryExercise("bird_dog", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, imageRes = R.drawable.bird_dog, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
            libraryExercise("plank", R.string.ex_plank, R.string.ex_plank_desc, R.string.ex_plank_tech, R.string.ex_plank_steps, R.string.ex_plank_mistakes, imageRes = R.drawable.plank, sets = 3, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
            libraryExercise("lunges", R.string.ex_lunges, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
            libraryExercise("pushups", R.string.ex_pushups, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY)
        )
    }

    fun getPostureExercises(): List<Exercise> {
        return listOf(
            Exercise("hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, 0, 0, R.drawable.pull_up, 3, 1, 60, true, ExerciseCategory.POSTURE, ExerciseSubCategory.SHOULDERS),
            Exercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, 0, 0, R.drawable.ic_posture, 3, 15, 0, false, ExerciseCategory.POSTURE, ExerciseSubCategory.SHOULDERS),
            Exercise("bird_dog_p", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, R.drawable.bird_dog, 3, 12, 0, false, ExerciseCategory.POSTURE, ExerciseSubCategory.CORE),
            Exercise("superman", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.string.ex_superman_steps, R.string.ex_superman_mistakes, R.drawable.plank, 3, 12, 0, false, ExerciseCategory.POSTURE, ExerciseSubCategory.SPINE),
            Exercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, R.drawable.ic_posture, 3, 15, 0, false, ExerciseCategory.POSTURE, ExerciseSubCategory.SHOULDERS),
            Exercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, 0, 0, R.drawable.squat, 3, 1, 60, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.LEGS),
            Exercise("pike_pushups", R.string.ex_pike_pushups, R.string.ex_pike_pushups_desc, R.string.ex_pike_pushups_tech, R.string.ex_pike_pushups_steps, R.string.ex_pike_pushups_mistakes, R.drawable.pike_pushup, 3, 8, 0, false, ExerciseCategory.STRENGTH, ExerciseSubCategory.SHOULDERS),
            Exercise("horse_stance", R.string.ex_horse_stance, R.string.ex_horse_stance_desc, R.string.ex_horse_stance_tech, R.string.ex_horse_stance_steps, R.string.ex_horse_stance_mistakes, R.drawable.horse_stance, 3, 1, 60, true, ExerciseCategory.POSTURE, ExerciseSubCategory.LEGS)
        )
    }

    fun getWarmupExercises(): List<Exercise> {
        return listOf(
            Exercise("neck_circles", R.string.ex_neck_circles, R.string.ex_neck_circles, R.string.ex_neck_circles, 0, 0, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.SPINE),
            Exercise("arm_circles", R.string.ex_arm_circles, R.string.ex_arm_circles, R.string.ex_arm_circles, 0, 0, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.SHOULDERS),
            Exercise("hip_circles", R.string.ex_hip_circles, R.string.ex_hip_circles, R.string.ex_hip_circles, 0, 0, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.HIPS),
            Exercise("leg_swings", R.string.ex_leg_swings, R.string.ex_leg_swings, R.string.ex_leg_swings, 0, 0, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.LEGS),
            Exercise("jumping_jacks", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, 0, 0, R.drawable.ic_exercise_placeholder, 1, 1, 60, true, ExerciseCategory.MOBILITY, ExerciseSubCategory.FULL_BODY)
        )
    }
}
