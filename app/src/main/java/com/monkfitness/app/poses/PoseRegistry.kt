package com.monkfitness.app.poses

import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.AnimationMode
import com.monkfitness.app.animation.AnimationRegistry

data class PoseConfig(
    val builder: PoseBuilder,
    val mode: AnimationMode,
    val alternating: Boolean = false
)

object PoseRegistry {
    private val configRegistry = mapOf(
        "world_greatest_stretch" to (AnimationMode.HOLD to false),
        "pushup_standard" to (AnimationMode.LOOP to false),
        "pushup_wide" to (AnimationMode.LOOP to false),
        "pushup_military" to (AnimationMode.LOOP to false),
        "pushup_knee" to (AnimationMode.LOOP to false),
        "pushup_diamond" to (AnimationMode.LOOP to false),
        "pushup_decline" to (AnimationMode.LOOP to false),
        "pike_pushup_standard" to (AnimationMode.LOOP to false),
        "squat_standard" to (AnimationMode.LOOP to false),
        "squat_sumo" to (AnimationMode.LOOP to false),
        "squat_jump" to (AnimationMode.LOOP to false),
        "deep_squat_hold" to (AnimationMode.LOOP to false),
        "birddog_hold" to (AnimationMode.LOOP to true),
        "birddog_reps" to (AnimationMode.LOOP to true),
        "cat_cow_reps" to (AnimationMode.LOOP to false),
        "superman_prone" to (AnimationMode.LOOP to false)
    )

    fun getPoseConfig(animationId: String): PoseConfig? {
        val (mode, alternating) = configRegistry[animationId] ?: return null
        val builder = AnimationRegistry.get(animationId) ?: return null
        return PoseConfig(builder, mode, alternating)
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
