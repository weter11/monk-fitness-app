package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Camera is a generic mathematical projection utility.
 *
 * The camera carries two independent things, and C1 keeps them apart:
 *  * the **viewpoint** — `yaw`/`pitch`/`focalLength` and the anchor `centerX`/`centerY` — authored by
 *    the pose's `PoseMetadata.camera` (`docs/ENGINE.md` §10: camera and environment are owned by the
 *    pose, not by the engine core and not by the rendering surface);
 *  * the **frame** — the zoom the projection lands at on a given surface. A pose cannot author that
 *    surface, so [CameraFraming] derives the frame from the bounds of the frame actually being drawn
 *    and writes the fitted [zoom] back here, never above [authoredZoom].
 *
 * Coordinates are `x = width * centerX + xr * sc * zoom`, `y = height * centerY - y2 * sc * zoom`,
 * so every offset from the anchor `(width*centerX, height*centerY)` is exactly linear in [zoom].
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
     * The zoom this camera was constructed with — the pose's authored `CameraDefinition.defaultZoom`
     * for every production camera, because `Camera` is built from `PoseMetadata.camera`.
     *
     * This is the reference [CameraFraming] measures its viewport fit against, never the camera's
     * current [zoom]: the fit is then a pure function of (viewpoint, pose, viewport) rather than a
     * ratchet that would keep a compact frame at the smallest zoom some earlier phase of the rep
     * needed.
     */
    val authoredZoom: Float = zoom

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
