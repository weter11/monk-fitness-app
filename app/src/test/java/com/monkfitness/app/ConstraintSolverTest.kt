package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-04 — Global contact-constraint / root-repositioning layer.
 *
 * These exercises drive the *render* path (build -> SkeletonPoseFinalizer, which runs the
 * [ConstraintSolver]) so the fixed contacts registered by the validation poses are honored:
 * the root/pelvis is derived from the contacts rather than a fixed authored value, and every
 * non-contact limb follows rigidly. The four reference poses must hold their contacts without
 * penetration or fold, and the solver must be deterministic (no flicker).
 */
class ConstraintSolverTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)

    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        val raw = pose.build(context)
        return SkeletonPoseFinalizer(def).finalize(raw)
    }

    private fun assertFinite(pose: SkeletonPose, label: String) {
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            assertTrue("$label: $joint not finite (${p.x},${p.y},${p.z})", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
    }

    @Test
    fun middleSplitPelvisRestsOnGroundFeetStayPlanted() {
        val pose = finalized(MiddleSplitPose())
        assertFinite(pose, "MiddleSplit")

        // Issue A fix: the global solver must NOT float the pelvis to fake straight legs. The
        // pelvis is derived from the ground contacts and stays near the authored ground level
        // (previously it was pushed up to ~210 into a floating "V").
        val pelvisY = pose.getJoint(Joint.PELVIS).y
        assertTrue("pelvis must rest near the ground, not float (was $pelvisY)", pelvisY < 60f)

        // Feet stay planted on the ground (no penetration) and splay wide.
        for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)) {
            assertTrue("$joint penetrates ground (${pose.getJoint(joint).y})", pose.getJoint(joint).y >= -1e-2f)
        }
        val af = pose.getJoint(Joint.ANKLE_F)
        val ab = pose.getJoint(Joint.ANKLE_B)
        assertTrue("ankles should splay wide (z spread ${kotlin.math.abs(af.z - ab.z)})", kotlin.math.abs(af.z - ab.z) > 100f)

        // The straight-limb target sits inside the proximal-bone length, so the solver re-bakes
        // it as a non-degenerate limb: the knee must be a real joint, not collapsed onto the ankle.
        val kf = pose.getJoint(Joint.KNEE_F)
        val ankleF = pose.getJoint(Joint.ANKLE_F)
        val kneeDist = kotlin.math.sqrt(
            (kf.x - ankleF.x).pow(2) + (kf.y - ankleF.y).pow(2) + (kf.z - ankleF.z).pow(2)
        )
        assertTrue("knee must be a real joint, not collapsed onto ankle (dist $kneeDist)", kneeDist > 5f)
    }

    @Test
    fun deepOverheadSquatFeetStayOnGroundAndFinite() {
        val pose = finalized(DeepOverheadSquatPose())
        assertFinite(pose, "DeepOverheadSquat")

        for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)) {
            assertTrue("$joint penetrates ground (${pose.getJoint(joint).y})", pose.getJoint(joint).y >= -1e-2f)
        }
    }

    @Test
    fun pikeSitFeetOnGroundAndFinite() {
        val pose = finalized(PikeSitPose())
        assertFinite(pose, "PikeSit")
        for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B)) {
            assertTrue("${joint} penetrates ground", pose.getJoint(joint).y >= -1e-2f)
        }
    }

    @Test
    fun deadHangHandsStayOnBar() {
        val pose = finalized(DeadHangPose())
        assertFinite(pose, "DeadHang")
        assertEquals("left hand should stay on the bar plane", 500f, pose.getJoint(Joint.HAND_A).y, 1.0f)
        assertEquals("right hand should stay on the bar plane", 500f, pose.getJoint(Joint.HAND_P).y, 1.0f)
    }

    @Test
    fun solverIsDeterministic() {
        // Two independent solves of the same frozen pose must be bit-identical (no randomness).
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
    fun productionPosesUnaffectedBySolver() {
        // Production poses never register fixed contacts, so the solver must be a no-op and the
        // finalized skeleton must remain finite and transforms-updated.
        val finalizer = SkeletonPoseFinalizer(def)
        val poses = listOf(
            AirSquatPose(), StandardPushUpPose(), StandardPullUpPose(), HangPose(),
            AlternatingForwardLungesPose(), StaticForearmPlankPose()
        )
        for (p in poses) {
            val skeleton = finalizer.finalize(p.build(context))
            assertTrue("${p.javaClass.simpleName} should be transforms-updated", skeleton.isTransformsUpdated)
            assertFinite(skeleton, p.javaClass.simpleName)
        }
    }
}
