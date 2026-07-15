package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Issue F — `SkeletonPoseFinalizer.reconstructChestFrame` must NOT overwrite an explicitly
 * authored chest rotation, and the fallback reconstruction (for chests whose rotation is left
 * unauthored / identity) must build a correct, orthonormal chest frame.
 *
 * Regression coverage:
 *  - An authored thoracic twist (chest-local +Y) survives finalization instead of being
 *    discarded and re-derived from the (symmetric) shoulder line.
 *  - An authored chest flex (chest-local +Z) survives finalization.
 *  - The identity-chest fallback derives a spine-aligned frame whose world rotation equals the
 *    FK-derived (correct) frame — i.e. it no longer produces the degenerate-matrix / wrong-angle
 *    result caused by the single-argument `Vector3.cross(v)` overload (Issue F).
 */
class ChestFrameIssueFTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val torso = def.torsoLength
    private val shoulderW = def.shoulderWidth

    private fun buildStandardChest(chestAxis: Vector3? = null, chestAngle: Float = 0f): SkeletonPose {
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.chest.localPosition.set(0f, torso, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -shoulderW)
        nodes.shoulderP.localPosition.set(0f, 0f, shoulderW)
        if (chestAxis != null) nodes.chest.localRotation.set(chestAxis, chestAngle)
        val pose = SkeletonPose()
        SkeletonPose.fromHierarchy(nodes.roots, pose)
        return SkeletonPoseFinalizer(def).finalize(pose)
    }

    @Test
    fun authoredChestTwistIsPreserved() {
        val twist = 0.5f
        val pose = buildStandardChest(chestAxis = Vector3(0f, 1f, 0f), chestAngle = twist)

        val chestRot = pose.getJointRotation(Joint.CHEST)
        assertEquals("authored twist angle must survive finalization", twist, chestRot.angle, 1e-3f)
        assertTrue("twist axis should remain ~+Y", abs(chestRot.axis.y) > 0.9f)

        // The twist must actually rotate the upper chain: the shoulder line gains an X component.
        val sA = pose.getJoint(Joint.SHOULDER_A)
        val sP = pose.getJoint(Joint.SHOULDER_P)
        val shoulderLineX = abs(sA.x - sP.x)
        assertTrue("authored twist must give the shoulder line an X component (got $shoulderLineX)", shoulderLineX > 10f)
    }

    @Test
    fun authoredChestFlexIsPreserved() {
        val flex = 0.4f
        val pose = buildStandardChest(chestAxis = Vector3(0f, 0f, 1f), chestAngle = flex)

        val chestRot = pose.getJointRotation(Joint.CHEST)
        assertEquals("authored flex angle must survive finalization", flex, chestRot.angle, 1e-3f)
        assertTrue("flex axis should remain ~+Z", abs(chestRot.axis.z) > 0.9f)
    }

    @Test
    fun identityChestFallbackAlignsWithSpine_notDegenerate() {
        // Tilt the pelvis so the trunk is horizontal (spine along world -X). Chest local rotation
        // is left identity, so the fallback reconstruction must orient the chest along the spine.
        val tilt = PI.toFloat() / 2f
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.pelvis.localRotation.set(Vector3(0f, 0f, 1f), tilt)
        nodes.chest.localPosition.set(0f, torso, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -shoulderW)
        nodes.shoulderP.localPosition.set(0f, 0f, shoulderW)
        val pose = SkeletonPose()
        SkeletonPose.fromHierarchy(nodes.roots, pose)
        val out = SkeletonPoseFinalizer(def).finalize(pose)

        // For a symmetric thorax the reconstructed chest world rotation equals the FK-derived
        // frame (a pure +Z tilt of 90 deg), NOT the ~120 deg result the old degenerate
        // single-argument cross produced.
        val chestRot = out.getJointRotation(Joint.CHEST)
        assertEquals("identity-chest fallback must yield the correct spine-aligned tilt", tilt, chestRot.angle, 1e-2f)
        assertTrue("fallback tilt axis should be ~+Z", abs(chestRot.axis.z) > 0.9f)

        // The chest world position still sits torsoLength along the spine from the pelvis.
        val chest = out.getJoint(Joint.CHEST)
        assertEquals(0f, chest.y, 1e-1f)
        assertEquals(-torso, chest.x, 1e-1f)
    }
}
