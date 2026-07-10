package com.monkfitness.app.animation

import com.monkfitness.app.poses.*

object AnimationRegistry {
    private val registry = mutableMapOf<String, PoseBuilder>()

    init {
        // Core registrations
        register("world_greatest_stretch", WorldGreatestStretchPose())
        register("birddog_hold", BirdDogPose())
        register("birddog_reps", BirdDogPose())
        register("cat_cow_reps", CatCowPose())
        register("superman_prone", SupermanPose())
        register("pushup_standard", StandardPushUpPose())
        register("pushup_wide", WidePushUpPose())
        register("pushup_military", MilitaryPushUpPose())
        register("pushup_knee", PushUpPose())
        register("pushup_diamond", PushUpPose())
        register("pushup_decline", PushUpPose())
        register("pike_pushup_standard", PushUpPose())
        register("squat_standard", SquatPose())
        register("squat_sumo", SquatPose())
        register("squat_jump", SquatPose())
        register("deep_squat_hold", SquatPose())
    }

    fun register(animationId: String, builder: PoseBuilder) {
        registry[animationId] = builder
    }

    fun get(animationId: String): PoseBuilder? = registry[animationId]
}
