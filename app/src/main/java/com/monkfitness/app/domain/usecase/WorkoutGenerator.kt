package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.MainCategory
import com.monkfitness.app.data.model.SubCategory
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
                    createExercise("pushups", R.string.ex_pushups, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_pushups_desc, techRes = R.string.ex_pushups_tech, stepsRes = R.string.ex_pushups_steps, mistakesRes = R.string.ex_pushups_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.FULL_BODY),
                    createExercise("squats", R.string.ex_squats, phase, baseReps = 12, baseSets = 3, imageRes = R.drawable.squat, descRes = R.string.ex_squats_desc, techRes = R.string.ex_squats_tech, stepsRes = R.string.ex_squats_steps, mistakesRes = R.string.ex_squats_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.LEGS),
                    createExercise("pullups", R.string.ex_pullups, phase, baseReps = 5, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_pullups_desc, techRes = R.string.ex_pullups_tech, stepsRes = R.string.ex_pullups_steps, mistakesRes = R.string.ex_pullups_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.FULL_BODY),
                    createExercise("plank", R.string.ex_plank, phase, baseReps = 1, baseSets = 3, isTimer = true, baseDuration = 30, imageRes = R.drawable.plank, descRes = R.string.ex_plank_desc, techRes = R.string.ex_plank_tech, stepsRes = R.string.ex_plank_steps, mistakesRes = R.string.ex_plank_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.CORE)
                )
                WorkoutType.STRENGTH_B -> listOf(
                    createExercise("dips", R.string.ex_dips, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_dips_desc, techRes = R.string.ex_dips_tech, stepsRes = R.string.ex_dips_steps, mistakesRes = R.string.ex_dips_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.SHOULDERS),
                    createExercise("lunges", R.string.ex_lunges, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.lunges, descRes = R.string.ex_lunges_desc, techRes = R.string.ex_lunges_tech, stepsRes = R.string.ex_lunges_steps, mistakesRes = R.string.ex_lunges_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.LEGS),
                    createExercise("rows", R.string.ex_rows, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_rows_desc, techRes = R.string.ex_rows_tech, stepsRes = R.string.ex_rows_steps, mistakesRes = R.string.ex_rows_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.FULL_BODY),
                    createExercise("glute_bridge", R.string.ex_glute_bridge, phase, baseReps = 15, baseSets = 3, imageRes = R.drawable.glute_bridge, descRes = R.string.ex_glute_bridge_desc, techRes = R.string.ex_glute_bridge_tech, stepsRes = R.string.ex_glute_bridge_steps, mistakesRes = R.string.ex_glute_bridge_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.HIPS)
                )
                WorkoutType.MOBILITY -> listOf(
                    createExercise("cat_cow", R.string.ex_cat_cow, phase, baseReps = 15, baseSets = 2, descRes = R.string.ex_cat_cow_desc, techRes = R.string.ex_cat_cow_tech, stepsRes = R.string.ex_cat_cow_steps, mistakesRes = R.string.ex_cat_cow_mistakes, mainCat = MainCategory.MOBILITY, subCat = SubCategory.SPINE),
                    createExercise("bird_dog", R.string.ex_bird_dog, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.bird_dog, descRes = R.string.ex_bird_dog_desc, techRes = R.string.ex_bird_dog_tech, stepsRes = R.string.ex_bird_dog_steps, mistakesRes = R.string.ex_bird_dog_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.CORE),
                    createExercise("world_greatest_stretch", R.string.ex_stretch, phase, baseReps = 5, baseSets = 2, descRes = R.string.ex_stretch_desc, techRes = R.string.ex_stretch_tech, stepsRes = R.string.ex_stretch_steps, mistakesRes = R.string.ex_stretch_mistakes, mainCat = MainCategory.MOBILITY, subCat = SubCategory.FULL_BODY)
                )
                WorkoutType.FUNCTIONAL -> listOf(
                    createExercise("burpees", R.string.ex_burpees, phase, baseReps = 10, baseSets = 3, descRes = R.string.ex_burpees_desc, techRes = R.string.ex_burpees_tech, stepsRes = R.string.ex_burpees_steps, mistakesRes = R.string.ex_burpees_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.FULL_BODY),
                    createExercise("mountain_climbers", R.string.ex_climbers, phase, baseReps = 20, baseSets = 3, descRes = R.string.ex_climbers_desc, techRes = R.string.ex_climbers_tech, stepsRes = R.string.ex_climbers_steps, mistakesRes = R.string.ex_climbers_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.CORE),
                    createExercise("kettlebell_swing", R.string.ex_kb_swing, phase, baseReps = 15, baseSets = 3, descRes = R.string.ex_kb_swing_desc, techRes = R.string.ex_kb_swing_tech, stepsRes = R.string.ex_kb_swing_steps, mistakesRes = R.string.ex_kb_swing_mistakes, mainCat = MainCategory.STRENGTH, subCat = SubCategory.FULL_BODY)
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
        mainCat: MainCategory = MainCategory.STRENGTH,
        subCat: SubCategory = SubCategory.FULL_BODY
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
            mainCategory = mainCat,
            subCategory = subCat
        )
    }

    fun getPostureExercises(): List<Exercise> {
        return listOf(
            Exercise("hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, R.string.ex_hang_steps, R.string.ex_hang_mistakes, R.drawable.pull_up, 3, 1, 60, true, MainCategory.POSTURE, SubCategory.SHOULDERS),
            Exercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.string.ex_face_pull_steps, R.string.ex_face_pull_mistakes, R.drawable.ic_posture, 3, 15, 0, false, MainCategory.POSTURE, SubCategory.SHOULDERS),
            Exercise("bird_dog_p", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, R.drawable.bird_dog, 3, 12, 0, false, MainCategory.POSTURE, SubCategory.CORE),
            Exercise("superman", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.string.ex_superman_steps, R.string.ex_superman_mistakes, R.drawable.plank, 3, 12, 0, false, MainCategory.POSTURE, SubCategory.SPINE),
            Exercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, R.drawable.ic_posture, 3, 15, 0, false, MainCategory.POSTURE, SubCategory.SHOULDERS),
            Exercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.string.ex_deep_squat_steps, R.string.ex_deep_squat_mistakes, R.drawable.squat, 3, 1, 60, true, MainCategory.POSTURE, SubCategory.HIPS),
            Exercise("pike_pushups", R.string.ex_pike_pushups, R.string.ex_pike_pushups_desc, R.string.ex_pike_pushups_tech, R.string.ex_pike_pushups_steps, R.string.ex_pike_pushups_mistakes, R.drawable.pike_pushup, 3, 8, 0, false, MainCategory.STRENGTH, SubCategory.SHOULDERS),
            Exercise("horse_stance", R.string.ex_horse_stance, R.string.ex_horse_stance_desc, R.string.ex_horse_stance_tech, R.string.ex_horse_stance_steps, R.string.ex_horse_stance_mistakes, R.drawable.horse_stance, 3, 1, 60, true, MainCategory.STRENGTH, SubCategory.LEGS)
        )
    }

    fun getWarmupExercises(): List<Exercise> {
        return listOf(
            Exercise("neck_circles", R.string.ex_neck_circles, R.string.ex_neck_circles_desc, R.string.ex_neck_circles_tech, R.string.ex_neck_circles_steps, R.string.ex_neck_circles_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, MainCategory.MOBILITY, SubCategory.SPINE),
            Exercise("arm_circles", R.string.ex_arm_circles, R.string.ex_arm_circles_desc, R.string.ex_arm_circles_tech, R.string.ex_arm_circles_steps, R.string.ex_arm_circles_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, MainCategory.MOBILITY, SubCategory.SHOULDERS),
            Exercise("hip_circles", R.string.ex_hip_circles, R.string.ex_hip_circles_desc, R.string.ex_hip_circles_tech, R.string.ex_hip_circles_steps, R.string.ex_hip_circles_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, MainCategory.MOBILITY, SubCategory.HIPS),
            Exercise("leg_swings", R.string.ex_leg_swings, R.string.ex_leg_swings_desc, R.string.ex_leg_swings_tech, R.string.ex_leg_swings_steps, R.string.ex_leg_swings_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, MainCategory.MOBILITY, SubCategory.LEGS),
            Exercise("jumping_jacks", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks_desc, R.string.ex_jumping_jacks_tech, R.string.ex_jumping_jacks_steps, R.string.ex_jumping_jacks_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 60, true, MainCategory.MOBILITY, SubCategory.FULL_BODY)
        )
    }

    fun getAllLibraryExercises(): List<Exercise> {
        val exercises = mutableListOf<Exercise>()

        // Add existing ones
        exercises.addAll(getExercisesForType(WorkoutType.STRENGTH_A, 1))
        exercises.addAll(getExercisesForType(WorkoutType.STRENGTH_B, 1))
        exercises.addAll(getExercisesForType(WorkoutType.MOBILITY, 1))
        exercises.addAll(getExercisesForType(WorkoutType.FUNCTIONAL, 1))
        exercises.addAll(getWarmupExercises())
        exercises.addAll(getPostureExercises())

        // Add new ones from curated list
        exercises.add(Exercise("scapular_pullups", R.string.ex_scapular_pullups, R.string.ex_scapular_pullups_desc, R.string.ex_scapular_pullups_tech, R.string.ex_scapular_pullups_steps, R.string.ex_scapular_pullups_mistakes, R.drawable.pull_up, 3, 12, 0, false, MainCategory.POSTURE, SubCategory.SHOULDERS))
        exercises.add(Exercise("snow_angels", R.string.ex_snow_angels, R.string.ex_snow_angels_desc, R.string.ex_snow_angels_tech, R.string.ex_snow_angels_steps, R.string.ex_snow_angels_mistakes, R.drawable.ic_posture, 3, 15, 0, false, MainCategory.POSTURE, SubCategory.SHOULDERS))
        exercises.add(Exercise("band_pullaparts", R.string.ex_band_pullaparts, R.string.ex_band_pullaparts_desc, R.string.ex_band_pullaparts_tech, R.string.ex_band_pullaparts_steps, R.string.ex_band_pullaparts_mistakes, R.drawable.ic_posture, 3, 20, 0, false, MainCategory.POSTURE, SubCategory.SHOULDERS))

        exercises.add(Exercise("thoracic_rotations", R.string.ex_thoracic_rotations, R.string.ex_thoracic_rotations_desc, R.string.ex_thoracic_rotations_tech, R.string.ex_thoracic_rotations_steps, R.string.ex_thoracic_rotations_mistakes, R.drawable.ic_exercise_placeholder, 2, 10, 0, false, MainCategory.MOBILITY, SubCategory.SPINE))
        exercises.add(Exercise("cobra_stretch", R.string.ex_cobra_stretch, R.string.ex_cobra_stretch_desc, R.string.ex_cobra_stretch_tech, R.string.ex_cobra_stretch_steps, R.string.ex_cobra_stretch_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 30, true, MainCategory.STRETCHING, SubCategory.SPINE))
        exercises.add(Exercise("childs_pose", R.string.ex_childs_pose, R.string.ex_childs_pose_desc, R.string.ex_childs_pose_tech, R.string.ex_childs_pose_steps, R.string.ex_childs_pose_mistakes, R.drawable.ic_exercise_placeholder, 1, 1, 60, true, MainCategory.STRETCHING, SubCategory.SPINE))
        exercises.add(Exercise("thoracic_extension", R.string.ex_thoracic_extension, R.string.ex_thoracic_extension_desc, R.string.ex_thoracic_extension_tech, R.string.ex_thoracic_extension_steps, R.string.ex_thoracic_extension_mistakes, R.drawable.ic_exercise_placeholder, 3, 10, 0, false, MainCategory.MOBILITY, SubCategory.SPINE))

        exercises.add(Exercise("hip_flexor_stretch", R.string.ex_hip_flexor_stretch, R.string.ex_hip_flexor_stretch_desc, R.string.ex_hip_flexor_stretch_tech, R.string.ex_hip_flexor_stretch_steps, R.string.ex_hip_flexor_stretch_mistakes, R.drawable.ic_exercise_placeholder, 2, 1, 45, true, MainCategory.STRETCHING, SubCategory.HIPS))
        exercises.add(Exercise("90_90_hips", R.string.ex_90_90_hips, R.string.ex_90_90_hips_desc, R.string.ex_90_90_hips_tech, R.string.ex_90_90_hips_steps, R.string.ex_90_90_hips_mistakes, R.drawable.ic_exercise_placeholder, 2, 10, 0, false, MainCategory.MOBILITY, SubCategory.HIPS))
        exercises.add(Exercise("hamstring_stretch", R.string.ex_hamstring_stretch, R.string.ex_hamstring_stretch_desc, R.string.ex_hamstring_stretch_tech, R.string.ex_hamstring_stretch_steps, R.string.ex_hamstring_stretch_mistakes, R.drawable.ic_exercise_placeholder, 2, 1, 45, true, MainCategory.STRETCHING, SubCategory.LEGS))

        return exercises.distinctBy { it.id }
    }
}
