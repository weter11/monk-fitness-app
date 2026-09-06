package com.monkfitness.app.arch

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PhaseBoundaryAsserts
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (R2) — helper-level enforcement tests for [PhaseBoundaryAsserts]
 * (the committed DIRECT unit-test standard established at P1 with
 * [com.monkfitness.app.animation.RuntimeContextSnapshot] / `RuntimeContextSnapshotTest`:
 * capture snapshot → mutate the protected state → assert IllegalStateException with the rule
 * tag in the message → plus a no-mutation control).
 *
 * These pin the boundary mechanism itself — including the Check 1 window (Phase 1 / IkStage),
 * for which production exposes no observable pelvis-mutation route (the limb stage writes only
 * middle/end joint locals, so a mutation there is by construction injected, exactly like the
 * P1 snapshot-mutation pattern). The end-to-end wiring (Check 2 firing through the real
 * Finalizer intent-carrier window) is covered behaviorally by [RootAuthorityTest].
 *
 * Access note: [PhaseBoundaryAsserts] is `internal` to the production module — visible to the
 * same-module unit-test compilation (internal visibility is module-scoped, and unit tests
 * compile as a friend module), mirroring how P1 tests exercise the internal snapshot helper.
 */
class PhaseBoundaryAssertsTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private class FrameFixture {
        val nodes = SkeletonFactory.createStandardSkeleton()
        val pose: SkeletonPose = SkeletonPose().apply { roots = nodes.roots }
    }

    @Test
    fun captureThenTranslateRootFailsWithR2Violation() {
        val fixture = FrameFixture()
        val snapshot = PhaseBoundaryAsserts.captureRoot(fixture.pose, "capture A (post-build)")
        assertNotNull("fixture guard: a standard hierarchy has a pelvis to capture", snapshot)

        fixture.nodes.pelvis.localPosition = Vector3(1f, 51f, 2f) // forbidden translate
        val thrown = runCatching {
            snapshot!!.assertUnchanged(fixture.pose, "Phase 1 (IkStage limb solve window)")
        }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException('R2 violation …'), got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R2 violation")
        )
    }

    @Test
    fun captureThenRotateRootFailsWithR2Violation() {
        // R2 covers rotation as well as translation ("translates or rotates the root").
        val fixture = FrameFixture()
        val snapshot = PhaseBoundaryAsserts.captureRoot(fixture.pose, "capture A (post-build)")
        assertNotNull(snapshot)

        fixture.nodes.pelvis.localRotation.set(Vector3(0f, 1f, 0f), 0.05f) // forbidden rotate
        val thrown = runCatching {
            snapshot!!.assertUnchanged(fixture.pose, "Phase 3/4 (Finalizer + publish window)")
        }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException('R2 violation …'), got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R2 violation")
        )
    }

    @Test
    fun captureThenChildJointMutationPassesControl() {
        // The important distinction: root authority ≠ child joints. FK-propagated child world
        // transforms and child LOCAL writes must never trip the root guard (an assertion that
        // fails on child movement is over-broad and would fire on every legitimate frame).
        val fixture = FrameFixture()
        val snapshot = PhaseBoundaryAsserts.captureRoot(fixture.pose, "capture A (post-build)")
        assertNotNull(snapshot)

        val chest = fixture.nodes.roots[0].children.first()
        chest.localPosition = Vector3(123f, 45f, -7f)
        chest.localRotation.set(Vector3(1f, 0f, 0f), 0.9f)
        runCatching { snapshot!!.assertUnchanged(fixture.pose, "Phase 1 (IkStage limb solve window)") }
            .exceptionOrNull()
            ?.let { throw AssertionError("child-joint mutation must not trip the R2 root guard: $it") }
    }

    @Test
    fun noMutationControlPasses() {
        val fixture = FrameFixture()
        val snapshot = PhaseBoundaryAsserts.captureRoot(fixture.pose, "capture A (post-build)")
        assertNotNull(snapshot)
        // Control (P1 pattern): re-compare with nothing changed — must not throw.
        snapshot!!.assertUnchanged(fixture.pose, "Phase 3/4 (Finalizer + publish window)")
    }

    @Test
    fun captureReturnsNullWhenNoPelvisExists() {
        // Structural early-out matching the solver's own (`solve` early-returns without a
        // pelvis): nothing to protect.
        val bare = SkeletonPose()
        assertNull(PhaseBoundaryAsserts.captureRoot(bare, "capture A (post-build)"))
    }

    @Test
    fun solvedFrameSettledRootBecomesTheProtectedReference() {
        // Reference-switch semantics proven at the helper level with REAL pipeline output:
        // after a posture-driven solve the settled root differs from the authored root (A);
        // a snapshot captured post-solve (B) must protect the settled value — comparing the
        // published pelvis against B passes, while an A-comparison would (correctly) fail.
        // This is the data half of the plan's "no equality claim vs A" rule: it shows A ≠ B
        // on a moved-root frame, which is why the reference switch at the solve site is
        // load-bearing.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        SkeletonPose.IntentBuilder(fixture.pose).posture(PostureIntent.Kind.STANDING)
        fixture.nodes.pelvis.localPosition = Vector3(100f, 50f, 0f)
        val a = PhaseBoundaryAsserts.captureRoot(fixture.pose, "capture A (post-build)")
        assertNotNull(a)
        val published = pipeline.produceFrame(fixture.pose).pose
        val standY = definition.shinLength + definition.thighLength + 25f
        assertEquals(standY, published.getJoint(Joint.PELVIS).y, 1e-4f)
        // Capture B (post-solve) equals the published root: protecting B passes…
        val b = PhaseBoundaryAsserts.captureRoot(published, "capture B (post-settlement)")
        assertNotNull(b)
        b!!.assertUnchanged(published, "Phase 3/4 (Finalizer + publish window)")
        // …while a stale A-comparison on the same frame would throw — the reference switch at
        // the solve site is load-bearing (this is why Check 2 must compare against B, not A).
        val thrown = runCatching {
            a!!.assertUnchanged(published, "Phase 3/4 (Finalizer + publish window)")
        }.exceptionOrNull()
        assertTrue(
            "expected the stale A-reference to fail on a root-moved frame, got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R2 violation")
        )
    }
}
