package com.monkfitness.app.arch

import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (R10) — Inter-Frame Smoothing consumed as Pipeline-owned Frame History
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P5 test plan; RFC §5 R10, §4.5, §4.2).
 *
 * Fixture: a minimal posture-driven authored frame (STANDING intent — the solver enters and
 * pins the seeded Y exactly; with zero contacts the relaxation loop never moves X, so the
 * published root isolates the smoothing math). The authoring X jitters frame to frame; each
 * frame's root is eased 25% of the way from the newly authored position toward the root the
 * PREVIOUS frame produced (ConstraintSolver SMOOTH_GAIN = 0.25 → `out = a + (prev − a)·0.25`),
 * with the previous root supplied from the pipeline's frame chain.
 *
 * Proven contracts (plan test (a) + (b), Risk 3 adjudication):
 *  1. Sequential playback through one pipeline keeps smoothing (frame N eases toward N−1).
 *  2. Identity-independence: the smoothing result depends on the supplied frame history and
 *     inputs, NOT on which [SkeletonPose] instance authored the frame — impossible under the
 *     deleted WeakHashMap cache (a second instance's first frame would solve strict).
 *  3. Interleaved multi-instance replay shares the ONE rolling history. This is the intended
 *     R10 replacement for per-instance memories (RFC §5 R10 forbids them; plan Risk 3 names
 *     this the deliberate semantic change, not a regression).
 *  4. [SkeletonPipeline.resetHistory] semantics unchanged from pre-P5: it clears the dynamics
 *     pose chain, NOT the smoothing root (the deleted solver cache likewise persisted across
 *     resetHistory — there was no API to clear it; the plan pins "resetHistory() unchanged").
 *  5. The solver alone, called directly, is a pure function of (inputs, supplied history):
 *     repeated solves on fresh carriers with the same explicit history agree, and the second
 *     solve on a DIFFERENT carrier is not eased toward the first carrier's result.
 */
class InterFrameSmoothingTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT
    private val gain = 0.25f // ConstraintSolver.SMOOTH_GAIN (private const; pinned via behavior)

    /** One authored STANDING-intent frame: pelvis re-authored at (x, 50) each call. */
    private class FrameFixture {
        val nodes = SkeletonFactory.createStandardSkeleton()
        val pose: SkeletonPose = SkeletonPose().apply {
            roots = nodes.roots
            SkeletonPose.IntentBuilder(this).posture(PostureIntent.Kind.STANDING)
        }
        fun authorRoot(x: Float): SkeletonPose {
            nodes.pelvis.localPosition = Vector3(x, 50f, 0f)
            return pose
        }
    }

    private fun publishedX(pose: SkeletonPose): Float = pose.getJoint(Joint.PELVIS).x

    /** Easing arithmetic under SMOOTH_GAIN: 25% from the new authored root toward `prev`. */
    private fun eased(authored: Float, prev: Float): Float = authored + (prev - authored) * gain

    @Test
    fun debugTripwireFiresWhenTheCommitCaptureIsDropped() {
        // Fault injection (P1 RuntimeContextSnapshotTest pattern): the plan's enforcement is a
        // debug `check` distinguishing "first frame" from "forgot to wire". The check cannot be
        // reached through the public API while the capture exists, so the fault is injected by
        // simulating a dropped commit-time capture — null the smoothing root AFTER a solving
        // frame armed it, then produce the next solving frame: the pipeline must throw with an
        // R10 violation instead of silently continuing unsmoothed.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        publishedX(pipeline.produceFrame(fixture.authorRoot(100f)).pose)
        publishedX(pipeline.produceFrame(fixture.authorRoot(200f)).pose) // arms capture + history

        val field = SkeletonPipeline::class.java.getDeclaredField("previousSmoothingRoot")
        field.isAccessible = true
        field.set(pipeline, null) // simulate: capture forgotten, armed flag left standing

        val thrown = runCatching {
            publishedX(pipeline.produceFrame(fixture.authorRoot(300f)).pose)
        }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException('R10 violation…'), got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R10 violation")
        )
    }

    @Test
    fun sequentialPlaybackEasesEachFrameTowardThePreviousRoot() {
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        val published = mutableListOf<Float>()
        var last: SkeletonPose? = null
        for (a in listOf(100f, 200f, 300f)) {
            val result = pipeline.produceFrame(fixture.authorRoot(a)).pose
            last = result
            published += publishedX(result)
        }

        // Frame 1: first frame of the history window — strict solve, root authored exactly.
        assertEquals("first frame must not ease (no history)", 100f, published[0], 1e-4f)
        // Frame 2: eased 25% from authored 200 toward frame 1's produced root 100 → 175.
        assertEquals("frame 2 eased toward frame 1", eased(200f, 100f), published[1], 1e-4f)
        assertEquals(175f, published[1], 1e-4f)
        // Frame 3: eased 25% from 300 toward the ACTUAL frame-2 result (rolling chain).
        assertEquals("frame 3 eased toward frame 2", eased(300f, published[1]), published[2], 1e-4f)
        assertEquals(268.75f, published[2], 1e-4f)
        // Anti-vacuity: the easing demonstrably ran (frame 2 is neither authored nor frame 1)
        // and the solver demonstrably executed (STANDING pinned Y; UNI-6 recorded motion).
        assertNotEquals("easing had no effect", 200f, published[1], 1e-3f)
        val finalFrame = last!!
        val standY = definition.shinLength + definition.thighLength + 25f
        assertEquals("posture seed pins Y exactly on the last frame", standY, finalFrame.getJoint(Joint.PELVIS).y, 1e-4f)
        assertTrue("fixture guard: the solve ran (root displaced from authored position)", finalFrame.rootTranslationDelta > 0f)
    }

    @Test
    fun smoothingIsIdentityIndependentAcrossPoseInstances() {
        // Plan test (b): distinct SkeletonPose instances, identical inputs + identical supplied
        // history ⇒ identical outputs. Sequence 1 authors BOTH frames through the SAME pose
        // instance; sequence 2 authors them through TWO instances. Under the deleted
        // identity-keyed cache the sequences diverged (a fresh instance has no cache entry →
        // its first solve is strict: frame 2 would be 200, not 175). With Frame History they
        // agree exactly — smoothing is a property of the pipeline's chain, never of identity.
        val shared = SkeletonPipeline(definition)
        val a = FrameFixture()
        val sameInstance = listOf(
            publishedX(shared.produceFrame(a.authorRoot(100f)).pose),
            publishedX(shared.produceFrame(a.authorRoot(200f)).pose)
        )
        val fresh = SkeletonPipeline(definition)
        val b1 = FrameFixture()
        val b2 = FrameFixture()
        val crossedInstances = listOf(
            publishedX(fresh.produceFrame(b1.authorRoot(100f)).pose),
            publishedX(fresh.produceFrame(b2.authorRoot(200f)).pose)
        )
        assertEquals("same-input sequences must agree on frame 1", sameInstance[0], crossedInstances[0], 1e-4f)
        assertEquals(
            "frame 2 easing must not depend on SkeletonPose identity (R10)",
            sameInstance[1], crossedInstances[1], 1e-4f
        )
        assertEquals("both agree on the history-derived value 175", eased(200f, 100f), crossedInstances[1], 1e-4f)
    }

    @Test
    fun interleavedReplaySharesOneRollingHistory() {
        // Risk 3 — the intended semantic replacement. A→B→A interleaved: pose A's third frame
        // eases toward the PREVIOUS frame produced (B's), because the pipeline owns ONE rolling
        // history. Under the deleted identity cache A would have eased toward its own last root
        // (100 → stays 100) and B's first frame would have solved strict (1000). Both of those
        // per-instance behaviors are architecturally forbidden and are asserted absent here.
        val pipeline = SkeletonPipeline(definition)
        val poseA = FrameFixture()
        val poseB = FrameFixture()

        val a1 = publishedX(pipeline.produceFrame(poseA.authorRoot(100f)).pose)  // first frame → strict
        val b = publishedX(pipeline.produceFrame(poseB.authorRoot(1000f)).pose)  // eases toward A1
        val a2 = publishedX(pipeline.produceFrame(poseA.authorRoot(100f)).pose)  // eases toward B

        assertEquals("A1 strict (first frame)", 100f, a1, 1e-4f)
        // B does NOT solve strict despite being a brand-new instance — identity no longer buys
        // an independent history: 1000 + (100−1000)·0.25 = 775 (old behavior: 1000).
        assertEquals("B eased toward the shared previous root, not solved strict", eased(1000f, a1), b, 1e-4f)
        assertEquals(775f, b, 1e-4f)
        // A2 eases toward B (old identity behavior: toward A's own 100 → exactly 100).
        assertEquals("A's third frame eases toward the shared rolling history", eased(100f, b), a2, 1e-4f)
        assertEquals(268.75f, a2, 1e-4f)
        assertNotEquals("per-instance identity-cache smoothing must be gone", 100f, a2, 1e-3f)
    }

    @Test
    fun freshPipelineInstanceStartsWithAnEmptyHistoryWindow() {
        // Frame History is per-pipeline (R14: no shared/global pipeline ⇒ no shared/global
        // temporal state). If the smoothing root lived in a static/global cache instead of the
        // pipeline's frame chain, a SECOND pipeline would ease its first frame toward the
        // first pipeline's last root. Under the deleted solver cache it did exactly that
        // (WeakHashMap lived on the ConstraintSolver singleton — this test fails red there).
        val authored = listOf(100f, 200f)
        val shared = FrameFixture()
        val first = SkeletonPipeline(definition)
        first.produceFrame(shared.authorRoot(authored[0]))
        val firstFrame2 = publishedX(first.produceFrame(shared.authorRoot(authored[1])).pose)
        assertEquals("first pipeline eased frame 2 toward frame 1", eased(200f, 100f), firstFrame2, 1e-4f)

        val second = SkeletonPipeline(definition)
        val fresh = FrameFixture()
        val secondFrame1 = publishedX(second.produceFrame(fresh.authorRoot(200f)).pose)
        assertEquals(
            "a fresh pipeline has no history to ease toward — strict first frame " +
                "(proves smoothing is Pipeline-owned per-instance state, not solver/global memory)",
            200f, secondFrame1, 1e-4f
        )
    }

    @Test
    fun resetHistoryKeepsPreP5Semantics() {
        // Plan §P5: "resetHistory() unchanged" — it clears the dynamics pose chain (previous/
        // prePrevious), not the smoothing root (the deleted solver cache was likewise never
        // cleared by resetHistory; there was no API for it). This test pins the unchanged
        // behavior so P5 does not silently alter the restart/seek contract.
        val pipeline = SkeletonPipeline(definition)
        val fixture = FrameFixture()
        publishedX(pipeline.produceFrame(fixture.authorRoot(100f)).pose)
        val beforeReset = publishedX(pipeline.produceFrame(fixture.authorRoot(200f)).pose)
        assertEquals(175f, beforeReset, 1e-4f)
        pipeline.resetHistory()
        val afterReset = publishedX(pipeline.produceFrame(fixture.authorRoot(300f)).pose)
        // Smoothing survived the reset: eased toward 175, not a strict 300.
        assertEquals("smoothing input survives resetHistory (unchanged semantics)", eased(300f, beforeReset), afterReset, 1e-4f)
        assertEquals(268.75f, afterReset, 1e-4f)
    }

    @Test
    fun directSolverIsPureFunctionOfInputsPlusSuppliedHistory() {
        // The solver alone (no pipeline): two fresh carriers, identical authored inputs,
        // identical EXPLICIT history argument ⇒ identical output, and the explicit history is
        // observably consumed. Under the deleted cache the second solve (fresh instance, no
        // cache entry) would solve strict (200) — this assertion distinguishes the trees.
        fun solvedRoot(authoredX: Float, history: Vector3?): Float {
            val fixture = FrameFixture()
            fixture.authorRoot(authoredX)
            ConstraintSolver.solve(fixture.pose, definition, history)
            return fixture.pose.getJoint(Joint.PELVIS).x
        }
        val h = Vector3(100f, 50f, 0f)
        val first = solvedRoot(200f, h)
        assertEquals("explicit history is consumed (25% from 200 toward 100)", 175f, first, 1e-4f)
        val second = solvedRoot(200f, h)
        assertEquals("same inputs + same supplied history ⇒ same output on a fresh carrier", first, second, 1e-5f)
        assertEquals("null history ⇒ strict per-frame solve", 200f, solvedRoot(200f, null), 1e-4f)
    }
}
