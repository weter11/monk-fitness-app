package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Camera is a generic mathematical projection utility.
 */
class Camera(
    var yaw: Float = 1.19f,
    var pitch: Float = 0.22f,
    var zoom: Float = 1.3f,
    var focalLength: Float = 1000f,
    var centerX: Float = 0.5f,
    var centerY: Float = 0.7f
) {
    constructor(definition: CameraDefinition) : this(
        yaw = definition.defaultYaw,
        pitch = definition.defaultPitch,
        zoom = definition.defaultZoom
    )

    /**
     * Projects a 3D world-space vector into the provided ProjectedPoint buffer.
     */
    fun project(v: Vector3, width: Float, height: Float, buffer: ProjectedPoint) {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val xr = v.x * cy + v.z * sy
        val zr = -v.x * sy + v.z * cy

        val cp = cos(pitch)
        val sp = sin(pitch)
        val y2 = v.y * cp + zr * sp
        val z2 = zr * cp - v.y * sp

        val sc = focalLength / (focalLength + z2)

        buffer.update(
            x = width * centerX + xr * sc * zoom,
            y = height * centerY - y2 * sc * zoom,
            depth = z2,
            scale = sc
        )
    }
}
