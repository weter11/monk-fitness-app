package com.monkfitness.app.animation

data class CameraDefinition(
    val defaultYaw: Float = 1.19f,
    val defaultPitch: Float = 0.22f,
    val defaultZoom: Float = 1.3f,
    val minYaw: Float = -1.5708f, // -90 degrees
    val maxYaw: Float = 1.5708f,  // +90 degrees
    val minZoom: Float = 0.5f,
    val maxZoom: Float = 3.0f,
    val allowRotation: Boolean = true,
    val allowZoom: Boolean = true
) {
    companion object {
        val DEFAULT = CameraDefinition()
    }
}
