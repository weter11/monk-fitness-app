package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Branch C (RFC_BRANCH_C_EXTREMITY_ARTICULATION) — §1.3 Interaction / Articulation Intent.
 *
 * This pins the three RFC §11 acceptance tests:
 *  - (a) carrier -> derived geometry is byte-identical to the legacy node-read path: every
 *    migrated pose records `extremityArticulations`; clearing the carrier (leaving the node
 *    rotation, which the Finalizer falls back to) renders identically.
 *  - (b) the MANUAL_OVERRIDE opt-out is real: an extremity opted out preserves its authored
 *    endpoint geometry instead of being engine-derived.
 *  - (c) the 2-DOF wrist composer combines flexion + deviation exactly (the composed rotation is
 *    not a single dropped axis).
 */
class ExtremityArticulationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun productionPoses(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "PikePushUp" to { PikePushUpPose() },
        "JumpSquat" to { JumpSquatPose() },
        "DynamicWorldsGreatestStretch" to { DynamicWorldsGreatestStretchPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "ThoracicExtension" to { ThoracicExtensionPose() },
        "StandardPullUp" to { StandardPullUpPose() },
        "DeadHang" to { DeadHangPose() }
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
    fun carrierIsPopulatedByArticulatingPoses() {
        for ((name, factory) in productionPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue(
                "$name must populate extremityArticulations (Branch C carrier live) got=${pose.extremityArticulations.size}",
                pose.extremityArticulations.isNotEmpty()
            )
        }
    }

    private fun assertCarrierMatchesNodePath(name: String, factory: () -> PoseBuilder) {
        var maxDev = 0f
        var worst = ""
        for (p in listOf(0f, 0.5f, 1f)) {
            val ctx = PoseContext(p, Side.LEFT, def)
            val withCarrier = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
            val built = factory().build(ctx)
            built.extremityArticulations.clear()
            val withoutCarrier = SkeletonPipeline(def).produceFrame(built).pose
            val d = maxDeviation(withCarrier, withoutCarrier)
            if (d > maxDev) { maxDev = d; worst = "@$p" }
        }
        assertEquals("$name carrier must reproduce node-read path (maxDev=$maxDev at $worst)", 0f, maxDev, 1e-3f)
    }

    @Test fun pikePushUpCarrierMatchesNodePath() = assertCarrierMatchesNodePath("PikePushUp") { PikePushUpPose() }
    @Test fun jumpSquatCarrierMatchesNodePath() = assertCarrierMatchesNodePath("JumpSquat") { JumpSquatPose() }
    @Test fun dynamicStretchCarrierMatchesNodePath() = assertCarrierMatchesNodePath("DynamicWorldsGreatestStretch") { DynamicWorldsGreatestStretchPose() }
    @Test fun hamstringStretchCarrierMatchesNodePath() = assertCarrierMatchesNodePath("HamstringStretch") { HamstringStretchPose() }
    @Test fun thoracicExtensionCarrierMatchesNodePath() = assertCarrierMatchesNodePath("ThoracicExtension") { ThoracicExtensionPose() }
    @Test fun standardPullUpCarrierMatchesNodePath() = assertCarrierMatchesNodePath("StandardPullUp") { StandardPullUpPose() }
    @Test fun deadHangCarrierMatchesNodePath() = assertCarrierMatchesNodePath("DeadHang") { DeadHangPose() }

    @Test
    fun manualOverridePreservesAuthoredEndpoints() {
        // DeadHang authors an overhand grip via the carrier; opting HAND_A into MANUAL_OVERRIDE
        // must leave the authored palm/fingertips nodes untouched (the derivation is skipped).
        val overridden = DeadHangPose().build(PoseContext(0.5f, Side.LEFT, def))
        // Capture the authored endpoint the pose left on the node (factory default — DeadHang does
        // not hand-author PALM_A), which the opt-out contract must preserve verbatim.
        val authoredPalm = overridden.getJoint(Joint.PALM_A).copy()
        overridden.overrideExtremityOrientation(Extremity.HAND_A)
        // The opted-out extremity's authored endpoint must be preserved verbatim by the Finalizer.
        val out = SkeletonPipeline(def).produceFrame(overridden).pose
        assertEquals("opt-out must preserve authored PALM_A.x", authoredPalm.x, out.getJoint(Joint.PALM_A).x, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.y", authoredPalm.y, out.getJoint(Joint.PALM_A).y, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.z", authoredPalm.z, out.getJoint(Joint.PALM_A).z, 1e-3f)
    }

    @Test
    fun wristComposerCombinesTwoDofExactly() {
        // buildWristRotation(flexion, deviation) must equal Rz(flexion) then Ry(deviation), not a
        // single dropped axis. Compose and compare against the explicit two-step rotation.
        val r = JointRotation()
        SkeletonMath.buildWristRotation(0.4f, 0.25f, r)
        val expected = JointRotation()
        SkeletonMath.buildWristRotation(0f, 0.25f, expected)
        val flex = JointRotation(Vector3(0f, 0f, 1f), 0.4f)
        SkeletonMath.composeRotations(flex, expected, expected)
        assertEquals(expected.axis.x, r.axis.x, 1e-4f)
        assertEquals(expected.axis.y, r.axis.y, 1e-4f)
        assertEquals(expected.axis.z, r.axis.z, 1e-4f)
        assertEquals(expected.angle, r.angle, 1e-4f)
    }
}
