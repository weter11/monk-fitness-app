package com.monkfitness.app.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative-path coverage for [RuntimeContextSnapshot], the enforcement helper: a snapshot captured from a
 * pose must reject that pose once its context mutates.
 *
 * The helper is exercised DIRECTLY — no pipeline-level fault injection, no production-code changes — which
 * is what makes the failure path committed regression coverage rather than audit evidence.
 */
class RuntimeContextSnapshotTest {

    @Test
    fun snapshotRejectsEnvironmentMutatedAfterCapture() {
        val pose = SkeletonPose()
        val snapshot = RuntimeContextSnapshot.of(pose)

        // Post-snapshot mutation of the injected context — exactly what R8 forbids.
        pose.environment = EnvironmentDefinition(
            ground = GroundDefinition(level = 12.5f)
        )

        val error = assertThrows(IllegalStateException::class.java) {
            snapshot.assertUnchanged(pose, "test: after environment mutation")
        }
        assertTrue(
            "message must carry the R8 violation marker, got: ${error.message}",
            error.message!!.contains("R8 violation")
        )
        assertTrue(
            "message must identify the stage, got: ${error.message}",
            error.message!!.contains("test: after environment mutation")
        )
        assertEquals(
            "only the mutated channel must be flagged",
            emptySet<SupportPoint>(),
            pose.supportedPoints
        )
    }

    @Test
    fun snapshotRejectsSupportedPointsMutatedAfterCapture() {
        val pose = SkeletonPose()
        val snapshot = RuntimeContextSnapshot.of(pose)

        // Post-snapshot mutation of the support declaration — exactly what R8 forbids.
        pose.supportedPoints.add(SupportPoint.LEFT_HAND)

        val error = assertThrows(IllegalStateException::class.java) {
            snapshot.assertUnchanged(pose, "test: after supportedPoints mutation")
        }
        assertTrue(
            "message must carry the R8 violation marker, got: ${error.message}",
            error.message!!.contains("R8 violation")
        )
    }

    @Test
    fun unchangedPosePassesAssertion() {
        val env = EnvironmentDefinition(ground = GroundDefinition(level = 3f))
        val pose = SkeletonPose().apply {
            environment = env
            supportedPoints.add(SupportPoint.LEFT_HAND)
            supportedPoints.add(SupportPoint.RIGHT_TOES)
        }

        // Control path: no post-capture mutation, so assertUnchanged must not throw.
        RuntimeContextSnapshot.of(pose).assertUnchanged(pose, "control: no mutation")
    }
}
