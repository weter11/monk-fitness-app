package com.monkfitness.app.arch

import com.monkfitness.app.BuildConfig
import com.monkfitness.app.animation.EnvironmentDefinition
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.poses.StandardPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 1 of IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md — R8 Runtime Context Injection
 * single-point verification.
 *
 * What this suite pins:
 *  1. Both [SkeletonPipeline.produceFrame] overloads inject through the single
 *     `injectRuntimeContext` point immediately before the stage chain, each preserving its
 *     own source: the renderer overload forwards caller-supplied arguments verbatim; the
 *     builder overload derives them from `pose.metadata`.
 *  2. The two paths are behaviorally identical when fed the same model (all-joint
 *     tolerance-based equivalence, mirroring the SkeletonPipelineM0Test convention).
 *  3. No stage mutates the context after injection (current-behavior contract observed
 *     through the public API; the debug-build fail-fast itself lives in
 *     `SkeletonPipeline.runStages` / `RuntimeContextSnapshot`). The enforcement's negative
 *     path (violation detection) is NOT covered by committed tests; it was validated by
 *     out-of-band fault injection during the Phase-1 audit (ad-hoc audit evidence, not
 *     committed regression coverage).
 *
 * This test changes no production semantics; it verifies the extraction preserved them.
 */
class RuntimeContextInjectionTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float) = PoseContext(progress, Side.LEFT, def)

    /** The support model the builder overload derives from `pose.metadata`. */
    private fun derivedEnvironment(builder: PoseBuilder): EnvironmentDefinition =
        builder.metadata.environment

    private fun derivedSupportPoints(builder: PoseBuilder): Set<SupportPoint> =
        builder.metadata.support.contacts.map { it.point }.toSet()

    @Test
    fun rendererOverloadInjectsCallerSuppliedModelEndState() {
        val env = EnvironmentDefinition() // non-default ground level distinguishes it from the field default usage below
        val points = setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_TOES)

        val pipeline = SkeletonPipeline(def)
        val pose = StandardPushUpPose().build(context(0.5f))
        // Pre-injection state: a freshly built pose carries no stamped context yet.
        assertEquals(EnvironmentDefinition(), pose.environment)
        assertTrue("freshly built pose must have empty supportedPoints", pose.supportedPoints.isEmpty())

        val result = pipeline.produceFrame(pose, env, points)

        assertNull("renderer overload must not attach a report", result.report)
        assertTrue("environment must be injected by reference", result.pose.environment === env)
        assertEquals("support points must be injected verbatim", points, result.pose.supportedPoints)
    }

    @Test
    fun builderOverloadInjectsMetadataDerivedModelEndState() {
        val builder = StandardPushUpPose()
        val pipeline = SkeletonPipeline(def)

        val result = pipeline.produceFrame(builder, context(0.5f))

        assertNull("builder overload must not attach a report", result.report)
        assertEquals(
            "environment must be derived from metadata",
            derivedEnvironment(builder),
            result.pose.environment
        )
        assertEquals(
            "support points must be derived from Contact Declarations",
            derivedSupportPoints(builder),
            result.pose.supportedPoints
        )
    }

    @Test
    fun builderAndExplicitValuePathsProduceEquivalentFrames() {
        var maxDev = 0f
        var worst = ""
        val builderA = StandardPushUpPose()
        val pipeline = SkeletonPipeline(def)

        for (i in 0..20) {
            val p = i / 20f
            val fromBuilder = pipeline.produceFrame(builderA, context(p)).pose

            // Same model, fed explicitly through the renderer overload on an independent pose.
            val builderB = StandardPushUpPose()
            val built = builderB.build(context(p))
            val fromValue = pipeline.produceFrame(
                built,
                derivedEnvironment(builderB),
                derivedSupportPoints(builderB)
            ).pose

            for (j in Joint.values()) {
                val a = fromBuilder.getJoint(j)
                val b = fromValue.getJoint(j)
                val d = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))
                if (d > maxDev) { maxDev = d; worst = "progress=$p joint=$j" }
            }
        }
        assertEquals("builder path deviates from explicit value path at $worst", 0f, maxDev, 1e-4f)
    }

    @Test
    fun defaultArgValuePathYieldsDefaultContext() {
        val pipeline = SkeletonPipeline(def)
        val pose = StandardPushUpPose().build(context(0.5f))

        val result = pipeline.produceFrame(pose)

        assertEquals("default environment must flow through the single injection point",
            EnvironmentDefinition(), result.pose.environment)
        assertTrue("default (empty) support model must flow through the single injection point",
            result.pose.supportedPoints.isEmpty())
    }

    @Test
    fun noStageMutatesInjectedContextOnInputOrOutputPose() {
        val env = EnvironmentDefinition()
        val points = setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_TOES)
        val pipeline = SkeletonPipeline(def)
        val builder = StandardPushUpPose()

        for (i in 0..10) {
            val p = i / 10f
            val built = builder.build(context(p))
            val result = pipeline.produceFrame(built, env, points)

            // The pose that ENTERED the chain still carries exactly the injected context:
            // no stage rewrote it post-injection (the R8 invariant, observed externally).
            assertTrue("input pose environment rewritten at progress=$p", result.pose.environment === built.environment && built.environment === env)
            assertEquals("input pose supportedPoints rewritten at progress=$p", points, built.supportedPoints)

            // The finalized output carries the same frozen context.
            assertEquals(points, result.pose.supportedPoints)
        }
    }

    @Test
    fun r8EnforcementIsActiveInUnitTestBuilds() {
        // The debug gate compiles the enforcement out of release builds; unit tests run under
        // BuildConfig.DEBUG == true, so the checks above execute against the guarded chain.
        assertTrue("unit tests must run with BuildConfig.DEBUG == true for R8 coverage",
            BuildConfig.DEBUG)
    }
}
