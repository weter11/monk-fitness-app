package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Knee push-up — performed from a kneeling plank: knees on the
 * floor, shins angled up, hands + knees as the four supports.
 * Uses the knee-pivot branch of BasePushUpPose (PivotType.KNEES).
 * The planted knee reads as on the mat via the foot/ankle
 * articulation; the hip stays flexed to keep the trunk level.
 */
class KneePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.8f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.KNEES,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_KNEE),
                SupportContact(SupportPoint.RIGHT_KNEE)
            )
        )
    )
}
