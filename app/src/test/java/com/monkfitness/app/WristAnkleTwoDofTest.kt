package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

private fun dist(a: Vector3, b: Vector3): Float {
    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * UNI-8 — the wrist and ankle were single-DOF joints: only one combined axis-angle was
 * applied, so a real 2-DOF motion (wrist pronation + radial deviation; ankle dorsi-flexion
 * + inversion/eversion) collapsed into a single rotation. This test proves the engine now
 * supports an independent SECOND rotation composed with the primary one:
 *  1. [SkeletonMath.composeRotations] combines two axis-angle rotations.
 *  2. [HandDefinition.computeHandJoints] with a primary + secondary rotation yields a hand
 *     pose different from either rotation applied alone.
 *  3. [FootDefinition.computeHeelToe] likewise honors a secondary ankle articulation.
 *  4. [SkeletonPose] stores/round-trips the secondary rotation and copies it via [copyFrom].
 */
class WristAnkleTwoDofTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    @Test
    fun testComposeRotationsCombinesTwoAxes() {
        val r1 = JointRotation(Vector3(0f, 0f, 1f), 0.4f)
        val r2 = JointRotation(Vector3(1f, 0f, 0f), 0.4f)
        val composed = JointRotation()
        SkeletonMath.composeRotations(r1, r2, composed)

        // Applied to a vector, the composed rotation differs from either single rotation.
        val v = Vector3(0f, 0f, -1f)
        val onlyR1 = SkeletonMath.rotAround(v.copy(), r1.axis, r1.angle, Vector3())
        val onlyR2 = SkeletonMath.rotAround(v.copy(), r2.axis, r2.angle, Vector3())
        val both = SkeletonMath.rotAround(v.copy(), composed.axis, composed.angle, Vector3())
        assertTrue("composed rotation must differ from primary-only", dist(both, onlyR1) > 1e-2f)
        assertTrue("composed rotation must differ from secondary-only", dist(both, onlyR2) > 1e-2f)
    }

    @Test
    fun testHandJointsHonorsSecondaryRotation() {
        val hand = def.hand
        val wrist = Vector3(0f, 0f, 0f)
        val dir = Vector3(0f, 0f, -1f)

        val primary = JointRotation(Vector3(0f, 0f, 1f), 0.5f)
        val secondary = JointRotation(Vector3(1f, 0f, 0f), 0.5f)

        val single = HandJoints()
        hand.computeHandJoints(wrist, dir, primary, single)

        val dual = HandJoints()
        hand.computeHandJoints(wrist, dir, primary, secondary, dual)

        assertTrue(
            "secondary wrist rotation must change the completed hand (2-DOF)",
            dist(dual.fingertips, single.fingertips) > 1e-2f
        )
    }

    @Test
    fun testHandJointsIdentitySecondaryMatchesSingleDof() {
        val hand = def.hand
        val wrist = Vector3(0f, 0f, 0f)
        val dir = Vector3(0f, 0f, -1f)
        val primary = JointRotation(Vector3(0f, 0f, 1f), 0.5f)

        val single = HandJoints()
        hand.computeHandJoints(wrist, dir, primary, single)

        val dual = HandJoints()
        hand.computeHandJoints(wrist, dir, primary, JointRotation(), dual)

        assertEquals(
            "identity secondary must reproduce the single-DOF result",
            0f, dist(dual.fingertips, single.fingertips), 1e-5f
        )
    }

    @Test
    fun testFootHeelToeHonorsSecondaryRotation() {
        val foot = def.foot
        val ankle = Vector3(0f, 0f, 0f)
        val neutral = Vector3(1f, 0f, 0f)

        val primary = JointRotation(Vector3(0f, 0f, 1f), 0.3f)
        val secondary = JointRotation(Vector3(0f, 1f, 0f), 0.3f)

        val toeSingle = Vector3(); val heelSingle = Vector3()
        foot.computeHeelToe(ankle, neutral, primary, toeSingle, heelSingle)

        val toeDual = Vector3(); val heelDual = Vector3()
        foot.computeHeelToe(ankle, neutral, primary, secondary, toeDual, heelDual)

        assertTrue(
            "secondary ankle rotation must change the completed foot (2-DOF)",
            dist(toeDual, toeSingle) > 1e-2f
        )
    }

    @Test
    fun testSkeletonPoseSecondaryRotationRoundTripsAndCopies() {
        val pose = SkeletonPose()
        val r = JointRotation(Vector3(0f, 1f, 0f), 0.4f)
        pose.setSecondaryRotation(Joint.HAND_A, r)

        assertEquals(0.4f, pose.getSecondaryRotation(Joint.HAND_A).angle, 1e-5f)
        // Unset joint reads as identity (single-DOF default).
        assertEquals(0f, pose.getSecondaryRotation(Joint.HAND_P).angle, 1e-5f)

        val copied = SkeletonPose()
        copied.copyFrom(pose)
        assertEquals(0.4f, copied.getSecondaryRotation(Joint.HAND_A).angle, 1e-5f)
    }
}
