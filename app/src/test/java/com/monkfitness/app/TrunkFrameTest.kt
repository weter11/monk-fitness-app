package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-09 — Full 3-DOF trunk frame (twist + side-bend) on the modern rotation-driven path.
 *
 * Verifies the new chest-orientation helpers produce real 3-D local rotations, and that the
 * twist/rotation-family poses (modern path) render a chest whose world frame carries a thoracic
 * twist (and is therefore a genuine 3-D orientation, not a single-axis Z lean).
 */
class TrunkFrameTest {

    private val context0 = PoseContext(
        progress = 0f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f, cycleDuration = 3000f, playbackSpeed = 1f,
        mirrored = false, phase = 0f, loopIndex = 0
    )
    private val context1 = PoseContext(
        progress = 1f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f, cycleDuration = 3000f, playbackSpeed = 1f,
        mirrored = false, phase = 0f, loopIndex = 0
    )

    /** Exposes the protected BasePose chest helpers for unit-level assertions. */
    private class HelperProbe : BasePose() {
        fun twist(node: SkeletonNode, t: Float) = buildChestTwist(node, t)
        fun sideBend(node: SkeletonNode, s: Float) = buildChestSideBend(node, s)
        fun orient(node: SkeletonNode, l: Float, t: Float, s: Float) = buildChestOrientation(node, l, t, s)
        override fun build(context: PoseContext): SkeletonPose = SkeletonPose()
    }

    @Test
    fun testBuildChestTwistAuthoredAs3DRotation() {
        val probe = HelperProbe()
        val chest = SkeletonNode(Joint.CHEST)
        probe.twist(chest, 0.5f)
        assertEquals("twist should rotate about the chest-local +Y axis", 1f, chest.localRotation.axis.y, 1e-4f)
        assertEquals("twist angle should be preserved", 0.5f, chest.localRotation.angle, 1e-4f)
    }

    @Test
    fun testBuildChestSideBendAuthoredAs3DRotation() {
        val probe = HelperProbe()
        val chest = SkeletonNode(Joint.CHEST)
        probe.sideBend(chest, 0.3f)
        assertEquals("side-bend should rotate about the chest-local +X axis", 1f, chest.localRotation.axis.x, 1e-4f)
        assertEquals("side-bend angle should be preserved", 0.3f, chest.localRotation.angle, 1e-4f)
    }

    @Test
    fun testBuildChestOrientationCombinesLeanTwistSideBend() {
        val probe = HelperProbe()
        val chest = SkeletonNode(Joint.CHEST)
        // Combined lean + twist + side-bend: the result must be a single non-trivial 3-D rotation.
        probe.orient(chest, 0.2f, 0.4f, 0.3f)
        assertTrue("combined orientation should be a real rotation", chest.localRotation.angle > 1e-3f)

        // Round-trip through the matrix utilities to confirm it is orthonormal/reconstructable.
        val ax = Vector3(); val ay = Vector3(); val az = Vector3()
        SkeletonMath.rotationToMatrix(chest.localRotation, ax, ay, az)
        // Columns of a rotation matrix are unit length and mutually orthogonal.
        assertEquals(1f, ax.mag(), 1e-3f)
        assertEquals(1f, ay.mag(), 1e-3f)
        assertEquals(1f, az.mag(), 1e-3f)
        assertEquals(0f, ax.dot(ay), 1e-3f)
    }

    @Test
    fun testQuadrupedThoracicRotationsChestCarriesTwist() {
        val pose = QuadrupedThoracicRotationsPose()
        val result = pose.build(context1)
        val r = result.getJointRotation(Joint.CHEST)
        // A thoracic twist is rotation about Y; the chest world frame must encode a real rotation.
        assertTrue("thoracic rotation pose must produce a non-trivial chest rotation", r.angle > 1e-2f)
    }

    @Test
    fun testQuadrupedTwistChangesShoulderPosition() {
        val pose = QuadrupedThoracicRotationsPose()
        val sA0 = pose.build(context0).getJoint(Joint.SHOULDER_A)
        val sA1 = pose.build(context1).getJoint(Joint.SHOULDER_A)
        // Increasing thoracic twist (progress 0 -> 1) must move the active shoulder — proof the
        // chest is a 3-D frame driving the upper chain, not a static Z lean.
        assertTrue(
            "shoulder A should move under thoracic twist: sA0=(${sA0.x},${sA0.z}), sA1=(${sA1.x},${sA1.z})",
            abs(sA1.x - sA0.x) + abs(sA1.z - sA0.z) > 1f
        )
    }

    @Test
    fun testDynamicWorldsGreatestStretchChestCarriesTwist() {
        val pose = DynamicWorldsGreatestStretchPose()
        val r = pose.build(context1).getJointRotation(Joint.CHEST)
        assertTrue("world's greatest stretch must produce a non-trivial chest rotation", r.angle > 1e-2f)
    }
}
