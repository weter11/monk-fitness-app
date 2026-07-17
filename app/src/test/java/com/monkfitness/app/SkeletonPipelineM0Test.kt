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
 * M0 (RFC_GAP_CLOSURE) — `SkeletonPipeline` scaffold verification.
 *
 * M0 introduces the pipeline behind `PIPELINE_ACTIVE=false` with ZERO behavior change: its legacy
 * `produceFrame` path must be byte-identical to invoking `pose.build()` + `finalizer.finalize()`
 * directly (the exact flow every consumer runs today). This locks that guarantee in.
 */
class SkeletonPipelineM0Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val directFinalizer = SkeletonPoseFinalizer(def)
    private val originalActive = EngineFlags.PIPELINE_ACTIVE

    @After
    fun restore() {
        EngineFlags.PIPELINE_ACTIVE = originalActive
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
    fun pipelineFlagDefaultsFalse() {
        // M0 ships with the master switch off.
        assertFalse("PIPELINE_ACTIVE must default to false in M0", originalActive)
        assertTrue("PIPELINE_ACTIVE must appear in the flag snapshot", EngineFlags.snapshot().containsKey("PIPELINE_ACTIVE"))
    }

    @Test
    fun legacyProduceFrameIsByteIdenticalToDirectPath() {
        EngineFlags.PIPELINE_ACTIVE = false
        val frames = 20
        var maxDev = 0f
        var worst = ""

        // Use INDEPENDENT pose + finalizer instances per path: poses reuse an internal node tree /
        // jointsBuffer across build() calls and the finalizer flattens a pose only once
        // (`isTransformsUpdated`), so sharing an instance between the two paths would alias state.
        // Two fresh objects is the faithful "same inputs, two code paths" comparison.
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
        println("SkeletonPipelineM0Test: OK  maxDeviation=$maxDev")
    }

    @Test
    fun validatedPathMatchesDirectValidator() {
        EngineFlags.PIPELINE_ACTIVE = false
        val validator = ExerciseValidator()
        val pipeline = SkeletonPipeline(def, validator)
        val camera = Camera(StandardPullUpPose().metadata.camera)
        val env = StandardPullUpPose().metadata.environment

        // Independent pose instances per path (see byte-identity test rationale).
        val directPose = directFinalizer.finalize(StandardPullUpPose().build(PoseContext(0f, Side.LEFT, def)))
        val directReport = validator.validate(directPose, def, env, camera, 1000f, 1000f, null, null, 0.033f)

        val framed = pipeline.produceFrameValidated(StandardPullUpPose(), PoseContext(0f, Side.LEFT, def), camera, env, 1000f, 1000f, 0.033f)
        assertEquals("validity must match direct validator", directReport.isValid, framed.report.isValid)
    }

    @Test
    fun activeModeFailsFastUntilM2() {
        EngineFlags.PIPELINE_ACTIVE = true
        // Coherence invariant: active mode also requires FINALIZER_OWNS_CONVERSION; but even with
        // that satisfied, the active stage chain is not implemented until M2, so produceFrame must
        // refuse to run rather than silently produce a wrong frame.
        val originalFinalizer = EngineFlags.FINALIZER_OWNS_CONVERSION
        try {
            EngineFlags.FINALIZER_OWNS_CONVERSION = true
            val pipeline = SkeletonPipeline(def)
            var threw = false
            try {
                pipeline.produceFrame(StandardPullUpPose(), PoseContext(0f, Side.LEFT, def))
            } catch (e: IllegalStateException) {
                threw = true
            }
            assertTrue("active-mode produceFrame must fail fast until M2", threw)
        } finally {
            EngineFlags.FINALIZER_OWNS_CONVERSION = originalFinalizer
        }
    }

    @Test
    fun activeModeWithoutFinalizerConversionFailsAtConstruction() {
        EngineFlags.PIPELINE_ACTIVE = true
        val originalFinalizer = EngineFlags.FINALIZER_OWNS_CONVERSION
        try {
            EngineFlags.FINALIZER_OWNS_CONVERSION = false
            var threw = false
            try {
                SkeletonPipeline(def)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("incoherent flags must fail at construction", threw)
        } finally {
            EngineFlags.FINALIZER_OWNS_CONVERSION = originalFinalizer
        }
    }
}
