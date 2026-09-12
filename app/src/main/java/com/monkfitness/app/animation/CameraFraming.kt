package com.monkfitness.app.animation

/**
 * C1 — the viewport frame.
 *
 * A pose authors its **viewpoint** (`PoseMetadata.camera`: yaw, pitch, focal length, zoom, anchor);
 * the runtime draws it onto whatever surface the app hands it. The surface is not the pose's
 * business — and until C1 the frame was not derived from it either. `Camera` mapped world units onto
 * pixels one-for-one (times the pose's authored zoom) and anchored the world origin at a hardcoded
 * `centerY = 0.7`, so the drawn size of a pose was *independent of the surface*: a standing pose, or
 * any pose with the arms overhead, was taller than a phone-sized canvas and lost its head (and
 * hands) off the top edge, while the identical frame fit an oversized one. Measured on the
 * production path, the hero canvas at `861x531` clipped **771 of 1683** sampled frames across **26
 * of the 51** production poses, and the same corpus clipped at `1280x720` (516 frames / 22 poses),
 * `1440x700` (531 / 22) and `1000x1000` (9 / 2) — the framing, never the geometry.
 *
 * This class owns exactly one rule, and no per-pose or per-viewport table:
 *
 * > **the frame is the largest zoom, not exceeding the pose's authored zoom, at which the whole
 * > drawn silhouette lies inside the viewport, anchored where the pose anchored it.**
 *
 * Consequences, by construction:
 *
 *  * **Containment is a fixed point.** A frame whose authored silhouette already fits returns the
 *    camera's authored zoom — the same zoom and the same anchor, so its projection is
 *    image-identical. The fit can only ever *shrink*, never enlarge and never recompose, which is
 *    why wide, supine and prone poses (which fit at the sizes above) cannot regress.
 *  * **The fit is pure.** It is measured against [Camera.authoredZoom], never against its own
 *    previous output, so it cannot ratchet down across the frames of a rep — every frame of every
 *    rep is framed by its own bounds alone.
 *  * **The silhouette is the rendered one.** Joints, bones, indicator discs and torso faces, sized
 *    by the same [SkeletonStyle] and [ScreenSpaceCompensation] the renderers stroke them with
 *    ([SkeletonRenderer], [SkeletonSnapshotRenderer]) — so the rule is checked against what is
 *    actually drawn, not against a model of it.
 *
 * ## Why one measurement is enough
 *
 * Every drawn extent scales *exactly linearly* with the zoom: positions through [Camera.project]
 * (`x = width*centerX + xr*sc*zoom`), bone thickness through `ScreenSpaceScale.thicknessScale`, disc
 * radii through `radiusScale`, face geometry through `project` as well — while `sc` itself is
 * independent of the zoom. The outline stroke the renderers add on top is the single exception: it
 * is a fixed pixel width (`ScreenSpaceScale.outlineScale` carries no `zoom` factor). So one
 * measurement at zoom 1 anchored at the origin yields the frame's linear part plus one constant, and
 * the fit reduces to four closed-form limits, one per edge of the viewport.
 *
 * ## Deliberately NOT framed
 *
 * The ground grid and the contact shadows are the *floor plane*, not the subject: [SkeletonProjector]
 * lays the grid across `+-260` world units and the blocks below are drawn to that horizon, so they
 * leave the frame in every pose by construction (and `EnvironmentDefinition`'s props — box, step,
 * bench, wall — are the pose's furniture, drawn against the same camera). This rule frames the
 * athlete.
 */
class CameraFraming(
    private val engine: SkeletonEngine,
    settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {

    private val projector = SkeletonProjector()
    private val projected = ProjectedSkeleton()
    private val compensator = ScreenSpaceCompensation(settings)
    private val scale = ScreenSpaceScale()
    private val style = engine.style

    /**
     * A camera used only to measure: the caller's viewpoint, zoom 1, anchored at the origin, so every
     * projected coordinate *is* the frame's offset from the anchor at unit zoom.
     */
    private val measure = Camera()

    /**
     * The frame's zoom for [pose] on a [width] x [height] surface: the largest zoom not exceeding
     * [Camera.authoredZoom] at which the pose's whole drawn silhouette lies inside the viewport,
     * keeping the camera's own anchor.
     *
     * Returns the camera's authored zoom when the frame already fits, so a caller that assigns this
     * back onto [Camera.zoom] leaves every already-fitting frame bit-identical.
     */
    fun frameZoom(camera: Camera, pose: SkeletonPose, width: Float, height: Float): Float {
        val authored = camera.authoredZoom
        if (width <= 0f || height <= 0f) return authored

        measure.yaw = camera.yaw
        measure.pitch = camera.pitch
        measure.focalLength = camera.focalLength
        measure.zoom = 1f
        measure.centerX = 0f
        measure.centerY = 0f
        projector.project(pose, measure, engine, width, height, projected, pose.environment.ground.level)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        // The zoom-independent outline the renderers stroke around every primitive: a bone adds
        // `outlineWidth * outlineScale` to its half thickness, an indicator disc adds `2f *
        // outlineScale` to its radius, and a face's `outlineWidth` stroke straddles its edge.
        // `outlineScale` is the one ScreenSpaceScale factor the renderers do NOT multiply by the
        // zoom, which is why the stroke is handled as a constant off the available budget.
        var stroke = 0f

        for (i in 0 until projected.boneCount) {
            val bone = projected.bones[i]
            compensator.computeScale(bone.p1, 1f, scale)
            val half = bone.thickness * scale.thicknessScale * 0.5f
            val x0 = minOf(bone.p1.x, bone.p2.x) - half
            val x1 = maxOf(bone.p1.x, bone.p2.x) + half
            val y0 = minOf(bone.p1.y, bone.p2.y) - half
            val y1 = maxOf(bone.p1.y, bone.p2.y) + half
            if (x0 < minX) minX = x0
            if (x1 > maxX) maxX = x1
            if (y0 < minY) minY = y0
            if (y1 > maxY) maxY = y1
            val boneStroke = style.outlineWidth * outlineScale(bone.p1.perspectiveScale)
            if (boneStroke > stroke) stroke = boneStroke
        }

        for (indicator in projected.indicators) {
            compensator.computeScale(indicator.point, 1f, scale)
            val radius = (if (indicator.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                scale.radiusScale
            val x0 = indicator.point.x - radius
            val x1 = indicator.point.x + radius
            val y0 = indicator.point.y - radius
            val y1 = indicator.point.y + radius
            if (x0 < minX) minX = x0
            if (x1 > maxX) maxX = x1
            if (y0 < minY) minY = y0
            if (y1 > maxY) maxY = y1
            val discStroke = 2f * outlineScale(indicator.point.perspectiveScale)
            if (discStroke > stroke) stroke = discStroke
        }

        for (i in 0 until projected.faceCount) {
            for (p in projected.faces[i].points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
                val faceStroke = style.outlineWidth * 0.5f * outlineScale(p.perspectiveScale)
                if (faceStroke > stroke) stroke = faceStroke
            }
        }

        val anchorX = width * camera.centerX
        val anchorY = height * camera.centerY
        var zoom = authored
        if (minX < 0f) zoom = limit(zoom, (anchorX - stroke) / -minX)
        if (maxX > 0f) zoom = limit(zoom, (width - anchorX - stroke) / maxX)
        if (minY < 0f) zoom = limit(zoom, (anchorY - stroke) / -minY)
        if (maxY > 0f) zoom = limit(zoom, (height - anchorY - stroke) / maxY)
        return zoom
    }

    /** `ScreenSpaceScale.outlineScale` — the one factor the renderers leave unscaled by the zoom. */
    private fun outlineScale(perspectiveScale: Float): Float =
        1f + (perspectiveScale - 1f) * OUTLINE_STRENGTH

    private fun limit(current: Float, candidate: Float): Float =
        if (candidate.isFinite() && candidate < current) candidate.coerceAtLeast(MINIMUM_ZOOM) else current

    private companion object {
        /** `ScreenSpaceSettings.outlineStrength` — fixed by the compensation stage, not by this class. */
        const val OUTLINE_STRENGTH = 0.05f

        /**
         * Contains a frame even when the viewport is degenerate (narrower than the outline stroke
         * around the anchor). Containment is then unattainable at any zoom, and collapsing onto the
         * anchor is the only honest answer left.
         */
        const val MINIMUM_ZOOM = 1e-3f
    }
}
