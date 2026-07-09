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
}
