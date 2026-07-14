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
        assertEquals("Should have exactly 32 unique indices", 32, indices.distinct().size)

        // Check that they correspond to 0..31
        val expectedRange = (0..31).toList()
        assertEquals("Indices must be a contiguous range from 0 to 31", expectedRange, indices.sorted())
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

    @Test
    fun testRotationDrivenPoseBuilderAndCompatibility() {
        val pelvis = SkeletonNode(Joint.PELVIS)
        pelvis.localPosition.set(0f, 10f, 0f)
        pelvis.localRotation.set(Vector3(0f, 0f, 1f), 0.1f)

        val chest = SkeletonNode(Joint.CHEST)
        chest.localPosition.set(0f, 50f, 0f)
        chest.localRotation.set(Vector3(0f, 0f, 1f), 0.2f)
        pelvis.addChild(chest)

        val shoulderA = SkeletonNode(Joint.SHOULDER_A)
        shoulderA.localPosition.set(0f, 0f, -20f)
        chest.addChild(shoulderA)

        val elbowA = SkeletonNode(Joint.ELBOW_A)
        elbowA.localPosition.set(-30f, 0f, 0f)
        shoulderA.addChild(elbowA)

        val handA = SkeletonNode(Joint.HAND_A)
        handA.localPosition.set(-30f, 0f, 0f)
        elbowA.addChild(handA)

        val wristA = SkeletonNode(Joint.WRIST_A)
        wristA.localPosition.set(0f, 0f, 0f)
        handA.addChild(wristA)

        val hipF = SkeletonNode(Joint.HIP_F)
        hipF.localPosition.set(0f, 0f, -20f)
        pelvis.addChild(hipF)

        val kneeF = SkeletonNode(Joint.KNEE_F)
        kneeF.localPosition.set(0f, -40f, 0f)
        hipF.addChild(kneeF)

        val ankleF = SkeletonNode(Joint.ANKLE_F)
        ankleF.localPosition.set(0f, -40f, 0f)
        kneeF.addChild(ankleF)

        val pose = SkeletonPose()
        pose.roots = listOf(pelvis)

        val finalizer = SkeletonPoseFinalizer(SkeletonDefinition.DEFAULT_ADULT)
        val finalizedPose = finalizer.finalize(pose)

        // Check roots reference preservation
        assertEquals(pose.roots, finalizedPose.roots)

        // Check world position derivation via FK:
        // Pelvis world position should be (0, 10, 0)
        assertEquals(0f, finalizedPose.getJoint(Joint.PELVIS).x, 1e-4f)
        assertEquals(10f, finalizedPose.getJoint(Joint.PELVIS).y, 1e-4f)

        // Pelvis world rotation should be 0.1 rad around Z-axis
        val pelvisRot = finalizedPose.getJointRotation(Joint.PELVIS)
        assertEquals(0.1f, pelvisRot.angle, 1e-4f)

        // Verify that missing segments like Heel/Toe and Palm/Fingertips are completed
        assertNotEquals(0f, finalizedPose.getJoint(Joint.TOE_F).mag(), 1e-4f)
        assertNotEquals(0f, finalizedPose.getJoint(Joint.HEEL_F).mag(), 1e-4f)
        assertNotEquals(0f, finalizedPose.getJoint(Joint.FINGERTIPS_A).mag(), 1e-4f)
    }

    @Test
    fun testAnimationStateAndPoseContextAPI() {
        val state = AnimationState(
            progress = 0.5f,
            side = Side.LEFT,
            phase = 1.5f,
            playbackSpeed = 1.2f,
            loopIndex = 3,
            deltaTime = 16.6f,
            mirrored = true
        )
        val context = PoseContext(
            state = state,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            cycleDuration = 4000f
        )

        // Verify primary values are accessible via PoseContext
        assertEquals(state, context.state)
        assertEquals(0.5f, context.progress, 1e-4f)
        assertEquals(Side.LEFT, context.side)
        assertEquals(1.5f, context.phase, 1e-4f)
        assertEquals(1.2f, context.playbackSpeed, 1e-4f)
        assertEquals(3, context.loopIndex)
        assertEquals(16.6f, context.deltaTime, 1e-4f)
        assertTrue(context.mirrored)
        assertEquals(4000f, context.cycleDuration, 1e-4f)

        // Verify compatibility constructor creates matching state
        val compatContext = PoseContext(
            progress = 0.5f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 4000f,
            playbackSpeed = 1.2f,
            mirrored = true,
            phase = 1.5f,
            loopIndex = 3
        )
        assertEquals(state, compatContext.state)
        assertEquals(4000f, compatContext.cycleDuration, 1e-4f)
    }
}
