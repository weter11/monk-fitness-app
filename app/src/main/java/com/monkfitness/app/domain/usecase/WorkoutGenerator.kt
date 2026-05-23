package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.WorkoutType

class WorkoutGenerator {

    private val allExercises = listOf(
        baseExercise("pushups", R.string.ex_pushups, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseExercise("squats", R.string.ex_squats, R.string.ex_squats_desc, R.string.ex_squats_tech, R.string.ex_squats_steps, R.string.ex_squats_mistakes, imageRes = R.drawable.squat, sets = 3, reps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("pullups", R.string.ex_pullups, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, reps = 5, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("plank", R.string.ex_plank, R.string.ex_plank_desc, R.string.ex_plank_tech, R.string.ex_plank_steps, R.string.ex_plank_mistakes, imageRes = R.drawable.plank, sets = 3, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseExercise("dips", R.string.ex_dips, R.string.ex_dips_desc, R.string.ex_dips_tech, R.string.ex_dips_steps, R.string.ex_dips_mistakes, imageRes = R.drawable.push_up, sets = 3, reps = 8, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("lunges", R.string.ex_lunges, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("rows", R.string.ex_rows, R.string.ex_rows_desc, R.string.ex_rows_tech, R.string.ex_rows_steps, R.string.ex_rows_mistakes, imageRes = R.drawable.pull_up, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("glute_bridge", R.string.ex_glute_bridge, R.string.ex_glute_bridge_desc, R.string.ex_glute_bridge_tech, R.string.ex_glute_bridge_steps, R.string.ex_glute_bridge_mistakes, imageRes = R.drawable.glute_bridge, sets = 3, reps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.HIPS),
        baseExercise("cat_cow", R.string.ex_cat_cow, R.string.ex_cat_cow_desc, R.string.ex_cat_cow_tech, R.string.ex_cat_cow_steps, R.string.ex_cat_cow_mistakes, sets = 2, reps = 15, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("bird_dog", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, imageRes = R.drawable.bird_dog, sets = 3, reps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.CORE),
        baseExercise("world_greatest_stretch", R.string.ex_stretch, R.string.ex_stretch_desc, R.string.ex_stretch_tech, R.string.ex_stretch_steps, R.string.ex_stretch_mistakes, sets = 2, reps = 5, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY),
        baseExercise("burpees", R.string.ex_burpees, R.string.ex_burpees_desc, R.string.ex_burpees_tech, R.string.ex_burpees_steps, R.string.ex_burpees_mistakes, sets = 3, reps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseExercise("mountain_climbers", R.string.ex_climbers, R.string.ex_climbers_desc, R.string.ex_climbers_tech, R.string.ex_climbers_steps, R.string.ex_climbers_mistakes, sets = 3, reps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseExercise("kettlebell_swing", R.string.ex_kb_swing, R.string.ex_kb_swing_desc, R.string.ex_kb_swing_tech, R.string.ex_kb_swing_steps, R.string.ex_kb_swing_mistakes, sets = 3, reps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseExercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.string.ex_face_pull_steps, R.string.ex_face_pull_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 15, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("scapular_pullups", R.string.ex_scapular_pullups, R.string.ex_scapular_pullups_desc, R.string.ex_scapular_pullups_tech, R.string.ex_scapular_pullups_steps, R.string.ex_scapular_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, reps = 8, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 12, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("reverse_snow_angels", R.string.ex_reverse_snow_angels, R.string.ex_reverse_snow_angels_desc, R.string.ex_reverse_snow_angels_tech, R.string.ex_reverse_snow_angels_steps, R.string.ex_reverse_snow_angels_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 12, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("band_pull_aparts", R.string.ex_band_pull_aparts, R.string.ex_band_pull_aparts_desc, R.string.ex_band_pull_aparts_tech, R.string.ex_band_pull_aparts_steps, R.string.ex_band_pull_aparts_mistakes, imageRes = R.drawable.ic_posture, sets = 3, reps = 15, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("thoracic_rotations", R.string.ex_thoracic_rotations, R.string.ex_thoracic_rotations_desc, R.string.ex_thoracic_rotations_tech, R.string.ex_thoracic_rotations_steps, R.string.ex_thoracic_rotations_mistakes, imageRes = R.drawable.bird_dog, sets = 2, reps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("cobra_stretch", R.string.ex_cobra_stretch, R.string.ex_cobra_stretch_desc, R.string.ex_cobra_stretch_tech, R.string.ex_cobra_stretch_steps, R.string.ex_cobra_stretch_mistakes, imageRes = R.drawable.plank, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("child_pose", R.string.ex_child_pose, R.string.ex_child_pose_desc, R.string.ex_child_pose_tech, R.string.ex_child_pose_steps, R.string.ex_child_pose_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("thoracic_extension", R.string.ex_thoracic_extension, R.string.ex_thoracic_extension_desc, R.string.ex_thoracic_extension_tech, R.string.ex_thoracic_extension_steps, R.string.ex_thoracic_extension_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, reps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("hip_flexor_stretch", R.string.ex_hip_flexor_stretch, R.string.ex_hip_flexor_stretch_desc, R.string.ex_hip_flexor_stretch_tech, R.string.ex_hip_flexor_stretch_steps, R.string.ex_hip_flexor_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),
        baseExercise("ninety_ninety_hips", R.string.ex_ninety_ninety_hips, R.string.ex_ninety_ninety_hips_desc, R.string.ex_ninety_ninety_hips_tech, R.string.ex_ninety_ninety_hips_steps, R.string.ex_ninety_ninety_hips_mistakes, imageRes = R.drawable.squat, sets = 2, reps = 8, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseExercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.string.ex_deep_squat_steps, R.string.ex_deep_squat_mistakes, imageRes = R.drawable.squat, sets = 2, reps = 1, durationSeconds = 45, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("hamstring_stretch", R.string.ex_hamstring_stretch, R.string.ex_hamstring_stretch_desc, R.string.ex_hamstring_stretch_tech, R.string.ex_hamstring_stretch_steps, R.string.ex_hamstring_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, imageRes = R.drawable.pull_up, sets = 3, reps = 1, durationSeconds = 60, isTimerBased = true, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("superman", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.string.ex_superman_steps, R.string.ex_superman_mistakes, imageRes = R.drawable.plank, sets = 3, reps = 12, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("pike_pushups", R.string.ex_pike_pushups, R.string.ex_pike_pushups_desc, R.string.ex_pike_pushups_tech, R.string.ex_pike_pushups_steps, R.string.ex_pike_pushups_mistakes, imageRes = R.drawable.pike_pushup, sets = 3, reps = 8, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("horse_stance", R.string.ex_horse_stance, R.string.ex_horse_stance_desc, R.string.ex_horse_stance_tech, R.string.ex_horse_stance_steps, R.string.ex_horse_stance_mistakes, imageRes = R.drawable.horse_stance, sets = 3, reps = 1, durationSeconds = 60, isTimerBased = true, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("neck_circles", R.string.ex_neck_circles, R.string.ex_neck_circles, R.string.ex_neck_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseExercise("arm_circles", R.string.ex_arm_circles, R.string.ex_arm_circles, R.string.ex_arm_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SHOULDERS),
        baseExercise("hip_circles", R.string.ex_hip_circles, R.string.ex_hip_circles, R.string.ex_hip_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseExercise("leg_swings", R.string.ex_leg_swings, R.string.ex_leg_swings, R.string.ex_leg_swings, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseExercise("jumping_jacks", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, reps = 1, durationSeconds = 60, isTimerBased = true, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY)
    )

    private val exercisesById = allExercises.associateBy { it.id }

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
                    phasedExercise("pushups", phase, baseReps = 8, baseSets = 3),
                    phasedExercise("squats", phase, baseReps = 12, baseSets = 3),
                    phasedExercise("pullups", phase, baseReps = 5, baseSets = 3),
                    phasedExercise("plank", phase, baseReps = 1, baseSets = 3, isTimer = true, baseDuration = 30)
                )
                WorkoutType.STRENGTH_B -> listOf(
                    phasedExercise("dips", phase, baseReps = 8, baseSets = 3),
                    phasedExercise("lunges", phase, baseReps = 10, baseSets = 3),
                    phasedExercise("rows", phase, baseReps = 10, baseSets = 3),
                    phasedExercise("glute_bridge", phase, baseReps = 15, baseSets = 3)
                )
                WorkoutType.MOBILITY -> listOf(
                    phasedExercise("cat_cow", phase, baseReps = 15, baseSets = 2),
                    phasedExercise("bird_dog", phase, baseReps = 10, baseSets = 3),
                    phasedExercise("world_greatest_stretch", phase, baseReps = 5, baseSets = 2)
                )
                WorkoutType.FUNCTIONAL -> listOf(
                    phasedExercise("burpees", phase, baseReps = 10, baseSets = 3),
                    phasedExercise("mountain_climbers", phase, baseReps = 20, baseSets = 3),
                    phasedExercise("kettlebell_swing", phase, baseReps = 15, baseSets = 3)
                )
                WorkoutType.REST -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun phasedExercise(
        id: String,
        phase: Int,
        baseReps: Int,
        baseSets: Int,
        isTimer: Boolean = false,
        baseDuration: Int = 0
    ): Exercise {
        val exercise = requireExercise(id)
        val sets = baseSets + (phase - 1)
        val reps = baseReps + (phase - 1) * 2
        val duration = baseDuration + (phase - 1) * 15

        return exercise.copy(
            sets = sets,
            reps = if (isTimer) 1 else reps,
            durationSeconds = duration,
            isTimerBased = isTimer
        )
    }

    private fun baseExercise(
        id: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        imageRes: Int = R.drawable.ic_exercise_placeholder,
        sets: Int,
        reps: Int,
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

    private fun requireExercise(id: String): Exercise {
        return checkNotNull(exercisesById[id]) { "Unknown exercise id: $id" }
    }

    fun getExerciseLibrary(): List<Exercise> = allExercises

    fun getPostureExercises(): List<Exercise> {
        return listOf(
            requireExercise("hang").copy(sets = 3, reps = 1, durationSeconds = 60, isTimerBased = true),
            requireExercise("face_pull").copy(sets = 3, reps = 15),
            requireExercise("bird_dog").copy(sets = 3, reps = 12, durationSeconds = 0, isTimerBased = false),
            requireExercise("superman").copy(sets = 3, reps = 12),
            requireExercise("wall_slides").copy(sets = 3, reps = 15),
            requireExercise("deep_squat").copy(sets = 3, reps = 1, durationSeconds = 60, isTimerBased = true),
            requireExercise("pike_pushups").copy(sets = 3, reps = 8, durationSeconds = 0, isTimerBased = false),
            requireExercise("horse_stance").copy(sets = 3, reps = 1, durationSeconds = 60, isTimerBased = true)
        )
    }

    fun getWarmupExercises(): List<Exercise> {
        return listOf(
            requireExercise("neck_circles").copy(sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true),
            requireExercise("arm_circles").copy(sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true),
            requireExercise("hip_circles").copy(sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true),
            requireExercise("leg_swings").copy(sets = 1, reps = 1, durationSeconds = 30, isTimerBased = true),
            requireExercise("jumping_jacks").copy(sets = 1, reps = 1, durationSeconds = 60, isTimerBased = true)
        )
    }
}
