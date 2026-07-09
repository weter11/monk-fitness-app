package com.monkfitness.app.animation

import kotlin.math.*

data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val scale: Float,
    val depth: Float
)

class Camera(
    var yaw: Float = 1.19f,
    var pitch: Float = 0.22f,
    var zoom: Float = 1.3f,
    var focalLength: Float = 1000f,
    var centerX: Float = 0.5f,
    var centerY: Float = 0.7f
) {
    fun project(v: Vector3, width: Float, height: Float): ProjectedPoint {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val xr = v.x * cy + v.z * sy
        val zr = -v.x * sy + v.z * cy

        val cp = cos(pitch)
        val sp = sin(pitch)
        val y2 = v.y * cp + zr * sp
        val z2 = zr * cp - v.y * sp

        val sc = focalLength / (focalLength + z2)

        return ProjectedPoint(
            x = width * centerX + xr * sc * zoom,
            y = height * centerY - y2 * sc * zoom,
            scale = sc,
            depth = z2
        )
    }
}
