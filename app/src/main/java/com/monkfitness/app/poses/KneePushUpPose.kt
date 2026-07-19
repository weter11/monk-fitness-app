package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class KneePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.8f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
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
        ),
        pivotType = PivotType.KNEES,
        supportContacts = setOf(
            SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND,
            SupportContact.LEFT_KNEE, SupportContact.RIGHT_KNEE
        ),
        exerciseFamily = "push-up",
        motionType = "Press",
        bodyOrientation = "Prone"
    )
}
