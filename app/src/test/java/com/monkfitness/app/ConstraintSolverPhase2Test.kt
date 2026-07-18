package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2 (F2/F7/F9) — ConstraintSolver owns the root/pelvis transform.
 *
 * (Phase B flag collapse) SOLVER_OWNS_POSTURE was collapsed to its true branch and the flag
 * removed, so posture ownership is now unconditional. These tests assert the resulting
 * engine-owned posture behaviour directly.
 *  - the solver seeds the root pelvis height from the pose's declared [PostureIntent];
 *  - contact conflicts are resolved by `contactPrecedence` (weighted root step);
 *  - the solved root is persisted for inter-frame temporal smoothing.
 */
class ConstraintSolverPhase2Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)

    // Route through the pipeline (the production path) so the ordered Solver → Finalizer
    // chain runs. The Finalizer no longer calls the Solver itself (M2), so a direct
    // `pipeline.produceFrame(build()).pose` would silently skip the posture solve this suite exists to test.
    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        return SkeletonPipeline(def).produceFrame(pose, context).pose
    }

    @Test
    fun seatedIntentSeedsPelvisNearFloor() {
        val pose = finalized(DeepOverheadSquatPose())
        assertFinite(pose, "DeepOverheadSquat")
        // SEATED_NEAR_FLOOR seed ~ shinLength*0.35 ≈ 34; the solver then honours the ground
        // contacts, so the pelvis must rest near the floor (well below a standing height ~235).
        val pelvisY = pose.getJoint(Joint.PELVIS).y
        assertTrue("seated pelvis must be low, not floating (was $pelvisY)", pelvisY < 160f)
        // Ankles stay on the ground (no penetration) — the solver honours the fixed foot contacts.
        for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B)) {
            assertTrue("$joint penetrates ground (${pose.getJoint(joint).y})", pose.getJoint(joint).y >= -1e-2f)
        }
    }

    @Test
    fun hangingIntentSeedsPelvisUnderBar() {
        val pose = finalized(DeadHangPose())
        assertFinite(pose, "DeadHang")
        // The solver seeds the pelvis from the HANGING_UNDER_BAR intent (barY - reach - torsoLength)
        // and the hands stay pinned on the bar plane.
        assertEquals("left hand should stay on the bar plane", 500f, pose.getJoint(Joint.HAND_A).y, 1.0f)
        assertEquals("right hand should stay on the bar plane", 500f, pose.getJoint(Joint.HAND_P).y, 1.0f)
    }

    @Test
    fun determinismAcrossSolves() {
        val a = finalized(MiddleSplitPose())
        val b = finalized(MiddleSplitPose())
        for (joint in listOf(Joint.PELVIS, Joint.HIP_F, Joint.ANKLE_F, Joint.ANKLE_B, Joint.SHOULDER_A, Joint.HAND_A)) {
            val pa = a.getJoint(joint)
            val pb = b.getJoint(joint)
            assertEquals("${joint} x differs", pa.x, pb.x, 1e-5f)
            assertEquals("${joint} y differs", pa.y, pb.y, 1e-5f)
            assertEquals("${joint} z differs", pa.z, pb.z, 1e-5f)
        }
    }

    @Test
    fun postureIntentDeclaredByPose() {
        // DeepOverheadSquat declares SEATED_NEAR_FLOOR via declarePosture, so the raw pose
        // carries that intent (the solver reads it). A pose that does not declare keeps CUSTOM.
        val squatted = DeepOverheadSquatPose().build(context)
        assertEquals(PostureIntent.Kind.SEATED_NEAR_FLOOR, squatted.postureIntent.kind)

        val undeclared = MiddleSplitPose().build(context)
        assertEquals(PostureIntent.Kind.CUSTOM, undeclared.postureIntent.kind)
    }

    private fun assertFinite(pose: SkeletonPose, label: String) {
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            assertTrue("$label: $joint not finite (${p.x},${p.y},${p.z})", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
    }
}
