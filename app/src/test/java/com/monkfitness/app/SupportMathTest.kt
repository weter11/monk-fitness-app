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
 *
 * Biomechanical Examples documented here:
 * 1. Standard Push-Up:
 *    - pivot = PivotType.FEET
 *    - contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND, SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES)
 * 2. Knee Push-Up:
 *    - pivot = PivotType.KNEES
 *    - contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND, SupportContact.LEFT_KNEE, SupportContact.RIGHT_KNEE)
 * 3. Decline Push-Up:
 *    - pivot = PivotType.FEET
 *    - contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND, SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES)
 *    - supportHeight = benchHeight (e.g. 30f)
 * 4. Plank:
 *    - pivot = PivotType.FEET
 *    - contacts = setOf(SupportContact.LEFT_FOREARM, SupportContact.RIGHT_FOREARM, SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES)
 * 5. Lunge:
 *    - pivot = PivotType.FEET
 *    - contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
 * 6. Bridge:
 *    - pivot = PivotType.FEET (or HIPS)
 *    - contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT, SupportContact.HIPS)
 */
class SupportMathTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

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
