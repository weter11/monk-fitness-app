package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Diamond push-up — hands brought together under the sternum (grip
 * multiplier ~0.1) so the thumbs/indices form a diamond. Heavy on the
 * triceps and inner chest; the narrow base angles the elbows up-and-in.
 */
class DiamondPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 0.1f

    // Elbow-bend plane points out-and-up (arms hug toward the midline).
    override val poleA = Vector3(0.5f, 0.5f, -2.0f)
    override val poleP = Vector3(0.5f, 0.5f, 2.0f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
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
