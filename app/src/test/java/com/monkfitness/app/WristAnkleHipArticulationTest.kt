package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Covers the three engine tickets, exercising only the production API surface (no parallel
 * rotation channels; wrist/ankle/hip articulation flows through the joint's single
 * [SkeletonNode.localRotation], the same path every other joint uses):
 *  - UNI-8: wrist/ankle 2-DOF combined articulation — two anatomical axes are composed into one
 *    exact rotation by [SkeletonMath.composeRotations] (reused by [SkeletonMath.buildWristRotation]
 *    / [SkeletonMath.buildAnkleRotation]) that the author writes into `localRotation`.
 *  - UNI-9: degenerate straight-limb bake guard (a too-close straight target no longer writes a
 *    zero-length second bone; both bone lengths are preserved via a triangle fallback).
 *  - UNI-10: hip 3-DOF ball-joint authoring composer ([SkeletonMath.buildHipRotation]).
 */
class WristAnkleHipArticulationTest {

    private fun mag(v: Vector3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    // ---------------------------------------------------------------- UNI-8: composeRotations

    @Test
    fun composeRotationsEqualsSequentialApplication() {
        val a = JointRotation(Vector3(0f, 0f, 1f), 0.3f)
        val b = JointRotation(Vector3(0f, 1f, 0f), 0.4f)
        val composed = JointRotation()
        SkeletonMath.composeRotations(a, b, composed)

        val v = Vector3(1f, 0f, 0f)
        // Apply b first, then a.
        val sequential = SkeletonMath.rotAround(v, b.axis, b.angle)
        SkeletonMath.rotAround(Vector3(sequential.x, sequential.y, sequential.z), a.axis, a.angle, sequential)

        val viaComposed = SkeletonMath.rotAround(v, composed.axis, composed.angle)

        assertEquals(sequential.x, viaComposed.x, 1e-4f)
        assertEquals(sequential.y, viaComposed.y, 1e-4f)
        assertEquals(sequential.z, viaComposed.z, 1e-4f)
    }

    @Test
    fun composeWithIdentityIsUnchanged() {
        val a = JointRotation(Vector3(0f, 0f, 1f), 0.5f)
        val identity = JointRotation()
        val out = JointRotation()
        SkeletonMath.composeRotations(a, identity, out)

        val v = Vector3(1f, 0f, 0f)
        val expected = SkeletonMath.rotAround(v, a.axis, a.angle)
        val actual = SkeletonMath.rotAround(v, out.axis, out.angle)
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
        assertEquals(expected.z, actual.z, 1e-4f)
    }

    // ---------------------------------------------------------------- UNI-8: wrist 2-DOF

    @Test
    fun wristCombinesFlexionAndDeviation() {
        // Composed into `out` (which the author writes into the wrist's localRotation).
        val out = JointRotation()
        SkeletonMath.buildWristRotation(0.3f, 0.4f, out)
        // Flexion about Z rotates the forearm +X toward +Y; deviation about Y rotates it toward -Z.
        val dir = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), out.axis, out.angle)
        // Both DOFs must contribute — neither axis is dropped.
        assertTrue("flexion component present", kotlin.math.abs(dir.y) > 1e-3f)
        assertTrue("deviation component present", kotlin.math.abs(dir.z) > 1e-3f)
    }

    @Test
    fun wristSingleAxisIsPure() {
        val flexOnly = JointRotation()
        SkeletonMath.buildWristRotation(0.3f, 0f, flexOnly)
        val d1 = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), flexOnly.axis, flexOnly.angle)
        assertEquals("flexion-only has no deviation (z)", 0f, d1.z, 1e-4f)

        val devOnly = JointRotation()
        SkeletonMath.buildWristRotation(0f, 0.4f, devOnly)
        val d2 = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), devOnly.axis, devOnly.angle)
        assertEquals("deviation-only has no flexion (y)", 0f, d2.y, 1e-4f)
    }

    @Test
    fun wristIdentityIsNeutral() {
        val out = JointRotation()
        SkeletonMath.buildWristRotation(0f, 0f, out)
        assertEquals(0f, out.angle, 1e-4f)
    }

    // ---------------------------------------------------------------- UNI-8: ankle 2-DOF

    @Test
    fun ankleDorsiflexionOnlyMatchesSagittalRotation() {
        val out = JointRotation()
        SkeletonMath.buildAnkleRotation(0.35f, 0f, out)
        val d = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), out.axis, out.angle)
        val expected = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f), 0.35f)
        assertEquals(expected.x, d.x, 1e-4f)
        assertEquals(expected.y, d.y, 1e-4f)
        assertEquals(expected.z, d.z, 1e-4f)
    }

    @Test
    fun ankleCombinesBothDofExactly() {
        val out = JointRotation()
        SkeletonMath.buildAnkleRotation(0.35f, 0.25f, out)
        // Apply to an off-axis probe so inversion (about the long X axis) is observable.
        val probe = Vector3(1f, 1f, 0f).normalize()
        val viaComposed = SkeletonMath.rotAround(probe, out.axis, out.angle)

        // Sequential: inversion (Rx) first, then dorsiflexion (Rz).
        val seq = SkeletonMath.rotAround(probe, Vector3(1f, 0f, 0f), 0.25f)
        SkeletonMath.rotAround(Vector3(seq.x, seq.y, seq.z), Vector3(0f, 0f, 1f), 0.35f, seq)

        assertEquals(seq.x, viaComposed.x, 1e-4f)
        assertEquals(seq.y, viaComposed.y, 1e-4f)
        assertEquals(seq.z, viaComposed.z, 1e-4f)
    }

    // ---------------------------------------------------------------- UNI-10: hip 3-DOF

    @Test
    fun hipFlexionOnlyIsSagittal() {
        val out = JointRotation()
        SkeletonMath.buildHipRotation(0.5f, 0f, 0f, -1f, out)
        val d = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), out.axis, out.angle)
        val expected = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f), 0.5f)
        assertEquals(expected.x, d.x, 1e-4f)
        assertEquals(expected.y, d.y, 1e-4f)
        assertEquals(expected.z, d.z, 1e-4f)
    }

    @Test
    fun hipAbductionMirrorsBySide() {
        val left = JointRotation()
        val right = JointRotation()
        SkeletonMath.buildHipRotation(0f, 0.4f, 0f, -1f, left)
        SkeletonMath.buildHipRotation(0f, 0.4f, 0f, 1f, right)
        // Opposite side signs must produce opposite-signed axial-frontal rotations.
        assertEquals(-0.4f, left.angle * left.axis.y, 1e-3f)
        assertEquals(0.4f, right.angle * right.axis.y, 1e-3f)
    }

    @Test
    fun hipComposesThreeDofExactly() {
        val out = JointRotation()
        val sideSign = -1f
        SkeletonMath.buildHipRotation(0.3f, 0.2f, 0.15f, sideSign, out)

        val probe = Vector3(1f, 0.5f, 0.25f).normalize()
        val viaComposed = SkeletonMath.rotAround(probe, out.axis, out.angle)

        // R = Rz(flex) * Ry(abd*sign) * Rx(rot*sign): apply Rx, then Ry, then Rz.
        val seq = SkeletonMath.rotAround(probe, Vector3(1f, 0f, 0f), 0.15f * sideSign)
        SkeletonMath.rotAround(Vector3(seq.x, seq.y, seq.z), Vector3(0f, 1f, 0f), 0.2f * sideSign, seq)
        SkeletonMath.rotAround(Vector3(seq.x, seq.y, seq.z), Vector3(0f, 0f, 1f), 0.3f, seq)

        assertEquals(seq.x, viaComposed.x, 1e-4f)
        assertEquals(seq.y, viaComposed.y, 1e-4f)
        assertEquals(seq.z, viaComposed.z, 1e-4f)
    }

    // ---------------------------------------------------------------- UNI-9: degenerate guard

    @Test
    fun straightLimbTooCloseKeepsBothBoneLengths() {
        val root = Vector3(0f, 0f, 0f)
        val target = Vector3(40f, 0f, 0f) // closer than the upper bone (L1 = 100)
        val l1 = 100f
        val l2 = 100f
        val result = SkeletonMath.solveStraightLimb(
            root, target, l1, l2, IKConstraint.LegConstraint
        )

        val bone1 = mag(Vector3(result.joint.x, result.joint.y, result.joint.z).subtract(root))
        val bone2 = mag(Vector3(result.end.x, result.end.y, result.end.z)
            .subtract(Vector3(result.joint.x, result.joint.y, result.joint.z)))

        // Both bones must keep their true lengths — no degenerate zero-length second bone.
        assertEquals("upper bone preserved", l1, bone1, 1e-2f)
        assertEquals("lower bone preserved", l2, bone2, 1e-2f)

        val jointToEnd = mag(Vector3(result.end.x, result.end.y, result.end.z)
            .subtract(Vector3(result.joint.x, result.joint.y, result.joint.z)))
        assertTrue("middle joint is not collapsed onto the end", jointToEnd > 1f)
    }

    @Test
    fun straightLimbReachableStaysStraight() {
        val root = Vector3(0f, 0f, 0f)
        val target = Vector3(190f, 0f, 0f) // within reach, longer than L1
        val l1 = 100f
        val l2 = 100f
        val result = SkeletonMath.solveStraightLimb(
            root, target, l1, l2, IKConstraint.LegConstraint
        )

        val bone1 = mag(Vector3(result.joint.x, result.joint.y, result.joint.z).subtract(root))
        assertEquals("upper bone exact", l1, bone1, 1e-2f)

        // Straight case: root, joint, end are collinear (cross product ~ 0).
        val rootToJoint = Vector3(result.joint.x, result.joint.y, result.joint.z).subtract(root)
        val rootToEnd = Vector3(result.end.x, result.end.y, result.end.z).subtract(root)
        val cross = rootToJoint.cross(rootToEnd)
        assertEquals(0f, mag(cross), 1e-2f)
    }
}
