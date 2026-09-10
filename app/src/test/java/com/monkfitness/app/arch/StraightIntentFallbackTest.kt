package com.monkfitness.app.arch

import com.monkfitness.app.animation.AngularJointLimits
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.ContactSpec
import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget
import com.monkfitness.app.animation.bakeIkLimb
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 (R5) — Straight-Intent-Dropped producer coverage
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P4; RFC §4.2 Straight-Limb Fallback, §4.4 OR row).
 *
 * Ownership model proven here: the fallback outcome is born at the branch that executes it —
 * the `dist < L1` bent fallback and the contact-slide branch inside `solveStraightLimb` — and is
 * carried as a scratch reading (RFC §4.3: the Limb Solve Result holds the clamp/straight/
 * bone-length readings). Producers fold it into the carrier stamp via
 * [com.monkfitness.app.animation.ValidationStampMerge.dropped]: the Active Limb Solver
 * implementations write first; the ConstraintSolver strengthens exactly once, from its own
 * branch evidence. NO code re-infers the outcome from a reconstructed predicate.
 *
 * Counterfactual red-gate (P2 discipline): the pose-level integration tests were executed
 * against `origin/main` (d1d8962) before green was claimed — there the flag has no writer at
 * all (registered defect V1) and they fail with `expected true / found false`.
 */
class StraightIntentFallbackTest {

    private val constraint = IKConstraint(30f, 1f, AngularJointLimits(15f, 180f, 170f))

    // ------------------------------------------------------------ scratch-level units (SkeletonMath)

    @Test
    fun straightRequestInsideProximalBoneRecordsFallbackInScratch() {
        // L1=2, L2=1: target at 0.5 clamps to minReach ≈ 1.24 — still < L1 → UNI-9 bent fallback.
        val result = SkeletonMath.solveStraightLimb(
            Vector3(0f, 0f, 0f), Vector3(0.5f, 0f, 0f), 2f, 1f, constraint
        )
        assertTrue("the executed bent-fallback branch must set the scratch reading", result.straightIntentDropped)
        assertTrue(
            "P4 changes no geometry: the fallback still preserves both bone lengths",
            SkeletonMath.bonesExact(Vector3(0f, 0f, 0f), result.joint, result.end, 2f, 1f)
        )
    }

    @Test
    fun honoredStraightRequestRecordsNoFallback() {
        val result = SkeletonMath.solveStraightLimb(
            Vector3(0f, 0f, 0f), Vector3(2.5f, 0f, 0f), 2f, 1f, constraint
        )
        assertFalse(result.straightIntentDropped)
    }

    @Test
    fun scratchReadingResetsPerSolveOnReusedBuffer() {
        val buffer = SkeletonMath.IKResult()
        SkeletonMath.solveStraightLimb(Vector3(0f, 0f, 0f), Vector3(0.5f, 0f, 0f), 2f, 1f, constraint, buffer)
        assertTrue(buffer.straightIntentDropped)
        // Reuse on a reachable straight request: the reading must describe THIS solve only.
        SkeletonMath.solveStraightLimb(Vector3(0f, 0f, 0f), Vector3(2.5f, 0f, 0f), 2f, 1f, constraint, buffer)
        assertFalse("a reused scratch buffer must not leak the previous solve's outcome", buffer.straightIntentDropped)
        // Reuse on a bent solve: same reset guarantee across both entry points.
        SkeletonMath.solveStraightLimb(Vector3(0f, 0f, 0f), Vector3(0.5f, 0f, 0f), 2f, 1f, constraint, buffer)
        SkeletonMath.solveIK(Vector3(0f, 0f, 0f), Vector3(1.5f, 0.5f, 0f), 2f, 1f, Vector3(0f, 0f, 1f), constraint, buffer)
        assertFalse("solveIK must not leave a stale straight reading behind", buffer.straightIntentDropped)
    }

    @Test
    fun contactSlideBelowProximalBoneRecordsFallback() {
        // Root above the ground plane aiming straight down inside L1: the slide path runs, and
        // the collinear middle collapses (dist < L1) — honest drop, zero placement changes.
        val result = SkeletonMath.solveStraightLimb(
            Vector3(0f, 1f, 0f), Vector3(0f, 0.5f, 0f), 2f, 1f, constraint,
            contact = ContactConstraint.ground(0f)
        )
        assertTrue(result.straightIntentDropped)
    }

    // ------------------------------------------------------------- authoring-family fold (bake)

    @Test
    fun packageLevelBakeFoldsScratchIntoCarrierStamp() {
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        val root = Vector3(0f, 0f, 0f)

        fun bake(straight: Boolean, targetX: Float): SkeletonPose {
            val pose = SkeletonPose()
            bakeIkLimb(
                root, targetX.let { Vector3(it, 0f, 0f) }, 2f, 1f,
                Vector3(0f, 0f, 1f), constraint, JointRotation(),
                nodes.kneeF, nodes.ankleF, SkeletonMath.IKResult(), pose,
                straight = straight
            )
            return pose
        }

        assertTrue(
            "the bake must fold the executed fallback branch's reading into the carrier",
            bake(straight = true, targetX = 0.5f).straightIntentDropped
        )
        assertFalse("a honored straight request must not drop", bake(straight = true, targetX = 2.5f).straightIntentDropped)
        assertFalse(
            "a bent request that never asked for straight cannot drop the straight intent",
            bake(straight = false, targetX = 0.5f).straightIntentDropped
        )
    }

    // ------------------------------------------------- validation-family producer (the V1 fix)

    @Test
    fun middleSplitBentLegProbeDropsStraightIntentAtBuild() {
        // MiddleSplitPose is the canonical straight-intent probe: legs author `straight = true`
        // at hip→foot ≈ 57 units vs thighLength 112 — the engine's only honest outcome is the
        // bent fallback, which P4 now surfaces on the carrier. This is the first real producer
        // coverage for the ONLY production authors of straight intent.
        //
        // P12 (WP-F / §12.8 retarget): the reading asserted HERE is the AUTHORING-CONFIGURATION
        // producer — the bake folding its executed fallback — so it pins flag-OFF explicitly
        // (R5 keeps both configurations supported; this test's claim is about the bake). Under
        // the activated configuration the drop is produced by the engine stage window and is
        // observed on the PUBLISHED state: that path is covered by
        // [middleSplitPublishedStateCarriesTheDropThroughThePipeline] plus
        // ValidationOwnershipReCertificationTest.straightProbeReadingComesFromActiveImplementation
        // (which pins build-window-empty / stage-window-produces exactly).
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = false
            val pose = MiddleSplitPose().build(PoseContext(0.5f, Side.LEFT, SkeletonDefinition.DEFAULT_ADULT))
            assertTrue(
                "P4 V1 regression: the probe's executed bent fallback must report straightIntentDropped",
                pose.straightIntentDropped
            )
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun middleSplitPublishedStateCarriesTheDropThroughThePipeline() {
        val result = SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT).produceFrame(
            MiddleSplitPose(), PoseContext(0.5f, Side.LEFT, SkeletonDefinition.DEFAULT_ADULT)
        )
        assertTrue(
            "intent → solver → merge → publish must not lose the semantic outcome",
            result.pose.straightIntentDropped
        )
    }

    // ------------------------------------------------------- ConstraintSolver strengthen-only

    @Test
    fun solverBranchEvidenceStrengthensARebentStraightContact() {
        // Synthetic contact whose target sits inside L1: the solver's own `canBeStraight ==
        // false` branch is the only honest producer of this settlement's finding.
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        val pose = SkeletonPose()
        pose.roots = nodes.roots
        val hip = nodes.hipF.worldPosition
        pose.contacts.add(
            ContactSpec(
                endJoint = Joint.ANKLE_F,
                rootJoint = Joint.HIP_F,
                parentRotationJoint = Joint.PELVIS,
                middleJoint = Joint.KNEE_F,
                targetWorld = Vector3(hip.x + 0.5f, hip.y, hip.z),
                pole = Vector3(0f, 0f, 1f),
                length1 = 2f,
                length2 = 1f,
                constraint = constraint,
                straight = true,
                contact = null
            )
        )
        ConstraintSolver.solve(pose, SkeletonDefinition.DEFAULT_ADULT)
        assertTrue(
            "settlement that re-bakes a straight spec bent must OR-strengthen the stamp",
            pose.straightIntentDropped
        )
    }

    @Test
    fun solverCannotEraseAPrimaryDrop() {
        // Benign posture-driven settlement (no straight specs): a primary `true` must survive
        // the merge-once strengthen-only write (R6 / §4.4 OR).
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        val pose = SkeletonPose()
        pose.roots = nodes.roots
        SkeletonPose.IntentBuilder(pose).posture(PostureIntent.Kind.STANDING)
        pose.straightIntentDropped = true
        ConstraintSolver.solve(pose, SkeletonDefinition.DEFAULT_ADULT)
        assertTrue(pose.straightIntentDropped)
    }

    // ----------------------------------------------------------- runtime-window enforcement

    @Test
    fun activeIkStageFoldsFallbackAndCountsOneExecution() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true

            fun solveStage(target: Vector3): SkeletonPose {
                val nodes = SkeletonFactory.createStandardSkeleton()
                nodes.shoulderA.localPosition.set(0f, 0f, 0f)
                nodes.elbowA.localPosition.set(1f, 0f, 0f)
                nodes.handA.localPosition.set(1f, 0f, 0f)
                nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
                val pose = SkeletonPose()
                pose.roots = nodes.roots
                pose.limbTargets.add(
                    WorldTarget(
                        Joint.HAND_A, target, straight = true,
                        length1 = 2f, length2 = 1f, constraint = constraint
                    )
                )
                IkStage.apply(
                    pose,
                    HumanSkeletonDefinition(upperArmLength = 2f, forearmLength = 1f, armIKConstraint = constraint)
                )
                return pose
            }

            val dropped = solveStage(Vector3(0.5f, 0f, 0f))
            assertTrue("IkStage must fold the executed fallback's reading", dropped.straightIntentDropped)
            assertEquals(1, dropped.limbSolverExecutions)
            assertFalse(solveStage(Vector3(2.5f, 0f, 0f)).straightIntentDropped)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun inactiveIkStageCountsZero() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = false
            val pose = SkeletonPose()
            pose.roots = SkeletonFactory.createStandardSkeleton().roots
            pose.limbTargets.add(WorldTarget(Joint.HAND_A, Vector3(0.5f, 0f, 0f), straight = true))
            IkStage.apply(pose, SkeletonDefinition.DEFAULT_ADULT)
            assertEquals("the gated stage must contribute no solver window", 0, pose.limbSolverExecutions)
            assertFalse(pose.straightIntentDropped)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun pipelineRejectsASecondRuntimeSolverWindow() {
        // Fault injection: a second Phase-1 runtime solver window on one frame must make the
        // pipeline throw. PR #216's counter shape could not catch this design; this pins the
        // ENFORCEMENT, not just the counter's existence.
        //
        // P12 WP-H (finding F-2) — the injection goes through the REGISTERED realization path. The
        // previous shape wrote the counter directly (`built.limbSolverExecutions = 2`), which tests
        // the `check` expression rather than the mechanism: a fabricated evidence value can never
        // be produced by production code, so the assertion proved nothing about enforcement. A
        // genuine second window is produced by the two registered engines co-executing on one
        // frame — the test-only direct stage window plus the pipeline's own stage window — the
        // exact co-execution shape `SingleActiveSolverEnforcementTest` anchors.
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true
            val definition = SkeletonDefinition.DEFAULT_ADULT
            val violation = runCatching {
                val built = MiddleSplitPose().build(PoseContext(0.5f, Side.LEFT, definition))
                IkStage.apply(built, definition) // window #1 (legitimate test-only co-execution)
                SkeletonPipeline(definition).produceFrame(built) // window #2 → must throw
            }.exceptionOrNull()
            assertTrue(
                "R5 runtime-window check must fire on a second registered solver window. Observed: " +
                    (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
                violation is IllegalStateException && violation.message.orEmpty().contains("R5 violation")
            )
            assertTrue(
                "the rejection must report the observed window count (2), i.e. the EVIDENCE failed " +
                    "the frame and not a fabricated value: ${violation?.message}",
                violation?.message.orEmpty().contains("windows executed this frame = 2")
            )
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    @Test
    fun pipelineWindowCounterIsNotStickyAcrossFrames() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true
            val pipeline = SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT)
            val ctx = PoseContext(0.5f, Side.LEFT, SkeletonDefinition.DEFAULT_ADULT)
            val built = MiddleSplitPose().build(ctx)
            pipeline.produceFrame(built)
            assertEquals("per-frame instrumentation, never sticky", 0, built.limbSolverExecutions)
            pipeline.produceFrame(built) // second frame must pass the same check
            assertEquals(0, built.limbSolverExecutions)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }
}
