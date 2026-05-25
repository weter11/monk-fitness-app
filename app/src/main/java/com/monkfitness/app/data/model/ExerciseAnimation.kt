package com.monkfitness.app.data.model

enum class ExerciseAnimationProfile {
    PUSH_UP,
    LOWER_BODY,
    SUSPENSION_PULL,
    PLANK_FLOW,
    GLUTE_BRIDGE,
    QUADRUPED_FLOW,
    FLOOR_STRETCH,
    FULL_BODY_FLOW,
    UPPER_BODY_POSTURE,
    HIP_MOBILITY,
    HORSE_STANCE,
    NECK_MOBILITY,
    JUMPING_JACK
}

fun exerciseAnimationProfile(exerciseId: String): ExerciseAnimationProfile? = when (exerciseId) {
    "pushups",
    "decline_pushups",
    "diamond_pushups",
    "dips",
    "pike_pushups" -> ExerciseAnimationProfile.PUSH_UP

    "squats",
    "lunges",
    "step_ups",
    "wall_sit",
    "deep_squat",
    "ankle_mobility",
    "hamstring_stretch",
    "calf_stretch" -> ExerciseAnimationProfile.LOWER_BODY

    "pullups",
    "rows",
    "scapular_pullups",
    "hang" -> ExerciseAnimationProfile.SUSPENSION_PULL

    "plank",
    "mountain_climbers" -> ExerciseAnimationProfile.PLANK_FLOW

    "glute_bridge" -> ExerciseAnimationProfile.GLUTE_BRIDGE

    "cat_cow",
    "bird_dog",
    "thoracic_rotations",
    "thoracic_extension" -> ExerciseAnimationProfile.QUADRUPED_FLOW

    "cobra_stretch",
    "child_pose",
    "superman" -> ExerciseAnimationProfile.FLOOR_STRETCH

    "world_greatest_stretch",
    "burpees",
    "kettlebell_swing" -> ExerciseAnimationProfile.FULL_BODY_FLOW

    "face_pull",
    "wall_slides",
    "reverse_snow_angels",
    "band_pull_aparts",
    "shoulder_cars",
    "y_t_raises",
    "scapular_retraction_hold",
    "lat_stretch",
    "arm_circles" -> ExerciseAnimationProfile.UPPER_BODY_POSTURE

    "hip_flexor_stretch",
    "hip_cars",
    "ninety_ninety_hips",
    "piriformis_stretch",
    "hip_circles",
    "leg_swings" -> ExerciseAnimationProfile.HIP_MOBILITY

    "horse_stance" -> ExerciseAnimationProfile.HORSE_STANCE

    "chin_tucks",
    "neck_circles" -> ExerciseAnimationProfile.NECK_MOBILITY

    "jumping_jacks" -> ExerciseAnimationProfile.JUMPING_JACK

    else -> null
}

val Exercise.animationProfile: ExerciseAnimationProfile?
    get() = exerciseAnimationProfile(id)

fun Exercise.hasAnimatedVariant(): Boolean = animationProfile != null || lottieRes != null
