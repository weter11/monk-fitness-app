package com.monkfitness.app.animation

import com.monkfitness.app.poses.*

object AnimationRegistry {
    private val registry = mutableMapOf<String, PoseBuilder>()

    init {
        // Core registrations
        register("world_greatest_stretch", DynamicWorldsGreatestStretchPose())
        register("birddog_hold", StaticBirdDogHoldPose())
        register("birddog_reps", AlternatingBirdDogPose())
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
        register("couch_stretch_hold", CouchStretchPose())
        register("hip_flexor_stretch_hold", HalfKneelingStretchPose())
        register("thoracic_rotations_reps", QuadrupedThoracicRotationsPose())
        register("thoracic_extension_reps", ThoracicExtensionPose())
        register("hamstring_stretch_hold", HamstringStretchPose())
        register("glute_bridge_standard", GluteBridgePose())
        register("pelvic_tilt_standard", PelvicTiltPose())
        register("cobra_stretch_hold", ProneCobraStretchPose())
        register("side_plank_standard", IsometricSidePlankPose())
        register("plank_standard", StaticForearmPlankPose())
        register("pullup_standard", StandardPullUpPose())
        register("chinup_standard", UnderhandChinUpPose())
        register("pullup_neutral", NeutralGripPullUpPose())
        register("pullup_wide", WideGripPullUpPose())
        register("dead_hang", HangPose())
        register("lunge_forward", AlternatingForwardLungesPose())
        register("lunge_reverse", AlternatingReverseLungesPose())
        register("lunge_side", AlternatingSideLungesPose())
    }

    fun register(animationId: String, builder: PoseBuilder) {
        registry[animationId] = builder
    }

    fun get(animationId: String): PoseBuilder? = registry[animationId]
}
