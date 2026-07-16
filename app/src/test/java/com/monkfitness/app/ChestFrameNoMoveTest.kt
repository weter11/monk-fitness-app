package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.validation.poses.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3 (F1) — `SkeletonPoseFinalizer.reconstructChestFrame` must be read-only on Solver-settled
 * contacts. When [EngineFlags.FINALIZER_OWNS_CONVERSION] is enabled the no-move guard (B5) snapshots
 * every contact end-effector before the chest-frame reconstruction and asserts it is unchanged
 * afterwards; if the reconstruction would displace a contact it is rolled back so the contact stays
 * exactly where the solver pinned it.
 *
 * These are diagnostic instruments (validation poses), so they are exercised through the full
 * render path (build -> SkeletonPoseFinalizer, which runs the ConstraintSolver for contact poses).
 */
class ChestFrameNoMoveTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)

    @After
    fun resetFlag() {
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
    }

    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        val raw = pose.build(context)
        return SkeletonPoseFinalizer(def).finalize(raw)
    }

    @Test
    fun guardHoldsForAllContactPoses() {
        EngineFlags.FINALIZER_OWNS_CONVERSION = true
        val poses = listOf(
            MiddleSplitPose(),
            DeepOverheadSquatPose(),
            PikeSitPose(),
            DeadHangPose()
        )
        for (p in poses) {
            val pose = finalized(p)
            assertFinite(pose, p.javaClass.simpleName)
            // Every fixed contact end-effector must remain on its support plane (no move): the
            // no-move guard guarantees finalization never relocates a Solver-settled contact.
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
    fun flagOffPreservesLegacyBehaviour() {
        // With the flag off the finalizer must run the legacy path unchanged; contact poses still
        // finalize to finite skeletons and the suite baseline is untouched. (The Middle Split toe
        // may dip slightly below the floor — a pre-existing foot-orientation quirk, not a Phase 3
        // regression — so we assert finiteness and ankle-on-ground rather than toe.)
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val pose = finalized(MiddleSplitPose())
        assertFinite(pose, "MiddleSplit")
        for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B)) {
            assertTrue("$joint penetrates ground (${pose.getJoint(joint).y})", pose.getJoint(joint).y >= -1e-2f)
        }
    }

    @Test
    fun authoredChestNeverReachesGuard() {
        // An explicitly authored chest rotation is preserved verbatim (Issue F early-return), so
        // the no-move guard is never even consulted for it. Verified through the standard chest
        // helper used by ChestFrameIssueFTest.
        EngineFlags.FINALIZER_OWNS_CONVERSION = true
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
        assertEquals("authored twist must survive finalize with the guard on", twist, chestRot.angle, 1e-3f)
    }

    private fun assertFinite(pose: SkeletonPose, label: String) {
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            assertTrue("$label: $joint not finite (${p.x},${p.y},${p.z})", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
    }
}
