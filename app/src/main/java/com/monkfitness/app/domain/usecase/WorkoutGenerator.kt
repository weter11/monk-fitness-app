package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
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
                    createExercise("pushups", R.string.ex_pushups, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_pushups_desc, techRes = R.string.ex_pushups_tech),
                    createExercise("squats", R.string.ex_squats, phase, baseReps = 12, baseSets = 3, imageRes = R.drawable.squat, descRes = R.string.ex_squats_desc, techRes = R.string.ex_squats_tech),
                    createExercise("pullups", R.string.ex_pullups, phase, baseReps = 5, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_pullups_desc, techRes = R.string.ex_pullups_tech),
                    createExercise("plank", R.string.ex_plank, phase, baseReps = 1, baseSets = 3, isTimer = true, baseDuration = 30, imageRes = R.drawable.plank, descRes = R.string.ex_plank_desc, techRes = R.string.ex_plank_tech)
                )
                WorkoutType.STRENGTH_B -> listOf(
                    createExercise("dips", R.string.ex_dips, phase, baseReps = 8, baseSets = 3, imageRes = R.drawable.push_up, descRes = R.string.ex_dips_desc, techRes = R.string.ex_dips_tech),
                    createExercise("lunges", R.string.ex_lunges, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.lunges, descRes = R.string.ex_lunges_desc, techRes = R.string.ex_lunges_tech),
                    createExercise("rows", R.string.ex_rows, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.pull_up, descRes = R.string.ex_rows_desc, techRes = R.string.ex_rows_tech),
                    createExercise("glute_bridge", R.string.ex_glute_bridge, phase, baseReps = 15, baseSets = 3, imageRes = R.drawable.glute_bridge, descRes = R.string.ex_glute_bridge_desc, techRes = R.string.ex_glute_bridge_tech)
                )
                WorkoutType.MOBILITY -> listOf(
                    createExercise("cat_cow", R.string.ex_cat_cow, phase, baseReps = 15, baseSets = 2),
                    createExercise("bird_dog", R.string.ex_bird_dog, phase, baseReps = 10, baseSets = 3, imageRes = R.drawable.bird_dog, descRes = R.string.ex_bird_dog_desc, techRes = R.string.ex_bird_dog_tech),
                    createExercise("world_greatest_stretch", R.string.ex_stretch, phase, baseReps = 5, baseSets = 2)
                )
                WorkoutType.FUNCTIONAL -> listOf(
                    createExercise("burpees", R.string.ex_burpees, phase, baseReps = 10, baseSets = 3),
                    createExercise("mountain_climbers", R.string.ex_climbers, phase, baseReps = 20, baseSets = 3),
                    createExercise("kettlebell_swing", R.string.ex_kb_swing, phase, baseReps = 15, baseSets = 3)
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
        descRes: Int = R.string.default_exercise_description,
        techRes: Int = R.string.default_exercise_technique
    ): Exercise {
        val sets = baseSets + (phase - 1)
        val reps = baseReps + (phase - 1) * 2
        val duration = baseDuration + (phase - 1) * 15

        return Exercise(
            id = id,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            imageRes = imageRes,
            sets = sets,
            reps = if (isTimer) 1 else reps,
            durationSeconds = duration,
            isTimerBased = isTimer
        )
    }

    fun getPostureExercises(): List<Exercise> {
        return listOf(
            Exercise("hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, R.drawable.pull_up, 3, 1, 60, true),
            Exercise("face_pull", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.drawable.ic_posture, 3, 15, 0, false),
            Exercise("bird_dog_p", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.drawable.bird_dog, 3, 12, 0, false),
            Exercise("superman", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.drawable.ic_plank, 3, 12, 0, false),
            Exercise("wall_slides", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.drawable.ic_posture, 3, 15, 0, false),
            Exercise("deep_squat", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.drawable.squat, 3, 1, 60, true)
        )
    }

    fun getWarmupExercises(): List<Exercise> {
        return listOf(
            Exercise("neck_circles", R.string.ex_neck_circles, R.string.ex_neck_circles, R.string.ex_neck_circles, R.drawable.ic_exercise_placeholder, 1, 1, 30, true),
            Exercise("arm_circles", R.string.ex_arm_circles, R.string.ex_arm_circles, R.string.ex_arm_circles, R.drawable.ic_exercise_placeholder, 1, 1, 30, true),
            Exercise("hip_circles", R.string.ex_hip_circles, R.string.ex_hip_circles, R.string.ex_hip_circles, R.drawable.ic_exercise_placeholder, 1, 1, 30, true),
            Exercise("leg_swings", R.string.ex_leg_swings, R.string.ex_leg_swings, R.string.ex_leg_swings, R.drawable.ic_exercise_placeholder, 1, 1, 30, true),
            Exercise("jumping_jacks", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.drawable.ic_exercise_placeholder, 1, 1, 60, true)
        )
    }
}
