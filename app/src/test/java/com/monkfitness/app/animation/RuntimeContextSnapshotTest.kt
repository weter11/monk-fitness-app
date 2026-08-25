package com.monkfitness.app.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative-path regression coverage for [RuntimeContextSnapshot] — the R8 enforcement
 * helper (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md, Phase 1 — Runtime Context Injection
 * single-point).
 *
 * Closes the gap disclosed during the Phase-1 audit: the enforcement failure path was
 * previously validated only by out-of-band fault injection (ad-hoc audit evidence, not
 * committed regression coverage). Per review, this suite exercises the snapshot helper
 * DIRECTLY — no pipeline-level fault injection, no production-code changes:
 * a snapshot captured from a pose must reject that pose once its context mutates.
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
