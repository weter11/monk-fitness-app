package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class ThoracicAndHamstringStretchPosesTest {

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
    fun testThoracicExtensionPoseBuildsCorrectly() {
        val pose = ThoracicExtensionPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisX1 = result1.getJoint(Joint.PELVIS).x

        // Pelvis leans forward to counterbalance backward thoracic extension (towards +X)
        assertTrue("Pelvis should shift forward to counterbalance back-bend: pelvisX0=$pelvisX0, pelvisX1=$pelvisX1", pelvisX1 > pelvisX0)
    }

    @Test
    fun testHamstringStretchPoseBuildsCorrectly() {
        val pose = HamstringStretchPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y

        // Pelvis Y should remain completely flat on the floor (at 15f)
        assertEquals("Pelvis Y should remain static on the floor", 15f, pelvisY0, 1e-4f)
        assertEquals("Pelvis Y should remain static on the floor", 15f, pelvisY1, 1e-4f)
    }
}
