package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class DynamicStretchPosesTest {

    private val context0 = PoseContext(
        progress = 0f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 3500f,
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
        cycleDuration = 3500f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    @Test
    fun testDynamicWorldsGreatestStretchPoseBuildsCorrectly() {
        val pose = DynamicWorldsGreatestStretchPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val handAX0 = result0.getJoint(Joint.HAND_A).x
        val handAY0 = result0.getJoint(Joint.HAND_A).y

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val handAX1 = result1.getJoint(Joint.HAND_A).x
        val handAY1 = result1.getJoint(Joint.HAND_A).y

        // The dynamic arm sweeps up, so the Y coordinate should rise
        assertTrue("Hand A should sweep upward: handAY0=$handAY0, handAY1=$handAY1", handAY1 > handAY0)
    }

    @Test
    fun testQuadrupedThoracicRotationsPoseBuildsCorrectly() {
        val pose = QuadrupedThoracicRotationsPose()
        assertNotNull(pose.metadata)

        // P12 WP-I: read the choreography off the PUBLISHED frame of the production pipeline — the
        // thoracic arm is realized by the engine-owned stage (the authoring bake registers intent
        // only, §12.7a), and this template returns an independent snapshot carrier, so the flat
        // joint array of a half-built carrier is not the frame the engine produces.
        val result0 = SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT).produceFrame(pose, context0).pose
        assertNotNull(result0)
        val elbowAX0 = result0.getJoint(Joint.ELBOW_A).x
        val elbowAY0 = result0.getJoint(Joint.ELBOW_A).y

        val result1 = SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT).produceFrame(pose, context1).pose
        assertNotNull(result1)
        val elbowAX1 = result1.getJoint(Joint.ELBOW_A).x
        val elbowAY1 = result1.getJoint(Joint.ELBOW_A).y

        // The arm sweeps up and open, raising elbow Y
        assertTrue("Elbow A should sweep open/upward: elbowAY0=$elbowAY0, elbowAY1=$elbowAY1", elbowAY1 > elbowAY0)
    }
}
