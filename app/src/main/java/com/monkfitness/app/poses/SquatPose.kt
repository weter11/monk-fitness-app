package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class SquatPose : BaseSquatPose() {

    override val squatH = 55f // 40f + 15f ankle height
    override val pelvisXEnd = 0f
    override val leanAngleEnd = 0.40f
    override val armLeanEnd = 1.0f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(
            defaultYaw = 1.19f,
            defaultPitch = 0.22f,
            defaultZoom = 1.2f
        ),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SLOW_DOWN_FAST_UP,
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )
}
