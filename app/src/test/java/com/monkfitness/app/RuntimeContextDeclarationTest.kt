package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * **B-5 — the Frame Context is a declared property of the pose, and no production entry point may
 * silently drop it.**
 *
 * The stabilization audit measured the renderer entry point
 * (`SkeletonPipeline.produceFrame(builtPose, environment = ∅, supportedPoints = ∅)`) as
 * "caller-supplied and defaulted to empty": every pose's declaration-derived Frame Context
 * (`RFC_RUNTIME_SKELETON_ARCHITECTURE` §5 R8 — the External Environment Definition plus the
 * Production Metadata Support Declaration) was dropped on that path, and every caller had to
 * re-derive it by hand (`exercise` UI) or silently lose it (validation viewer, snapshot renderer).
 *
 * What this suite pins:
 *  1. Re-entering a produced frame through the renderer entry point WITHOUT arguments preserves the
 *     Frame Context the frame already carries — it neither rewrites the caller's frame nor
 *     re-publishes it declaration-free. (RED before the B-5 fix: 24 of the 49 registered poses
 *     carry a Support Declaration and every one of them was erased.)
 *  2. The omitted default IS the frame's own Frame Context (behavioural, not merely set equality).
 *  3. The two production entry points publish identical geometry when fed identical runtime inputs
 *     (the builder path's derived context vs the renderer path's explicitly supplied one) — and they
 *     become DISTINGUISHABLE when the declaration is dropped, so the equality is not vacuous.
 *  4. An explicit argument still wins, including an explicitly empty model: the fix removes the
 *     silent rewrite, not the caller's ability to choose.
 *  5. The Support Declaration has exactly ONE production resolution
 *     (`SupportDefinition.supportPoints`), and every production call site of the renderer path
 *     resolves it (a source-level guard: the call sites themselves are Compose/Android surfaces that
 *     a JVM unit test cannot execute).
 */
class RuntimeContextDeclarationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun productionIds(): List<String> = PoseRegistry.getDedicatedAnimationIds().sorted()

    /** A FRESH builder instance (the registry hands out shared, reused singletons). */
    private fun fresh(builder: PoseBuilder): PoseBuilder =
        builder.javaClass.getDeclaredConstructor().newInstance()

    private fun context(progress: Float) = PoseContext(progress, Side.LEFT, def)

    private fun published(id: String, progress: Float): Pair<PoseBuilder, SkeletonPose> {
        val builder = fresh(PoseRegistry.getPoseConfig(id)!!.builder)
        return builder to SkeletonPipeline(def).produceFrame(builder, context(progress)).pose
    }

    /**
     * Frame Context of a published frame in a value-comparable form: `EnvironmentDefinition`
     * embeds `Vector3`s without value equality, so the rendered form is the value projection.
     */
    private fun frameContext(pose: SkeletonPose): Pair<String, Set<SupportPoint>> =
        pose.environment.toString() to pose.supportedPoints.toSet()

    private fun worstJoint(a: SkeletonPose, b: SkeletonPose): Pair<Float, String> {
        var max = 0f; var worst = "-"
        for (j in Joint.entries) {
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            val d = maxOf(abs(pa.x - pb.x), abs(pa.y - pb.y), abs(pa.z - pb.z))
            if (d > max) { max = d; worst = j.name }
        }
        return max to worst
    }

    // ---------------------------------------------------------------- 1. no silent erasure

    @Test
    fun rendererEntryPointNeverErasesTheFrameContextItIsHanded() {
        val erased = ArrayList<String>()
        var carryingFrames = 0
        for (id in productionIds()) {
            val (_, produced) = published(id, 0.5f)
            val carried = produced.supportedPoints.toSet()
            val carriedEnvironment = produced.environment
            if (carried.isEmpty()) continue
            carryingFrames++

            // The renderer path with NO arguments: the shape every renderer caller used before B-5.
            val reentered = SkeletonPipeline(def).produceFrame(produced).pose

            // Collect EVERY violation before failing, so the RED reading names the whole corpus.
            if (produced.supportedPoints.toSet() != carried) erased += "$id(support)"
            if (produced.environment !== carriedEnvironment) erased += "$id(environment)"
            if (reentered.supportedPoints.toSet() != carried) erased += "$id(republished)"
        }
        assertTrue(
            "the renderer entry point rewrote/erased the Frame Context of the frame it was handed: $erased",
            erased.isEmpty()
        )
        assertTrue(
            "the sweep must actually exercise frames that carry a Frame Context (found $carryingFrames)",
            carryingFrames >= 20
        )
    }

    // ---------------------------------------------------------------- 2. the default IS the frame's context

    @Test
    fun omittedFrameContextIsTheContextTheFrameCarries() {
        var checked = 0
        for (id in productionIds()) {
            val shared = PoseRegistry.getPoseConfig(id)!!.builder
            val declaration = shared.metadata.support
            if (declaration.supportPoints.isEmpty()) continue

            // Builder path: §5 R8 resolves the declaration onto the carrier.
            val (_, fromBuilder) = published(id, 0.5f)
            assertEquals(
                "$id: the builder path must publish exactly its declared support model",
                declaration.supportPoints, fromBuilder.supportedPoints.toSet()
            )

            // Renderer path, arguments omitted — must publish the same model, not an empty one.
            val (_, second) = published(id, 0.5f)
            val omitted = SkeletonPipeline(def).produceFrame(second).pose
            assertEquals(
                "$id: an omitted Frame Context must publish the context the frame carries",
                declaration.supportPoints, omitted.supportedPoints.toSet()
            )

            // ... and it is behaviourally the same as supplying that context explicitly.
            val (_, third) = published(id, 0.5f)
            val explicit = SkeletonPipeline(def)
                .produceFrame(third, third.environment, third.supportedPoints).pose
            assertEquals(
                "$id: omitted must equal explicitly-supplied-own-context",
                frameContext(explicit), frameContext(omitted)
            )
            checked++
        }
        assertTrue("the sweep must cover the declaration-carrying poses (checked $checked)", checked >= 20)
    }

    // ---------------------------------------------------------------- 3. entry points agree on identical inputs

    @Test
    fun builderAndRendererEntryPointsAgreeOnIdenticalRuntimeInputs() {
        var compared = 0
        var distinguishable = 0
        for (id in productionIds()) {
            val shared = PoseRegistry.getPoseConfig(id)!!.builder
            val declaration = shared.metadata.support
            for (p in listOf(0f, 0.5f, 1f)) {
                val (_, fromBuilder) = published(id, p)

                // The renderer path fed the SAME resolved context (§5 R8) the builder path derived.
                val builder = fresh(shared)
                val built = builder.build(context(p))
                val fromRenderer = SkeletonPipeline(def)
                    .produceFrame(built, builder.metadata.environment, declaration.supportPoints).pose

                assertEquals(
                    "$id@$p: identical runtime inputs must give an identical Frame Context",
                    frameContext(fromBuilder), frameContext(fromRenderer)
                )
                val (dev, joint) = worstJoint(fromBuilder, fromRenderer)
                assertEquals(
                    "$id@$p: the two entry points must agree on identical runtime inputs " +
                        "(maxDev=$dev at $joint)",
                    0f, dev, 1e-4f
                )
                compared++

                // Sensitivity (non-vacuity): with the declaration dropped, the same comparison must
                // report a real difference for at least one pose — otherwise it could not detect it.
                val droppedBuilder = fresh(shared)
                val droppedPose = droppedBuilder.build(context(p))
                val dropped = SkeletonPipeline(def)
                    .produceFrame(droppedPose, droppedBuilder.metadata.environment).pose
                if (worstJoint(fromBuilder, dropped).first > 1e-4f) distinguishable++
            }
        }
        assertTrue("the sweep must cover the production corpus (compared $compared)", compared >= 100)
        assertTrue(
            "the entry-point comparison must be able to detect a dropped Support Declaration " +
                "(distinguishable frames=$distinguishable of $compared)",
            distinguishable > 0
        )
    }

    // ---------------------------------------------------------------- 4. an explicit argument still wins

    @Test
    fun explicitFrameContextStillOverridesWhatTheFrameCarries() {
        val id = productionIds().first {
            PoseRegistry.getPoseConfig(it)!!.builder.metadata.support.supportPoints.isNotEmpty()
        }
        val (_, produced) = published(id, 0.5f)
        assertTrue("the fixture must carry a declared support model", produced.supportedPoints.isNotEmpty())

        val emptyEnvironment = EnvironmentDefinition()
        val overridden = SkeletonPipeline(def)
            .produceFrame(produced, emptyEnvironment, emptySet()).pose

        assertTrue(
            "$id: an explicitly empty Frame Context must still be honoured (the caller's choice)",
            overridden.supportedPoints.isEmpty()
        )
        assertSame(
            "$id: an explicit environment must be forwarded verbatim",
            emptyEnvironment, overridden.environment
        )
    }

    // ---------------------------------------------------------------- 5. one production resolution

    /** All production Kotlin sources, keyed by path relative to `src/main/java`. */
    private fun mainSources(): Map<String, String> {
        var dir = File(System.getProperty("user.dir") ?: ".")
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) { moduleRoot = dir; break }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root from user.dir")
        val base = File(root, "src/main/java")
        return base.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .associate { it.relativeTo(base).path to it.readText() }
    }

    @Test
    fun supportDeclarationHasOneProductionResolution() {
        val reDerivation = Regex("""contacts\s*\.\s*map\s*\{[^}]*\bpoint\b""")
        val offenders = mainSources()
            .filterKeys { !it.endsWith("animation/SupportDefinition.kt") }
            .filterValues { reDerivation.containsMatchIn(it) }
            .keys.sorted()
        assertTrue(
            "the Support Declaration must have exactly ONE production resolution " +
                "(SupportDefinition.supportPoints) — a second derivation is a parallel source of " +
                "truth that silently desyncs the renderer path: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun productionRendererCallSitesResolveTheFrameContext() {
        val sources = mainSources()
        // The renderer entry points must forward the Frame Context they were given to the pipeline
        // (before B-5 both called `produceFrame(pose)`, dropping environment AND support model).
        val forwarding = Regex("""produceFrame\(\s*pose\s*,\s*environment\s*,\s*supportedPoints\s*\)""")
        for (file in listOf("SkeletonRenderer.kt", "SkeletonSnapshotRenderer.kt")) {
            val key = sources.keys.firstOrNull { it.endsWith(file) }
                ?: error("expected $file in app/src/main")
            assertTrue(
                "$key must forward the Frame Context into the pipeline (produceFrame(pose, environment, supportedPoints))",
                forwarding.containsMatchIn(sources.getValue(key))
            )
        }
        // Call sites that hold the pose declaration must resolve it through the single source.
        for (file in listOf("ui/components/ExerciseAnimation.kt", "validation/ValidationPoseLauncher.kt")) {
            val key = sources.keys.firstOrNull { it.endsWith(file) }
                ?: error("expected $file in app/src/main")
            assertTrue(
                "$key must resolve the Support Declaration via SupportDefinition.supportPoints",
                "metadata.support.supportPoints" in sources.getValue(key)
            )
        }
    }
}
