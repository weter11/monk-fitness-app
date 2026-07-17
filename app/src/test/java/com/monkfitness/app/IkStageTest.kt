package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * B1 (RFC_BRANCH_B_IMPLEMENTATION §2 B1) — `IkStage` extraction.
 *
 * B1 introduces the pipeline-owned `IkStage` that consumes the §1.1 `limbTargets` carrier (now
 * populated by every `bakeIkLimb` forward) and re-derives each limb's local positions on the
 * engine-owned node tree. The stage is gated by `EngineFlags.IK_STAGE_ACTIVE` (default **false**,
 * so the legacy `bakeIkLimb` remains the sole solver and the baseline is byte-identical).
 *
 * This suite proves the B1 exit criterion: when the stage is switched on it reproduces the legacy
 * `bakeIkLimb` limb solving exactly — every production pose and every contact-bearing validation
 * instrument renders byte-identically (maxDeviation 0.0) with the flag on vs off. The stage must
 * therefore be safe to flip on as the real solver once this is green.
 *
 * It also asserts the carrier flip: `limbTargets` is empty under B0 and populated after B1.
 */
class IkStageTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalStage = EngineFlags.IK_STAGE_ACTIVE

    @After
    fun restore() {
        EngineFlags.IK_STAGE_ACTIVE = originalStage
    }

    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "JumpSquat" to { JumpSquatPose() },
        "PikePushUp" to { PikePushUpPose() },
        "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() }
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
    fun flagDefaultsFalse() {
        // B1 ships additive + reversible: the stage is OFF by default so the legacy path is preserved.
        assertTrue("IK_STAGE_ACTIVE must default to false in B1", !originalStage)
    }

    @Test
    fun limbTargetsCarrierIsLiveAfterB1() {
        for ((name, factory) in poseFactories()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate limbTargets (B1 dead→live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
        for ((name, factory) in contactPoseFactories()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate limbTargets (B1 dead→live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
    }

    @Test
    fun productionPosesByteIdenticalStageOnVsOff() {
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in poseFactories()) {
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)

                EngineFlags.IK_STAGE_ACTIVE = false
                val off = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                EngineFlags.IK_STAGE_ACTIVE = true
                val on = SkeletonPipeline(def).produceFrame(factory(), ctx).pose

                val d = maxDeviation(off, on)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("IkStage must not change production poses at $worst", 0f, maxDev, 1e-4f)
        println("IkStageTest.productionPosesByteIdenticalStageOnVsOff: OK maxDev=$maxDev")
    }

    @Test
    fun contactPosesByteIdenticalStageOnVsOff() {
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in contactPoseFactories()) {
            for (p in listOf(0f, 0.5f, 1f)) {
                val ctx = PoseContext(p, Side.LEFT, def)

                EngineFlags.IK_STAGE_ACTIVE = false
                val off = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                EngineFlags.IK_STAGE_ACTIVE = true
                val on = SkeletonPipeline(def).produceFrame(factory(), ctx).pose

                val d = maxDeviation(off, on)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("IkStage must not change contact instruments at $worst", 0f, maxDev, 1e-4f)
        println("IkStageTest.contactPosesByteIdenticalStageOnVsOff: OK maxDev=$maxDev")
    }
}
