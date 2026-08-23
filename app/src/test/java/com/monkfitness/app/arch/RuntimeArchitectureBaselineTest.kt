package com.monkfitness.app.arch

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.ArmCirclesPose
import com.monkfitness.app.poses.SquatPose
import com.monkfitness.app.poses.StandardPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0 of IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md — baseline & characterization harness.
 *
 * NOT an R-rule enforcement phase: this is the regression net every later implementation
 * phase is checked against. Values below are captured from CURRENT behavior, including any
 * known-defective readings (plan source-verification items V1/V2/V12); they are consciously
 * updated only by the phases that intentionally change them.
 *
 * Fixtures (context: progress=0.5, side=RIGHT, definition=SkeletonDefinition.DEFAULT_ADULT,
 * dt=1/60, cycleDuration=2500):
 *  - PUSHUP:        StandardPushUpPose          — contact pose (hands/toes support)
 *  - SQUAT_POSTURE: SquatPose + IntentBuilder.posture(STANDING) — posture-driven
 *                   (solver entered via `postureDriven`, no contacts)
 *  - ARMCIRCLES:    ArmCirclesPose              — contact-less CUSTOM (solver skipped;
 *                   residual stamps come from the Finalizer's enforceContactNoMove path)
 */
class RuntimeArchitectureBaselineTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float = 0.5f) = PoseContext(
        progress = progress,
        side = Side.RIGHT,
        definition = definition,
        deltaTime = 1f / 60f,
        cycleDuration = 2500f
    )

    /** Production squat authoring with a non-CUSTOM posture intent, so the solver enters via
     *  the posture-driven branch. Intent re-declared through the sole-mutator surface
     *  ([SkeletonPose.IntentBuilder]) because intent setters are `private set` outside the
     *  animation package and concrete pose classes are final. */
    private fun postureDrivenFrame(): SkeletonPose {
        val pose = SquatPose().build(context())
        SkeletonPose.IntentBuilder(pose).posture(PostureIntent.Kind.STANDING)
        return frame(pose)
    }

    private fun frame(builder: PoseBuilder): SkeletonPose {
        val pipeline = SkeletonPipeline(definition)
        return pipeline.produceFrame(builder.build(context())).pose
    }

    private fun frame(pose: SkeletonPose): SkeletonPose {
        val pipeline = SkeletonPipeline(definition)
        return pipeline.produceFrame(pose).pose
    }

    // ------------------------------------------------------------------
    // Golden values — captured from unmodified source at Phase 0.
    // ------------------------------------------------------------------

    private object PushUpGolden {
        val pelvis = floatArrayOf(60.00885f, 53.970253f, 0.0f)
        val handA = floatArrayOf(-6.0209026f, 55.639805f, -68.99999f)
        val handP = floatArrayOf(-6.0209026f, 55.639805f, 68.99999f)
        val ankleF = floatArrayOf(268.00098f, 25.0f, -22.0f)
        val ankleB = floatArrayOf(268.00098f, 25.0f, 22.0f)
        val toeF = floatArrayOf(285.57257f, 42.5716f, -22.0f)
        val toeB = floatArrayOf(285.57257f, 42.5716f, 22.0f)
        const val maxIkClampAmount = 0.0f
        const val straightIntentDropped = false
        const val boneLengthsVerified = true
        const val rootTranslationDelta = 0.0f
        const val rootRotationDelta = 0.0f
        const val bilateralSymmetryDelta = 0.0f
        const val bilateralOppositeBend = false
        val hipRomExcursion = mapOf("HIP_B" to 82.07055f, "HIP_F" to 82.07055f)
        val hipRomSagittal = mapOf("HIP_B" to 82.07053f, "HIP_F" to 82.07053f)
        val hipRomFrontal = mapOf("HIP_B" to 0.0f, "HIP_F" to -0.0f)
        val hipRomAxial = mapOf("HIP_B" to 0.0f, "HIP_F" to -0.0f)
    }

    private object SquatPostureGolden {
        val pelvis = floatArrayOf(0.0f, 235.0f, 0.0f)
        val handA = floatArrayOf(8.883305f, 342.45065f, -81.831375f)
        val handP = floatArrayOf(8.883305f, 342.45065f, 81.831375f)
        val ankleF = floatArrayOf(-7.6293945E-6f, 115.000015f, -33.0f)
        val ankleB = floatArrayOf(-7.6293945E-6f, 115.000015f, 33.0f)
        val toeF = floatArrayOf(23.105614f, 115.000015f, -23.853815f)
        val toeB = floatArrayOf(23.105614f, 115.000015f, 23.853815f)
        const val maxIkClampAmount = 30.0f
        const val straightIntentDropped = false
        const val boneLengthsVerified = true
        const val rootTranslationDelta = 90.0f
        const val rootRotationDelta = 0.0f
        const val bilateralSymmetryDelta = 0.0f
        const val bilateralOppositeBend = false
        val hipRomExcursion = mapOf("HIP_B" to 62.003006f, "HIP_F" to 62.003006f)
        val hipRomSagittal = mapOf("HIP_B" to 59.123177f, "HIP_F" to 59.123177f)
        val hipRomFrontal = mapOf("HIP_B" to 11.969091f, "HIP_F" to 11.969091f)
        val hipRomAxial = mapOf("HIP_B" to 0.0f, "HIP_F" to -0.0f)
    }

    private object ArmCirclesGolden {
        val pelvis = floatArrayOf(0.0f, 235.0f, 0.0f)
        val handA = floatArrayOf(-68.5891f, 480.45483f, -51.338505f)
        val handP = floatArrayOf(-68.5891f, 480.45483f, 51.338505f)
        val ankleF = floatArrayOf(0.0f, 288.7445f, -37.76506f)
        val ankleB = floatArrayOf(0.0f, 288.7445f, 37.76506f)
        val toeF = floatArrayOf(4.7040253f, 297.8015f, -15.107492f)
        val toeB = floatArrayOf(4.7040253f, 297.8015f, 15.107492f)
        const val maxIkClampAmount = 124.935135f
        const val straightIntentDropped = false
        const val boneLengthsVerified = true
        const val rootTranslationDelta = 235.0f
        const val rootRotationDelta = 0.0f
        const val bilateralSymmetryDelta = 0.0f
        const val bilateralOppositeBend = false
        val hipRomExcursion = mapOf("HIP_B" to 114.73246f, "HIP_F" to 114.73246f)
        val hipRomSagittal = mapOf("HIP_B" to 59.224636f, "HIP_F" to 59.224636f)
        val hipRomFrontal = mapOf("HIP_B" to 17.131237f, "HIP_F" to 17.131237f)
        val hipRomAxial = mapOf("HIP_B" to 0.0f, "HIP_F" to -0.0f)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    @Test
    fun contactPoseStandardPushUpMatchesBaseline() {
        assertGolden("PUSHUP", frame(StandardPushUpPose()), PushUpGolden)
    }

    @Test
    fun postureDrivenSquatMatchesBaseline() {
        assertGolden("SQUAT_POSTURE", postureDrivenFrame(), SquatPostureGolden)
    }

    @Test
    fun contactlessCustomArmCirclesMatchesBaseline() {
        assertGolden("ARMCIRCLES", frame(ArmCirclesPose()), ArmCirclesGolden)
    }

    // ------------------------------------------------------------------
    // Assertion helpers — exact float equality (no delta), per Phase 0 spec.
    // ------------------------------------------------------------------

    private fun assertJoint(label: String, pose: SkeletonPose, joint: Joint, expected: FloatArray) {
        val v = pose.getJoint(joint)
        assertEquals("$label.$joint.x", expected[0], v.x, 0f)
        assertEquals("$label.$joint.y", expected[1], v.y, 0f)
        assertEquals("$label.$joint.z", expected[2], v.z, 0f)
    }

    private fun assertGolden(label: String, pose: SkeletonPose, golden: Any) {
        assertJoint(label, pose, Joint.PELVIS, read(golden, "pelvis"))
        assertJoint(label, pose, Joint.HAND_A, read(golden, "handA"))
        assertJoint(label, pose, Joint.HAND_P, read(golden, "handP"))
        assertJoint(label, pose, Joint.ANKLE_F, read(golden, "ankleF"))
        assertJoint(label, pose, Joint.ANKLE_B, read(golden, "ankleB"))
        assertJoint(label, pose, Joint.TOE_F, read(golden, "toeF"))
        assertJoint(label, pose, Joint.TOE_B, read(golden, "toeB"))

        assertEquals(
            "$label.maxIkClampAmount",
            readFloat(golden, "maxIkClampAmount"),
            pose.maxIkClampAmount,
            0f
        )
        assertEquals(
            "$label.straightIntentDropped",
            readBoolean(golden, "straightIntentDropped"),
            pose.straightIntentDropped
        )
        assertEquals(
            "$label.boneLengthsVerified",
            readBoolean(golden, "boneLengthsVerified"),
            pose.boneLengthsVerified
        )
        assertEquals(
            "$label.rootTranslationDelta",
            readFloat(golden, "rootTranslationDelta"),
            pose.rootTranslationDelta,
            0f
        )
        assertEquals(
            "$label.rootRotationDelta",
            readFloat(golden, "rootRotationDelta"),
            pose.rootRotationDelta,
            0f
        )
        assertEquals(
            "$label.bilateralSymmetryDelta",
            readFloat(golden, "bilateralSymmetryDelta"),
            pose.bilateralSymmetryDelta,
            0f
        )
        assertEquals(
            "$label.bilateralOppositeBend",
            readBoolean(golden, "bilateralOppositeBend"),
            pose.bilateralOppositeBend
        )

        val expectedKeys = setOf("HIP_B", "HIP_F")
        assertEquals(
            "$label.hipRomKeys",
            expectedKeys,
            pose.hipRomStamps.keys.map { it.name }.toSet()
        )
        for (key in expectedKeys) {
            val stamp = pose.hipRomStamps[Joint.valueOf(key)]!!
            assertEquals("$label.hipRom.$key.excursion", readMapFloat(golden, "hipRomExcursion", key), stamp.excursionDegrees, 0f)
            assertEquals("$label.hipRom.$key.sagittal", readMapFloat(golden, "hipRomSagittal", key), stamp.sagittalDegrees, 0f)
            assertEquals("$label.hipRom.$key.frontal", readMapFloat(golden, "hipRomFrontal", key), stamp.frontalDegrees, 0f)
            assertEquals("$label.hipRom.$key.axial", readMapFloat(golden, "hipRomAxial", key), stamp.axialDegrees, 0f)
        }
    }

    private fun read(owner: Any, name: String): FloatArray =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as FloatArray

    private fun readFloat(owner: Any, name: String): Float =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(owner)

    private fun readBoolean(owner: Any, name: String): Boolean =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(owner)

    private fun readMapFloat(owner: Any, name: String, key: String): Float {
        @Suppress("UNCHECKED_CAST")
        val map = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as Map<String, Float>
        return map.getValue(key)
    }
}
