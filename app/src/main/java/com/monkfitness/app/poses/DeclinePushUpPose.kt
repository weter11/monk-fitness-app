package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class DeclinePushUpPose : BasePushUpPose() {

    private val boxHeight = 40f

    override val gripWidthMultiplier = 1.5f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                BoxProp(
                    center = Vector3(60f + 210f * cos(asin(((60f - 65f) / 210f).coerceIn(-1f,1f))) + 10f, boxHeight / 2f, 0f),
                    width = 70f,
                    height = boxHeight,
                    depth = 60f
                )
            )
        ),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            ),
            supportHeight = boxHeight
        )
    )
}
