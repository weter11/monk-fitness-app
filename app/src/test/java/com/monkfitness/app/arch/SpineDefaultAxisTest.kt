package com.monkfitness.app.arch

import com.monkfitness.app.animation.BasePose
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.validation.poses.BaseValidationPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 (R13) — Spine Intent default-axis ownership.
 *
 * RFC §5 R13: "The default axis of Spine Intent is defined by the Skeleton Definition's
 * anatomical axes, not by call-site defaults; call-site defaults derive from the definition."
 * Plan §Phase 10: the axis-less `buildSpineCurve` default must resolve through the authoring
 * `PoseContext.definition`; an explicit `axis` argument remains an override.
 *
 * Scenarios:
 *  A. Custom definition with `anatomicalForward` = unit +X (raw-bit distinct from +Z): an
 *     axis-less build through the real authoring path must record `spineIntent.axis` exactly
 *     equal to the definition's forward — by raw bits, because an ownership test that admits
 *     epsilon cannot distinguish the definition's vector from a lookalike constant.
 *  B. Explicit axis override still wins under the same custom definition — this separates
 *     "the default changed" (A) from "the explicit argument is ignored" (B) as failure modes.
 *  C. `HumanSkeletonDefinition.anatomicalForward` is bit-equal to the historical hardcoded
 *     +Z call-site default (no-drift gate; the runtime golden suite is the other half).
 *  D. Build-lifecycle hygiene: pose instances are long-lived singletons reused across builds —
 *     the default must follow the CURRENT build's context definition, never leak a stale one.
 */
class SpineDefaultAxisTest {

    // --- Fixture definitions ------------------------------------------------------

    /**
     * Minimal custom definition: everything except the R13-owned anatomical forward mirrors
     * the human baseline (this phase adds exactly one definition-level property).
     */
    private class ForwardXDefinition : SkeletonDefinition {
        private val human = HumanSkeletonDefinition()
        override val torsoLength = human.torsoLength
        override val neckLength = human.neckLength
        override val thighLength = human.thighLength
        override val shinLength = human.shinLength
        override val footLength = human.footLength
        override val foot = human.foot
        override val upperArmLength = human.upperArmLength
        override val forearmLength = human.forearmLength
        override val hand = human.hand
        override val shoulderWidth = human.shoulderWidth
        override val hipWidth = human.hipWidth
        override val defaultCamera: CameraDefinition = human.defaultCamera
        override val armIKConstraint: IKConstraint = human.armIKConstraint
        override val legIKConstraint: IKConstraint = human.legIKConstraint
        override val anatomicalForward: Vector3 = Vector3(1f, 0f, 0f)
    }

    // --- Fixture probes over the real authoring paths ------------------------------

    /** Exercise family: axis-less `buildSpineCurve` inside the final `build` template. */
    private class AxislessSpineProbe : BasePose() {
        override fun onBuild(context: PoseContext): SkeletonPose {
            val nodes = SkeletonFactory.createStandardSkeleton()
            buildSpineCurve(nodes.pelvis, nodes.chest, 0.25f, -0.1f)
            return SkeletonPose.fromHierarchy(nodes.roots, jointsBuffer)
        }
    }

    /** Exercise family: explicit `axis` argument must keep winning. */
    private class ExplicitAxisProbe : BasePose() {
        override fun onBuild(context: PoseContext): SkeletonPose {
            val nodes = SkeletonFactory.createStandardSkeleton()
            buildSpineCurve(nodes.pelvis, nodes.chest, 0.25f, -0.1f, Vector3(0f, 1f, 0f))
            return SkeletonPose.fromHierarchy(nodes.roots, jointsBuffer)
        }
    }

    /** Validation family: the parallel authoring path, axis-less. */
    private class AxislessValidationSpineProbe : BaseValidationPose() {
        override fun buildStatic(definition: SkeletonDefinition): SkeletonPose {
            ensureHierarchy(definition)
            buildSpineCurve(pelvis!!, chest!!, 0.25f, -0.1f)
            return finalizePose()
        }
    }

    private val forwardX = ForwardXDefinition()
    private val human = HumanSkeletonDefinition()

    private fun assertExactAxis(expected: Vector3, actual: Vector3, context: String) {
        assertEquals("$context (x raw bits)", expected.x.toRawBits(), actual.x.toRawBits())
        assertEquals("$context (y raw bits)", expected.y.toRawBits(), actual.y.toRawBits())
        assertEquals("$context (z raw bits)", expected.z.toRawBits(), actual.z.toRawBits())
    }

    private fun assertJointIntentAxis(pose: SkeletonPose, joint: Joint, expected: Vector3, context: String) {
        val intent = pose.jointIntents.firstOrNull { it.joint == joint }
        assertTrue("$context: $joint intent must be recorded", intent != null)
        assertExactAxis(expected, intent!!.rotation.axis, "$context ($joint jointIntent axis)")
    }

    // --- A. Definition-owned spine default ----------------------------------------

    @Test
    fun exerciseAxislessSpineCurveDefaultsToDefinitionAnatomicalForward() {
        val pose = AxislessSpineProbe().build(PoseContext(0f, Side.LEFT, forwardX))
        // Anti-vacuity: the probe genuinely authored a spine curve through the real path.
        assertTrue("probe must record non-zero spine angles", pose.spineIntent.lumbarRad != 0f)
        assertExactAxis(
            forwardX.anatomicalForward, pose.spineIntent.axis,
            "axis-less buildSpineCurve must default to the definition's anatomicalForward"
        )
        // The historical hardcoded +Z must NOT silently win under a non-+Z definition.
        assertNotEquals(
            "default must not fall back to hardcoded +Z",
            1f.toRawBits(), pose.spineIntent.axis.z.toRawBits()
        )
        assertJointIntentAxis(pose, Joint.PELVIS, forwardX.anatomicalForward, "pelvis jointIntent")
        assertJointIntentAxis(pose, Joint.CHEST, forwardX.anatomicalForward, "chest jointIntent")
    }

    @Test
    fun validationAxislessSpineCurveDefaultsToDefinitionAnatomicalForward() {
        val pose = AxislessValidationSpineProbe().build(PoseContext(0f, Side.LEFT, forwardX))
        assertTrue("validation probe must record non-zero spine angles", pose.spineIntent.lumbarRad != 0f)
        assertExactAxis(
            forwardX.anatomicalForward, pose.spineIntent.axis,
            "validation axis-less buildSpineCurve must default to the definition's anatomicalForward"
        )
        assertNotEquals(
            "validation default must not fall back to hardcoded +Z",
            1f.toRawBits(), pose.spineIntent.axis.z.toRawBits()
        )
    }

    // --- B. Explicit axis remains an override --------------------------------------

    @Test
    fun explicitAxisArgumentOverridesDefinitionDefault() {
        val pose = ExplicitAxisProbe().build(PoseContext(0f, Side.LEFT, forwardX))
        assertExactAxis(
            Vector3(0f, 1f, 0f), pose.spineIntent.axis,
            "explicit axis must win over the definition-owned default"
        )
        // If an implementation ignored the explicit argument the default would show through
        // here (+X); if it kept the old hardcoded default, +Z. Both fail distinct assertions.
        assertNotEquals("explicit must not equal the +X definition default", 1f.toRawBits(), pose.spineIntent.axis.x.toRawBits())
        assertNotEquals("explicit must not equal the old +Z hardcoded default", 1f.toRawBits(), pose.spineIntent.axis.z.toRawBits())
    }

    // --- C. Standard-definition compatibility ---------------------------------------

    @Test
    fun humanDefinitionAnatomicalForwardEqualsHistoricalAxisZ() {
        // Plan §Phase 10 no-drift gate: verify forward equals the current hardcoded axisZ,
        // bit-exact, so standard definitions keep byte-identical axis-less authoring.
        assertExactAxis(Vector3(0f, 0f, 1f), human.anatomicalForward, "HumanSkeletonDefinition.anatomicalForward")
    }

    @Test
    fun standardDefinitionAxislessAuthoringKeepsAxisZDefault() {
        val pose = AxislessSpineProbe().build(PoseContext(0f, Side.LEFT, human))
        assertExactAxis(Vector3(0f, 0f, 1f), pose.spineIntent.axis, "standard-definition exercise axis-less default")
        val vpose = AxislessValidationSpineProbe().build(PoseContext(0f, Side.LEFT, human))
        assertExactAxis(Vector3(0f, 0f, 1f), vpose.spineIntent.axis, "standard-definition validation axis-less default")
    }

    // --- D. No stale-definition leak across rebuilds --------------------------------

    @Test
    fun definitionDefaultRebindsAcrossRebuilds() {
        // Long-lived pose singletons must resolve the default from the CURRENT build's
        // context.definition, never from a previous build's definition.
        val probe = AxislessSpineProbe()
        val first = probe.build(PoseContext(0f, Side.LEFT, forwardX))
        assertExactAxis(forwardX.anatomicalForward, first.spineIntent.axis, "first build (custom def)")
        val second = probe.build(PoseContext(0f, Side.LEFT, human))
        assertExactAxis(Vector3(0f, 0f, 1f), second.spineIntent.axis, "second build (human def) must not leak +X")
        val third = probe.build(PoseContext(0f, Side.LEFT, forwardX))
        assertExactAxis(forwardX.anatomicalForward, third.spineIntent.axis, "third build (custom def) must rebind")
    }
}
