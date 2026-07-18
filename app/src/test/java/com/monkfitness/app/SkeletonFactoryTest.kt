package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class SkeletonFactoryTest {

    private fun collectTraversalOrder(node: SkeletonNode, result: MutableList<Joint>) {
        result.add(node.joint)
        for (child in node.children) {
            collectTraversalOrder(child, result)
        }
    }

    @Test
    fun testStandardSkeletonTopologyAndCount() {
        val nodes = SkeletonFactory.createStandardSkeleton()

        // 1. Root verify
        assertEquals(1, nodes.roots.size)
        assertEquals(Joint.PELVIS, nodes.roots[0].joint)
        assertNull(nodes.pelvis.parent)

        // 2. Parent checks
        assertEquals(nodes.pelvis, nodes.lumbar.parent)
        assertEquals(nodes.lumbar, nodes.chest.parent)
        assertEquals(nodes.chest, nodes.neck.parent)
        assertEquals(nodes.neck, nodes.head.parent)

        assertEquals(nodes.chest, nodes.clavicleA.parent)
        assertEquals(nodes.clavicleA, nodes.scapulaA.parent)
        assertEquals(nodes.scapulaA, nodes.shoulderA.parent)
        assertEquals(nodes.shoulderA, nodes.elbowA.parent)
        assertEquals(nodes.elbowA, nodes.handA.parent)
        assertEquals(nodes.handA, nodes.palmA.parent)
        assertEquals(nodes.palmA, nodes.knucklesA.parent)
        assertEquals(nodes.knucklesA, nodes.fingertipsA.parent)

        assertEquals(nodes.chest, nodes.clavicleP.parent)
        assertEquals(nodes.clavicleP, nodes.scapulaP.parent)
        assertEquals(nodes.scapulaP, nodes.shoulderP.parent)
        assertEquals(nodes.shoulderP, nodes.elbowP.parent)
        assertEquals(nodes.elbowP, nodes.handP.parent)
        assertEquals(nodes.handP, nodes.palmP.parent)
        assertEquals(nodes.palmP, nodes.knucklesP.parent)
        assertEquals(nodes.knucklesP, nodes.fingertipsP.parent)

        assertEquals(nodes.pelvis, nodes.hipF.parent)
        assertEquals(nodes.hipF, nodes.kneeF.parent)
        assertEquals(nodes.kneeF, nodes.ankleF.parent)
        assertEquals(nodes.ankleF, nodes.heelF.parent)
        assertEquals(nodes.ankleF, nodes.toeF.parent)

        assertEquals(nodes.pelvis, nodes.hipB.parent)
        assertEquals(nodes.hipB, nodes.kneeB.parent)
        assertEquals(nodes.kneeB, nodes.ankleB.parent)
        assertEquals(nodes.ankleB, nodes.heelB.parent)
        assertEquals(nodes.ankleB, nodes.toeB.parent)

        // 3. Node count check
        val joints = mutableListOf<Joint>()
        collectTraversalOrder(nodes.roots[0], joints)
        assertEquals(31, joints.size)
    }

    @Test
    fun testPushUpSkeletonTopologyAndCount() {
        val nodes = SkeletonFactory.createPushUpSkeleton()

        // 1. Root verify
        assertEquals(1, nodes.roots.size)
        assertEquals(Joint.ANKLE_F, nodes.roots[0].joint)
        assertNull(nodes.ankleF.parent)

        // 2. Parent checks
        assertEquals(nodes.ankleF, nodes.heelF.parent)
        assertEquals(nodes.ankleF, nodes.toeF.parent)
        assertEquals(nodes.ankleF, nodes.kneeF.parent)
        assertEquals(nodes.kneeF, nodes.hipF.parent)
        assertEquals(nodes.hipF, nodes.pelvis.parent)
        assertEquals(nodes.pelvis, nodes.lumbar.parent)
        assertEquals(nodes.lumbar, nodes.chest.parent)
        assertEquals(nodes.chest, nodes.neck.parent)
        assertEquals(nodes.neck, nodes.head.parent)

        assertEquals(nodes.chest, nodes.clavicleA.parent)
        assertEquals(nodes.clavicleA, nodes.scapulaA.parent)
        assertEquals(nodes.scapulaA, nodes.shoulderA.parent)
        assertEquals(nodes.shoulderA, nodes.elbowA.parent)
        assertEquals(nodes.elbowA, nodes.handA.parent)
        assertEquals(nodes.handA, nodes.palmA.parent)
        assertEquals(nodes.palmA, nodes.knucklesA.parent)
        assertEquals(nodes.knucklesA, nodes.fingertipsA.parent)

        assertEquals(nodes.chest, nodes.clavicleP.parent)
        assertEquals(nodes.clavicleP, nodes.scapulaP.parent)
        assertEquals(nodes.scapulaP, nodes.shoulderP.parent)
        assertEquals(nodes.shoulderP, nodes.elbowP.parent)
        assertEquals(nodes.elbowP, nodes.handP.parent)
        assertEquals(nodes.handP, nodes.palmP.parent)
        assertEquals(nodes.palmP, nodes.knucklesP.parent)
        assertEquals(nodes.knucklesP, nodes.fingertipsP.parent)

        assertEquals(nodes.pelvis, nodes.hipB.parent)
        assertEquals(nodes.hipB, nodes.kneeB.parent)
        assertEquals(nodes.kneeB, nodes.ankleB.parent)
        assertEquals(nodes.ankleB, nodes.heelB.parent)
        assertEquals(nodes.ankleB, nodes.toeB.parent)

        // 3. Node count check
        val joints = mutableListOf<Joint>()
        collectTraversalOrder(nodes.roots[0], joints)
        assertEquals(31, joints.size)
    }

    @Test
    fun testStandardSkeletonTraversalOrder() {
        val nodes = SkeletonFactory.createStandardSkeleton()
        val joints = mutableListOf<Joint>()
        collectTraversalOrder(nodes.roots[0], joints)

        val expectedOrder = listOf(
            Joint.PELVIS,
            Joint.LUMBAR,
            Joint.CHEST,
            Joint.NECK_END,
            Joint.HEAD_POS,
            Joint.CLAVICLE_A,
            Joint.SCAPULA_A,
            Joint.SHOULDER_A,
            Joint.ELBOW_A,
            Joint.HAND_A,
            Joint.PALM_A,
            Joint.KNUCKLES_A,
            Joint.FINGERTIPS_A,
            Joint.CLAVICLE_P,
            Joint.SCAPULA_P,
            Joint.SHOULDER_P,
            Joint.ELBOW_P,
            Joint.HAND_P,
            Joint.PALM_P,
            Joint.KNUCKLES_P,
            Joint.FINGERTIPS_P,
            Joint.HIP_F,
            Joint.KNEE_F,
            Joint.ANKLE_F,
            Joint.HEEL_F,
            Joint.TOE_F,
            Joint.HIP_B,
            Joint.KNEE_B,
            Joint.ANKLE_B,
            Joint.HEEL_B,
            Joint.TOE_B
        )

        assertEquals(expectedOrder, joints)
    }

    @Test
    fun testPushUpSkeletonTraversalOrder() {
        val nodes = SkeletonFactory.createPushUpSkeleton()
        val joints = mutableListOf<Joint>()
        collectTraversalOrder(nodes.roots[0], joints)

        val expectedOrder = listOf(
            Joint.ANKLE_F,
            Joint.HEEL_F,
            Joint.TOE_F,
            Joint.KNEE_F,
            Joint.HIP_F,
            Joint.PELVIS,
            Joint.LUMBAR,
            Joint.CHEST,
            Joint.NECK_END,
            Joint.HEAD_POS,
            Joint.CLAVICLE_A,
            Joint.SCAPULA_A,
            Joint.SHOULDER_A,
            Joint.ELBOW_A,
            Joint.HAND_A,
            Joint.PALM_A,
            Joint.KNUCKLES_A,
            Joint.FINGERTIPS_A,
            Joint.CLAVICLE_P,
            Joint.SCAPULA_P,
            Joint.SHOULDER_P,
            Joint.ELBOW_P,
            Joint.HAND_P,
            Joint.PALM_P,
            Joint.KNUCKLES_P,
            Joint.FINGERTIPS_P,
            Joint.HIP_B,
            Joint.KNEE_B,
            Joint.ANKLE_B,
            Joint.HEEL_B,
            Joint.TOE_B
        )

        assertEquals(expectedOrder, joints)
    }

    @Test
    fun testFinalizerCompatibilityWithMigratedPoses() {
        // Run migrated poses (StandardPushUpPose and AirSquatPose) through finalizer and assert valid state
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // 1. Verify StandardPushUpPose
        val pushupPose = StandardPushUpPose().build(context)
        assertNotNull(pushupPose.roots)
        assertTrue(pushupPose.roots.isNotEmpty())
        assertEquals(Joint.ANKLE_F, pushupPose.roots[0].joint)

        val pushupFinalized = pipeline.produceFrame(pushupPose).pose
        assertNotNull(pushupFinalized)
        assertTrue(pushupFinalized.isTransformsUpdated)

        // 2. Verify AirSquatPose
        val squatPose = AirSquatPose().build(context)
        assertNotNull(squatPose.roots)
        assertTrue(squatPose.roots.isNotEmpty())
        assertEquals(Joint.PELVIS, squatPose.roots[0].joint)

        val squatFinalized = pipeline.produceFrame(squatPose).pose
        assertNotNull(squatFinalized)
        assertTrue(squatFinalized.isTransformsUpdated)

        // 3. Verify AlternatingForwardLungesPose
        val lungePose = AlternatingForwardLungesPose().build(context)
        assertNotNull(lungePose.roots)
        assertTrue(lungePose.roots.isNotEmpty())
        assertEquals(Joint.PELVIS, lungePose.roots[0].joint)

        val lungeFinalized = pipeline.produceFrame(lungePose).pose
        assertNotNull(lungeFinalized)
        assertTrue(lungeFinalized.isTransformsUpdated)
    }
}
