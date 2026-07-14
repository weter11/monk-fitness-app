package com.monkfitness.app.domain.usecase

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseFamily
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.LibraryStats
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import com.monkfitness.app.poses.PoseRegistry
import kotlin.math.roundToInt
import kotlin.random.Random

class WorkoutGenerator {

    private data class WorkoutSelectionRule(
        val count: Int,
        val preferredMatch: (Exercise) -> Boolean,
        val fallbackMatch: (Exercise) -> Boolean = preferredMatch
    )

    private val hyperlordosisHighPriority = listOf("dead_bug", "glute_bridge", "pelvic_tilt", "hip_flexor_stretch", "couch_stretch")
    private val hyperlordosisMediumPriority = listOf("bird_dog", "side_plank", "child_pose", "thoracic_rotations")

    val families = listOf(
        ExerciseFamily("pushups", R.string.family_pushups, R.string.family_pushups_desc),
        ExerciseFamily("squats", R.string.family_squats, R.string.family_squats_desc),
        ExerciseFamily("birddog", R.string.family_birddog, R.string.family_birddog_desc),
        ExerciseFamily("hip_flexor", R.string.family_hip_flexor, R.string.family_hip_flexor_desc),
        ExerciseFamily("thoracic", R.string.family_thoracic, R.string.family_thoracic_desc),
        ExerciseFamily("hamstring", R.string.family_hamstring, R.string.family_hamstring_desc),
        ExerciseFamily("cobra", R.string.family_cobra, R.string.family_cobra_desc),
        ExerciseFamily("plank", R.string.family_plank, R.string.family_plank_desc),
        ExerciseFamily("pullups", R.string.family_pullups, R.string.family_pullups_desc),
        ExerciseFamily("lunges", R.string.family_lunges, R.string.family_lunges_desc),
        ExerciseFamily("rows", R.string.family_rows, R.string.family_rows_desc),
        ExerciseFamily("glute_bridge", R.string.family_glute_bridge, R.string.family_glute_bridge_desc),
        ExerciseFamily("pelvic_control", R.string.family_pelvic_control, R.string.family_pelvic_control_desc),
        ExerciseFamily("cat_cow", R.string.family_cat_cow, R.string.family_cat_cow_desc),
        ExerciseFamily("burpees", R.string.family_burpees, R.string.family_burpees_desc),
        ExerciseFamily("face_pull", R.string.family_face_pull, R.string.family_face_pull_desc),
        ExerciseFamily("reverse_snow_angels", R.string.family_reverse_snow_angels, R.string.family_reverse_snow_angels_desc),
        ExerciseFamily("shoulder_mobility", R.string.family_shoulder_mobility, R.string.family_shoulder_mobility_desc),
        ExerciseFamily("hip_mobility", R.string.family_hip_mobility, R.string.family_hip_mobility_desc),
        ExerciseFamily("ankle_mobility", R.string.family_ankle_mobility, R.string.family_ankle_mobility_desc),
        ExerciseFamily("dips", R.string.family_dips, R.string.family_dips_desc),
        ExerciseFamily("pike_pushups", R.string.family_pike_pushups, R.string.family_pike_pushups_desc),
        ExerciseFamily("wall_sit", R.string.family_wall_sit, R.string.family_wall_sit_desc),
        ExerciseFamily("neck_mobility", R.string.family_neck_mobility, R.string.family_neck_mobility_desc),
        ExerciseFamily("jumping_jacks", R.string.family_jumping_jacks, R.string.family_jumping_jacks_desc),
        ExerciseFamily("horse_stance", R.string.family_horse_stance, R.string.family_horse_stance_desc),
        ExerciseFamily("superman", R.string.family_superman, R.string.family_superman_desc),
        ExerciseFamily("child_pose", R.string.family_child_pose, R.string.family_child_pose_desc),
        ExerciseFamily("engineering_validation", R.string.family_engineering_validation, R.string.family_engineering_validation_desc)
    )

    private val allExercises = listOf(
        // Push-ups
        baseRepExercise("pushups", "pushups", "pushup_standard", R.string.ex_pushups, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("pushups_wide", "pushups", "pushup_wide", R.string.ex_pushups_wide, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("pushups_military", "pushups", "pushup_military", R.string.ex_pushups_military, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("pushups_knee", "pushups", "pushup_knee", R.string.ex_pushups_knee, R.string.ex_pushups_desc, R.string.ex_pushups_tech, R.string.ex_pushups_steps, R.string.ex_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 8, baseMaxReps = 12, phase4MinReps = 12, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("decline_pushups", "pushups", "pushup_decline", R.string.ex_decline_pushups, R.string.ex_decline_pushups_desc, R.string.ex_decline_pushups_tech, R.string.ex_decline_pushups_steps, R.string.ex_decline_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("diamond_pushups", "pushups", "pushup_diamond", R.string.ex_diamond_pushups, R.string.ex_diamond_pushups_desc, R.string.ex_diamond_pushups_tech, R.string.ex_diamond_pushups_steps, R.string.ex_diamond_pushups_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),

        // Squats
        baseRepExercise("squats", "squats", "squat_standard", R.string.ex_squats, R.string.ex_squats_desc, R.string.ex_squats_tech, R.string.ex_squats_steps, R.string.ex_squats_mistakes, imageRes = R.drawable.squat, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("squats_sumo", "squats", "squat_sumo", R.string.ex_squats_sumo, R.string.ex_squats_desc, R.string.ex_squats_tech, R.string.ex_squats_steps, R.string.ex_squats_mistakes, imageRes = R.drawable.squat, sets = 3, baseMinReps = 10, baseMaxReps = 14, phase4MinReps = 16, phase4MaxReps = 22, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("squats_jump", "squats", "squat_jump", R.string.ex_squats_jump, R.string.ex_squats_desc, R.string.ex_squats_tech, R.string.ex_squats_steps, R.string.ex_squats_mistakes, imageRes = R.drawable.squat, sets = 3, baseMinReps = 8, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseTimerExercise("deep_squat", "squats", "deep_squat_hold", R.string.ex_deep_squat, R.string.ex_deep_squat_desc, R.string.ex_deep_squat_tech, R.string.ex_deep_squat_steps, R.string.ex_deep_squat_mistakes, imageRes = R.drawable.squat, sets = 2, baseDurationSeconds = 45, phase4DurationSeconds = 90, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),

        // Pull-ups
        baseRepExercise("pullups", "pullups", "pullup_standard", R.string.ex_pullups, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 3, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("pullups_chin", "pullups", "chinup_standard", R.string.ex_pullups_chin, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 4, baseMaxReps = 6, phase4MinReps = 7, phase4MaxReps = 11, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("pullups_neutral", "pullups", "pullup_neutral", R.string.ex_pullups_neutral, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 3, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("pullups_wide", "pullups", "pullup_wide", R.string.ex_pullups_wide, R.string.ex_pullups_desc, R.string.ex_pullups_tech, R.string.ex_pullups_steps, R.string.ex_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 2, baseMaxReps = 4, phase4MinReps = 5, phase4MaxReps = 8, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseTimerExercise("hang", "pullups", "dead_hang", R.string.ex_hang, R.string.ex_hang_desc, R.string.ex_hang_tech, imageRes = R.drawable.pull_up, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 90, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),

        // --- Engineering Validation (static biomechanics reference poses) ---
        // These are NOT exercises: they are flagged isTestPose = true so they are
        // excluded from workout generation, recommendations, statistics and
        // progression. They appear only in the exercise browser / search and open
        // in the standard viewer for engine validation.
        baseTimerExercise("test_middle_split", "engineering_validation", "test_middle_split", R.string.ex_test_middle_split, R.string.ex_test_middle_split_desc, R.string.ex_test_middle_split_tech, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 10, phase4DurationSeconds = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY, isTestPose = true),
        baseTimerExercise("test_pike_sit", "engineering_validation", "test_pike_sit", R.string.ex_test_pike_sit, R.string.ex_test_pike_sit_desc, R.string.ex_test_pike_sit_tech, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 10, phase4DurationSeconds = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY, isTestPose = true),
        baseTimerExercise("test_deep_overhead_squat", "engineering_validation", "test_deep_overhead_squat", R.string.ex_test_deep_overhead_squat, R.string.ex_test_deep_overhead_squat_desc, R.string.ex_test_deep_overhead_squat_tech, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 10, phase4DurationSeconds = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY, isTestPose = true),
        baseTimerExercise("test_dead_hang", "engineering_validation", "test_dead_hang", R.string.ex_test_dead_hang, R.string.ex_test_dead_hang_desc, R.string.ex_test_dead_hang_tech, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 10, phase4DurationSeconds = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY, isTestPose = true),

        // Plank
        baseTimerExercise("plank", "plank", "plank_standard", R.string.ex_plank, R.string.ex_plank_desc, R.string.ex_plank_tech, R.string.ex_plank_steps, R.string.ex_plank_mistakes, imageRes = R.drawable.plank, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseTimerExercise("side_plank", "plank", "side_plank_standard", R.string.ex_side_plank, R.string.ex_side_plank_desc, R.string.ex_side_plank_tech, R.string.ex_side_plank_steps, R.string.ex_side_plank_mistakes, imageRes = R.drawable.plank, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),

        // Lunges
        baseRepExercise("lunges", "lunges", "lunge_forward", R.string.ex_lunges, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("lunges_reverse", "lunges", "lunge_reverse", R.string.ex_lunges_reverse, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("lunges_side", "lunges", "lunge_side", R.string.ex_lunges_side, R.string.ex_lunges_desc, R.string.ex_lunges_tech, R.string.ex_lunges_steps, R.string.ex_lunges_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 6, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),
        baseRepExercise("step_ups", "lunges", "step_up_standard", R.string.ex_step_ups, R.string.ex_step_ups_desc, R.string.ex_step_ups_tech, R.string.ex_step_ups_steps, R.string.ex_step_ups_mistakes, imageRes = R.drawable.lunges, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),

        // Rows
        baseRepExercise("rows", "rows", "row_standard", R.string.ex_rows, R.string.ex_rows_desc, R.string.ex_rows_tech, R.string.ex_rows_steps, R.string.ex_rows_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),

        // Glute Bridge
        baseRepExercise("glute_bridge", "glute_bridge", "glute_bridge_standard", R.string.ex_glute_bridge, R.string.ex_glute_bridge_desc, R.string.ex_glute_bridge_tech, R.string.ex_glute_bridge_steps, R.string.ex_glute_bridge_mistakes, imageRes = R.drawable.glute_bridge, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.HIPS),

        // Pelvic Control
        baseRepExercise("dead_bug", "pelvic_control", "dead_bug_standard", R.string.ex_dead_bug, R.string.ex_dead_bug_desc, R.string.ex_dead_bug_tech, R.string.ex_dead_bug_steps, R.string.ex_dead_bug_mistakes, imageRes = R.drawable.plank, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 20, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("leg_raises", "pelvic_control", "leg_raise_standard", R.string.ex_leg_raises, R.string.ex_leg_raises_desc, R.string.ex_leg_raises_tech, R.string.ex_leg_raises_steps, R.string.ex_leg_raises_mistakes, imageRes = R.drawable.plank, sets = 3, baseMinReps = 10, baseMaxReps = 15, phase4MinReps = 15, phase4MaxReps = 25, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("pelvic_tilt", "pelvic_control", "pelvic_tilt_standard", R.string.ex_pelvic_tilt, R.string.ex_pelvic_tilt_desc, R.string.ex_pelvic_tilt_tech, R.string.ex_pelvic_tilt_steps, R.string.ex_pelvic_tilt_mistakes, imageRes = R.drawable.glute_bridge, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.HYPERLORDOSIS),

        // Hip Flexor
        baseTimerExercise("couch_stretch", "hip_flexor", "couch_stretch_hold", R.string.ex_couch_stretch, R.string.ex_couch_stretch_desc, R.string.ex_couch_stretch_tech, R.string.ex_couch_stretch_steps, R.string.ex_couch_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, baseDurationSeconds = 45, phase4DurationSeconds = 90, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HYPERLORDOSIS),
        baseTimerExercise("hip_flexor_stretch", "hip_flexor", "hip_flexor_stretch_hold", R.string.ex_hip_flexor_stretch, R.string.ex_hip_flexor_stretch_desc, R.string.ex_hip_flexor_stretch_tech, R.string.ex_hip_flexor_stretch_steps, R.string.ex_hip_flexor_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),

        // Spinal Articulation
        baseRepExercise("cat_cow", "cat_cow", "cat_cow_reps", R.string.ex_cat_cow, R.string.ex_cat_cow_desc, R.string.ex_cat_cow_tech, R.string.ex_cat_cow_steps, R.string.ex_cat_cow_mistakes, sets = 2, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 16, phase4MaxReps = 20, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseTimerExercise("cobra_stretch", "cobra", "cobra_stretch_hold", R.string.ex_cobra_stretch, R.string.ex_cobra_stretch_desc, R.string.ex_cobra_stretch_tech, R.string.ex_cobra_stretch_steps, R.string.ex_cobra_stretch_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),

        // Bird Dog
        baseRepExercise("bird_dog", "birddog", "birddog_hold", R.string.ex_bird_dog, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, imageRes = R.drawable.bird_dog, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 14, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.CORE),
        baseRepExercise("bird_dog_reps", "birddog", "birddog_reps", R.string.ex_bird_dog_reps, R.string.ex_bird_dog_desc, R.string.ex_bird_dog_tech, R.string.ex_bird_dog_steps, R.string.ex_bird_dog_mistakes, imageRes = R.drawable.bird_dog, sets = 3, baseMinReps = 8, baseMaxReps = 12, phase4MinReps = 12, phase4MaxReps = 18, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.CORE),

        // Thoracic
        baseRepExercise("world_greatest_stretch", "thoracic", "world_greatest_stretch", R.string.ex_stretch, R.string.ex_stretch_desc, R.string.ex_stretch_tech, R.string.ex_stretch_steps, R.string.ex_stretch_mistakes, sets = 2, baseMinReps = 4, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 10, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("thoracic_rotations", "thoracic", "thoracic_rotations_reps", R.string.ex_thoracic_rotations, R.string.ex_thoracic_rotations_desc, R.string.ex_thoracic_rotations_tech, R.string.ex_thoracic_rotations_steps, R.string.ex_thoracic_rotations_mistakes, imageRes = R.drawable.bird_dog, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),
        baseRepExercise("thoracic_extension", "thoracic", "thoracic_extension_reps", R.string.ex_thoracic_extension, R.string.ex_thoracic_extension_desc, R.string.ex_thoracic_extension_tech, R.string.ex_thoracic_extension_steps, R.string.ex_thoracic_extension_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),

        // Burpees
        baseRepExercise("burpees", "burpees", "burpee_standard", R.string.ex_burpees, R.string.ex_burpees_desc, R.string.ex_burpees_tech, R.string.ex_burpees_steps, R.string.ex_burpees_mistakes, sets = 3, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 15, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY),
        baseRepExercise("mountain_climbers", "burpees", "mountain_climber_standard", R.string.ex_climbers, R.string.ex_climbers_desc, R.string.ex_climbers_tech, R.string.ex_climbers_steps, R.string.ex_climbers_mistakes, sets = 3, baseMinReps = 12, baseMaxReps = 16, phase4MinReps = 18, phase4MaxReps = 28, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.CORE),

        // Face Pull
        baseRepExercise("face_pull", "face_pull", "face_pull_banded", R.string.ex_face_pull, R.string.ex_face_pull_desc, R.string.ex_face_pull_tech, R.string.ex_face_pull_steps, R.string.ex_face_pull_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 24, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BANDS)),
        baseRepExercise("band_pull_aparts", "face_pull", "band_pull_aparts_standard", R.string.ex_band_pull_aparts, R.string.ex_band_pull_aparts_desc, R.string.ex_band_pull_aparts_tech, R.string.ex_band_pull_aparts_steps, R.string.ex_band_pull_aparts_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 12, baseMaxReps = 15, phase4MinReps = 18, phase4MaxReps = 25, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BANDS)),
        baseRepExercise("y_t_raises", "face_pull", "yt_raises_standard", R.string.ex_y_t_raises, R.string.ex_y_t_raises_desc, R.string.ex_y_t_raises_tech, R.string.ex_y_t_raises_steps, R.string.ex_y_t_raises_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("scapular_retraction_hold", "face_pull", "scapular_retraction_hold", R.string.ex_scapular_retraction_hold, R.string.ex_scapular_retraction_hold_desc, R.string.ex_scapular_retraction_hold_tech, R.string.ex_scapular_retraction_hold_steps, R.string.ex_scapular_retraction_hold_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),

        // Reverse Snow Angels
        baseRepExercise("reverse_snow_angels", "reverse_snow_angels", "reverse_snow_angel_prone", R.string.ex_reverse_snow_angels, R.string.ex_reverse_snow_angels_desc, R.string.ex_reverse_snow_angels_tech, R.string.ex_reverse_snow_angels_steps, R.string.ex_reverse_snow_angels_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),
        baseRepExercise("scapular_pullups", "reverse_snow_angels", "scapular_pullup_deadhang", R.string.ex_scapular_pullups, R.string.ex_scapular_pullups_desc, R.string.ex_scapular_pullups_tech, R.string.ex_scapular_pullups_steps, R.string.ex_scapular_pullups_mistakes, imageRes = R.drawable.pull_up, sets = 3, baseMinReps = 4, baseMaxReps = 6, phase4MinReps = 7, phase4MaxReps = 10, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),
        baseRepExercise("wall_slides", "reverse_snow_angels", "wall_slide_standard", R.string.ex_wall_slides, R.string.ex_wall_slides_desc, R.string.ex_wall_slides_tech, R.string.ex_wall_slides_steps, R.string.ex_wall_slides_mistakes, imageRes = R.drawable.ic_posture, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SHOULDERS),

        // Shoulder Mobility
        baseRepExercise("shoulder_cars", "shoulder_mobility", "shoulder_cars_standard", R.string.ex_shoulder_cars, R.string.ex_shoulder_cars_desc, R.string.ex_shoulder_cars_tech, R.string.ex_shoulder_cars_steps, R.string.ex_shoulder_cars_mistakes, sets = 2, baseMinReps = 4, baseMaxReps = 5, phase4MinReps = 6, phase4MaxReps = 8, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("lat_stretch", "shoulder_mobility", "lat_stretch_hold", R.string.ex_lat_stretch, R.string.ex_lat_stretch_desc, R.string.ex_lat_stretch_tech, R.string.ex_lat_stretch_steps, R.string.ex_lat_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SHOULDERS),

        // Hip Mobility
        baseRepExercise("hip_cars", "hip_mobility", "hip_cars_standard", R.string.ex_hip_cars, R.string.ex_hip_cars_desc, R.string.ex_hip_cars_tech, R.string.ex_hip_cars_steps, R.string.ex_hip_cars_mistakes, sets = 2, baseMinReps = 3, baseMaxReps = 4, phase4MinReps = 5, phase4MaxReps = 6, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseRepExercise("ninety_ninety_hips", "hip_mobility", "ninety_ninety_hips", R.string.ex_ninety_ninety_hips, R.string.ex_ninety_ninety_hips_desc, R.string.ex_ninety_ninety_hips_tech, R.string.ex_ninety_ninety_hips_steps, R.string.ex_ninety_ninety_hips_mistakes, imageRes = R.drawable.squat, sets = 2, baseMinReps = 6, baseMaxReps = 8, phase4MinReps = 10, phase4MaxReps = 14, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseTimerExercise("piriformis_stretch", "hip_mobility", "piriformis_stretch_hold", R.string.ex_piriformis_stretch, R.string.ex_piriformis_stretch_desc, R.string.ex_piriformis_stretch_tech, R.string.ex_piriformis_stretch_steps, R.string.ex_piriformis_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.HIPS),

        // Ankle Mobility
        baseRepExercise("ankle_mobility", "ankle_mobility", "ankle_mobility_standard", R.string.ex_ankle_mobility, R.string.ex_ankle_mobility_desc, R.string.ex_ankle_mobility_tech, R.string.ex_ankle_mobility_steps, R.string.ex_ankle_mobility_mistakes, sets = 2, baseMinReps = 8, baseMaxReps = 10, phase4MinReps = 12, phase4MaxReps = 16, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("calf_stretch", "ankle_mobility", "calf_stretch_hold", R.string.ex_calf_stretch, R.string.ex_calf_stretch_desc, R.string.ex_calf_stretch_tech, R.string.ex_calf_stretch_steps, R.string.ex_calf_stretch_mistakes, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 60, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),

        // Dips
        baseRepExercise("dips", "dips", "dip_parallel_bar", R.string.ex_dips, R.string.ex_dips_desc, R.string.ex_dips_tech, R.string.ex_dips_steps, R.string.ex_dips_mistakes, imageRes = R.drawable.push_up, sets = 3, baseMinReps = 5, baseMaxReps = 7, phase4MinReps = 8, phase4MaxReps = 12, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS, requiredEquipment = setOf(Equipment.BAR)),

        // Pike Pushups
        baseRepExercise("pike_pushups", "pike_pushups", "pike_pushup_standard", R.string.ex_pike_pushups, R.string.ex_pike_pushups_desc, R.string.ex_pike_pushups_tech, R.string.ex_pike_pushups_steps, R.string.ex_pike_pushups_mistakes, imageRes = R.drawable.pike_pushup, sets = 3, baseMinReps = 4, baseMaxReps = 6, phase4MinReps = 7, phase4MaxReps = 10, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.SHOULDERS),

        // Wall Sit
        baseTimerExercise("wall_sit", "wall_sit", "wall_sit_hold", R.string.ex_wall_sit, R.string.ex_wall_sit_desc, R.string.ex_wall_sit_tech, R.string.ex_wall_sit_steps, R.string.ex_wall_sit_mistakes, imageRes = R.drawable.horse_stance, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.LEGS),

        // Neck Mobility
        baseRepExercise("chin_tucks", "neck_mobility", "chin_tuck_standard", R.string.ex_chin_tucks, R.string.ex_chin_tucks_desc, R.string.ex_chin_tucks_tech, R.string.ex_chin_tucks_steps, R.string.ex_chin_tucks_mistakes, sets = 2, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SPINE),
        baseTimerExercise("neck_circles", "neck_mobility", "neck_circles_hold", R.string.ex_neck_circles, R.string.ex_neck_circles, R.string.ex_neck_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SPINE),

        // Jumping Jacks
        baseTimerExercise("jumping_jacks", "jumping_jacks", "jumping_jack_standard", R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, R.string.ex_jumping_jacks, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.FULL_BODY),

        // Horse Stance
        baseTimerExercise("horse_stance", "horse_stance", "horse_stance_hold", R.string.ex_horse_stance, R.string.ex_horse_stance_desc, R.string.ex_horse_stance_tech, R.string.ex_horse_stance_steps, R.string.ex_horse_stance_mistakes, imageRes = R.drawable.horse_stance, sets = 3, baseDurationSeconds = 30, phase4DurationSeconds = 90, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.LEGS),

        // Superman
        baseRepExercise("superman", "superman", "superman_prone", R.string.ex_superman, R.string.ex_superman_desc, R.string.ex_superman_tech, R.string.ex_superman_steps, R.string.ex_superman_mistakes, imageRes = R.drawable.plank, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 14, phase4MaxReps = 18, category = ExerciseCategory.POSTURE, subCategory = ExerciseSubCategory.SPINE),

        // Child Pose
        baseTimerExercise("child_pose", "child_pose", "child_pose_hold", R.string.ex_child_pose, R.string.ex_child_pose_desc, R.string.ex_child_pose_tech, R.string.ex_child_pose_steps, R.string.ex_child_pose_mistakes, imageRes = R.drawable.ic_exercise_placeholder, sets = 2, baseDurationSeconds = 45, phase4DurationSeconds = 90, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.SPINE),

        // Misc / Others
        baseRepExercise("kettlebell_swing", "burpees", "kb_swing_backpack", R.string.ex_kb_swing, R.string.ex_kb_swing_desc, R.string.ex_kb_swing_tech, R.string.ex_kb_swing_steps, R.string.ex_kb_swing_mistakes, sets = 3, baseMinReps = 10, baseMaxReps = 12, phase4MinReps = 16, phase4MaxReps = 22, category = ExerciseCategory.STRENGTH, subCategory = ExerciseSubCategory.FULL_BODY, requiredEquipment = setOf(Equipment.BACKPACK)),
        baseTimerExercise("hamstring_stretch", "hamstring", "hamstring_stretch_hold", R.string.ex_hamstring_stretch, R.string.ex_hamstring_stretch_desc, R.string.ex_hamstring_stretch_tech, R.string.ex_hamstring_stretch_steps, R.string.ex_hamstring_stretch_mistakes, imageRes = R.drawable.lunges, sets = 2, baseDurationSeconds = 30, phase4DurationSeconds = 75, category = ExerciseCategory.STRETCHING, subCategory = ExerciseSubCategory.LEGS),
        baseTimerExercise("arm_circles", "shoulder_mobility", "arm_circles_hold", R.string.ex_arm_circles, R.string.ex_arm_circles, R.string.ex_arm_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.SHOULDERS),
        baseTimerExercise("hip_circles", "hip_mobility", "hip_circles_hold", R.string.ex_hip_circles, R.string.ex_hip_circles, R.string.ex_hip_circles, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.HIPS),
        baseTimerExercise("leg_swings", "hip_mobility", "leg_swings_hold", R.string.ex_leg_swings, R.string.ex_leg_swings, R.string.ex_leg_swings, imageRes = R.drawable.ic_exercise_placeholder, sets = 1, baseDurationSeconds = 20, phase4DurationSeconds = 45, category = ExerciseCategory.MOBILITY, subCategory = ExerciseSubCategory.LEGS)
    )

    private val oldToNewIdMap = mapOf(
        "pushups" to "pushups",
        "decline_pushups" to "decline_pushups",
        "diamond_pushups" to "diamond_pushups",
        "squats" to "squats",
        "pullups" to "pullups",
        "plank" to "plank",
        "dips" to "dips",
        "lunges" to "lunges",
        "step_ups" to "step_ups",
        "rows" to "rows",
        "glute_bridge" to "glute_bridge",
        "dead_bug" to "dead_bug",
        "side_plank" to "side_plank",
        "pelvic_tilt" to "pelvic_tilt",
        "couch_stretch" to "couch_stretch",
        "cat_cow" to "cat_cow",
        "bird_dog" to "bird_dog",
        "world_greatest_stretch" to "world_greatest_stretch",
        "burpees" to "burpees",
        "mountain_climbers" to "mountain_climbers",
        "kettlebell_swing" to "kettlebell_swing",
        "face_pull" to "face_pull",
        "scapular_pullups" to "scapular_pullups",
        "wall_slides" to "wall_slides",
        "reverse_snow_angels" to "reverse_snow_angels",
        "band_pull_aparts" to "band_pull_aparts",
        "thoracic_rotations" to "thoracic_rotations",
        "shoulder_cars" to "shoulder_cars",
        "cobra_stretch" to "cobra_stretch",
        "child_pose" to "child_pose",
        "thoracic_extension" to "thoracic_extension",
        "hip_flexor_stretch" to "hip_flexor_stretch",
        "hip_cars" to "hip_cars",
        "ninety_ninety_hips" to "ninety_ninety_hips",
        "deep_squat" to "deep_squat",
        "ankle_mobility" to "ankle_mobility",
        "hamstring_stretch" to "hamstring_stretch",
        "hang" to "hang",
        "superman" to "superman",
        "pike_pushups" to "pike_pushups",
        "wall_sit" to "wall_sit",
        "chin_tucks" to "chin_tucks",
        "y_t_raises" to "y_t_raises",
        "scapular_retraction_hold" to "scapular_retraction_hold",
        "horse_stance" to "horse_stance",
        "calf_stretch" to "calf_stretch",
        "lat_stretch" to "lat_stretch",
        "piriformis_stretch" to "piriformis_stretch",
        "neck_circles" to "neck_circles",
        "arm_circles" to "arm_circles",
        "hip_circles" to "hip_circles",
        "leg_swings" to "leg_swings",
        "jumping_jacks" to "jumping_jacks"
    )

    private val exercisesById = allExercises.associateBy { it.id }

    private fun getEligibleExercises(disabledFamilies: Set<String>): List<Exercise> {
        val production = allExercises.filter { !it.isTestPose }
        val eligible = production.filter { exercise ->
            val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
            families.isEmpty() || families.none { it.key in disabledFamilies }
        }
        return eligible.ifEmpty { production }
    }

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
        availableEquipment: Set<Equipment> = emptySet(),
        disabledFamilies: Set<String> = emptySet()
    ): Workout {
        val safeDay = if (day in 1..56) day else 1
        val week = ((safeDay - 1) / 7) + 1
        val phase = (((week - 1) / 2) + 1).coerceIn(1, 4)
        val type = getWorkoutType(safeDay)

        return Workout(
            id = safeDay,
            type = type,
            exercises = getExercisesForType(type, phase, safeDay, flexibilityTrainingType, focusAreas, availableEquipment, disabledFamilies)
        )
    }

    fun generatePostureMobilityWorkout(
        day: Int,
        flexibilityTrainingType: FlexibilityTrainingType = FlexibilityTrainingType.BOTH,
        focusAreas: Set<ExerciseSubCategory> = setOf(ExerciseSubCategory.FULL_BODY),
        availableEquipment: Set<Equipment> = emptySet(),
        disabledFamilies: Set<String> = emptySet()
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
                    focusAreas = focusAreas,
                    disabledFamilies = disabledFamilies
                ),
                availableEquipment = availableEquipment,
                phase = phase,
                random = Random(safeDay * 10_000 + WorkoutType.POSTURE_MOBILITY.ordinal),
                disabledFamilies = disabledFamilies
            )
        )
    }

    private fun getExercisesForType(
        type: WorkoutType,
        phase: Int,
        daySeed: Int,
        flexibilityTrainingType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>,
        availableEquipment: Set<Equipment>,
        disabledFamilies: Set<String> = emptySet()
    ): List<Exercise> {
        val isHyperlordosisActive = ExerciseSubCategory.HYPERLORDOSIS in focusAreas
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
                        focusAreas = focusAreas,
                        disabledFamilies = disabledFamilies
                    ),
                    availableEquipment = availableEquipment,
                    phase = phase,
                    random = Random(daySeed * 10_000 + type.ordinal),
                    disabledFamilies = disabledFamilies
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
                        focusAreas = focusAreas,
                        disabledFamilies = disabledFamilies
                    ),
                    availableEquipment = availableEquipment,
                    phase = phase,
                    random = Random(daySeed * 10_000 + type.ordinal),
                    disabledFamilies = disabledFamilies
                )
                WorkoutType.REST -> return emptyList()
            }

            val rawExercises = selectExercises(selectionRules, phase, Random(daySeed * 1_000 + type.ordinal), disabledFamilies)
            val adaptedExercises = if (isHyperlordosisActive) {
                rawExercises.map { exercise ->
                    if (exercise.id == "superman") {
                        val birdDog = requireExercise("bird_dog")
                        phasedExercise(birdDog, phase)
                    } else {
                        exercise
                    }
                }
            } else {
                rawExercises
            }

            filterExercisesForAvailableEquipment(
                exercises = adaptedExercises,
                availableEquipment = availableEquipment,
                phase = phase,
                random = Random(daySeed * 10_000 + type.ordinal),
                disabledFamilies = disabledFamilies
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
        focusAreas: Set<ExerciseSubCategory>,
        disabledFamilies: Set<String> = emptySet()
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
                random = Random(daySeed * 1_000 + index),
                disabledFamilies = disabledFamilies
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
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): Exercise {
        val eligible = getEligibleExercises(disabledFamilies)
        val candidates = eligible.filter { exercise ->
            exercise.id !in selectedIds && exercise.matchesTrainingType(trainingType)
        }

        fun select(match: (Exercise) -> Boolean): Exercise? = candidates.filter(match).randomOrNull(random)

        if (ExerciseSubCategory.HYPERLORDOSIS in prioritizedFocusAreas) {
            val highPriority = select { it.id in hyperlordosisHighPriority && it.matchesTrainingType(trainingType) }
            if (highPriority != null) return highPriority

            val mediumPriority = select { it.id in hyperlordosisMediumPriority && it.matchesTrainingType(trainingType) }
            if (mediumPriority != null) return mediumPriority
        }

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
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): List<Exercise> {
        val selectedIds = mutableSetOf<String>()

        return buildList {
            selectionRules.forEach { rule ->
                repeat(rule.count) {
                    val exercise = pickExercise(rule, selectedIds, random, disabledFamilies)
                    selectedIds += exercise.id
                    add(phasedExercise(exercise, phase))
                }
            }
        }
    }

    private fun pickExercise(
        rule: WorkoutSelectionRule,
        selectedIds: Set<String>,
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): Exercise {
        val eligible = getEligibleExercises(disabledFamilies)
        val preferredMatches = eligible.filter { it.id !in selectedIds && rule.preferredMatch(it) }
        val fallbackMatches = eligible.filter { it.id !in selectedIds && rule.fallbackMatch(it) }

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
        familyId: String,
        animationId: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        imageRes: Int? = null,
        sets: Int,
        baseMinReps: Int,
        baseMaxReps: Int,
        phase4MinReps: Int,
        phase4MaxReps: Int,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory,
        requiredEquipment: Set<Equipment> = emptySet(),
        isTestPose: Boolean = false
    ): Exercise {
        val currentMaxReps = baseMaxReps.coerceAtLeast(baseMinReps)
        return Exercise(
            id = id,
            familyId = familyId,
            animationId = animationId,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
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
            requiredEquipment = normalizeRequiredEquipment(requiredEquipment),
            isTestPose = isTestPose
        )
    }

    private fun baseTimerExercise(
        id: String,
        familyId: String,
        animationId: String,
        nameRes: Int,
        descRes: Int,
        techRes: Int,
        stepsRes: Int = 0,
        mistakesRes: Int = 0,
        imageRes: Int? = null,
        sets: Int,
        baseDurationSeconds: Int,
        phase4DurationSeconds: Int,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory,
        requiredEquipment: Set<Equipment> = emptySet(),
        isTestPose: Boolean = false
    ): Exercise {
        return Exercise(
            id = id,
            familyId = familyId,
            animationId = animationId,
            nameRes = nameRes,
            descriptionRes = descRes,
            techniqueRes = techRes,
            stepsRes = stepsRes,
            mistakesRes = mistakesRes,
            imageRes = imageRes,
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
            requiredEquipment = normalizeRequiredEquipment(requiredEquipment),
            isTestPose = isTestPose
        )
    }

    private fun requireExercise(id: String): Exercise {
        val mappedId = oldToNewIdMap[id] ?: id
        return checkNotNull(exercisesById[mappedId]) { "Unknown exercise id: $id" }
    }

    fun getExerciseLibrary(
        availableEquipment: Set<Equipment> = emptySet()
    ): List<Exercise> {
        val normalizedEquipment = normalizeAvailableEquipment(availableEquipment)
        return allExercises.filter { it.isAccessibleWith(normalizedEquipment) }
    }

    private fun filterExercisesForAvailableEquipment(
        exercises: List<Exercise>,
        availableEquipment: Set<Equipment>,
        phase: Int,
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): List<Exercise> {
        if (exercises.isEmpty()) return emptyList()

        val normalizedEquipment = normalizeAvailableEquipment(availableEquipment)
        val selectedIds = mutableSetOf<String>()
        val resolvedExercises = mutableListOf<Exercise>()
        val unresolvedProfiles = mutableListOf<Exercise>()

        exercises.forEach { exercise ->
            val resolved = when {
                exercise.id !in selectedIds && exercise.isAccessibleWith(normalizedEquipment) -> exercise
                else -> findReplacementExercise(exercise, selectedIds, normalizedEquipment, phase, random, disabledFamilies)
            }

            if (resolved != null) {
                selectedIds += resolved.id
                resolvedExercises += resolved
            } else {
                unresolvedProfiles += exercise
            }
        }

        unresolvedProfiles.forEach { exercise ->
            val fallback = findBodyweightReplacement(exercise, selectedIds, phase, random, disabledFamilies) ?: return@forEach
            selectedIds += fallback.id
            resolvedExercises += fallback
        }

        return if (resolvedExercises.isNotEmpty()) {
            resolvedExercises
        } else {
            buildBodyweightFallback(exercises, selectedIds, phase, random, disabledFamilies)
        }
    }

    private fun findReplacementExercise(
        target: Exercise,
        selectedIds: Set<String>,
        availableEquipment: Set<Equipment>,
        phase: Int,
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): Exercise? {
        val eligible = getEligibleExercises(disabledFamilies)
        val accessibleCandidates = eligible.filter { candidate ->
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
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): Exercise? {
        val eligible = getEligibleExercises(disabledFamilies)
        val bodyweightCandidates = eligible.filter { candidate ->
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
        random: Random,
        disabledFamilies: Set<String> = emptySet()
    ): List<Exercise> {
        val mutableSelectedIds = selectedIds.toMutableSet()
        val fallback = mutableListOf<Exercise>()

        exercises.forEach { exercise ->
            val replacement = findBodyweightReplacement(exercise, mutableSelectedIds, phase, random, disabledFamilies) ?: return@forEach
            mutableSelectedIds += replacement.id
            fallback += replacement
        }

        if (fallback.isNotEmpty()) {
            return fallback
        }

        val eligible = getEligibleExercises(disabledFamilies)
        return eligible
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
        return when {
            availableEquipment.isEmpty() -> emptySet()
            availableEquipment == setOf(Equipment.NONE) -> setOf(Equipment.NONE)
            else -> availableEquipment.filter { it != Equipment.NONE }.toSet()
        }
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
        val normalizedAvailableEquipment = normalizeAvailableEquipment(availableEquipment)
        return when {
            normalizedAvailableEquipment.isEmpty() -> true
            normalizedAvailableEquipment == setOf(Equipment.NONE) -> normalizeRequiredEquipment(requiredEquipment).isEmpty()
            else -> normalizeRequiredEquipment(requiredEquipment).all { it in normalizedAvailableEquipment }
        }
    }

    fun getPostureExercises(focusAreas: Set<ExerciseSubCategory> = emptySet()): List<Exercise> {
        val isHyperlordosisActive = ExerciseSubCategory.HYPERLORDOSIS in focusAreas
        val basePostureList = listOf(
            requireExercise("hang").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true),
            requireExercise("face_pull").copy(sets = 3, reps = 15, minReps = 15, maxReps = 15, durationSeconds = 0, isTimerBased = false),
            requireExercise("bird_dog").copy(sets = 3, reps = 12, minReps = 12, maxReps = 12, durationSeconds = 0, isTimerBased = false),
            requireExercise("superman").copy(sets = 3, reps = 12, minReps = 12, maxReps = 12),
            requireExercise("wall_slides").copy(sets = 3, reps = 15, minReps = 15, maxReps = 15),
            requireExercise("deep_squat").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true),
            requireExercise("pike_pushups").copy(sets = 3, reps = 8, minReps = 8, maxReps = 8, durationSeconds = 0, isTimerBased = false),
            requireExercise("horse_stance").copy(sets = 3, reps = 1, minReps = 0, maxReps = 0, durationSeconds = 60, isTimerBased = true)
        )

        return if (isHyperlordosisActive) {
            basePostureList.map { exercise ->
                if (exercise.id == "superman") {
                    exercise.copy(sets = (exercise.sets / 2).coerceAtLeast(1))
                } else {
                    exercise
                }
            } + listOf(
                requireExercise("pelvic_tilt").copy(sets = 3, reps = 15, minReps = 15, maxReps = 15),
                requireExercise("dead_bug").copy(sets = 3, reps = 12, minReps = 12, maxReps = 12)
            )
        } else {
            basePostureList
        }
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

    fun getLibraryStats(): LibraryStats {
        val dedicatedAnimationIds = PoseRegistry.getDedicatedAnimationIds()
        val production = allExercises.filter { !it.isTestPose }
        return LibraryStats(
            totalExercises = production.size,
            totalFamilies = families.size,
            totalCategories = production.map { it.category }.distinct().size,
            totalBodyRegions = production.map { it.subCategory }.distinct().size,
            totalLanguages = 3, // EN, RU, UK
            animatedExercisesCount = production.count { it.animationId in dedicatedAnimationIds }
        )
    }
}
