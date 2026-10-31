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
 * Baseline & characterization harness — NOT an enforcement suite: this is the regression net
 * every later change is checked against. Values are captured from CURRENT behavior, including
 * known-defective readings, and are consciously updated only by changes that intentionally
 * alter them.
 *
 * Fixtures (progress=0.5, side=RIGHT, definition=SkeletonDefinition.DEFAULT_ADULT, dt=1/60,
 * cycleDuration=2500):
 *  - PUSHUP:        StandardPushUpPose          — contact pose (hands/toes support)
 *  - SQUAT_POSTURE: SquatPose + IntentBuilder.posture(STANDING) — posture-driven
 *                   (solver entered via `postureDriven`, no contacts)
 *  - ARMCIRCLES:    ArmCirclesPose              — posture-driven: declares STANDING in-build,
 *                   so the solver RUNS via the postureDriven branch despite zero Contact
 *                   Declarations (contact guard stays empty)
 *
 * Intent-timing note (a characterization caveat, not an architectural invariant).
 *
 * SQUAT_POSTURE writes its posture intent AFTER [SquatPose.build] returns (via
 * [SkeletonPose.IntentBuilder.posture], the sole mutation surface outside the animation
 * package), because the current production squat path declares CUSTOM in-build and never
 * enters the postureDriven branch on its own. This is a test-side workaround.
 *
 * Post-build writes are behaviorally equivalent to in-build authoring under the CURRENT
 * consumer set: `postureIntent` has exactly three production read sites, all evaluated at or
 * after pipeline injection — SkeletonPipeline.produceFrame (solver-entry gate) and
 * ConstraintSolver (solve-time gate and posture regularization). No `build()` implementation
 * reads it, so a write anywhere in the pre-injection window is indistinguishable downstream.
 *
 * Scope: this holds for those consumers only. A future build-time or pre-injection reader of
 * `postureIntent` invalidates this fixture's equivalence argument.
 *
 * Golden-update policy for known-compromised stamp families.
 *
 * Transform goldens (7 joints per fixture) are the PRIMARY regression baseline. The following
 * Validation Stamp families are characterization data, NOT frozen semantic truth — they encode
 * current behavior including known defects, and legitimate corrections are EXPECTED to change
 * them (update the golden in the same change and name the responsible cause):
 *
 *  - `straightIntentDropped` (all fixtures, currently `false`): currently vacuous in
 *    production — no production writer of the straight intent exists, so the assertion cannot
 *    fail today and pins nothing about engine behavior.
 *  - `boneLengthsVerified` (all fixtures, currently `true`): the read sits downstream of the
 *    solver's erase-and-re-AND of the stamp, so the value coincides with correct behavior only
 *    because all fixture bakes pass.
 *  - `rootTranslationDelta` on ARMCIRCLES (currently `235.0`): produced by the
 *    ConstraintSolver's root-displacement computation, reached because this fixture enters the
 *    solver via its in-build STANDING posture — NOT by the Finalizer's contact strengthen, whose
 *    loop runs zero iterations against an empty contact guard. Whether that strengthen write
 *    site conflicts with the sole-producer rule remains OPEN clarification debt. The value
 *    characterizes current displacement behavior; it is NOT certified as intended semantics.
 *
 * Transform goldens must only change when the pipeline's numeric output intentionally changes.
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
     *  the posture-driven branch. Intent is re-declared through [SkeletonPose.IntentBuilder]
     *  because intent setters are `private set` outside the animation package. */
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

    private object PushUpGolden {
        val pelvis = floatArrayOf(60.00885f, 53.970253f, 0.0f)
        // This fixture publishes the COLD FIRST frame of a fresh pose on a fresh pipeline, so the
        // arm chain realizes in the trunk frame the pose itself declares rather than a
        // pelvis-only frame replaced after realization: handA/handP sit on their authored target
        // instead of floating 55.6 units above the floor. Pelvis/feet and the SETTLED rep are
        // unchanged (see ColdFrameLimbRealizationTest).
        val handA = floatArrayOf(-58.321594f, 7.6293945E-6f, -68.99999f)
        val handP = floatArrayOf(-58.321594f, 7.6293945E-6f, 68.99999f)
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
        // `SquatPose`'s authored arm target is projected onto its chain's reachable annulus: the
        // declared hand target sits `10.2` u from the shoulder against `minReach = 40.134` at build
        // time, so the solver used to relocate the hand onto the minimum-flexion circle
        // (`maxIkClampAmount` `30.000000`). The projection margin (`REACH_MARGIN = 1e-4` of the
        // chain's span, `0.004` u here) moves handA/handP by `<= 0.0036` u and takes the stamp to
        // `0.000000`; the leg target is INSIDE its band here (`120.5` of `[56.009, 205.800]`), so
        // every other golden — pelvis, ankleF/B, toeF/B, other stamps, hipRom maps — is
        // byte-identical. Cold frame: the same frame the pose's own build publishes.
        val pelvis = floatArrayOf(0.0f, 235.0f, 0.0f)
        val handA = floatArrayOf(8.881805f, 342.44965f, -81.834946f)
        val handP = floatArrayOf(8.881805f, 342.44965f, 81.834946f)
        val ankleF = floatArrayOf(-7.6293945E-6f, 115.000015f, -33.0f)
        val ankleB = floatArrayOf(-7.6293945E-6f, 115.000015f, 33.0f)
        val toeF = floatArrayOf(23.105614f, 115.000015f, -23.853815f)
        val toeB = floatArrayOf(23.105614f, 115.000015f, 23.853815f)
        const val maxIkClampAmount = 0.0f
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
        // Two pose-side corrections (no engine change) move these numbers: the pose authors its
        // limb targets in the chain root's own frame instead of a floor-anchored one, so the solver
        // no longer relocates every effector upward (ankleF/B and toeF/B land at 29.24701, and
        // maxIkClampAmount 124.935135 → 0.047027588 — the residual float-level stance-width term,
        // inside the engine's own 0.1 reachability flag); and the pose declares its planted feet on
        // the canonical support channel, so the toes sit level with the ankles. The fixture's ROLE
        // is unchanged: still the posture-driven, zero-Contact-Declaration representative — a
        // Support Declaration is Frame Context, not a Contact Declaration.
        val pelvis = floatArrayOf(0.0f, 235.0f, 0.0f)
        val handA = floatArrayOf(-128.47998f, 355.0f, -56.0f)
        val handP = floatArrayOf(-128.47998f, 355.0f, 56.0f)
        val ankleF = floatArrayOf(0.0f, 29.24701f, -26.398996f)
        val ankleB = floatArrayOf(0.0f, 29.24701f, 26.398996f)
        val toeF = floatArrayOf(24.849743f, 29.24701f, -26.28621f)
        val toeB = floatArrayOf(24.849743f, 29.24701f, 26.28621f)
        const val maxIkClampAmount = 0.047027588f
        const val straightIntentDropped = false
        const val boneLengthsVerified = true
        const val rootTranslationDelta = 235.0f
        const val rootRotationDelta = 0.0f
        const val bilateralSymmetryDelta = 0.0f
        const val bilateralOppositeBend = false
        val hipRomExcursion = mapOf("HIP_B" to 11.0318f, "HIP_F" to 11.0318f)
        val hipRomSagittal = mapOf("HIP_B" to 10.516254f, "HIP_F" to 10.516254f)
        val hipRomFrontal = mapOf("HIP_B" to 3.2956252f, "HIP_F" to 3.2956252f)
        val hipRomAxial = mapOf("HIP_B" to 0.0f, "HIP_F" to -0.0f)
    }

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

    // Assertion helpers — exact float equality (no delta): this is a characterization baseline.

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
