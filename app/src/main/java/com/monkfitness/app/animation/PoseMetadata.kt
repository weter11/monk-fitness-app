package com.monkfitness.app.animation

enum class LoopMode {
    LOOP,
    HOLD,
    PING_PONG,
    ONCE
}

enum class FacingDirection {
    FRONT,
    LEFT,
    RIGHT
}

data class PoseMetadata(
    val camera: CameraDefinition = CameraDefinition.DEFAULT,
    val durationSeconds: Float = 3.0f,
    val loopMode: LoopMode = LoopMode.LOOP,
    val supportsMirroring: Boolean = false,
    val groundHeight: Float = 0f,
    val initialFacing: FacingDirection = FacingDirection.FRONT,
    val environment: EnvironmentDefinition = EnvironmentDefinition()
)
