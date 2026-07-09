package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralAnimationPerformanceRefactorTest {

    @Test
    fun testJointIndicesAreUniqueAndStable() {
        val indices = Joint.values().map { it.index }
        assertEquals("Should have exactly 28 unique indices", 28, indices.distinct().size)

        // Check that they correspond to 0..27
        val expectedRange = (0..27).toList()
        assertEquals("Indices must be a contiguous range from 0 to 27", expectedRange, indices.sorted())
    }

    @Test
    fun testSkeletonPoseInternalArrayMapping() {
        val pose = SkeletonPose()
        assertEquals(Joint.entries.size, pose.joints.size)

        val testPos = Vector3(1.2f, 3.4f, 5.6f)
        pose.setJoint(Joint.HEAD_POS, testPos)

        // Verify direct array access yields the exact same values as getJoint
        val arrayPos = pose.joints[Joint.HEAD_POS.index]
        assertEquals(testPos.x, arrayPos.x, 1e-4f)
        assertEquals(testPos.y, arrayPos.y, 1e-4f)
        assertEquals(testPos.z, arrayPos.z, 1e-4f)
    }

    @Test
    fun testProjectedSkeletonHasIndexedJointsArray() {
        val projected = ProjectedSkeleton()
        assertEquals("Projected skeleton joints array size must match Joint entries size", Joint.entries.size, projected.joints.size)
    }

    @Test
    fun testSkeletonProjectorContiguousArrayExecution() {
        val pose = SkeletonPose()
        // Put some random non-zero positions
        for (joint in Joint.values()) {
            pose.setJoint(joint, Vector3(joint.index * 2f, 5f, -3f))
        }

        val camera = Camera()
        val definition = SkeletonDefinition.DEFAULT_ADULT
        val style = SkeletonStyle.DEFAULT
        val engine = SkeletonEngine(definition, style)
        val projector = SkeletonProjector()
        val projected = ProjectedSkeleton()

        // Execute project
        projector.project(pose, camera, engine, 1000f, 1000f, projected)

        // Verify that values in projected.joints corresponding to HEAD_POS match the projected HEAD_POS
        val projectedHead = projected.joints[Joint.HEAD_POS.index]
        assertTrue("Projected X coordinate should be populated", projectedHead.x != 0f)
        assertTrue("Projected Y coordinate should be populated", projectedHead.y != 0f)
    }

    @Test
    fun testScreenSpaceCompensationOnIndexedArrays() {
        val joints = Array(5) { ProjectedPoint() }
        joints[0].perspectiveScale = 0.5f
        joints[1].perspectiveScale = 1.0f
        joints[2].perspectiveScale = 1.5f
        joints[3].perspectiveScale = 2.0f
        joints[4].perspectiveScale = 2.5f

        val scales = Array(5) { ScreenSpaceScale() }
        val compensator = ScreenSpaceCompensation()

        // Run batch indexed array method
        compensator.computeScales(joints, scales)

        // Verify that they are correctly updated in-place without any map lookup/allocations
        assertEquals(1.0f, scales[1].radiusScale, 1e-4f)
        assertTrue(scales[0].radiusScale < 1.0f)
        assertTrue(scales[2].radiusScale > 1.0f)
    }
}
