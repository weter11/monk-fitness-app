package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Issue E — verifies the spine is modelled as two real segments (PELVIS -> LUMBAR -> CHEST)
 * with independent lower-spine (lumbar / pelvis-tilt) and thoracic DOF, and that the LUMBAR
 * defaults to a pass-through so existing single-bend poses are unchanged.
 *
 * Assertions are position/geometry based: the finalizer's chest-frame reconstruction derives a
 * single chest orientation (Issue F territory) and is deliberately left unchanged here, so this
 * test proves Issue E via the world positions the two segments produce, which is what the
 * renderer and downstream chain consume.
 */
class LumbarThoracicSpineTest {

    private val torso = SkeletonDefinition.DEFAULT_ADULT.torsoLength      // 120f
    private val shoulderW = SkeletonDefinition.DEFAULT_ADULT.shoulderWidth // 46f

    /** Builds a standard skeleton with the trunk offsets authored, then finalizes it. */
    private fun buildAndFinalize(
        lumbarZ: Float = 0f,
        chestAxis: Vector3? = null,
        chestAngle: Float = 0f
    ): SkeletonPose {
        val nodes = SkeletonFactory.createStandardSkeleton()
        // Trunk geometry: chest sits torsoLength above the lumbar; shoulders straddle the chest.
        nodes.chest.localPosition.set(0f, torso, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -shoulderW)
        nodes.shoulderP.localPosition.set(0f, 0f, shoulderW)

        // Author lower-spine (lumbar) and/or thoracic (chest) rotations.
        if (lumbarZ != 0f) nodes.lumbar.localRotation.set(Vector3(0f, 0f, 1f), lumbarZ)
        if (chestAxis != null) nodes.chest.localRotation.set(chestAxis, chestAngle)

        val pose = SkeletonPose()
        SkeletonPose.fromHierarchy(nodes.roots, pose)
        return SkeletonPoseFinalizer(SkeletonDefinition.DEFAULT_ADULT).finalize(pose)
    }

    @Test
    fun lumbarDefaultsToPassThrough_chestUnchanged() {
        val pose = buildAndFinalize()

        // LUMBAR is coincident with the pelvis and adds no rotation.
        val lumbarPos = pose.getJoint(Joint.LUMBAR)
        assertEquals(0f, lumbarPos.x, 1e-3f)
        assertEquals(0f, lumbarPos.y, 1e-3f)
        assertEquals(0f, lumbarPos.z, 1e-3f)
        assertEquals(0f, abs(pose.getJointRotation(Joint.LUMBAR).angle), 1e-3f)

        // The chest resolves to exactly torsoLength above the pelvis (identical to the old
        // single PELVIS->CHEST link), and the shoulders straddle it symmetrically along Z.
        val chest = pose.getJoint(Joint.CHEST)
        assertEquals(0f, chest.x, 1e-2f)
        assertEquals(torso, chest.y, 1e-2f)
        assertEquals(0f, chest.z, 1e-2f)

        val sA = pose.getJoint(Joint.SHOULDER_A)
        val sP = pose.getJoint(Joint.SHOULDER_P)
        assertEquals(torso, sA.y, 1f); assertEquals(torso, sP.y, 1f)
        assertEquals(-shoulderW, sA.z, 1f); assertEquals(shoulderW, sP.z, 1f)
        assertEquals(0f, sA.x, 1f); assertEquals(0f, sP.x, 1f)
    }

    @Test
    fun lumbarFlexionCarriesTheWholeThorax() {
        val l = 0.4f
        val pose = buildAndFinalize(lumbarZ = l)

        // The lower spine carries the bend about +Z.
        val lumbarRot = pose.getJointRotation(Joint.LUMBAR)
        assertEquals(l, abs(lumbarRot.angle), 1e-3f)
        assertTrue("lumbar axis should be ~Z", abs(lumbarRot.axis.z) > 0.9f)

        // The chest is carried along the rotated spine: its world position is the torso offset
        // rotated about +Z by the lumbar angle (the whole thorax follows the lower spine).
        val chest = pose.getJoint(Joint.CHEST)
        assertEquals(-torso * sin(l), chest.x, 0.5f)
        assertEquals(torso * cos(l), chest.y, 0.5f)
        assertEquals(0f, chest.z, 0.5f)

        // Both shoulders translate WITH the thorax (same x,y as the chest) — a single rigid
        // torso hinged only at the pelvis could not carry the upper body this way.
        val sA = pose.getJoint(Joint.SHOULDER_A)
        val sP = pose.getJoint(Joint.SHOULDER_P)
        assertEquals(chest.x, sA.x, 0.5f); assertEquals(chest.y, sA.y, 0.5f)
        assertEquals(chest.x, sP.x, 0.5f); assertEquals(chest.y, sP.y, 0.5f)
    }

    @Test
    fun lumbarAndThoracicArticulateIndependently() {
        // Lower spine leans (about +Z); thorax twists (about +Y) — two different motions.
        val l = 0.4f
        val pose = buildAndFinalize(lumbarZ = l, chestAxis = Vector3(0f, 1f, 0f), chestAngle = 0.5f)

        // Lumbar still holds only the lean.
        val lumbarRot = pose.getJointRotation(Joint.LUMBAR)
        assertEquals(l, abs(lumbarRot.angle), 1e-3f)
        assertTrue("lumbar axis should be ~Z", abs(lumbarRot.axis.z) > 0.9f)

        // The thoracic twist rotates the shoulder line out of the pure-Z plane: the shoulder
        // line now has a real X component, which a single rigid torso could not represent.
        val sA = pose.getJoint(Joint.SHOULDER_A)
        val sP = pose.getJoint(Joint.SHOULDER_P)
        val shoulderLineX = abs(sA.x - sP.x)
        assertTrue("thoracic twist must give the shoulder line an X component (got $shoulderLineX)", shoulderLineX > 10f)
    }
}
