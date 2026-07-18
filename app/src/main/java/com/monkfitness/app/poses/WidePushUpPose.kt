package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Wide push-up — hands wider than shoulders, opening the chest and
 * loading the serratus / outer pec. The wider grip spreads the
 * shoulder line; elbows track out to the sides via the IK pole.
 */
class WidePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.9f

    // Elbow-bend plane points out-and-up so the arms flare wide.
    override val poleA = Vector3(0.2f, 0.8f, -2.0f)
    override val poleP = Vector3(0.2f, 0.8f, 2.0f)

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
