package com.monkfitness.app

import com.monkfitness.app.animation.AngularJointLimits
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.ValidationStampMerge
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightIntentFallbackTest {
    private val constraint = IKConstraint(30f, 1f, AngularJointLimits(15f, 180f, 170f))

    @Test
    fun straightRequestInsideProximalBoneFallsBackAndReportsDrop() {
        val result = SkeletonMath.solveStraightLimb(
            Vector3(), Vector3(0.5f, 0f, 0f), 2f, 1f, constraint
        )

        assertTrue(result.straightIntentDropped)
        assertTrue(SkeletonMath.bonesExact(Vector3(), result.joint, result.end, 2f, 1f))
    }

    @Test
    fun validStraightRequestDoesNotReportDrop() {
        val result = SkeletonMath.solveStraightLimb(
            Vector3(), Vector3(2.5f, 0f, 0f), 2f, 1f, constraint
        )

        assertFalse(result.straightIntentDropped)
        assertTrue(SkeletonMath.bonesExact(Vector3(), result.joint, result.end, 2f, 1f))
    }

    @Test
    fun ordinaryBentRequestDoesNotReportDrop() {
        val result = SkeletonMath.solveIK(
            Vector3(), Vector3(1.5f, 0.5f, 0f), 2f, 1f, Vector3(0f, 0f, 1f), constraint
        )

        assertFalse(result.straightIntentDropped)
    }

    @Test
    fun droppedMergeCannotBeCleared() {
        var dropped = ValidationStampMerge.dropped(false, true)
        dropped = ValidationStampMerge.dropped(dropped, false)

        assertTrue(dropped)
    }
}
