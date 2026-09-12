package com.monkfitness.app.animation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The frame an exercise is drawn at: **one** zoom and **one** anchor, for every frame of its motion.
 *
 * C2 introduced this so that the presentation cannot breathe: [CameraFraming.exerciseFrame] derives
 * it once, from the exercise's whole drawn motion instead of from the frame being drawn, and every
 * frame of the rep is then projected through the same values. See [CameraFraming].
 */
data class CameraFrame(val zoom: Float, val centerX: Float, val centerY: Float) {

    /** Writes the frame onto [camera] — the zoom and the anchor, and nothing else. */
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
 * The viewport frame of the exercise hero.
 *
 * A pose authors its **viewpoint** (`PoseMetadata.camera`: yaw, pitch, focal length, zoom, anchor, and
 * the yaw the rotation gesture may reach); the runtime draws it onto whatever surface the app hands
 * it. The surface is not the pose's business — and until C1 the frame was not derived from it either.
 * `Camera` mapped world units onto pixels one-for-one (times the pose's authored zoom) and anchored
 * the world origin at a hardcoded `centerY = 0.7`, so the drawn size of a pose was *independent of
 * the surface*: a standing pose, or any pose with the arms overhead, was taller than a phone-sized
 * canvas and lost its head (and hands) off the top edge, while the identical frame fit an oversized
 * one. Measured on the production path, the hero canvas at `861x531` clipped **771 of 1683** sampled
 * frames across **26 of the 51** production poses, and the same corpus clipped at `1280x720`
 * (516 frames / 22 poses), `1440x700` (531 / 22) and `1000x1000` (9 / 2) — the framing, never the
 * geometry.
 *
 * C1 derived the frame from the frame being drawn. That closed the containment defect, and it made the
 * subject's size a function of the subject's *current* bounds: a rep that folds and unfolds was drawn
 * through a zoom that pushed in and pulled back out as it moved (the audit's recorded residual (b),
 * "the scale breathes with the frame"). This class owns the rule that removes that, in two scopes:
 *
 *  * **[exerciseFrame] — the default presentation (C2).** One frame for the whole exercise: the
 *    largest zoom, not exceeding the pose's authored zoom, at which the athlete — together with the
 *    ground it contacts — stays inside the viewport for **every** frame of the motion, at **every**
 *    viewpoint the pose declares reachable. The frame is a function of the exercise and the surface
 *    alone, so it is derived once (before the first frame is drawn) and every frame of the rep is
 *    drawn through it: no breathing, and no per-pose zoom override anywhere.
 *  * **[frameZoom] — the opt-in dynamic mode (C1, kept bit-identical).** The frame is re-derived from
 *    each drawn frame's own bounds; the subject's size then changes with its pose. This is the
 *    "camera push-in" the UI exposes as a toggle, OFF by default.
 *
 * Both scopes obey the same ceiling: **the frame never exceeds the zoom the pose authored**, so a
 * pose's composition intent is an upper bound the runtime only ever clips against the surface it is
 * given. The frame may therefore only ever *shrink* the subject; it never blows a small pose up to
 * fill a large canvas.
 *
 * ## What is framed, and why the ground is in it
 *
 * The subject is the athlete **plus the floor it contacts**: the drawn silhouette (joints, bones,
 * indicator discs, torso faces — sized by the same [SkeletonStyle] and [ScreenSpaceCompensation] the
 * renderers stroke them with) and the drawn contact shadows, which are the athlete's own ground
 * points projected onto the floor plane. Framing the contact marks is what puts the floor "slightly
 * below the figure" without a hardcoded pixel or anchor: the content box's bottom edge *is* the
 * deepest drawn ground point under the athlete, so centring that box leaves the athlete's feet just
 * above the bottom edge with the ground directly beneath them, and the athlete using the whole
 * surface instead of the 70% a fixed `centerY = 0.7` left it. Nothing else about the ground moves:
 * the grid lines and the environment props (box, step, bench, wall) are laid out to a horizon that
 * leaves the frame by construction and are still not framed — the rule frames the athlete and its
 * footing.
 *
 * ## Why one measurement is enough
 *
 * Every drawn extent scales *exactly linearly* with the zoom: positions through [Camera.project]
 * (`x = width*centerX + xr*sc*zoom`), bone thickness through `ScreenSpaceScale.thicknessScale`, disc
 * radii through `radiusScale`, shadow radii through `shadowScale`, face geometry through `project` as
 * well — while `sc` itself is independent of the zoom. The outline stroke the renderers add on top is
 * the single exception: it is a fixed pixel width (`ScreenSpaceScale.outlineScale` carries no `zoom`
 * factor). So one measurement at zoom 1 anchored at the origin yields the frame's linear part plus
 * one constant, and the fit is a closed form: the box is scaled down until it fits, then centred.
 *
 * Positions do **not** depend on the anchor (`(width*centerX, height*centerY)` is a pure translation)
 * nor on the surface, so the exercise's box is measured once, in anchor-free coordinates, and the
 * anchor is then derived from it. That is also what makes the pre-pass cheap: the surface enters the
 * derivation only through the final division.
 *
 * ## The two continuous dimensions of the motion, and how their peaks are covered
 *
 * The exercise's box is a union over two **continuous** dimensions — the rep's progress, and the yaw
 * the viewer can rotate through — measured here on a finite grid ([PROGRESS_SAMPLES] x
 * [YAW_SAMPLES]). A drawn edge is not constant between two samples, so a peak that falls *inside* a
 * sampling cell is invisible to the union: measured before this correction, the burpee's standing
 * jump climbed `24.3` px above a frame fitted to the `1/16`-sampled union of its own motion, and the
 * same effect shows up wherever a rep moves quickly (a gait, a swing).
 *
 * The correction is derived from the samples, not from a pixel constant. Between two dense samples
 * every one of these extents is locally quadratic, and a quadratic edge exceeds its sampled endpoints
 * at the middle of the cell by exactly `|e_prev - 2*e_k + e_next| / 8`. Each edge of the measured box
 * is therefore grown by the largest second difference of that edge over the grid, divided by eight —
 * a margin that is a function of the motion's own curvature: a slow rep contributes a fraction of a
 * pixel, a jump contributes exactly as much as its peak overshoots the samples, and it vanishes as
 * the sampling is refined.
 *
 * ## The pitch, and the anchor the pose authored
 *
 * The only viewpoint axis the app can move is yaw: the drag gesture rotates the camera about the
 * athlete (`AnimationController.onRotate`) within the range the pose declares
 * (`CameraDefinition.minYaw`/`maxYaw` about `defaultYaw`), and pitch is authored and fixed. The sweep
 * is therefore over that declared yaw range at the pose's authored pitch, and the yaw/pitch/focal
 * length the author wrote are **part of the frame the pose is drawn with, not something this class
 * derives** — it derives the scale and the anchor alone.
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

    // The pre-pass reuses its boxes: one per sampled yaw of the frame being measured, one per sampled
    // frame of the side being played, and the accumulating content box.
    private val yawBoxes = Array(YAW_SAMPLES + 1) { Edges() }
    private val progressBoxes = Array(PROGRESS_SAMPLES + 1) { Edges() }
    private val contentBox = Edges()
    private val frameBox = Edges()

    // ----------------------------------------------------------------------------------------------
    // The default presentation — one frame for the whole exercise
    // ----------------------------------------------------------------------------------------------

    /**
     * The static frame of [builder]'s exercise on a [width] x [height] surface: the largest zoom, not
     * exceeding [Camera.authoredZoom], at which the athlete and its ground contact stay inside the
     * viewport for every frame of the motion the exercise plays ([sides]), at every yaw its
     * declaration allows; and the anchor that centres that content in the viewport.
     *
     * The motion is read from [builder] itself — the same builder the renderer is handed each frame,
     * played through its own [SkeletonPipeline] so the frames measured are the frames the pipeline
     * publishes (the renderer's pipeline, and its Frame History, are never touched) — and the
     * viewpoints are the pose's own declaration (`CameraDefinition.defaultYaw` +/- `minYaw`/`maxYaw`
     * at the authored pitch). [sides] is the side sequence the play advances through, so the frame
     * covers exactly the frames that will be drawn: an alternating exercise plays `RIGHT` then
     * `LEFT` (`AnimationController`), a mirrored-off one only ever its right side. Nothing here is
     * per-pose or per-viewport: the same rule covers a squat, a pull-up, a rotated profile view and a
     * `512x512` snapshot.
     *
     * **Call once per (exercise, surface)** — the pre-pass plays the builder across the whole rep —
     * and then apply the returned frame to the camera for every frame. The result is a pure function
     * of (declaration, builder, sides, surface) and never of the camera's current zoom or anchor, so
     * a camera that already carries a frame from a previous surface cannot ratchet it: two calls with
     * the same arguments return the same frame, bit for bit.
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

        // The drawn extent is the measured box scaled by the zoom **plus the outline stroke, which is
        // a constant in pixels** (see the class KDoc) — so the stroke comes off the budget, it is not
        // scaled with the box. The fit is then: scale the box until it fits, and centre it.
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
     * **The pipeline's output is a reused buffer**: each [SkeletonPipeline.produceFrame] publishes
     * into the same carrier, so every frame is measured (and consumed) before the next one is built —
     * never retained. The pipeline is this class's own, and it is reset before the pre-pass, so the
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
        // that dimension's grid, and the box is grown by the larger of the two per edge.
        val margins = Margins()

        for (side in sides) {
            for (i in 0..PROGRESS_SAMPLES) {
                val context = PoseContext(
                    progress = i.toFloat() / PROGRESS_SAMPLES,
                    side = side,
                    definition = engine.definition
                )
                // Measured (and consumed) before the next frame is published: the carrier is reused.
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
            // The side is complete: fold its frames into the content and their second differences into
            // the progress margin before the next side reuses the buffers.
            for (i in 0..PROGRESS_SAMPLES) {
                contentBox.include(progressBoxes[i])
                if (i >= 2) {
                    margins.include(progressBoxes[i - 2], progressBoxes[i - 1], progressBoxes[i])
                }
            }
        }

        contentBox.grow(margins)
    }

    // ----------------------------------------------------------------------------------------------
    // The opt-in dynamic mode — the frame of the frame being drawn (C1)
    // ----------------------------------------------------------------------------------------------

    /**
     * The frame's zoom for [pose] on a [width] x [height] surface: the largest zoom not exceeding
     * [Camera.authoredZoom] at which the pose's whole drawn silhouette lies inside the viewport,
     * keeping the camera's own anchor.
     *
     * Returns the camera's authored zoom when the frame already fits, so a caller that assigns this
     * back onto [Camera.zoom] leaves every already-fitting frame bit-identical. The measured subject
     * here is the athlete alone, exactly as C1 shipped it — the ground was added by
     * [exerciseFrame]'s composition, not by this rule. The default presentation is [exerciseFrame].
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
        // The anchor is the pose's own: the dynamic mode touches the zoom alone (see the class KDoc).
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

    // ----------------------------------------------------------------------------------------------
    // The measurement — the drawn silhouette, and the ground the athlete contacts
    // ----------------------------------------------------------------------------------------------

    /**
     * The drawn box of [pose] measured through [measure], written into [into]: everything the
     * renderers actually stroke — bones (with their half thickness and the outline the renderers add),
     * indicator discs (radius + outline), torso faces (a stroke centred on the path edge) — and, when
     * [withGround] is set, the contact shadows: the athlete's own ground points, drawn on the floor
     * plane as ellipses of `style.shadowRadiusX/Y` x `ScreenSpaceScale.shadowScale`. Shadow centres
     * and radii are both linear in the zoom, so the floor enters the fit's closed form as well.
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
     * The second-difference margin of one sampled dimension: walking the sampled boxes in order, the
     * largest `|e_prev - 2*e_k + e_next| / 8` per edge over every interior sample — the exact
     * overshoot of a locally quadratic edge at the middle of a sampling cell, which is what a peak
     * falling inside a cell adds that the sampled union cannot see.
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
         * Contains a frame even when the viewport is degenerate (narrower than the outline stroke
         * around the anchor). Containment is then unattainable at any zoom, and collapsing onto the
         * anchor is the only honest answer left.
         */
        const val MINIMUM_ZOOM = 1e-3f

        /**
         * How finely the two continuous dimensions of the exercise's motion are sampled: the rep's
         * progress (`1/32` of the play, for each side the play advances through) and the reachable yaw
         * range (`5` degrees over the `180` degrees the rotation gesture can cross). Both dimensions
         * are covered by the second-difference margin above, which is what makes this resolution
         * sufficient rather than merely plausible: a `1/256` x `0.5`-degree reference sweep of the
         * whole corpus measures `0.0` px of residual overshoot against the frame derived here.
         */
        const val PROGRESS_SAMPLES = 32
        const val YAW_SAMPLES = 36
    }
}
