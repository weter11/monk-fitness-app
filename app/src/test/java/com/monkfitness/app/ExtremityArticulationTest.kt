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
        assertEquals("$name carrier must reproduce node-read path (maxDev=$maxDev at $worst)", 0f, maxDev, 1e-4f)
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
        // must leave the authored palm/fingertips geometry untouched (the derivation is skipped).
        //
        // P12 WP-I: the "authored endpoint" reference is the FK value on the produced frame's node
        // tree — `PALM_A` is a carrier-only extremity-derived joint, so the derivation writes the
        // FLAT CARRIER and never the node. Reading it from the half-built carrier instead (as the
        // pre-activation form did) is no longer the frame: under state 3 `build()` registers limb
        // intent and the engine-owned stage realizes the limb (§12.7a). The claim — the opt-out
        // preserves the authored endpoint verbatim — is unchanged and is now asserted on the
        // published frame of the production path.
        val ctx = PoseContext(0.5f, Side.LEFT, def)
        val built = DeadHangPose().build(ctx)
        built.overrideExtremityOrientation(Extremity.HAND_A)
        val out = SkeletonPipeline(def).produceFrame(built).pose
        val authored = palmNodeWorld(out)
        val published = out.getJoint(Joint.PALM_A)
        assertEquals("opt-out must preserve authored PALM_A.x", authored.x, published.x, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.y", authored.y, published.y, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.z", authored.z, published.z, 1e-3f)

        // Control (non-vacuity): the SAME frame with the hand auto-owned IS derived — the carrier
        // value departs from the authored FK value — so the preservation above is a real opt-out
        // effect and not a derivation that happens to be a no-op.
        val derivedFrame = SkeletonPipeline(def).produceFrame(DeadHangPose(), ctx).pose
        val derivedAuthored = palmNodeWorld(derivedFrame)
        val derivedPublished = derivedFrame.getJoint(Joint.PALM_A)
        val shift = abs(derivedPublished.x - derivedAuthored.x) +
            abs(derivedPublished.y - derivedAuthored.y) +
            abs(derivedPublished.z - derivedAuthored.z)
        assertTrue(
            "control: an auto-owned hand must be engine-derived (observed authored->carrier shift=$shift)",
            shift > 1e-3f
        )
    }

    /** World position of the `PALM_A` node on a produced frame's tree (the authored FK value). */
    private fun palmNodeWorld(pose: SkeletonPose): Vector3 {
        fun find(node: SkeletonNode): SkeletonNode? {
            if (node.joint == Joint.PALM_A) return node
            for (c in node.children) {
                find(c)?.let { return it }
            }
            return null
        }
        for (root in pose.roots) {
            find(root)?.let { return it.worldPosition }
        }
        error("the produced frame must carry a PALM_A node")
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
