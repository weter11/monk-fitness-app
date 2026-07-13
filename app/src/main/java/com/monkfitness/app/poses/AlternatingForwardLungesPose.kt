package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class AlternatingForwardLungesPose : BaseLungePose() {

    override val stepSize = 85f
    override val stepDirection = 1f
    override val lateralOffsetMultiplier = 0f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 4.0f, loopMode = LoopMode.LOOP,
        // LINEAR curve ensures our internal sine wave governs the acceleration flawlessly
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )
}
