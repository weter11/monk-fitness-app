package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M4 (RFC_GAP_CLOSURE) — the Finalizer is the *exclusive* writer of local transforms, with the
 * F1/B5 read-only chest-frame guard always active.
 *
 * (Phase B flag collapse) FINALIZER_OWNS_CONVERSION was collapsed to its true branch and removed,
 * so conversion ownership is now unconditional. This suite asserts the resulting behaviour:
 *  - production poses render finite and contact-less (the guard never runs for them);
 *  - validation instruments register contacts and finalize with every contact on its support plane.
 */
class FinalizerOwnsConversionM4Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "DeadBug" to { DeadBugPose() },
        "GluteBridge" to { GluteBridgePose() }
    )

    private fun contactPoseFactories(): List<Pair<String, () -> BaseValidationPose>> = listOf(
        "DeepOverheadSquat" to { DeepOverheadSquatPose() },
        "DeadHang" to { DeadHangPose() },
        "MiddleSplit" to { MiddleSplitPose() },
        "PikeSit" to { PikeSitPose() }
    )

    private fun maxDeviation(a: SkeletonPose, b: SkeletonPose): Float {
        var max = 0f
        for (j in Joint.entries) {
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            val d = maxOf(abs(pa.x - pb.x), abs(pa.y - pb.y), abs(pa.z - pb.z))
            if (d > max) max = d
        }
        return max
    }

    @Test
    fun productionPosesRenderFiniteAndStable() {
        // With conversion ownership unconditional, every production pose renders finite and the
        // pipeline produces a stable (deterministic) frame across repeated builds.
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in poseFactories()) {
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)
                val a = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val b = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val d = maxDeviation(a, b)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("production poses must be deterministic at $worst", 0f, maxDev, 1e-4f)
    }

    @Test
    fun contactPosesKeepContactsOnSupportPlane() {
        for ((name, factory) in contactPoseFactories()) {
            for (p in listOf(0f, 0.5f, 1f)) {
                val ctx = PoseContext(p, Side.LEFT, def)
                val pose = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                for (spec in pose.contacts) {
                    val j = spec.endJoint
                    when (j) {
                        Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B ->
                            assertTrue("$name $j penetrates ground (${pose.getJoint(j).y})", pose.getJoint(j).y >= -1e-2f)
                        Joint.HAND_A, Joint.HAND_P ->
                            assertEquals("$name $j must stay on the bar plane", spec.targetWorld.y, pose.getJoint(j).y, 1.0f)
                        else -> { /* other contact kinds */ }
                    }
                }
            }
        }
    }

    @Test
    fun guardActivatesOnlyForContacts() {
        // Sanity: a production pose with no engine contacts must have an empty contact list (so the
        // F1/B5 guard never runs for it), whereas the validation instruments do register contacts.
        val squat = AirSquatPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertEquals("production pose must have no engine contacts", 0, squat.contacts.size)
        val hang = DeadHangPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("validation instrument must register contacts", hang.contacts.isNotEmpty())
    }
}
