package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class MilitaryPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.0f
    override val handAnchorXOffset = 5f
    override val poleA = Vector3(1f, 0.2f, -0.1f)
    override val poleP = Vector3(1f, 0.2f, 0.1f)
    override val handDirA: Vector3 = Vector3(-1f, 0f, 0f).normalize()
    override val handDirP: Vector3 = Vector3(-1f, 0f, 0f).normalize()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )
}
