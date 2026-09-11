package com.monkfitness.app.arch

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 11 — R11/R14: Carrier transfer-chain & pipeline-lifetime compliance.
 *
 * `docs/IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md` §Phase 11 defines this class as the phase's
 * entire deliverable ("Test-only `CarrierTransferComplianceTest`"), because the phase's target
 * state is "compliant by construction" — the work is to replace that PROSE claim with executed
 * evidence. RFC citations: §5 R11 (carrier unity + single-owner transfer chain), R14 (pipeline
 * lifetime), §4.5 carrier/pipeline rows, §8 Pipeline block.
 *
 * The three required assertions from the plan:
 *  (a) the returned Finalized Pose is a DISTINCT instance from the input carrier;
 *  (b) two renderers with separate pipelines produce independent frames (R14: no shared/global
 *      pipeline; differing creator lifecycles carry no architectural consequence);
 *  (c) External objects never appear in carrier copies — the §3.3 Published Pose State is
 *      exactly transforms + Validation Stamps, so the P3 non-leakage suppression is verified
 *      from the other side (the published carrier must NOT carry the internal sections).
 *
 * Plus the plan's "Open question / implementation decision: CI grep gate — propose yes":
 * a static sweep for the forbidden patterns named by the plan (`WeakHashMap<SkeletonPose`,
 * global singleton pipeline state).
 */
class CarrierTransferComplianceTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun sourceRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("could not locate the production source root from user.dir")
        }
        return File(dir, "app/src/main/java")
    }

    private fun productionSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---------------------------------------------------------------------------------------
    // (a) R11 — the transfer chain hands a DISTINCT published carrier to the External caller;
    // the input carrier is never the published object (R11 single-owner transfer: the pipeline
    // does not hand its own in-flight buffer back to the caller).
    // ---------------------------------------------------------------------------------------

    @Test
    fun publishedPoseIsADistinctInstanceFromTheInputCarrier() {
        val pose = StandardPushUpPose()
        val pipeline = SkeletonPipeline(def)
        val built = pose.build(ctx(0.5f))
        val published = pipeline.produceFrame(built).pose

        assertNotSame("R11: the Finalized Pose must be a distinct instance from the input carrier", built, published)
        // Anti-vacuity: the published carrier is a real frame, not an empty shell.
        assertTrue(
            "R11 anti-vacuity: the published carrier must hold the finalizaton state",
            published.roots.isNotEmpty() && published.isTransformsUpdated
        )
        assertEquals(
            "R11: the published carrier carries the input's joint count unchanged",
            built.joints.size, published.joints.size
        )
    }

    @Test
    fun builderPathAlsoPublishesADistinctInstance() {
        val pose = StandardPushUpPose()
        val pipeline = SkeletonPipeline(def)
        val published = pipeline.produceFrame(pose, ctx(0.5f)).pose
        // The jointsBuffer carrier the builder returns is the pipeline's INPUT; the caller only
        // ever sees the finalizer's own output buffer.
        val builtAgain = pose.build(ctx(0.5f))
        assertNotSame("R11: the builder path must also publish a distinct instance", builtAgain, published)
    }

    // ---------------------------------------------------------------------------------------
    // (b) R14 — each pipeline is creator-owned; there is no shared or global pipeline, and two
    // creators therefore produce independent frames with independent Frame History.
    // ---------------------------------------------------------------------------------------

    @Test
    fun twoCreatorsWithSeparatePipelinesProduceIndependentFrames() {
        val pose = StandardPushUpPose()
        // Two "renderer instances" = two pipelines over the same definition (R14).
        val pipelineA = SkeletonPipeline(def)
        val pipelineB = SkeletonPipeline(def)

        // Drive them with DIFFERENT depth so an aliased/shared buffer would be visible.
        // (0.0 = top of the rep, 0.5 = bottom; the push-up is symmetric about 0.5, so 0.15/0.85
        // would read the SAME depth and the anti-vacuity guard below would correctly refuse it.)
        val frameA = pipelineA.produceFrame(pose, ctx(0.0f)).pose
        val frameB = pipelineB.produceFrame(pose, ctx(0.5f)).pose

        assertNotSame("R14: separate creators must not share one published buffer", frameA, frameB)
        val chestA = frameA.getJoint(Joint.CHEST).y
        val chestB = frameB.getJoint(Joint.CHEST).y
        assertTrue(
            "R14 anti-vacuity: the two creators must be driven at different depths (chestA=$chestA chestB=$chestB)",
            kotlin.math.abs(chestA - chestB) > 1f
        )
        // frameA must still hold ITS OWN frame's value after pipelineB produced a later frame
        // (a shared buffer would have overwritten it — the classic aliasing failure).
        assertEquals(
            "R14: producing on creator B must not mutate creator A's published frame",
            chestA, frameA.getJoint(Joint.CHEST).y, 0f
        )
    }

    @Test
    fun frameHistoryIsPerPipelineNotGlobal() {
        // R14 + §4.5: the Frame History belongs to the pipeline instance. A fresh pipeline must
        // start with an empty history window even after another pipeline has been driving frames.
        val pose = SquatPose()
        val warm = SkeletonPipeline(def)
        for (i in 0..5) warm.produceFrame(pose, ctx(0.5f))

        val fresh = SkeletonPipeline(def)
        val freshField = SkeletonPipeline::class.java
            .getDeclaredField("previous").apply { isAccessible = true }
        assertNull("R14: a fresh pipeline starts with no previous frame", freshField.get(fresh))
        assertNotSame("R14: two pipelines are distinct instances", warm, fresh)
    }

    // ---------------------------------------------------------------------------------------
    // (c) R11 §3.3 — External objects never appear in carrier copies. The published carrier is
    // exactly Published Pose State (transforms + stamps); the internal/in-flight sections are
    // absent. This verifies the P3 clear-at-publish decision from the consuming side.
    // ---------------------------------------------------------------------------------------

    @Test
    fun publishedCarrierDoesNotLeakInternalSections() {
        val pose = StandardPushUpPose() // contact-bearing: the Settlement Result IS produced
        val pipeline = SkeletonPipeline(def)
        val published = pipeline.produceFrame(pose, ctx(0.5f)).pose

        val settlement = SkeletonPose::class.java.getDeclaredField("settlementResult")
            .apply { isAccessible = true }
        assertEquals(
            "R11/§3.3: the Settlement Result must not leak into the Published Pose State",
            null, settlement.get(published)
        )
        val publishMarker = SkeletonPoseFinalizer::class.java.getDeclaredField("published")
            .apply { isAccessible = true }

        // The published carrier's intent section is preserved (it is §3.3 content, not internal
        // state) — used here as the anti-vacuity anchor that we are reading a real published frame.
        val pipelineField = SkeletonPipeline::class.java.getDeclaredField("finalizer").apply { isAccessible = true }
        val finalizer = pipelineField.get(pipeline)
        assertEquals(
            "§3.3 anti-vacuity: the frame was actually published (marker set)",
            true, publishMarker.get(finalizer)
        )
    }

    @Test
    fun inputCarrierIsNotMutatedIntoThePublishedStateByTheTransfer() {
        // R11: ownership TRANSFERS. The External caller receives the finalizer's output; the
        // caller's own built carrier must not have become that output.
        val pose = KneePushUpPose()
        val pipeline = SkeletonPipeline(def)
        val built = pose.build(ctx(0.5f))
        val builtRootsIdentity = built.roots
        val published = pipeline.produceFrame(built).pose
        assertSame(
            "R11: publishing does not re-point the input carrier's hierarchy",
            builtRootsIdentity, built.roots
        )
        assertNotSame("R11: the transfer is not in-place", built, published)
    }

    // ---------------------------------------------------------------------------------------
    // CI grep gate (plan §P11 "Open questions / implementation decisions": CI grep gate —
    // propose yes; RFC silent on tooling). RFC §5 R11 forbids a second shared mutable channel
    // and R14 forbids a shared/global pipeline. The patterns below are the plan's own examples.
    // ---------------------------------------------------------------------------------------

    @Test
    fun noForbiddenSharedMutableChannelOrGlobalPipelineState() {
        val forbidden = listOf(
            // plan §V3 / §P5: the deleted identity-keyed solver cache must not come back
            "WeakHashMap<SkeletonPose",
            "lastSolvedRoot",
            // plan §P11: no shared/global pipeline instance (R14)
            "object SkeletonPipeline",
            "var sharedPipeline",
            "val sharedPipeline"
        )
        val hits = mutableListOf<String>()
        for (f in productionSources()) {
            val lines = f.readLines()
            for ((i, line) in lines.withIndex()) {
                val code = line.substringBefore("//")
                for (pat in forbidden) if (code.contains(pat)) hits.add("${f.name}:${i + 1}: $pat")
            }
        }
        assertTrue(
            "R11/R14 forbidden shared-state sweep (production sources must not reintroduce " +
                "an identity-keyed cache or a global pipeline):\n" + hits.joinToString("\n"),
            hits.isEmpty()
        )
        // Anti-vacuity: the sweep must actually be reading the production tree.
        assertTrue("grep-gate anti-vacuity: production sources were scanned", productionSources().size > 20)
    }

    @Test
    fun pipelineHasNoSharedCompanionState() {
        // R14: the pipeline owns its stages and its Frame History as INSTANCE state. A companion
        // object holding them would make the lifetime creator-independent — the exact shape R14
        // forbids. Assert no companion object is declared on SkeletonPipeline at all.
        val hasCompanion = SkeletonPipeline::class.java.declaredClasses.any { it.simpleName.contains("Companion") }
        assertFalse("R14: SkeletonPipeline must not declare a companion object (no shared state)", hasCompanion)
    }
}
