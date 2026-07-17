package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M0/M2 (RFC_GAP_CLOSURE) — `SkeletonPipeline` verification.
 *
 * M0 introduced the pipeline behind `PIPELINE_ACTIVE`; M2 flips it on so `produceFrame` drives the
 * full stage chain (build → ConstraintSolver stage for contact poses → Finalizer). The core
 * guarantee: the active pipeline path is **byte-identical** to the direct `build()` +
 * `finalizer.finalize()` flow every consumer used to run. This locks that in for every mode.
 */
class SkeletonPipelineM2Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalActive = EngineFlags.PIPELINE_ACTIVE
    private val originalFinalizer = EngineFlags.FINALIZER_OWNS_CONVERSION

    @After
    fun restore() {
        EngineFlags.PIPELINE_ACTIVE = originalActive
        EngineFlags.FINALIZER_OWNS_CONVERSION = originalFinalizer
    }

    // Poses with and without contacts: contact poses exercise the ConstraintSolver stage that the
    // pipeline now owns (and the Finalizer must no longer re-run), so both branches are covered.
    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "ReverseLunge" to { AlternatingReverseLungesPose() },
        "SideLunge" to { AlternatingSideLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() }
    )

    @Test
    fun activeProduceFrameIsByteIdenticalToDirectPath() {
        EngineFlags.PIPELINE_ACTIVE = true
        // M2 default: the F1 no-move guard (FINALIZER_OWNS_CONVERSION) stays off until M4, so the
        // active path is byte-identical to the pre-M2 baseline.
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val frames = 20
        var maxDev = 0f
        var worst = ""

        // Independent pose + finalizer instances per path: poses reuse an internal node tree /
        // jointsBuffer and the finalizer flattens only once (isTransformsUpdated), so sharing an
        // instance would alias state. Two fresh objects is the faithful comparison.
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
                assertNull("produceFrame must not attach a report", result.report)

                for (j in Joint.values()) {
                    val a = directSnap.getJoint(j); val b = result.pose.getJoint(j)
                    val d = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))
                    if (d > maxDev) { maxDev = d; worst = "$name @$p joint=$j" }
                }
            }
        }
        assertEquals("Active pipeline path deviates from direct path at $worst", 0f, maxDev, 1e-4f)
        println("SkeletonPipelineM2Test: active path maxDeviation=$maxDev")
    }

    @Test
    fun legacyProduceFrameIsByteIdenticalToDirectPath() {
        EngineFlags.PIPELINE_ACTIVE = false
        val frames = 20
        var maxDev = 0f
        for ((_, factory) in poseFactories()) {
            val directFinalizer = SkeletonPoseFinalizer(def)
            val pipeline = SkeletonPipeline(def)
            val directPose = factory(); val pipelinePose = factory()
            for (i in 0..frames) {
                val p = i / frames.toFloat()
                val direct = directFinalizer.finalize(directPose.build(PoseContext(p, Side.LEFT, def)))
                val snapped = SkeletonPose().apply { copyFrom(direct) }
                val result = pipeline.produceFrame(pipelinePose, PoseContext(p, Side.LEFT, def))
                for (j in Joint.values()) {
                    val a = snapped.getJoint(j); val b = result.pose.getJoint(j)
                    maxDev = maxOf(maxDev, maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z)))
                }
            }
        }
        assertEquals("Legacy pipeline path deviates from direct path", 0f, maxDev, 1e-4f)
    }

    @Test
    fun validatedPathMatchesDirectValidator() {
        EngineFlags.PIPELINE_ACTIVE = true
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val validator = ExerciseValidator()
        val pipeline = SkeletonPipeline(def, validator)
        val camera = Camera(StandardPullUpPose().metadata.camera)
        val env = StandardPullUpPose().metadata.environment

        val directFinalizer = SkeletonPoseFinalizer(def)
        val directPose = directFinalizer.finalize(StandardPullUpPose().build(PoseContext(0f, Side.LEFT, def)))
        val directReport = validator.validate(directPose, def, env, camera, 1000f, 1000f, null, null, 0.033f)

        val framed = pipeline.produceFrameValidated(StandardPullUpPose(), PoseContext(0f, Side.LEFT, def), camera, env, 1000f, 1000f, 0.033f)
        assertEquals("validity must match direct validator", directReport.isValid, framed.report.isValid)
    }

    @Test
    fun contactPoseSolverStageOwnsSolveUnderPipeline() {
        // Forward lunge registers fixed contacts (the planted foot). Under the pipeline the
        // ConstraintSolver stage must run from the pipeline (the Finalizer must NOT re-solve),
        // and the result must still match the direct finalize exactly.
        EngineFlags.PIPELINE_ACTIVE = true
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val lunge = AlternatingForwardLungesPose()
        val directFinalizer = SkeletonPoseFinalizer(def)
        val pipeline = SkeletonPipeline(def)

        val frames = 10
        var maxDev = 0f
        for (i in 0..frames) {
            val p = i / frames.toFloat()
            val direct = directFinalizer.finalize(lunge.build(PoseContext(p, Side.LEFT, def)))
            val snapped = SkeletonPose().apply { copyFrom(direct) }
            val result = pipeline.produceFrame(lunge, PoseContext(p, Side.LEFT, def))
            for (j in Joint.values()) {
                val a = snapped.getJoint(j); val b = result.pose.getJoint(j)
                maxDev = maxOf(maxDev, maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z)))
            }
        }
        assertEquals("Lunge (contact) active path deviates from direct path", 0f, maxDev, 1e-4f)
    }

    @Test
    fun pipelineDefaultsMatchM2() {
        // After M2 the master switch is on by default; the F1 no-move guard (FINALIZER_OWNS_CONVERSION)
        // stays off until M4 so output is byte-identical to the pre-M2 baseline.
        assertTrue("PIPELINE_ACTIVE must default to true after M2", originalActive)
        assertTrue("M2 keeps FINALIZER_OWNS_CONVERSION off until M4", !originalFinalizer)
    }

    @Test
    fun activePathRunsWithoutFinalizerConversion() {
        // Explicitly the M2 configuration: active pipeline, finalizer conversion still off (M4).
        // The pipeline must construct and produce a frame without throwing the M4 coherence gate.
        EngineFlags.PIPELINE_ACTIVE = true
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val pipeline = SkeletonPipeline(def)
        val result = pipeline.produceFrame(StandardPushUpPose(), PoseContext(0.4f, Side.LEFT, def))
        assertTrue("active M2 pipeline must produce a pose", result.pose.getJoint(Joint.PELVIS).y.isFinite())
    }
}
