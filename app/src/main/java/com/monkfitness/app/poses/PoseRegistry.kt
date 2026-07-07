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
        "pushups" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "diamond_pushups" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "decline_pushups" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "pike_pushups" to PoseConfig(PushUpPose(), AnimationMode.LOOP),
        "squats" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "deep_squat" to PoseConfig(SquatPose(), AnimationMode.LOOP),
        "bird_dog" to PoseConfig(BirdDogPose(), AnimationMode.LOOP, alternating = true),
        "cat_cow" to PoseConfig(CatCowPose(), AnimationMode.LOOP),
        "superman" to PoseConfig(SupermanPose(), AnimationMode.LOOP)
    )

    fun getPoseConfig(exerciseId: String): PoseConfig? = registry[exerciseId]
}
