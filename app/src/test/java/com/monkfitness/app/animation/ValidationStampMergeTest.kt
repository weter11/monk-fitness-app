package com.monkfitness.app.animation

import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.poses.SquatPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md — R4/R6 Validation Stamp merge
 * centralization; RFC_RUNTIME_SKELETON_ARCHITECTURE §4.4 merge rules, §5 R4/R6).
 *
 * Coverage:
 *  - merge-rule truth tables for the three [ValidationStampMerge] operators;
 *  - the registered V2 defect, exercised through the REAL posture-driven settlement path
 *    (decision F7): [SquatPose] declares no Contact Declarations, so settlement is entered
 *    via the posture-driven branch by declaring `PostureIntent.Kind.STANDING` through the
 *    sole-mutator surface — the same pattern as the Phase-0 harness
 *    (`RuntimeArchitectureBaselineTest.postureDrivenFrame`). No solver state is manufactured.
 *
 * Counterfactual evidence (2026-08-25, independent review D1 correction): executed against
 * a detached worktree at `origin/main` (pre-Phase-2 sources carrying the unconditional
 * erase), [successfulSettlementKeepsFailedPrimaryVerificationFalseEndState] FAILS —
 * settlement erases the seeded `false` and publishes `true`. On the Phase-2 tree the same
 * test passes: the merge-once logic preserves the failed primary verification.
 *
 * Anti-vacuity guards: both settlement tests assert `rootTranslationDelta > 0f` after the
 * solve — the UNI-6 computation runs only when the solver body executes past its early
 * returns, so a future fixture drift that silently skips settlement fails the test instead
 * of passing it vacuously (the original PUSHUP-based version had exactly that flaw: its
 * fixture never reached `ConstraintSolver.solve` at all).
 *
 * Sole-producer overwrite behavior of the Root Translation / Rotation Deltas (plan test
 * item d) is enforced byte-exactly by the Phase-0 golden fixtures
 * (`arch.RuntimeArchitectureBaselineTest`) and is intentionally not duplicated here.
 */
class ValidationStampMergeTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float = 0.5f) = PoseContext(
        progress = progress,
        side = Side.RIGHT,
        definition = definition,
        deltaTime = 1f / 60f,
        cycleDuration = 2500f
    )

    /** Builds a real posture-driven frame: SquatPose + STANDING ⇒ solver enters via
     *  `postureDriven` (no Contact Declarations involved). */
    private fun postureDrivenPose(): SkeletonPose {
        val pose = SquatPose().build(context())
        SkeletonPose.IntentBuilder(pose).posture(PostureIntent.Kind.STANDING)
        return pose
    }

    // ------------------------------------------------------------------
    // Merge-rule truth tables (RFC §4.4 merge-rule column)
    // ------------------------------------------------------------------

    @Test
    fun clampMergeIsMaxMonotonic() {
        assertEquals(5f, ValidationStampMerge.clamp(0f, 5f), 0f)
        assertEquals(7f, ValidationStampMerge.clamp(7f, 3f), 0f)
        assertEquals(2f, ValidationStampMerge.clamp(2f, 2f), 0f)
        assertEquals(0f, ValidationStampMerge.clamp(0f, 0f), 0f)
    }

    @Test
    fun verifiedMergeIsFurtherRestrictingAnd() {
        assertTrue(ValidationStampMerge.verified(true, true))
        assertFalse(ValidationStampMerge.verified(true, false))
        assertFalse(ValidationStampMerge.verified(false, true))
        assertFalse(ValidationStampMerge.verified(false, false))
    }

    @Test
    fun droppedMergeIsMonotonicOr() {
        assertFalse(ValidationStampMerge.dropped(false, false))
        assertTrue(ValidationStampMerge.dropped(false, true))
        assertTrue(ValidationStampMerge.dropped(true, false))
        assertTrue(ValidationStampMerge.dropped(true, true))
    }

    // ------------------------------------------------------------------
    // V2 defect regression (decision F7 — real posture-driven settlement path)
    // ------------------------------------------------------------------

    /**
     * End state proven: a successful posture-driven settlement cannot restore a failed
     * primary verification (registered defect V2). The seeded `false` simulates an
     * authoring-time limb whose bake violated the Bone-Length Invariant; settlement itself
     * completes cleanly (no contact limbs exist to fail re-verification).
     */
    @Test
    fun successfulSettlementKeepsFailedPrimaryVerificationFalseEndState() {
        val pose = postureDrivenPose()
        pose.boneLengthsVerified = false // seed the primary producer's failure reading

        ConstraintSolver.solve(pose, definition)

        // Settlement demonstrably executed (UNI-6 computed a non-zero displacement for this
        // fixture); without this guard a skipped solve would make the test vacuously green.
        assertTrue(
            "fixture guard: solver did not execute (rootTranslationDelta=${pose.rootTranslationDelta})",
            pose.rootTranslationDelta > 0f
        )
        assertFalse(
            "V2 regression: settlement restored an erased primary verification",
            pose.boneLengthsVerified
        )
    }

    /** Control: an unseeded healthy posture-driven pose settles with the stamp still `true`. */
    @Test
    fun healthyContactSettlementKeepsVerificationTrueEndState() {
        val pose = postureDrivenPose()

        ConstraintSolver.solve(pose, definition)

        assertTrue(
            "fixture guard: solver did not execute (rootTranslationDelta=${pose.rootTranslationDelta})",
            pose.rootTranslationDelta > 0f
        )
        assertTrue(pose.boneLengthsVerified)
    }
}
