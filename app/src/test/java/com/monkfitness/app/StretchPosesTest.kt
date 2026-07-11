package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class StretchPosesTest {

    private val context0 = PoseContext(
        progress = 0f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 3000f,
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
        cycleDuration = 3000f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    @Test
    fun testCouchStretchPoseBuildsCorrectly() {
        val pose = CouchStretchPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisX1 = result1.getJoint(Joint.PELVIS).x

        // Pelvis pushes backward into the couch (towards -X)
        assertTrue("Pelvis should shift backward to increase stretch: pelvisX0=$pelvisX0, pelvisX1=$pelvisX1", pelvisX1 < pelvisX0)
    }

    @Test
    fun testHalfKneelingStretchPoseBuildsCorrectly() {
        val pose = HalfKneelingStretchPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisX1 = result1.getJoint(Joint.PELVIS).x

        // Pelvis lunges forward to drive the stretch (towards +X)
        assertTrue("Pelvis should lunge forward: pelvisX0=$pelvisX0, pelvisX1=$pelvisX1", pelvisX1 > pelvisX0)
    }
}
