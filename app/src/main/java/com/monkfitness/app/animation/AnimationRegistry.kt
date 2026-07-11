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
        register("pushup_knee", KneePushUpPose())
        register("pushup_diamond", DiamondPushUpPose())
        register("pushup_decline", DeclinePushUpPose())
        register("pike_pushup_standard", PikePushUpPose())
        register("squat_standard", AirSquatPose())
        register("squat_sumo", SumoSquatPose())
        register("squat_jump", JumpSquatPose())
        register("deep_squat_hold", DeepSquatHoldPose())
    }

    fun register(animationId: String, builder: PoseBuilder) {
        registry[animationId] = builder
    }

    fun get(animationId: String): PoseBuilder? = registry[animationId]
}
