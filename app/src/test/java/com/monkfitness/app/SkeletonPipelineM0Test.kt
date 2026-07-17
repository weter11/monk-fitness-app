package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M2 (RFC_GAP_CLOSURE) — `SkeletonPipeline` active stage-chain verification.
 *
 * M2 flips `PIPELINE_ACTIVE=true` and makes `produceFrame` drive the full ordered stage chain
 * (`build` → `ConstraintSolver.solve` → `SkeletonPoseFinalizer.finalize`), with the Finalizer's
 * internal Solver call removed. The two entry points ([produceFrame] with a [PoseBuilder] and with
 * a pre-built [SkeletonPose] for renderers) must be byte-identical to a manual Solver+Finalizer run,
 * and the legacy bypass (flag off) must remain byte-identical to the direct finalizer path. This
 * locks the "zero regression vs pre-M2 baseline" guarantee in.
 */
class SkeletonPipelineM0Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalActive = EngineFlags.PIPELINE_ACTIVE
    private val originalFinalizer = EngineFlags.FINALIZER_OWNS_CONVERSION

    @After
    fun restore() {
        EngineFlags.PIPELINE_ACTIVE = originalActive
        EngineFlags.FINALIZER_OWNS_CONVERSION = originalFinalizer
    }

    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() }
    )

    @Test
    fun pipelineFlagDefaultsTrue() {
        // M2 ships with the master switch on.
        assertTrue("PIPELINE_ACTIVE must default to true in M2", originalActive)
        assertTrue("PIPELINE_ACTIVE must appear in the flag snapshot", EngineFlags.snapshot().containsKey("PIPELINE_ACTIVE"))
    }

    @Test
    fun activeProduceFrameIsByteIdenticalToManualSolvePlusFinalize() {
        EngineFlags.PIPELINE_ACTIVE = true
        val frames = 20
        var maxDev = 0f
        var worst = ""

        // Use INDEPENDENT pose + finalizer instances per path: poses reuse an internal node tree /
        // jointsBuffer across build() calls and the finalizer flattens a pose only once
        // (`isTransformsUpdated`), so sharing an instance between the two paths would alias state.
        // Two fresh objects is the faithful "same inputs, two code paths" comparison. The manual
        // reference path replicates exactly what the pipeline's runStages does:
        //   build -> (Solver iff contacts) -> finalizer.finalize.
        for ((name, factory) in poseFactories()) {
            val refFinalizer = SkeletonPoseFinalizer(def)
            val refPose = factory()
            val pipeline = SkeletonPipeline(def)
            val pipelinePose = factory()

            for (i in 0..frames) {
                val p = i / frames.toFloat()

                val built = refPose.build(PoseContext(p, Side.LEFT, def))
                if (built.roots.isNotEmpty() && built.hasContacts()) {
                    ConstraintSolver.solve(built, def)
                }
                val ref = refFinalizer.finalize(built)
                val refSnap = SkeletonPose().apply { copyFrom(ref) }

                val result = pipeline.produceFrame(pipelinePose, PoseContext(p, Side.LEFT, def))
                assertNull("active produceFrame must not attach a report", result.report)

                for (j in Joint.values()) {
                    val a = refSnap.getJoint(j); val b = result.pose.getJoint(j)
                    val d = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))
                    if (d > maxDev) { maxDev = d; worst = "$name @$p joint=$j" }
                }
            }
        }
        assertEquals("Active pipeline deviates from manual solve+finalize at $worst", 0f, maxDev, 1e-4f)
        println("SkeletonPipelineM0Test.activeProduceFrameIsByteIdenticalToManualSolvePlusFinalize: OK  maxDeviation=$maxDev")
    }

    @Test
    fun rendererPathIsByteIdenticalToBuilderPath() {
        EngineFlags.PIPELINE_ACTIVE = true
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in poseFactories()) {
            val pipeline = SkeletonPipeline(def)
            val poseA = factory()
            val poseB = factory()
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)
                // Builder-based entry point.
                val fromBuilder = pipeline.produceFrame(poseA, ctx).pose
                // Renderer-style entry point: a pose that has already been built.
                val built = poseB.build(ctx)
                val fromBuilt = pipeline.produceFrame(built).pose
                for (j in Joint.values()) {
                    val a = fromBuilder.getJoint(j); val b = fromBuilt.getJoint(j)
                    val d = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))
                    if (d > maxDev) { maxDev = d; worst = "$name @$p joint=$j" }
                }
            }
        }
        assertEquals("Renderer (built-pose) path deviates from builder path at $worst", 0f, maxDev, 1e-4f)
        println("SkeletonPipelineM0Test.rendererPathIsByteIdenticalToBuilderPath: OK  maxDeviation=$maxDev")
    }

    @Test
    fun legacyBypassIsByteIdenticalToDirectFinalizer() {
        // Legacy mode: pipeline off AND finalizer-authority off (the M4 flip is a pipeline-era
        // concern; the coherence invariant forbids FINALIZER_OWNS_CONVERSION without PIPELINE_ACTIVE).
        EngineFlags.PIPELINE_ACTIVE = false
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val frames = 20
        var maxDev = 0f
        var worst = ""

        for ((name, factory) in poseFactories()) {
            val directFinalizer = SkeletonPoseFinalizer(def)
            val directPose = factory()
            val pipeline = SkeletonPipeline(def)
            val pipelinePose = factory()

            for (i in 0..frames) {
                val p = i / frames.toFloat()

                val direct = directFinalizer.finalize(directPose.build(PoseContext(p, Side.LEFT, def)))
                val directSnap = SkeletonPose().apply { copyFrom(direct) }

                val result = pipeline.produceFrame(pipelinePose, PoseContext(p, Side.LEFT, def))
                assertNull("legacy produceFrame must not attach a report", result.report)

                for (j in Joint.values()) {
                    val a = directSnap.getJoint(j); val b = result.pose.getJoint(j)
                    val d = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))
                    if (d > maxDev) { maxDev = d; worst = "$name @$p joint=$j" }
                }
            }
        }
        assertEquals("Pipeline legacy path deviates from direct path at $worst", 0f, maxDev, 1e-4f)
        println("SkeletonPipelineM0Test.legacyBypassIsByteIdenticalToDirectFinalizer: OK  maxDeviation=$maxDev")
    }

    @Test
    fun validatedPathMatchesDirectValidator() {
        EngineFlags.PIPELINE_ACTIVE = true
        val validator = ExerciseValidator()
        val pipeline = SkeletonPipeline(def, validator)
        val camera = Camera(StandardPullUpPose().metadata.camera)
        val env = StandardPullUpPose().metadata.environment

        // Independent pose instances per path (see byte-identity test rationale).
        val built = StandardPullUpPose().build(PoseContext(0f, Side.LEFT, def))
        if (built.roots.isNotEmpty() && built.hasContacts()) ConstraintSolver.solve(built, def)
        val directPose = SkeletonPoseFinalizer(def).finalize(built)
        val directReport = validator.validate(directPose, def, env, camera, 1000f, 1000f, null, null, 0.033f)

        val framed = pipeline.produceFrameValidated(StandardPullUpPose(), PoseContext(0f, Side.LEFT, def), camera, env, 1000f, 1000f, 0.033f)
        assertEquals("validity must match direct validator", directReport.isValid, framed.report.isValid)
    }

    @Test
    fun incoherentFlagsFailAtConstruction() {
        // The coherence invariant only fires when FINALIZER_OWNS_CONVERSION is turned on WITHOUT
        // the pipeline being active (an incoherent "finalizer owns conversion but nothing drives
        // it" configuration). M4 keeps FINALIZER_OWNS_CONVERSION on by default.
        EngineFlags.PIPELINE_ACTIVE = false
        EngineFlags.FINALIZER_OWNS_CONVERSION = true
        var threw = false
        try {
            SkeletonPipeline(def)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("FINALIZER_OWNS_CONVERSION without PIPELINE_ACTIVE must fail", threw)
    }
}
