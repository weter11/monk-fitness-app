package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class AirSquatPose : BaseSquatPose() {

    override val squatH = 65f
    override val pelvisXEnd = -25f
    override val leanAngleEnd = 0.45f
    override val armLeanEnd = 1.0f // 1.0 * 40f = 40f reach

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )
}
