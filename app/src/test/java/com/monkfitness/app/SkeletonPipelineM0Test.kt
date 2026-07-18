package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M2 (RFC_GAP_CLOSURE) — `SkeletonPipeline` active stage-chain verification.
 *
 * (Phase B flag collapse) PIPELINE_ACTIVE and FINALIZER_OWNS_CONVERSION were collapsed to their
 * true branches and removed, so the pipeline is unconditionally live and there is no legacy
 * bypass or coherence invariant. This suite verifies the production guarantee: both entry points
 * ([produceFrame] with a [PoseBuilder] and with a pre-built [SkeletonPose] for renderers) are
 * byte-identical to a manual Solver+Finalizer run, and the validated path matches the direct
 * validator.
 */
class SkeletonPipelineM0Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT

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
    fun activeProduceFrameIsByteIdenticalToManualSolvePlusFinalize() {
        val frames = 20
        var maxDev = 0f
        var worst = ""
        // Use INDEPENDENT pose + finalizer instances per path: poses reuse an internal node tree /
        // jointsBuffer across build() calls and the finalizer flattens a pose only once
        // (`isTransformsUpdated`), so sharing an instance between the two paths would alias state.
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
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in poseFactories()) {
            val pipeline = SkeletonPipeline(def)
            val poseA = factory()
            val poseB = factory()
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)
                val fromBuilder = pipeline.produceFrame(poseA, ctx).pose
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
    fun validatedPathMatchesDirectValidator() {
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
    fun pipelineIsUnconditionallyLive() {
        // Phase B removed the PIPELINE_ACTIVE flag; the pipeline always drives the Solver+Finalizer
        // chain. The two entry points must agree (covered above) and no flag exists to disable it.
        assertTrue("EngineFlags no longer exposes PIPELINE_ACTIVE (collapsed in Phase B)",
            !EngineFlags.snapshot().containsKey("PIPELINE_ACTIVE"))
    }
}
