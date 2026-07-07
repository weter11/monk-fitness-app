package com.monkfitness.app.poses

import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.AnimationMode

data class PoseConfig(
    val builder: PoseBuilder,
    val mode: AnimationMode,
    val alternating: Boolean = false
)

object PoseRegistry {
    private val registry = mapOf(
        "world_greatest_stretch" to PoseConfig(WorldGreatestStretchPose(), AnimationMode.HOLD),
        "pushup_standard" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pushup_wide" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pushup_military" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pushup_knee" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pushup_diamond" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pushup_decline" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pike_pushup_standard" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "squat_standard" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "squat_sumo" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "squat_jump" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "deep_squat_hold" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "birddog_hold" to PoseConfig(BirdDogPose(), AnimationMode.LOOP, alternating = true),
        "birddog_reps" to PoseConfig(BirdDogPose(), AnimationMode.LOOP, alternating = true),
        "cat_cow_reps" to PoseConfig(CatCowPose(), AnimationMode.LOOP),
        "superman_prone" to PoseConfig(SupermanPose(), AnimationMode.LOOP)
    )

    fun getPoseConfig(animationId: String): PoseConfig? = registry[animationId]
}
