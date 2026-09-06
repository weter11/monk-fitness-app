package com.monkfitness.app.arch

import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6 (R2) — root-authority boundary assertions
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P6 test plan; RFC §5 R2, §3 rotation-space rule,
 * §6 phase boundaries).
 *
 * R2: from the end of build until publish, ConstraintSolver is the sole subsystem that
 * translates or ROTATES the root (pelvis). P6 turns that convention into a debug-time failure
 * at the phase boundaries of [SkeletonPipeline.runStages]:
 *
 *  - capture A — window entry (post-build / post-injection; last lawful authoring write);
 *  - Check 1 — after Phase 1 (`IkStage.apply`): pelvis bit-identical to A;
 *  - capture B — after Phase 2 (`ConstraintSolver.solve`): the authorized mover's settled root
 *    becomes the protected reference; NO equality claim between A and B (the solver is allowed
 *    to move the root);
 *  - Check 2 — after Phase 3/4 (Finalizer + publish): pelvis bit-identical to the protected
 *    reference;
 *  - solve-skipped branch (contact-less CUSTOM): no authorized mover exists after build, so
 *    the protected reference stays A — Check 2 then proves A survives to publish untouched.
 *
 * P5 carry-forward: which reference a frame protects is decided by the EXPLICIT solve-branch
 * instrumentation inside `runStages`, never inferred from the retained Frame History
 * (`previous`/`prePrevious`/`previousSmoothingRoot`) — see
 * [solverSkippedFrameAfterSolvedFrameProtectsOwnAuthoredRoot], the P5 pitfall-1 false-fire trap.
 *
 * This file compiles on the PRE-P6 tree (the future utility is looked up reflectively), so the
 * same bytes run as the counterfactual RED gate on `origin/main` without P6 instrumentation.
 */
class RootAuthorityTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    /** One authored frame on a fresh node tree: pelvis re-authored at (x, y, 0) each call. */
    private class FrameFixture {
        val nodes = SkeletonFactory.createStandardSkeleton()
        val pose: SkeletonPose = SkeletonPose().apply {
            roots = nodes.roots
        }
        fun authorRoot(x: Float, y: Float = 50f): SkeletonPose {
            nodes.pelvis.localPosition = Vector3(x, y, 0f)
            return pose
        }
    }

    @Test
    fun pipelineDeclaresR2BoundaryInstrumentation() {
        // Wiring audit — RED on pre-P6 main: the shared utility does not exist and the
        // pipeline has no boundary call sites, so a forbidden in-window pelvis mutation
        // currently runs through runStages completely undetected. (Supplementary audit —
        // the behavioral tests in this class are the primary regression.)
        val util = try {
            Class.forName("com.monkfitness.app.animation.PhaseBoundaryAsserts")
        } catch (e: ClassNotFoundException) {
            null
        }
        assertNotNull(
            "the shared PhaseBoundaryAsserts utility must exist in the production animation " +
                "package (plan §P6 — the mechanism later phases reuse)",
            util
        )
        // Centralization: the enforcement message lives in the utility exactly once, and the
        // pipeline calls the capture/assert helpers at the boundaries (no ad-hoc duplicate
        // root-authority mechanism in production).
        val sources = productionAnimationSources()
        val r2Sites = sources.filter { (_, lines) ->
            lines.any { it.contains("R2 violation") }
        }.keys
        assertEquals(
            "\"R2 violation\" enforcement text must be centralized in PhaseBoundaryAsserts.kt " +
                "only, but appeared in: $r2Sites",
            setOf("PhaseBoundaryAsserts.kt"),
            r2Sites
        )
        val pipeline = sources["SkeletonPipeline.kt"] ?: emptyList()
        val captures = pipeline.count { it.contains("PhaseBoundaryAsserts.captureRoot") }
        val windowLabels = listOf("Phase 1 (IkStage", "Phase 3/4 (Finalizer")
        val missing = windowLabels.filter { label -> pipeline.none { it.contains(label) } }
        assertTrue(
            "SkeletonPipeline.runStages must drive all four R2 boundary points through " +
                "PhaseBoundaryAsserts (capture A + capture B = 2 captureRoot sites; Check 1 " +
                "+ Check 2 named windows). Captures found: $captures; missing window labels: " +
                "$missing",
            captures >= 2 && missing.isEmpty()
        )
    }

    @Test
    fun forbiddenRootRotationInFinalizerWindowThrowsAtCheck2() {
        // THE primary behavioral regression (plan test (a)/(d) shape): a real root mutation
        // executed INSIDE the protected window by production code — the Finalizer's intent
        // carrier consumer ([SkeletonPoseFinalizer] applyIntentCarriers) writes the PELVIS node's
        // localRotation when a pose declares a PELVIS relative articulation on a contact-less
        // pose (contact poses skip the consumer; no production pose declares a pelvis
        // articulation, so the window is empty in normal operation — the fault is the
        // declaration itself, which is exactly the shape of a future regression). Before P6 the
        // mutation reaches the published frame SILENTLY (RED); after P6 the boundary check must
        // fail fast with an R2 violation.
        //
        // Contact-less CUSTOM fixture ⇒ solver legitimately skipped ⇒ the protected reference is
        // capture A (plan: "Checks 1 and 2 then compare against A directly"), so this test also
        // pins the solve-skipped branch handling end-to-end through publish.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        val pose = fixture.authorRoot(100f, 50f)
        SkeletonPose.IntentBuilder(pose).joint(Joint.PELVIS, JointRotation(Vector3(0f, 1f, 0f), 0.35f))

        val thrown = runCatching { pipeline.produceFrame(pose) }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException('R2 violation …') for a post-build root rotation " +
                "inside the protected window, got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R2 violation")
        )
    }

    @Test
    fun forbiddenRootRotationAfterSolvedFrameThrowsAtCheck2AgainstSettledRoot() {
        // Same injection on a frame that DID solve (posture-driven STANDING, no contacts): the
        // solver moved the root (seed pins Y) and capture B became the protected reference. The
        // Finalizer-window rotation must still trip Check 2 — now against B. If the guard
        // wrongly compared against A (authored Y=50) instead of B, this test would still throw
        // for the rotation, but [cleanSolvedFrameRootMotionPassesBothBoundaries] is the paired
        // over-breadth guard that fails if the reference does NOT switch to B on solved frames.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        val pose = fixture.authorRoot(100f, 50f)
        SkeletonPose.IntentBuilder(pose)
            .posture(PostureIntent.Kind.STANDING)
            .joint(Joint.PELVIS, JointRotation(Vector3(1f, 0f, 0f), 0.2f))

        val thrown = runCatching { pipeline.produceFrame(pose) }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException('R2 violation …') for a root rotation in the " +
                "post-settlement window of a solved frame, got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R2 violation")
        )
    }

    @Test
    fun cleanSolvedFrameRootMotionPassesBothBoundaries() {
        // Plan test (b) + over-breadth guard for the A→B reference switch: the ConstraintSolver
        // legitimately translates the root (posture seed pins Y off the authored value); the
        // boundary checks must not treat the authorized mover's own write as a violation. If
        // Check 2 compared the published pelvis against capture A (authored 50) instead of B
        // (settled standY), this frame would throw.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        SkeletonPose.IntentBuilder(fixture.pose).posture(PostureIntent.Kind.STANDING)
        fixture.authorRoot(100f, 50f)
        val result = pipeline.produceFrame(fixture.pose)
        val standY = definition.shinLength + definition.thighLength + 25f
        // Anti-vacuity: the solver ran AND moved the root (authored 50 → posture seed pins standY).
        assertEquals(standY, result.pose.getJoint(Joint.PELVIS).y, 1e-4f)
        assertNotEquals("fixture guard: the posture seed must actually move the root", 50f, standY, 1f)
        assertTrue("fixture guard: settlement recorded root displacement", result.pose.rootTranslationDelta > 0f)
    }

    @Test
    fun solverSkippedFrameAfterSolvedFrameProtectsOwnAuthoredRoot() {
        // P5 carry-forward (armed-state, not retained history) + plan branch handling: frame 1
        // SOLVES (its settled root differs from authored), frame 2 is a contact-less CUSTOM
        // skip authored at a DIFFERENT root. Correct reference for frame 2 is its OWN capture A
        // (no authorized mover exists after build). An implementation that inferred solver
        // execution state from the retained Frame History would protect frame 2 against frame
        // 1's settled root and false-fire — exactly P5 pitfall 1. The resetHistory() variant
        // pins that history presence/absence never changes the outcome.
        val pipeline = SkeletonPipeline(definition)
        val solvedFixture = FrameFixture()
        SkeletonPose.IntentBuilder(solvedFixture.pose).posture(PostureIntent.Kind.STANDING)
        val solvedRootY = pipeline.produceFrame(solvedFixture.authorRoot(100f)).pose
            .getJoint(Joint.PELVIS).y

        val skipFixture = FrameFixture()
        skipFixture.authorRoot(10f, 33f) // deliberately different from the solved frame's root
        val skipped = pipeline.produceFrame(skipFixture.pose).pose
        assertEquals(10f, skipped.getJoint(Joint.PELVIS).x, 0f)
        assertEquals(33f, skipped.getJoint(Joint.PELVIS).y, 0f)
        assertNotEquals("fixture guard: frames must have different roots", solvedRootY, 33f, 1f)

        // Same again after resetHistory() (previous/prePrevious == null): the skip frame must
        // still pass with its authored root intact.
        pipeline.resetHistory()
        val skipFixture2 = FrameFixture()
        skipFixture2.authorRoot(11f, 34f)
        val skipped2 = pipeline.produceFrame(skipFixture2.pose).pose
        assertEquals(11f, skipped2.getJoint(Joint.PELVIS).x, 0f)
        assertEquals(34f, skipped2.getJoint(Joint.PELVIS).y, 0f)
    }

    @Test
    fun ikStageActiveFrameMovesLimbsWithoutTrippingCheck1() {
        // Plan test (c) positive half: with the engine-side limb stage ACTIVE, Phase 1 runs and
        // writes limb-node locals through the real production path. Check 1 must pass — limb
        // writes and FK-propagated child transforms are NOT root mutation (the
        // important-distinction clause). The negative half of plan (c) — a pelvis mutation
        // INSIDE IkStage — has no observable production route today (no data fed to the stage
        // can write the pelvis node; the stage's chain map never names PELVIS as a write
        // target), so its non-vacuity is pinned at the helper level in
        // [PhaseBoundaryAssertsTest] plus the source audit of the Check-1 call site.
        val reference = SkeletonPipeline(definition)
        val refFixture = FrameFixture()
        refFixture.pose.limbTargets.add(WorldTarget(Joint.ANKLE_F, Vector3(30f, -60f, 0f)))
        val refPublished = reference.produceFrame(refFixture.authorRoot(40f)).pose

        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        fixture.pose.limbTargets.add(WorldTarget(Joint.ANKLE_F, Vector3(30f, -60f, 0f)))
        fixture.authorRoot(40f)
        try {
            IK_STAGE_ACTIVE = true
            val out = pipeline.produceFrame(fixture.pose).pose
            // Both boundaries survived a live Phase 1 window; pelvis untouched, limb solved.
            assertEquals(40f, out.getJoint(Joint.PELVIS).x, 1e-4f)
            assertNotEquals(
                "anti-vacuity: IkStage must have actually re-solved the leg",
                refPublished.getJoint(Joint.ANKLE_F).x, out.getJoint(Joint.ANKLE_F).x, 1e-3f
            )
        } finally {
            IK_STAGE_ACTIVE = false
        }
    }

    /** Production animation sources keyed by file name (walk-up per the Gradle user.dir pitfall). */
    private fun productionAnimationSources(): Map<String, List<String>> {
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/animation").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error(
            "Could not locate app module root from ${System.getProperty("user.dir")}"
        )
        val srcDir = File(root, "src/main/java/com/monkfitness/app/animation")
        return srcDir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!
            .associate { it.name to it.readLines() }
    }
}
