package com.monkfitness.app.poses

import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.AnimationRegistry

data class PoseConfig(
    val builder: PoseBuilder,
    val alternating: Boolean = false
)

object PoseRegistry {
    private val configRegistry = mapOf(
        "world_greatest_stretch" to false,
        "pushup_standard" to false,
        "pushup_wide" to false,
        "pushup_military" to false,
        "pushup_knee" to false,
        "pushup_diamond" to false,
        "pushup_decline" to false,
        "pike_pushup_standard" to false,
        "squat_standard" to false,
        "squat_sumo" to false,
        "squat_jump" to false,
        "deep_squat_hold" to false,
        "birddog_hold" to true,
        "birddog_reps" to true,
        "cat_cow_reps" to false,
        "superman_prone" to false,
        "couch_stretch_hold" to false,
        "hip_flexor_stretch_hold" to false,
        "thoracic_rotations_reps" to false,
        "thoracic_extension_reps" to false,
        "hamstring_stretch_hold" to false,
        "cobra_stretch_hold" to false,
        "dead_bug_standard" to false,
        "kb_swing_backpack" to false,
        "reverse_snow_angel_prone" to false,
        "scapular_pullup_deadhang" to false,
        "leg_raise_standard" to false,
        "mountain_climber_standard" to false,
        "side_plank_standard" to false,
        "plank_standard" to false,
        "pullup_standard" to false,
        "chinup_standard" to false,
        "pullup_neutral" to false,
        "pullup_wide" to false,
        "dead_hang" to false,
        "lunge_forward" to false,
        "lunge_reverse" to false,
        "lunge_side" to false,
        "row_standard" to false,
        "band_pull_aparts_standard" to false,
        "yt_raises_standard" to false,
        "shoulder_cars_standard" to false
    )

    fun getPoseConfig(animationId: String): PoseConfig? {
        val alternating = configRegistry[animationId] ?: return null
        val builder = AnimationRegistry.get(animationId) ?: return null
        return PoseConfig(builder, alternating)
    }

    fun getDedicatedAnimationIds(): Set<String> {
        val animationIds = configRegistry.keys
        val builderToIds = mutableMapOf<Class<out PoseBuilder>, MutableSet<String>>()

        animationIds.forEach { id ->
            val builder = AnimationRegistry.get(id)
            if (builder != null) {
                builderToIds.getOrPut(builder.javaClass) { mutableSetOf() }.add(id)
            }
        }

        return builderToIds.values
            .filter { it.size == 1 }
            .flatten()
            .toSet()
    }
}
