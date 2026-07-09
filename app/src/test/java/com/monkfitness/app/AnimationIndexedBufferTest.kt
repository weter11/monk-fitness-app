package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class AnimationIndexedBufferTest {

    @Test
    fun testStableJointIndices() {
        val indicesSet = mutableSetOf<Int>()
        val maxIndex = Joint.entries.size - 1

        for (joint in Joint.entries) {
            val index = joint.index
            assertNotNull(index)
            // Ensure stable indices are within the valid range of the array
            assertTrue("Joint index $index is out of bounds (0..$maxIndex)", index in 0..maxIndex)
            // Ensure each index is unique
            assertTrue("Duplicate joint index found: $index", indicesSet.add(index))
        }

        // Ensure we have exactly as many unique indices as there are joints
        assertEquals(Joint.entries.size, indicesSet.size)
    }

    @Test
    fun testSkeletonPoseIndexedAccess() {
        val pose = SkeletonPose()
        val pelvis = Joint.PELVIS
        val pos = Vector3(1f, 2f, 3f)
        pose.setJoint(pelvis, pos)

        assertEquals(1f, pose.joints[pelvis.index].x)
        assertEquals(2f, pose.joints[pelvis.index].y)
        assertEquals(3f, pose.joints[pelvis.index].z)

        assertEquals(pose.getJoint(pelvis), pose.joints[pelvis.index])
    }

    @Test
    fun testProjectedSkeletonIndexedAccess() {
        val skeleton = ProjectedSkeleton()
        val head = Joint.HEAD_POS
        skeleton.joints[head.index].update(10f, 20f, 30f, 1.5f)

        assertEquals(10f, skeleton.joints[head.index].x)
        assertEquals(20f, skeleton.joints[head.index].y)
        assertEquals(30f, skeleton.joints[head.index].depth)
        assertEquals(1.5f, skeleton.joints[head.index].perspectiveScale)
    }

    @Test
    fun testValidatePoses() {
        val definition = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(definition)

        // 1. Validate Squat Pose
        val squat = com.monkfitness.app.poses.SquatPose()
        val squatContext = PoseContext(
            progress = 0.5f,
            deltaTime = 0f,
            cycleDuration = 3f,
            playbackSpeed = 1f,
            side = Side.LEFT,
            mirrored = false,
            phase = 0f,
            loopIndex = 0,
            definition = definition
        )
        val squatPoseRaw = squat.build(squatContext)
        val squatPoseFinal = finalizer.finalize(squatPoseRaw)

        // Ensure PELVIS is at its valid height and not mutated to headDir
        val squatPelvis = squatPoseFinal.getJoint(Joint.PELVIS)
        assertTrue("Squat Pelvis height should be valid", squatPelvis.y > 40f)

        // 2. Validate World's Greatest Stretch
        val wgs = com.monkfitness.app.poses.WorldGreatestStretchPose()
        val wgsContext = PoseContext(
            progress = 0.5f,
            deltaTime = 0f,
            cycleDuration = 6f,
            playbackSpeed = 1f,
            side = Side.RIGHT,
            mirrored = false,
            phase = 0f,
            loopIndex = 0,
            definition = definition
        )
        val wgsPoseRaw = wgs.build(wgsContext)
        val wgsPoseFinal = finalizer.finalize(wgsPoseRaw)

        // Ensure PELVIS is at its valid position and not mutated to hand/wrist
        val wgsPelvis = wgsPoseFinal.getJoint(Joint.PELVIS)
        val wgsWristA = wgsPoseFinal.getJoint(Joint.WRIST_A)
        assertTrue("WGS Pelvis position should be correct", wgsPelvis.y in 35f..55f)
        assertTrue("WGS Pelvis should not be mutated to WRIST_A", wgsPelvis != wgsWristA)
    }
}
