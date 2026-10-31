package com.monkfitness.app.animation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The frame an exercise is drawn at: one zoom and one anchor for every frame of its motion, so the
 * presentation cannot breathe as the rep folds and unfolds.
 */
data class CameraFrame(val zoom: Float, val centerX: Float, val centerY: Float) {

    /** Writes only the zoom and the anchor onto [camera]. */
    fun applyTo(camera: Camera) {
        camera.zoom = zoom
        camera.centerX = centerX
        camera.centerY = centerY
    }

    companion object {
        /** The frame [camera] already carries — what a caller that derives no frame draws with. */
        fun of(camera: Camera): CameraFrame = CameraFrame(camera.zoom, camera.centerX, camera.centerY)
    }
}

/**
 * The viewport frame of the exercise hero: one zoom and one anchor for the whole motion.
 *
 * A pose authors its viewpoint (`PoseMetadata.camera`) but not the surface the runtime draws it
 * onto, and a frame derived from each drawn frame's own bounds makes the subject's size breathe as
 * the rep folds and unfolds. So [exerciseFrame] derives the frame once, from the exercise's whole
 * drawn motion at every yaw the pose declares reachable, and every drawn frame uses it; [frameZoom]
 * is the opt-in dynamic mode that re-derives it per drawn frame.
 *
 * Both obey the same ceiling: the frame never exceeds [Camera.authoredZoom], so a pose's composition
 * intent is an upper bound the runtime only ever clips against the surface it is given — the frame
 * may shrink the subject, never blow a small pose up to fill a large canvas.
 *
 * The subject is the athlete plus the floor it contacts: the drawn silhouette (joints, bones,
 * indicator discs, torso faces, sized by the same [SkeletonStyle] and [ScreenSpaceCompensation] the
 * renderers stroke them with) and the drawn contact shadows. Framing the contact marks is what puts
 * the floor slightly below the figure without a hardcoded pixel or anchor: the content box's bottom
 * edge is the deepest drawn ground point under the athlete, so centring that box leaves the feet
 * just above the bottom edge. Grid lines and environment props are laid out to a horizon that leaves
 * the frame by construction and are not framed.
 *
 * One measurement at zoom 1 anchored at the origin is enough because every drawn extent scales
 * exactly linearly with the zoom — positions through [Camera.project], bone thickness through
 * `thicknessScale`, disc radii through `radiusScale`, shadow radii through `shadowScale`, face
 * geometry through `project` — while `sc` itself is independent of the zoom. The outline stroke the
 * renderers add is the single exception: a fixed pixel width (`outlineScale` carries no zoom factor),
 * so it comes off the budget as a constant instead of scaling with the box. Positions depend on
 * neither the anchor (a pure translation) nor the surface, so the box is measured in anchor-free
 * coordinates and the anchor derived from it; the surface enters only through the final division.
 *
 * The box is a union over two continuous dimensions — rep progress and viewable yaw — measured on a
 * finite grid. A peak that falls inside a sampling cell is invisible to that union, so each edge is
 * grown by the largest second difference of that edge over the grid, divided by eight: between dense
 * samples these extents are locally quadratic, and a quadratic edge exceeds its sampled endpoints at
 * the cell middle by exactly `|e_prev - 2*e_k + e_next| / 8`. The margin is a function of the
 * motion's own curvature, and it vanishes as the sampling is refined.
 *
 * Only yaw is movable (the drag gesture, within the pose's declared range); pitch is authored and
 * fixed. This class derives the scale and the anchor alone — yaw, pitch and focal length are part of
 * the frame the pose is drawn with.
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
     * Measures only: the caller's viewpoint, zoom 1, anchored at the origin, so every projected
     * coordinate is the frame's offset from the anchor at unit zoom.
     */
    private val measure = Camera()

    // Reused pre-pass boxes: one per sampled yaw, one per sampled progress, plus the content box.
    private val yawBoxes = Array(YAW_SAMPLES + 1) { Edges() }
    private val progressBoxes = Array(PROGRESS_SAMPLES + 1) { Edges() }
    private val contentBox = Edges()
    private val frameBox = Edges()

    /**
     * The static frame of [builder]'s exercise on a [width] x [height] surface: the largest zoom, not
     * exceeding [Camera.authoredZoom], at which the athlete and its ground contact stay inside the
     * viewport for every frame of the motion the exercise plays ([sides]), at every yaw its
     * declaration allows; plus the anchor that centres that content.
     *
     * The motion is read from [builder] itself, played through its own [SkeletonPipeline], so the
     * frames measured are the frames the pipeline publishes — the renderer's pipeline and its Frame
     * History are never touched. [sides] is the side sequence the play advances through, so the frame
     * covers exactly the frames that will be drawn. The result is a pure function of (declaration,
     * builder, sides, surface), never of the camera's current zoom or anchor, so a camera carrying a
     * frame from a previous surface cannot ratchet it: call once per (exercise, surface), then apply
     * the frame to the camera for every frame.
     *
     * **The pre-pass plays [builder]**, which publishes into a reused carrier: it must run before the
     * caller builds the frame it is about to hand the renderer, or that frame's pose is the pre-pass's
     * last sample.
     */
    fun exerciseFrame(
        camera: Camera,
        builder: PoseBuilder,
        sides: List<Side>,
        width: Float,
        height: Float
    ): CameraFrame {
        val authored = camera.authoredZoom
        if (width <= 0f || height <= 0f) return CameraFrame(authored, camera.centerX, camera.centerY)

        val declaration = builder.metadata.camera
        exerciseBounds(camera, builder, sides, declaration, width, height)

        // The drawn extent is the box scaled by the zoom plus the outline stroke, which is a constant
        // in pixels (see the class KDoc) — so it comes off the budget rather than scaling with the
        // box. The fit is then: scale the box until it fits, and centre it.
        val zoom = min(
            authored,
            min(
                (width - 2f * contentBox.stroke) / contentBox.width,
                (height - 2f * contentBox.stroke) / contentBox.height
            )
        ).coerceAtLeast(MINIMUM_ZOOM)

        val centerX = 0.5f - (contentBox.minX + contentBox.maxX) * 0.5f * zoom / width
        val centerY = 0.5f - (contentBox.minY + contentBox.maxY) * 0.5f * zoom / height
        return CameraFrame(zoom, centerX, centerY)
    }

    /**
     * Measures the union of the drawn content over the exercise's motion and its reachable viewpoints
     * into [contentBox], in anchor-free unit-zoom coordinates, grown by the margin its own sampling
     * cannot see.
     *
     * **The pipeline's output is a reused buffer**: every frame is measured and consumed before the
     * next one is built, never retained. The pipeline is this class's own and is reset first, so the
     * measurement cannot depend on how often the exercise has been framed.
     */
    private fun exerciseBounds(
        camera: Camera,
        builder: PoseBuilder,
        sides: List<Side>,
        declaration: CameraDefinition,
        width: Float,
        height: Float
    ) {
        val pipeline = SkeletonPipeline(engine.definition)
        pipeline.resetHistory()

        contentBox.reset()
        measure.pitch = camera.pitch
        measure.focalLength = camera.focalLength
        measure.zoom = 1f
        measure.centerX = 0f
        measure.centerY = 0f

        val yawFrom = declaration.defaultYaw + declaration.minYaw
        val yawSpan = declaration.maxYaw - declaration.minYaw
        val yawCells = if (yawSpan == 0f) 0 else YAW_SAMPLES
        // One margin object for both dimensions: each contributes the largest second difference of
        // its own grid, and the box grows by the larger of the two per edge.
        val margins = Margins()

        for (side in sides) {
            for (i in 0..PROGRESS_SAMPLES) {
                val context = PoseContext(
                    progress = i.toFloat() / PROGRESS_SAMPLES,
                    side = side,
                    definition = engine.definition
                )
                // Measured before the next frame is published: the carrier is reused.
                val frame = pipeline.produceFrame(builder, context).pose

                val sampled = progressBoxes[i]
                sampled.reset()
                for (k in 0..yawCells) {
                    measure.yaw = if (yawCells == 0) yawFrom else yawFrom + yawSpan * k / yawCells
                    measureInto(frame, width, height, withGround = true, into = yawBoxes[k])
                    sampled.include(yawBoxes[k])
                    if (k >= 2) margins.include(yawBoxes[k - 2], yawBoxes[k - 1], yawBoxes[k])
                }
            }
            // Fold the completed side into the content and its second differences into the progress
            // margin before the next side reuses the buffers.
            for (i in 0..PROGRESS_SAMPLES) {
                contentBox.include(progressBoxes[i])
                if (i >= 2) {
                    margins.include(progressBoxes[i - 2], progressBoxes[i - 1], progressBoxes[i])
                }
            }
        }

        contentBox.grow(margins)
    }

    /**
     * The frame's zoom for [pose] on a [width] x [height] surface: the largest zoom not exceeding
     * [Camera.authoredZoom] at which the pose's whole drawn silhouette lies inside the viewport,
     * keeping the camera's own anchor.
     *
     * Returns the authored zoom when the frame already fits, so assigning this back onto
     * [Camera.zoom] leaves already-fitting frames bit-identical. The measured subject is the athlete
     * alone — the ground belongs to [exerciseFrame]'s composition, not to this rule.
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
        // The anchor is the pose's own: the dynamic mode touches the zoom alone.
        val content = frameBox
        measureInto(pose, width, height, withGround = false, into = content)

        val anchorX = width * camera.centerX
        val anchorY = height * camera.centerY
        var zoom = authored
        if (content.minX < 0f) zoom = limit(zoom, (anchorX - content.stroke) / -content.minX)
        if (content.maxX > 0f) zoom = limit(zoom, (width - anchorX - content.stroke) / content.maxX)
        if (content.minY < 0f) zoom = limit(zoom, (anchorY - content.stroke) / -content.minY)
        if (content.maxY > 0f) zoom = limit(zoom, (height - anchorY - content.stroke) / content.maxY)
        return zoom
    }

    /**
     * The drawn box of [pose], written into [into]: everything the renderers actually stroke — bones
     * (half thickness plus outline), indicator discs (radius + outline), torso faces (a stroke centred
     * on the path edge) — and, when [withGround] is set, the contact shadows, drawn on the floor
     * plane as ellipses of `style.shadowRadiusX/Y` x `ScreenSpaceScale.shadowScale`. Shadow centres
     * and radii are both linear in the zoom, so the floor enters the closed form too.
     */
    private fun measureInto(
        pose: SkeletonPose,
        width: Float,
        height: Float,
        withGround: Boolean,
        into: Edges
    ) {
        into.reset()
        projector.project(
            pose = pose,
            camera = measure,
            engine = engine,
            width = width,
            height = height,
            buffer = projected,
            groundLevel = pose.environment.ground.level
        )

        // The zoom-independent outline the renderers stroke around every primitive: a bone adds
        // `outlineWidth * outlineScale` to its half thickness, an indicator disc adds `2f *
        // outlineScale` to its radius, and a face's stroke straddles its edge. `outlineScale` is the
        // one factor NOT multiplied by the zoom, hence the constant off the available budget.
        var stroke = 0f

        for (i in 0 until projected.boneCount) {
            val bone = projected.bones[i]
            compensator.computeScale(bone.p1, 1f, scale)
            val half = bone.thickness * scale.thicknessScale * 0.5f
            val x0 = minOf(bone.p1.x, bone.p2.x) - half
            val x1 = maxOf(bone.p1.x, bone.p2.x) + half
            val y0 = minOf(bone.p1.y, bone.p2.y) - half
            val y1 = maxOf(bone.p1.y, bone.p2.y) + half
            if (x0 < into.minX) into.minX = x0
            if (x1 > into.maxX) into.maxX = x1
            if (y0 < into.minY) into.minY = y0
            if (y1 > into.maxY) into.maxY = y1
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
            if (x0 < into.minX) into.minX = x0
            if (x1 > into.maxX) into.maxX = x1
            if (y0 < into.minY) into.minY = y0
            if (y1 > into.maxY) into.maxY = y1
            val discStroke = 2f * outlineScale(indicator.point.perspectiveScale)
            if (discStroke > stroke) stroke = discStroke
        }

        for (i in 0 until projected.faceCount) {
            for (p in projected.faces[i].points) {
                if (p.x < into.minX) into.minX = p.x
                if (p.x > into.maxX) into.maxX = p.x
                if (p.y < into.minY) into.minY = p.y
                if (p.y > into.maxY) into.maxY = p.y
                val faceStroke = style.outlineWidth * 0.5f * outlineScale(p.perspectiveScale)
                if (faceStroke > stroke) stroke = faceStroke
            }
        }

        if (withGround) {
            for (point in projected.shadowPoints) {
                compensator.computeScale(point, 1f, scale)
                val rx = style.shadowRadiusX * scale.shadowScale
                val ry = style.shadowRadiusY * scale.shadowScale
                val x0 = point.x - rx
                val x1 = point.x + rx
                val y0 = point.y - ry
                val y1 = point.y + ry
                if (x0 < into.minX) into.minX = x0
                if (x1 > into.maxX) into.maxX = x1
                if (y0 < into.minY) into.minY = y0
                if (y1 > into.maxY) into.maxY = y1
            }
        }

        if (stroke > into.stroke) into.stroke = stroke
    }

    /** `ScreenSpaceScale.outlineScale` — the one factor the renderers leave unscaled by the zoom. */
    private fun outlineScale(perspectiveScale: Float): Float =
        1f + (perspectiveScale - 1f) * OUTLINE_STRENGTH

    private fun limit(current: Float, candidate: Float): Float =
        if (candidate.isFinite() && candidate < current) candidate.coerceAtLeast(MINIMUM_ZOOM) else current

    /** One measured box: the drawn silhouette's four edges in anchor-free unit-zoom space. */
    private class Edges {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var stroke = 0f

        fun reset() {
            minX = Float.MAX_VALUE
            maxX = -Float.MAX_VALUE
            minY = Float.MAX_VALUE
            maxY = -Float.MAX_VALUE
            stroke = 0f
        }

        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY

        fun include(other: Edges) {
            if (other.minX < minX) minX = other.minX
            if (other.maxX > maxX) maxX = other.maxX
            if (other.minY < minY) minY = other.minY
            if (other.maxY > maxY) maxY = other.maxY
            if (other.stroke > stroke) stroke = other.stroke
        }

        fun grow(minXBy: Float, maxXBy: Float, minYBy: Float, maxYBy: Float) {
            minX -= minXBy
            maxX += maxXBy
            minY -= minYBy
            maxY += maxYBy
        }

        fun grow(margins: Margins) = grow(margins.minX, margins.maxX, margins.minY, margins.maxY)
    }

    /**
     * The second-difference margin of one sampled dimension: the largest
     * `|e_prev - 2*e_k + e_next| / 8` per edge over every interior sample — the exact overshoot of a
     * locally quadratic edge at the middle of a cell, which a peak falling inside a cell adds and the
     * sampled union cannot see.
     */
    private class Margins {
        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f

        fun include(previous: Edges, current: Edges, next: Edges) {
            minX = max(minX, abs(previous.minX - 2f * current.minX + next.minX) * EIGHTH)
            maxX = max(maxX, abs(previous.maxX - 2f * current.maxX + next.maxX) * EIGHTH)
            minY = max(minY, abs(previous.minY - 2f * current.minY + next.minY) * EIGHTH)
            maxY = max(maxY, abs(previous.maxY - 2f * current.maxY + next.maxY) * EIGHTH)
        }

        private companion object {
            const val EIGHTH = 0.125f
        }
    }

    private companion object {
        /** `ScreenSpaceSettings.outlineStrength` — fixed by the compensation stage, not by this class. */
        const val OUTLINE_STRENGTH = 0.05f

        /**
         * Bounds a degenerate viewport (narrower than the outline stroke around the anchor), where
         * containment is unattainable at any zoom and collapsing onto the anchor is all that is left.
         */
        const val MINIMUM_ZOOM = 1e-3f

        /**
         * Sampling of the two continuous dimensions: the rep's progress (`1/32` of the play, per side)
         * and the reachable yaw range (`5` degrees over the `180` the rotation gesture can cross).
         * The second-difference margin above is what makes this resolution sufficient rather than
         * merely plausible — a `1/256` x `0.5`-degree reference sweep of the corpus measures `0.0` px
         * of residual overshoot.
         */
        const val PROGRESS_SAMPLES = 32
        const val YAW_SAMPLES = 36
    }
}
