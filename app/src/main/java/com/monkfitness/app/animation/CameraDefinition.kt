package com.monkfitness.app.animation

data class CameraDefinition(
    val defaultYaw: Float = 1.19f,
    val defaultPitch: Float = 0.22f,
    val defaultZoom: Float = 1.3f,
    val minYaw: Float = 0.5f,
    val maxYaw: Float = 2.0f,
    val allowRotation: Boolean = true,
    val allowZoom: Boolean = true
) {
    companion object {
        val DEFAULT = CameraDefinition()
    }
}
