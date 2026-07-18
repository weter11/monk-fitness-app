package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Military push-up — hands directly under the shoulders (grip ~ shoulder
 * width) with a slightly forward hand anchor, emphasising a rigid,
 * bodyline-strict plank. The tight grip keeps the elbows close to the
 * ribs.
 */
class MilitaryPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.0f
    override val handAnchorXOffset = 5f

    // Elbow-bend plane points nearly straight back (tight, close-to-body).
    override val poleA = Vector3(1f, 0.2f, -0.1f)
    override val poleP = Vector3(1f, 0.2f, 0.1f)

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
