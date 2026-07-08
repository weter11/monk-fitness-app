package com.monkfitness.app.animation

import com.monkfitness.app.poses.*

object AnimationRegistry {
    private val registry = mapOf<String, PoseBuilder>(
        "world_greatest_stretch" to WorldGreatestStretchPose(),
        "birddog_hold" to BirdDogPose(),
        "birddog_reps" to BirdDogPose(),
        "cat_cow_reps" to CatCowPose(),
        "superman_prone" to SupermanPose(),
        "pushup_standard" to PushUpPose(),
        "pushup_wide" to PushUpPose(),
        "pushup_military" to PushUpPose(),
        "pushup_knee" to PushUpPose(),
        "pushup_diamond" to PushUpPose(),
        "pushup_decline" to PushUpPose(),
        "pike_pushup_standard" to PushUpPose(),
        "squat_standard" to SquatPose(),
        "squat_sumo" to SquatPose(),
        "squat_jump" to SquatPose(),
        "deep_squat_hold" to SquatPose()
    )

    fun get(animationId: String): PoseBuilder? = registry[animationId]
}
