package com.monkfitness.app.arch

import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonPoseFinalizer
import com.monkfitness.app.animation.Side
import com.monkfitness.app.poses.StandardPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Phase 8 (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P8) — publish-ordering
 * structuralization (RFC_RUNTIME_SKELETON_ARCHITECTURE §6 Phase 4 fixed internal order,
 * §3.3 Published Pose State immutability onset, §4.4 note on Finalizer stamp write phases).
 *
 * RFC §6 Phase 4 fixes the internal order: Flatten writes every published transform AND the
 * Finalizer completes every attributable Validation Stamp write, and ONLY THEN does Published
 * Pose State become immutable. The pre-P8 implementation already performed this order but only
 * implicitly (`derivation/flatten → applyValidationStamps → return outputPose` — no explicit
 * publish boundary, no enforcement against a later stamp write or a silent re-entry on the
 * reused `outputPose` buffer). P8 makes the boundary STRUCTURAL — an explicit `publish()` tail
 * with a debug-only publication marker — without changing any numerical behavior.
 *
 * IMPLEMENTATION DECISION (plan §P8, not dictated by §6 Phase 4's literal text): the
 * single-shot publish guard. §6 fixes write ORDER and §3.3 the immutability ONSET; the guard is
 * the enforcement mechanism FOR §3.3's "immutable from the moment Phase 4 completes" — a second
 * publication of the same carrier through the same finalizer (silent re-entry would republish
 * the reused private `outputPose` buffer) throws in DEBUG. Release builds compile the checks
 * out; the marker is runtime enforcement state on the finalizer instance, never Published Pose
 * State contents.
 *
 * The guard recognizes two legitimate re-arm evidences, both boundary events the architecture
 * already defines (§6 Phase 0.5 / §4.5 R11 ownership transfer; the per-build carrier-hygiene
 * reset):
 *  - the SkeletonPipeline starting a new execution window for a frame
 *    (`SkeletonPoseFinalizer.beginPublishWindow`, called from `runStages`),
 *  - a fresh authoring pass over the carrier (`IntentBuilder.reset` bumps
 *    `SkeletonPose.buildCycleToken` — the jointsBuffer instance is legitimately reused across
 *    builds, so a new build cycle is a new publish cycle).
 * Neither evidence exists for a silent re-entry: same carrier, same build, no new window — that
 * is the §3.3 violation and it must throw.
 *
 * This file compiles against the pre-P8 tree (public API + reflection + source-text audits
 * only), so the same bytes run as the counterfactual RED gate on `origin/main` — the P6/P7
 * pattern.
 */
class PublishOrderingTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun ctx(progress: Float = 0.5f) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = definition,
        deltaTime = 1f / 60f, cycleDuration = 2500f
    )

    /** A production contact frame through the REAL pipeline (pushup: hands+toes support). */
    private fun pipelineFrame(progress: Float = 0.5f): SkeletonPose =
        SkeletonPipeline(definition)
            .produceFrame(StandardPushUpPose(), ctx(progress))
            .pose

    // ---------------------------------------------------------------------------------
    // 1 — Single-pass finalization remains valid: a normal frame finalizes and publishes
    //     with the complete Finalizer stamp set present on the returned pose.
    // ---------------------------------------------------------------------------------
    @Test
    fun normalFrameFinalizesAndPublishesWithFullStampSet() {
        val published = pipelineFrame(0.5f)
        // Finalizer-owned §4.4 stamps all present on the published carrier.
        assertEquals("hip ROM stamps for both hips", 2, published.hipRomStamps.size)
        assertTrue("published pose is transforms-updated", published.isTransformsUpdated)
        // A second pipeline frame on a fresh pipeline finalizes+publishes identically-shaped.
        val second = pipelineFrame(0.75f)
        assertEquals(2, second.hipRomStamps.size)
    }

    // ---------------------------------------------------------------------------------
    // 2 — happen-before: applyValidationStamps runs before the publication marker.
    //     Runtime proof: a normal publish SUCCEEDS end-to-end while the stamp-entry guard
    //     (test 3) rejects any post-marker stamp write — so within the successful publish
    //     the stamps necessarily completed BEFORE the marker was set. Paired here with the
    //     source-order audit inside the extracted publish() tail.
    // ---------------------------------------------------------------------------------
    @Test
    fun validationStampsAreWrittenBeforeThePublicationMarker() {
        val finalizer = SkeletonPoseFinalizer(definition)
        val built = StandardPushUpPose().build(ctx(0.5f))
        val published = finalizer.finalize(built)

        // Stamps present on the published pose: applyValidationStamps ran to completion…
        assertEquals(2, published.hipRomStamps.size)

        val src = productionSource("SkeletonPoseFinalizer.kt")
        val publishAt = src.indexOfFirst { it.trimStart().startsWith("private fun publish(") }
        assertTrue("P8 must extract an explicit publish() tail (not on the pre-P8 tree)", publishAt >= 0)
        val region = memberRegion(src, publishAt)
        val stampAt = region.indexOfFirst { it.trimStart().startsWith("applyValidationStamps(") }
        val markerAt = region.indexOfFirst { it.trimStart().startsWith("published = true") }
        assertTrue("publish() must call applyValidationStamps", stampAt >= 0)
        assertTrue("publish() must set the publication marker", markerAt >= 0)
        assertTrue(
            "applyValidationStamps must happen-before published=true (stampAt=$stampAt markerAt=$markerAt)",
            stampAt < markerAt
        )
        val returnAt = region.indexOfFirst { it.trimStart().startsWith("return ") }
        assertTrue("the marker must precede the publish return (no post-marker work)", returnAt > markerAt)
        val flattenAt = region.indexOfFirst { it.contains("assertFinalFlattenComplete(") }
        assertTrue("the final flatten-completion check must precede the stamp phase",
            flattenAt in 0..<stampAt)
    }

    // ---------------------------------------------------------------------------------
    // 3 — No stamp write after publication: the stamp-writing entry itself is guarded.
    //     Reaching the private applyValidationStamps reflectively on an already-published
    //     finalizer must throw the publish-order violation (the actual guard path, not a
    //     source-text proxy).
    // ---------------------------------------------------------------------------------
    @Test
    fun stampWriteAfterPublicationThrowsPublishOrderViolation() {
        val finalizer = SkeletonPoseFinalizer(definition)
        val published = finalizer.finalize(StandardPushUpPose().build(ctx(0.5f)))
        val stampMethod = try {
            SkeletonPoseFinalizer::class.java.getDeclaredMethod(
                "applyValidationStamps", SkeletonPose::class.java
            )
        } catch (e: NoSuchMethodException) {
            fail("the Finalizer must keep applyValidationStamps as the single stamp-writing " +
                "entry (P2 canonical surface): $e")
            return
        }
        stampMethod.isAccessible = true
        try {
            stampMethod.invoke(finalizer, published)
            fail("post-publication Validation Stamp write must throw in DEBUG; " +
                "pre-P8 there is no publish-order guard at all")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            assertTrue("expected IllegalStateException, got $cause", cause is IllegalStateException)
            assertTrue("failure must identify the publish-order violation, got: ${cause.message}",
                cause.message!!.contains("Publish-order violation"))
        }
    }

    // ---------------------------------------------------------------------------------
    // 4 — Re-entering the same finalizer with the same carrier after publish fails in
    //     DEBUG. This is THE §P8 minimum: "a second finalize(...) on an already-published
    //     pose through the same SkeletonPoseFinalizer instance must fail in DEBUG."
    //     No new build cycle and no new pipeline window occur between the two calls, so
    //     the reused output buffer would be silently republished (§3.3 violation).
    // ---------------------------------------------------------------------------------
    @Test
    fun secondFinalizeOnSameFinalizerAndCarrierThrowsPublishOrderViolation() {
        val finalizer = SkeletonPoseFinalizer(definition)
        val built = StandardPushUpPose().build(ctx(0.5f))
        val first = finalizer.finalize(built)
        assertTrue("first publish succeeded (transforms-updated output)", first.isTransformsUpdated)
        try {
            finalizer.finalize(built)
            fail("re-publishing the same carrier through the same finalizer instance must " +
                "throw in DEBUG (§3.3 immutability onset); pre-P8 the reused outputPose " +
                "buffer is silently republished")
        } catch (e: IllegalStateException) {
            assertTrue("failure must identify the publish-order / post-publish violation, got: ${e.message}",
                e.message!!.contains("Publish-order violation"))
        }
    }

    // ---------------------------------------------------------------------------------
    // 5 — The marker is instance-local runtime state, not global: a fresh
    //     SkeletonPoseFinalizer publishes normally after another instance published, and a
    //     REUSED finalizer re-arms on the documented boundary evidences (new authoring
    //     cycle / new pipeline window) instead of staying tripped forever.
    // ---------------------------------------------------------------------------------
    @Test
    fun publicationMarkerIsInstanceLocalNotGlobal() {
        val firstFinalizer = SkeletonPoseFinalizer(definition)
        firstFinalizer.finalize(StandardPushUpPose().build(ctx(0.5f)))
        // A DIFFERENT finalizer instance publishes its own frame without a throw → the
        // marker never leaked to a second instance (a global/static marker would throw here).
        val b = SkeletonPoseFinalizer(definition).finalize(StandardPushUpPose().build(ctx(0.55f)))
        assertEquals("fresh finalizer published normally", 2, b.hipRomStamps.size)
        // The reused finalizer re-arms on a NEW authoring cycle (fresh builder → fresh
        // carrier → new build token): instance-local marker, not a sticky one-shot lifetime.
        val freshCarrier = StandardPushUpPose().build(ctx(0.6f))
        firstFinalizer.finalize(freshCarrier)
        // Same finalizer + same carrier + SAME build cycle → guarded again (the marker was
        // re-set by the publish above, proving re-arm is per-publish-unit).
        try {
            firstFinalizer.finalize(freshCarrier)
            fail("post-publish re-entry must throw after interleaved legitimate frames")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Publish-order violation"))
        }
    }

    // ---------------------------------------------------------------------------------
    // 6 — Pipeline integration: the REAL SkeletonPipeline keeps producing published frames
    //     correctly on both entry-point shapes (sequential playback on ONE pipeline — the
    //     renderer pattern — re-arms via the window evidence every frame), and the stamps
    //     of the published pose are present on every frame.
    // ---------------------------------------------------------------------------------
    @Test
    fun pipelineKeepsPublishingFramesCorrectlyAcrossSequentialFrames() {
        val pipeline = SkeletonPipeline(definition)
        val builder = StandardPushUpPose()
        for (i in 0..5) {
            val pose = pipeline.produceFrame(builder, ctx(i / 5f)).pose
            assertEquals("frame $i publishes the full Finalizer stamp set", 2, pose.hipRomStamps.size)
        }
        // Built-carrier entry point (renderer path) also keeps working per-frame on one
        // pipeline: same reused jointsBuffer, new build cycle every frame.
        val direct = SkeletonPipeline(definition)
        val shared = StandardPushUpPose()
        repeat(3) {
            val built = shared.build(ctx(0.4f))
            val pose = direct.produceFrame(built).pose
            assertEquals(2, pose.hipRomStamps.size)
        }
    }

    // ---------------------------------------------------------------------------------
    // 7 — Structural single-tail audit: exactly ONE publish tail owns the final
    //     flatten-completion check, the stamp write, and the marker; finalize() has no
    //     second hidden stamp/publish return path; the stamp entry stays private; the
    //     marker assignment is debug-gated.
    // ---------------------------------------------------------------------------------
    @Test
    fun publishBoundaryIsASingleExplicitTail() {
        val src = productionSource("SkeletonPoseFinalizer.kt")

        // Exactly one publication-marker assignment (code lines; the assignment idiom is a
        // line starting with `published = true`).
        val markerSites = src.withIndex()
            .filter { (_, l) -> l.trimStart().startsWith("published = true") }
            .map { it.index }
        assertEquals("exactly one publication-marker assignment", 1, markerSites.size)

        val publishAt = src.indexOfFirst { it.trimStart().startsWith("private fun publish(") }
        assertTrue("P8 must extract an explicit publish() tail", publishAt >= 0)

        // Exactly one call site of applyValidationStamps and it sits inside publish().
        val stampCalls = src.withIndex()
            .filter { (_, l) -> l.trimStart().startsWith("applyValidationStamps(") }
            .map { it.index }
        assertEquals("single stamp-write call site", 1, stampCalls.size)
        val publishEnd = publishAt + memberRegion(src, publishAt).size
        assertTrue("the stamp call must live inside the publish tail (publishAt=$publishAt " +
                "stampCall=${stampCalls[0]})", stampCalls[0] in (publishAt + 1) until publishEnd)
        assertTrue("the marker must live inside the publish tail",
            markerSites[0] in (publishAt + 1) until publishEnd)

        // finalize() ends by delegating to publish() — no second return path to the buffer.
        val finalizeAt = src.indexOfFirst { it.trimStart().startsWith("fun finalize(") }
        val finalizeRegion = memberRegion(src, finalizeAt)
        assertTrue("finalize() must delegate its tail to publish(outputPose)",
            finalizeRegion.any { Regex("""return\s+publish\(""").containsMatchIn(it) })
        assertTrue("finalize() must keep no direct `return outputPose` (single publish tail)",
            finalizeRegion.none { Regex("""return\s+outputPose""").containsMatchIn(it) })
        assertTrue("finalize() must not call applyValidationStamps directly anymore",
            finalizeRegion.none { it.trimStart().startsWith("applyValidationStamps(") })

        // The marker assignment must sit inside a BuildConfig.DEBUG-gated region.
        assertTrue("the publication marker must be debug-gated",
            enclosedByDebugGuard(src, markerSites[0]))

        // The flatten-completion check belongs to the publish tail, before the stamp call.
        val flattenChecks = src.withIndex()
            .filter { (_, l) -> l.contains("assertFinalFlattenComplete(") && !l.trimStart().startsWith("private fun") }
            .map { it.index }
        assertEquals("one call site for the final flatten-completion check", 1, flattenChecks.size)
        assertTrue("publish() must run the flatten-completion check before the stamps",
            flattenChecks[0] in (publishAt + 1) until stampCalls[0])

        // No caller may publish independently of finalization: the stamp entry stays private.
        val visibility = src.first { it.contains("fun applyValidationStamps") }
        assertTrue("applyValidationStamps must remain private to the finalizer",
            visibility.trimStart().startsWith("private fun"))
    }

    // ---------------------------------------------------------------------------------
    // Helpers (P6/P7 idiom: walk-up per the Gradle user.dir pitfall; region + debug scans)
    // ---------------------------------------------------------------------------------

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

    private fun productionSource(name: String): List<String> =
        productionAnimationSources()[name]
            ?: error("$name not found in the production animation package")

    /**
     * Lines of a member-function region starting AFTER the declaration line at [headerIndex],
     * ending at the next sibling member declaration (same idiom as the P7 funRegion, minus
     * the header line).
     */
    private fun memberRegion(lines: List<String>, headerIndex: Int): List<String> {
        val rest = lines.drop(headerIndex + 1)
        val end = rest.indexOfFirst {
            val t = it.trimStart()
            (t.startsWith("fun ") || t.startsWith("private fun ") || t.startsWith("internal fun ") ||
                t.startsWith("class ") || t.startsWith("companion ")) ||
                (t.startsWith("private ") && !t.startsWith("private fun") && !it.contains("("))
        }
        return rest.take(if (end < 0) rest.size else end)
    }

    /**
     * Upward brace walk: does [idx] sit inside an `if (BuildConfig.DEBUG) { … }` block
     * (directly or nested through other control-flow blocks)? (P7 idiom.)
     */
    private fun enclosedByDebugGuard(lines: List<String>, idx: Int): Boolean {
        var depth = 0
        for (i in (idx - 1) downTo 0) {
            val line = lines[i]
            var j = line.length - 1
            while (j >= 0) {
                when (line[j]) {
                    '}' -> depth++
                    '{' -> {
                        if (depth == 0) {
                            val t = line.trim()
                            if (t.contains("BuildConfig.DEBUG")) return true
                            if (!(t.startsWith("if ") || t.startsWith("} else if") ||
                                    t.startsWith("else") || t.contains("if ("))) return false
                        } else depth--
                    }
                }
                j--
            }
        }
        return false
    }
}
