package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * SupportMathTest verifies the correctness of the generic biomechanical support model:
 * - PivotType enum and SupportContact enum usage.
 * - SupportDefinition.
 * - SupportMath automatic lever length calculations.
 * - SupportMath support centroid calculations.
 * - SupportMath LeverModel calculations including offsets.
 */
class SupportMathTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    @Test
    fun testSupportPointEnum() {
        // Verify target values exist in SupportPoint
        assertNotNull(SupportPoint.LEFT_FOOT)
        assertNotNull(SupportPoint.RIGHT_FOOT)
        assertNotNull(SupportPoint.LEFT_TOES)
        assertNotNull(SupportPoint.RIGHT_TOES)
        assertNotNull(SupportPoint.LEFT_KNEE)
        assertNotNull(SupportPoint.RIGHT_KNEE)
        assertNotNull(SupportPoint.LEFT_HAND)
        assertNotNull(SupportPoint.RIGHT_HAND)
        assertNotNull(SupportPoint.LEFT_ELBOW)
        assertNotNull(SupportPoint.RIGHT_ELBOW)
        assertNotNull(SupportPoint.LEFT_FOREARM)
        assertNotNull(SupportPoint.RIGHT_FOREARM)
        assertNotNull(SupportPoint.PELVIS)
        assertNotNull(SupportPoint.BACK)
        assertNotNull(SupportPoint.HIPS)
        assertNotNull(SupportPoint.CUSTOM)
    }

    @Test
    fun testPivotTypeEnum() {
        assertNotNull(PivotType.FEET)
        assertNotNull(PivotType.KNEES)
        assertNotNull(PivotType.HANDS)
        assertNotNull(PivotType.HIPS)
        assertNotNull(PivotType.ELBOWS)
        assertNotNull(PivotType.PELVIS)
        assertNotNull(PivotType.CUSTOM)
    }

    @Test
    fun testSupportContactDataClass() {
        val contact = SupportContact(
            point = SupportPoint.LEFT_HAND,
            supportsWeight = true,
            fixedPosition = false,
            friction = 0.8f,
            heightOffset = 5f
        )
        assertEquals(SupportPoint.LEFT_HAND, contact.point)
        assertTrue(contact.supportsWeight)
        assertFalse(contact.fixedPosition)
        assertEquals(0.8f, contact.friction, 1e-4f)
        assertEquals(5f, contact.heightOffset, 1e-4f)

        // Test companion objects are non-null and correctly mapped
        assertEquals(SupportPoint.LEFT_FOOT, SupportContact.LEFT_FOOT.point)
        assertEquals(SupportPoint.RIGHT_FOOT, SupportContact.RIGHT_FOOT.point)
        assertEquals(SupportPoint.LEFT_TOES, SupportContact.LEFT_TOES.point)
        assertEquals(SupportPoint.RIGHT_TOES, SupportContact.RIGHT_TOES.point)
        assertEquals(SupportPoint.LEFT_KNEE, SupportContact.LEFT_KNEE.point)
        assertEquals(SupportPoint.RIGHT_KNEE, SupportContact.RIGHT_KNEE.point)
        assertEquals(SupportPoint.LEFT_HAND, SupportContact.LEFT_HAND.point)
        assertEquals(SupportPoint.RIGHT_HAND, SupportContact.RIGHT_HAND.point)
        assertEquals(SupportPoint.LEFT_FOREARM, SupportContact.LEFT_FOREARM.point)
        assertEquals(SupportPoint.RIGHT_FOREARM, SupportContact.RIGHT_FOREARM.point)
        assertEquals(SupportPoint.HIPS, SupportContact.HIPS.point)
        assertEquals(SupportPoint.CUSTOM, SupportContact.CUSTOM.point)
    }

    @Test
    fun testLeverLengthComputation() {
        // FEET: shin + thigh + torso = 98 + 112 + 120 = 330
        val feetLength = SupportMath.computeLeverLength(PivotType.FEET, def)
        assertEquals(330f, feetLength, 1e-4f)

        // KNEES: thigh + torso = 112 + 120 = 232
        val kneesLength = SupportMath.computeLeverLength(PivotType.KNEES, def)
        assertEquals(232f, kneesLength, 1e-4f)

        // HANDS: torso = 120
        val handsLength = SupportMath.computeLeverLength(PivotType.HANDS, def)
        assertEquals(120f, handsLength, 1e-4f)

        // CUSTOM: returns the custom length passed to it
        val customLength = SupportMath.computeLeverLength(PivotType.CUSTOM, def, 150f)
        assertEquals(150f, customLength, 1e-4f)
    }

    @Test
    fun testSupportCentroidComputation() {
        val pose = SkeletonPose()

        // Mock joint coordinates
        pose.setJoint(Joint.HAND_P, Vector3(10f, 20f, 30f))
        pose.setJoint(Joint.HAND_A, Vector3(20f, 40f, 60f))

        val contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
        val centroid = SupportMath.computeSupportCentroid(pose, contacts)

        // Average of (10, 20, 30) and (20, 40, 60) should be (15, 30, 45)
        assertEquals(15f, centroid.x, 1e-4f)
        assertEquals(30f, centroid.y, 1e-4f)
        assertEquals(45f, centroid.z, 1e-4f)
    }

    @Test
    fun testLeverModelComputationWithOffsets() {
        val pose = SkeletonPose()

        // Mock knees coordinates
        pose.setJoint(Joint.KNEE_B, Vector3(100f, 50f, 10f))
        pose.setJoint(Joint.KNEE_F, Vector3(120f, 50f, -10f))

        val support = SupportDefinition(
            pivot = PivotType.KNEES,
            contacts = setOf(SupportContact.LEFT_KNEE, SupportContact.RIGHT_KNEE),
            supportHeight = 0f,
            offsetX = 5f,
            offsetY = -2f,
            offsetZ = 0f
        )

        val model = SupportMath.computeLeverModel(pose, def, support)

        // Knee pivot position is average of KNEE_B and KNEE_F: (110, 50, 0)
        // With offsetX = 5 and offsetY = -2, it should be (115, 48, 0)
        assertEquals(115f, model.pivotPosition.x, 1e-4f)
        assertEquals(48f, model.pivotPosition.y, 1e-4f)
        assertEquals(0f, model.pivotPosition.z, 1e-4f)

        // Lever length should be knees length: thigh + torso = 112 + 120 = 232f
        assertEquals(232f, model.leverLength, 1e-4f)
    }

    @Test
    fun testSolveNearStraightLimb() {
        // Test with L1 = 98 (shin), L2 = 112 (thigh), targetFlexionDegrees = 8f
        val result = SkeletonMath.solveNearStraightLimb(98f, 112f, 8f)

        // Expected target linear distance (d) is around 209.4907 (roughly 99.76% of 210)
        assertEquals(209.4907f, result.d, 0.01f)

        // Linear ratio check
        val ratio = result.d / (98f + 112f)
        assertTrue(ratio >= 0.996f && ratio <= 0.999f)

        // Verify x and y satisfy L1^2 = x^2 + y^2
        val lengthSquared = result.x * result.x + result.y * result.y
        assertEquals(98f * 98f, lengthSquared, 1e-2f)

        // Verify the other side's distance: (d - x)^2 + y^2 should equal L2^2
        val remainingX = result.d - result.x
        val otherSideSquared = remainingX * remainingX + result.y * result.y
        assertEquals(112f * 112f, otherSideSquared, 1e-2f)
    }
}
