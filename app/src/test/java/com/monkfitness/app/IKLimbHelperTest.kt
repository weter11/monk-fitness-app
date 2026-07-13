package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class IKLimbHelperTest {

    @Test
    fun testPushUpPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify Standard PushUp compiles, runs, and finalizes correctly with bakeIkLimb helper
        val pushupPose = StandardPushUpPose().build(context)
        val finalizedPushup = finalizer.finalize(pushupPose)

        assertNotNull(finalizedPushup)
        assertTrue(finalizedPushup.isTransformsUpdated)

        // Check finite and reasonable values for hand and elbow joints
        val handA = finalizedPushup.getJoint(Joint.HAND_A)
        val elbowA = finalizedPushup.getJoint(Joint.ELBOW_A)
        val shoulderA = finalizedPushup.getJoint(Joint.SHOULDER_A)

        assertTrue(handA.x.isFinite() && handA.y.isFinite() && handA.z.isFinite())
        assertTrue(elbowA.x.isFinite() && elbowA.y.isFinite() && elbowA.z.isFinite())
        assertTrue(shoulderA.x.isFinite() && shoulderA.y.isFinite() && shoulderA.z.isFinite())
    }

    @Test
    fun testSquatPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify AirSquat compiles, runs, and finalizes correctly with bakeIkLimb helper
        val squatPose = AirSquatPose().build(context)
        val finalizedSquat = finalizer.finalize(squatPose)

        assertNotNull(finalizedSquat)
        assertTrue(finalizedSquat.isTransformsUpdated)

        val kneeF = finalizedSquat.getJoint(Joint.KNEE_F)
        val ankleF = finalizedSquat.getJoint(Joint.ANKLE_F)
        val hipF = finalizedSquat.getJoint(Joint.HIP_F)

        assertTrue(kneeF.x.isFinite() && kneeF.y.isFinite() && kneeF.z.isFinite())
        assertTrue(ankleF.x.isFinite() && ankleF.y.isFinite() && ankleF.z.isFinite())
        assertTrue(hipF.x.isFinite() && hipF.y.isFinite() && hipF.z.isFinite())
    }

    @Test
    fun testLungePoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify Lunge compiles, runs, and finalizes correctly with bakeIkLimb helper
        val lungePose = AlternatingForwardLungesPose().build(context)
        val finalizedLunge = finalizer.finalize(lungePose)

        assertNotNull(finalizedLunge)
        assertTrue(finalizedLunge.isTransformsUpdated)

        val kneeF = finalizedLunge.getJoint(Joint.KNEE_F)
        val ankleF = finalizedLunge.getJoint(Joint.ANKLE_F)

        assertTrue(kneeF.x.isFinite())
        assertTrue(ankleF.x.isFinite())
    }

    @Test
    fun testHangPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify HangPose compiles, runs, and finalizes correctly with bakeIkLimb helper
        val hangPose = HangPose().build(context)
        val finalizedHang = finalizer.finalize(hangPose)

        assertNotNull(finalizedHang)
        assertTrue(finalizedHang.isTransformsUpdated)

        val elbowA = finalizedHang.getJoint(Joint.ELBOW_A)
        val kneeF = finalizedHang.getJoint(Joint.KNEE_F)

        assertTrue(elbowA.x.isFinite())
        assertTrue(kneeF.x.isFinite())
    }

    @Test
    fun testPullUpPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify StandardPullUpPose compiles, runs, and finalizes correctly with bakeIkLimb helper
        val pullupPose = StandardPullUpPose().build(context)
        val finalizedPullup = finalizer.finalize(pullupPose)

        assertNotNull(finalizedPullup)
        assertTrue(finalizedPullup.isTransformsUpdated)

        val elbowA = finalizedPullup.getJoint(Joint.ELBOW_A)
        val kneeF = finalizedPullup.getJoint(Joint.KNEE_F)

        assertTrue(elbowA.x.isFinite())
        assertTrue(kneeF.x.isFinite())
    }
}
