package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Standard Push-Up — natural-looking standard-width push-up.
 *
 * Inherits the proven, byte-identical arm/leg IK from [BasePushUpPose] (its exact shoulder-seed +
 * pole round-trip, which the engine's B1 invariant requires). This subclass only tunes the knobs
 * the shared base exposes for a natural look: a shoulder-width grip and slightly tucked elbow poles
 * so the arms don't flare straight out like a rigid plank.
 *
 * Note: deeper naturalism (neutral spine curve, scapular protraction, planted-toe / flat-palm
 * articulation) was prototyped but breaks the production B1 byte-identity invariant between the
 * authored bake and the engine-owned IkStage re-solve for the prone push-up (the base's
 * rotAround(chest) shoulder seed + W1 wrist/ankle derivation is tightly coupled for this pose).
 * That naturalism belongs in an engine change (seed IkStage from the real shoulder node), not in
 * the pose file. See commit history / the diagnostic in StandardPushUpDiagTest for the evidence.
 */
class StandardPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.5f

    // Elbows tuck slightly back-and-down (natural) rather than flaring straight out.
    override val poleA = Vector3(1f, 0.5f, -0.85f)
    override val poleP = Vector3(1f, 0.5f, 0.85f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = emptyList()
        ),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        )
    )
}
