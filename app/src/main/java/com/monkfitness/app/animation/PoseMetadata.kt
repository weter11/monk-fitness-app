package com.monkfitness.app.animation

data class PoseMetadata(
    val camera: CameraDefinition = CameraDefinition.DEFAULT,
    val cycleDurationMs: Int = 3000,
    val loopMode: LoopMode = LoopMode.LOOP,
    val supportsMirroring: Boolean = true,
    val groundHeight: Float = 0f,
    val initialFacing: FacingDirection = FacingDirection.FRONT
) {
    companion object {
        val DEFAULT = PoseMetadata()
    }
}
