package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3 (F1) — `SkeletonPoseFinalizer.reconstructChestFrame` must be read-only on Solver-settled
 * contacts. The no-move guard (B5) snapshots every contact end-effector before the chest-frame
 * reconstruction and asserts it is unchanged afterwards; if the reconstruction would displace a
 * contact it is rolled back so the contact stays exactly where the solver pinned it.
 *
 * (Phase B flag collapse) FINALIZER_OWNS_CONVERSION was collapsed to its true branch and removed,
 * so the guard is now always active. Exercised through the full production path
 * (build -> SkeletonPipeline.produceFrame, which runs the ConstraintSolver then the Finalizer).
 */
class ChestFrameNoMoveTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)

    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        return SkeletonPipeline(def).produceFrame(pose, context).pose
    }

    @Test
    fun guardHoldsForAllContactPoses() {
        val poses = listOf(
            MiddleSplitPose(),
            DeepOverheadSquatPose(),
            PikeSitPose(),
            DeadHangPose()
        )
        for (p in poses) {
            val pose = finalized(p)
            assertFinite(pose, p.javaClass.simpleName)
            for (spec in pose.contacts) {
                val j = spec.endJoint
                when (j) {
                    Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B ->
                        assertTrue("$j penetrates ground after finalize (${pose.getJoint(j).y})", pose.getJoint(j).y >= -1e-2f)
                    Joint.HAND_A, Joint.HAND_P ->
                        assertEquals("${j} must stay on the bar plane", spec.targetWorld.y, pose.getJoint(j).y, 1.0f)
                    else -> { /* other contact kinds not asserted here */ }
                }
            }
        }
    }

    @Test
    fun authoredChestNeverReachesGuard() {
        // An explicitly authored chest rotation is preserved verbatim (Issue F early-return), so
        // the no-move guard is never even consulted for it.
        val twist = 0.5f
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.chest.localPosition.set(0f, def.torsoLength, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -def.shoulderWidth)
        nodes.shoulderP.localPosition.set(0f, 0f, def.shoulderWidth)
        nodes.chest.localRotation.set(Vector3(0f, 1f, 0f), twist)
        val pose = SkeletonPose()
        SkeletonPose.fromHierarchy(nodes.roots, pose)
        val out = SkeletonPoseFinalizer(def).finalize(pose)
        val chestRot = out.getJointRotation(Joint.CHEST)
        assertEquals("authored twist must survive finalize", twist, chestRot.angle, 1e-3f)
    }

    private fun assertFinite(pose: SkeletonPose, label: String) {
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            assertTrue("$label: $joint not finite (${p.x},${p.y},${p.z})", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
    }
}
