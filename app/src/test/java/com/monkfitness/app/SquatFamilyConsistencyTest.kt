package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Family-consistency guard for the Squat family: consistency across all members.
 *
 * Before this consolidation, SumoSquatPose / JumpSquatPose / DeepSquatHoldPose each duplicated the
 * entire BaseSquatPose.build() pipeline (~40 lines of boilerplate), which let them drift from the
 * shared realization. This test locks in the consolidation:
 *   1. Every squat variant's build() is the single BaseSquatPose pipeline (no variant overrides it).
 *   2. Each variant's realized vertical travel matches its established baseline (no silent geometry
 *      regression in the shared realization).
 */
class SquatFamilyConsistencyTest {

    // Established travel baselines (byte-stable since the consolidation).
    //
    // SquatPose / AirSquatPose / JumpSquatPose were re-measured after the gaze-intent origin fix in
    // `BasePose.buildGaze`. MotionProbe warms the pipeline at progress 0.3 and then samples progress
    // 0..1, so the pre-fix head at the p=0 sample was authored from the *warm-up* build's neck world
    // position — an offset equal to the trunk's motion between p=0.3 and p=0, which shrank the measured
    // Y travel. With the origin taken from the current build the head realizes the authored gaze at
    // that sample, so each of the three upright variants gains the offset it was losing
    // (+1.0 / +1.7 / +2.5 units). The epsilon is unchanged (0.5) and every variant is still pinned
    // individually; SumoSquat and DeepSquatHold did not move.
    private val baselineTravel = mapOf(
        "SquatPose" to 192.3f,
        "AirSquatPose" to 185.5f,
        "SumoSquatPose" to 191.9f,
        "JumpSquatPose" to 95.5f,
        "DeepSquatHoldPose" to 0.0f
    )

    @Test
    fun allVariantsShareBaseBuildPipeline() {
        val baseBuild: Method = BaseSquatPose::class.java.getDeclaredMethod("onBuild", PoseContext::class.java)
        val failures = mutableListOf<String>()
        for (name in baselineTravel.keys) {
            val cls = Class.forName("com.monkfitness.app.poses.$name")
            // BasePose.build() is a final template method (reset + onBuild), so a variant physically
            // cannot override build(); the drift guard therefore checks onBuild(), the pipeline
            // realization subclasses author.
            val own = cls.declaredMethods.any { it.name == "onBuild" && it.returnType == SkeletonPose::class.java }
            if (own) failures.add("$name re-implements onBuild() instead of using BaseSquatPose.onBuild()")
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
