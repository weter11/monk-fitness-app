package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class BirdDogPosesTest {

    private val context0 = PoseContext(
        progress = 0f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 2500f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    private val context1 = PoseContext(
        progress = 1f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 2500f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    @Test
    fun testStaticBirdDogHoldPoseBuildsCorrectly() {
        val pose = StaticBirdDogHoldPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvis0 = result0.getJoint(Joint.PELVIS)
        val handP0 = result0.getJoint(Joint.HAND_P).x

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvis1 = result1.getJoint(Joint.PELVIS)
        val handP1 = result1.getJoint(Joint.HAND_P).x

        // Pelvis height (Y) should remain stable (tabletop)
        assertEquals(pelvis0.y, pelvis1.y, 1e-4f)

        // Hand P (Right Arm) extends forward (larger X coordinate) as progress goes from 0.0 to 1.0
        assertTrue("Right hand should extend forward: handP0=$handP0, handP1=$handP1", handP1 > handP0)
    }

    @Test
    fun testAlternatingBirdDogPoseAlternatesCorrectly() {
        val pose = AlternatingBirdDogPose()
        assertNotNull(pose.metadata)

        // progress = 0.25 -> rightExt is max(0f, sin(0.5PI)) = 1.0, leftExt is 0.0 (Right Arm & Left Leg extend)
        val contextRightExt = PoseContext(
            progress = 0.25f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 4000f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultRight = pose.build(contextRightExt)
        val rightHandX = resultRight.getJoint(Joint.HAND_P).x
        val leftHandX = resultRight.getJoint(Joint.HAND_A).x

        assertTrue("Right arm (HAND_P) should be extended forward compared to left hand (HAND_A)", rightHandX > leftHandX)

        // progress = 0.75 -> leftExt is max(0f, -sin(1.5PI)) = 1.0, rightExt is 0.0 (Left Arm & Right Leg extend)
        val contextLeftExt = PoseContext(
            progress = 0.75f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 4000f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultLeft = pose.build(contextLeftExt)
        val rightHandXAfter = resultLeft.getJoint(Joint.HAND_P).x
        val leftHandXAfter = resultLeft.getJoint(Joint.HAND_A).x

        assertTrue("Left arm (HAND_A) should be extended forward compared to right hand (HAND_P)", leftHandXAfter > rightHandXAfter)
    }
}
