package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class SquatPosesTest {

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
    fun testAirSquatPoseBuildsCorrectly() {
        val pose = AirSquatPose()
        assertNotNull(pose.metadata)
        assertEquals(MotionCurve.EASE_IN_OUT, pose.metadata.motionCurve)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertNotEquals(0f, pelvisY0)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        // At progress 1.0, pelvis should be lower (squatting depth) than at progress 0.0
        assertTrue("Pelvis should descend during air squat: pelvisY0=$pelvisY0, pelvisY1=$pelvisY1", pelvisY1 < pelvisY0)
    }

    @Test
    fun testSumoSquatPoseBuildsCorrectly() {
        val pose = SumoSquatPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertNotEquals(0f, pelvisY0)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertTrue("Pelvis should descend during sumo squat: pelvisY0=$pelvisY0, pelvisY1=$pelvisY1", pelvisY1 < pelvisY0)
    }

    @Test
    fun testJumpSquatPoseBuildsCorrectly() {
        val pose = JumpSquatPose()
        assertNotNull(pose.metadata)
        assertEquals(MotionCurve.LINEAR, pose.metadata.motionCurve)

        // At progress 0.5, rawSin = sin(0.5 * 2 * PI - PI/2) = sin(PI/2) = 1.0 (Peak flight)
        val contextJump = PoseContext(
            progress = 0.5f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 1800f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultJump = pose.build(contextJump)
        assertNotNull(resultJump)
        val pelvisYJump = resultJump.getJoint(Joint.PELVIS).y

        // At progress 0.0, rawSin = sin(0.0 * 2 * PI - PI/2) = sin(-PI/2) = -1.0 (Deep squat)
        val contextSquat = PoseContext(
            progress = 0.0f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 1800f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultSquat = pose.build(contextSquat)
        assertNotNull(resultSquat)
        val pelvisYSquat = resultSquat.getJoint(Joint.PELVIS).y

        assertTrue("Pelvis should be higher at jump peak than squat depth: pelvisYJump=$pelvisYJump, pelvisYSquat=$pelvisYSquat", pelvisYJump > pelvisYSquat)
    }

    @Test
    fun testDeepSquatHoldPoseIsStatic() {
        val pose = DeepSquatHoldPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y

        val result1 = pose.build(context1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y

        assertEquals("Deep squat hold pelvis height should be static across progress", pelvisY0, pelvisY1, 1e-4f)
    }
}
