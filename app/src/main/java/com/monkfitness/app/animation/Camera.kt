package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Mathematical projection utility.
 *
 * The viewpoint (`yaw`/`pitch`/`focalLength`, anchor `centerX`/`centerY`) is authored by the pose
 * through `PoseMetadata.camera`. The frame — the zoom the projection lands at — is not authorable:
 * a pose cannot know the surface being drawn, so [CameraFraming] derives it from the bounds of the
 * frame actually drawn and writes the fitted [zoom] back here, never above [authoredZoom].
 *
 * Coordinates are `x = width * centerX + xr * sc * zoom`, `y = height * centerY - y2 * sc * zoom`,
 * so every offset from the anchor is exactly linear in [zoom].
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
     * The zoom this camera was constructed with (the pose's authored `defaultZoom`), never the
     * current [zoom]: [CameraFraming] measures its fit against this, so the fit stays a pure
     * function of (viewpoint, pose, viewport) instead of a ratchet that keeps a compact frame at
     * the smallest zoom some earlier phase of the rep needed.
     */
    val authoredZoom: Float = zoom

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
