package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Level 6 Family Upgrade guard for the Squat family (RFC_MONKENGINE_EXECUTION_MODES L6:
 * "Consistency across all members").
 *
 * Before this upgrade, SumoSquatPose / JumpSquatPose / DeepSquatHoldPose each duplicated the entire
 * BaseSquatPose.build() pipeline (~40 lines of boilerplate), which let them drift from the shared
 * realization. This test locks in the consolidation:
 *   1. Every squat variant's build() is the single BaseSquatPose pipeline (no variant overrides it).
 *   2. Each variant's realized vertical travel matches its established baseline (no silent
 *      geometry regression in the shared realization).
 */
class SquatFamilyConsistencyTest {

    // Established travel baselines (measured 2026-07-20, before L6 consolidation; byte-stable after).
    private val baselineTravel = mapOf(
        "SquatPose" to 191.3f,
        "AirSquatPose" to 183.8f,
        "SumoSquatPose" to 191.9f,
        "JumpSquatPose" to 93.0f,
        "DeepSquatHoldPose" to 0.0f
    )

    @Test
    fun allVariantsShareBaseBuildPipeline() {
        val baseBuild: Method = BaseSquatPose::class.java.getDeclaredMethod("build", PoseContext::class.java)
        val failures = mutableListOf<String>()
        for (name in baselineTravel.keys) {
            val cls = Class.forName("com.monkfitness.app.poses.$name")
            val own = cls.declaredMethods.any { it.name == "build" && it.returnType == SkeletonPose::class.java }
            if (own) failures.add("$name re-implements build() instead of using BaseSquatPose.build()")
            // sanity: it really is a BaseSquatPose and the inherited method resolves
            assertTrue("$name must extend BaseSquatPose", BaseSquatPose::class.java.isAssignableFrom(cls))
        }
        assertTrue(
            "Squat family drifted from the shared L6 realization:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun travelMatchesEstablishedBaseline() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val failures = mutableListOf<String>()
        for ((name, base) in baselineTravel) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name), def)
            // Allow a small epsilon; the shared realization must preserve each variant's travel.
            if (kotlin.math.abs(travel - base) > 0.5f) {
                failures.add("$name travel=%.1f (baseline %.1f)".format(travel, base))
            }
        }
        assertTrue(
            "Squat family travel regressed from baseline (shared realization broke a variant):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
