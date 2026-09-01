package com.monkfitness.app

import com.monkfitness.app.animation.AngularJointLimits
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.ValidationStampMerge
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.WorldTarget
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.poses.StandardPushUpPose
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun contactRebakeBoundaryMatchesCanonicalStraightOutcome() {
        val insideEpsilon = 1.998f
        val canonical = SkeletonMath.straightFallbackRequired(insideEpsilon, 2f, 1f, constraint)
        val result = SkeletonMath.solveStraightLimb(
            Vector3(), Vector3(insideEpsilon, 0f, 0f), 2f, 1f, constraint
        )

        assertTrue("contact re-bake must take its bent branch at this boundary", insideEpsilon < 2f - 1e-3f)
        assertTrue(canonical)
        assertTrue(result.straightIntentDropped)
        assertTrue(canonical == result.straightIntentDropped)
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

    @Test
    fun contactStraightFallbackReportsDrop() {
        val result = SkeletonMath.solveStraightLimb(
            Vector3(0f, 1f, 0f), Vector3(0f, 0.5f, 0f), 2f, 1f, constraint,
            contact = com.monkfitness.app.animation.ContactConstraint.ground(0f)
        )

        assertTrue(result.straightIntentDropped)
    }

    @Test
    fun activeIkStageReportsStraightFallbackAndValidStraightRemainsClear() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true
            fun solve(target: Vector3): SkeletonPose {
                val nodes = SkeletonFactory.createStandardSkeleton()
                nodes.shoulderA.localPosition.set(0f, 0f, 0f)
                nodes.elbowA.localPosition.set(1f, 0f, 0f)
                nodes.handA.localPosition.set(1f, 0f, 0f)
                nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
                val pose = SkeletonPose()
                pose.roots = nodes.roots
                pose.limbTargets.add(WorldTarget(Joint.HAND_A, target, straight = true))
                IkStage.apply(pose, SkeletonDefinition.DEFAULT_ADULT)
                return pose
            }

            val activePose = solve(Vector3(0.5f, 0f, 0f))
            assertTrue(activePose.straightIntentDropped)
            val executions = SkeletonPose::class.java.getDeclaredField("limbSolverExecutions")
            executions.isAccessible = true
            assertEquals(1, executions.getInt(activePose))
            assertFalse(solve(Vector3(2.5f, 0f, 0f)).straightIntentDropped)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun inactiveIkStageSkipsPhase1Execution() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = false
            val pose = SkeletonPose()
            pose.roots = SkeletonFactory.createStandardSkeleton().roots
            val executions = SkeletonPose::class.java.getDeclaredField("limbSolverExecutions")
            executions.isAccessible = true

            IkStage.apply(pose, SkeletonDefinition.DEFAULT_ADULT)

            assertEquals(0, executions.getInt(pose))
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun pipelineRejectsMultiplePhase1SolverExecutions() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = false
            val pose = StandardPushUpPose().build(PoseContext(0.5f, Side.RIGHT, SkeletonDefinition.DEFAULT_ADULT))
            val executions = SkeletonPose::class.java.getDeclaredField("limbSolverExecutions")
            executions.isAccessible = true
            executions.setInt(pose, 2)
            val runStages = SkeletonPipeline::class.java.getDeclaredMethod("runStages", SkeletonPose::class.java)
            runStages.isAccessible = true
            try {
                runStages.invoke(SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT), pose)
                throw AssertionError("R5 multiple-solver invariant did not throw")
            } catch (e: InvocationTargetException) {
                assertTrue(e.cause is IllegalStateException)
            }
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun phase1ExecutionInstrumentationDoesNotCrossCopyBoundary() {
        val source = SkeletonPose()
        val published = SkeletonPose()
        val executions = SkeletonPose::class.java.getDeclaredField("limbSolverExecutions")
        executions.isAccessible = true
        executions.setInt(source, 1)

        published.copyFrom(source)

        assertEquals(0, executions.getInt(published))
    }
}
