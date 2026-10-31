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
 * [ValidationStampMerge] merge rules, and the V2 settlement defect.
 *
 * The defect is exercised through the REAL posture-driven settlement path: [SquatPose] declares no Contact
 * Declarations, so settlement is entered via the posture-driven branch by declaring
 * `PostureIntent.Kind.STANDING` through the sole-mutator surface. No solver state is manufactured.
 *
 * Counterfactual: against sources that erase unconditionally,
 * [successfulSettlementKeepsFailedPrimaryVerificationFalseEndState] FAILS — settlement erases the seeded
 * `false` and publishes `true`; with merge-once logic the failed primary verification survives.
 *
 * Anti-vacuity: both settlement tests assert `rootTranslationDelta > 0f` after the solve, because the
 * computation runs only when the solver body executes past its early returns — so fixture drift that skips
 * settlement fails instead of passing vacuously (the original PUSHUP-based fixture never reached
 * `ConstraintSolver.solve` at all).
 *
 * Sole-producer overwrite of the Root Translation / Rotation Deltas is enforced byte-exactly by the golden
 * fixtures in `arch.RuntimeArchitectureBaselineTest` and is intentionally not duplicated here.
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

        // Settlement demonstrably executed (a non-zero displacement for this fixture); without this
        // guard a skipped solve would make the test vacuously green.
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
