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
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x
        val chestX0 = result0.getJoint(Joint.CHEST).x
        val headX0 = result0.getJoint(Joint.HEAD_POS).x

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        val pelvisX1 = result1.getJoint(Joint.PELVIS).x
        val chestX1 = result1.getJoint(Joint.CHEST).x
        val headX1 = result1.getJoint(Joint.HEAD_POS).x

        // Pelvis stays grounded and level in tall kneeling (no forward shift, no floating).
        assertEquals("Pelvis Y should remain grounded (tall kneel)", 127f, pelvisY0, 1e-3f)
        assertEquals("Pelvis Y should remain grounded (tall kneel)", 127f, pelvisY1, 1e-3f)
        assertEquals("Pelvis X should stay centered over the knees", 0f, pelvisX1 - pelvisX0, 1e-3f)

        // Thoracic extension: the chest and head rotate up and BACK (-X) as the thorax extends.
        assertTrue("Chest should extend backward with thoracic extension: chestX0=$chestX0, chestX1=$chestX1", chestX1 < chestX0 - 5f)
        assertTrue("Head should follow the thoracic extension backward: headX0=$headX0, headX1=$headX1", headX1 < headX0 - 5f)
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
