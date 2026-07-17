package com.monkfitness.app.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * SkeletonSnapshotRenderer is a non-Compose, off-screen renderer for SkeletonPose.
 * It renders high-quality snapshots, sprite sheets, and contact sheets of arbitrary resolutions,
 * while matching the painter's algorithm sorting, ground/shadow projection, and styling of
 * the real-time Compose-based SkeletonRenderer.
 */
class SkeletonSnapshotRenderer(
    private val engine: SkeletonEngine
) {
    // M2 (RFC_GAP_CLOSURE): finalization is owned by the engine orchestrator (SkeletonPipeline),
    // which runs the ConstraintSolver stage for contact poses and the Finalizer. The off-screen
    // renderer delegates to it so the active stage chain governs output (no direct finalizer call).
    private val pipeline = SkeletonPipeline(engine.definition)
    private val projector = SkeletonProjector()
    private val compensator = ScreenSpaceCompensation(ScreenSpaceSettings.DEFAULT)
    private val skeletonBuffer = ProjectedSkeleton()
    private val scaleBuffer = ScreenSpaceScale()

    // Pre-allocated RenderItems and index buffers to achieve zero allocation in the rendering loop
    private val renderItems = Array(100) { RenderItem() }
    private val indices = IntArray(100)

    // Reusable paint and path buffers
    private val paintFill = Paint().apply { isAntiAlias = true }
    private val paintStroke = Paint().apply { isAntiAlias = true }
    private val rectF = RectF()
    private val androidPath = Path()

    // Pre-allocated corner and sorting structures for prop rendering
    private val propCorners = Array(8) { Vector3() }
    private val propProjected = Array(8) { ProjectedPoint() }
    private val propFaceSortItems = Array(6) { PropFaceSortItem() }

    companion object {
        private val FACE_INDICES = arrayOf(
            intArrayOf(0, 1, 2, 3), // front
            intArrayOf(5, 4, 7, 6), // back
            intArrayOf(4, 0, 3, 7), // left
            intArrayOf(1, 5, 6, 2), // right
            intArrayOf(3, 2, 6, 7), // top
            intArrayOf(4, 5, 1, 0)  // bottom
        )
    }

    private class PropFaceSortItem {
        var faceIndex = 0
        var avgDepth = 0f
    }

    /**
     * Renders a single frame of a SkeletonPose into a Bitmap.
     */
    fun renderPose(
        pose: SkeletonPose,
        camera: Camera,
        environment: EnvironmentDefinition = EnvironmentDefinition(),
        width: Int = 512,
        height: Int = 512,
        showGround: Boolean = true,
        transparentBackground: Boolean = true,
        backgroundColor: Int = android.graphics.Color.WHITE
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (!transparentBackground) {
            canvas.drawColor(backgroundColor)
        }

        val finalizedPose = pipeline.produceFrame(pose).pose
        projector.project(
            pose = finalizedPose,
            camera = camera,
            engine = engine,
            width = width.toFloat(),
            height = height.toFloat(),
            buffer = skeletonBuffer,
            groundLevel = environment.ground.level
        )

        // 1. Draw Background (None)

        // 2. Draw Ground Grid and Shadows
        if (showGround && environment.ground.visible) {
            drawGround(canvas, camera)
        }

        // 3. Draw Environment Props (Boxes, Steps, Benches, Walls)
        drawEnvironment(canvas, environment, camera, width.toFloat(), height.toFloat())

        // 4. Draw Skeleton (Bones, Joints, Torso Faces in correct depth order)
        drawSkeleton(canvas, camera)

        // 5. Draw Foreground (None)

        return bitmap
    }

    /**
     * Renders multiple frames across a PoseBuilder's lifecycle into an ExerciseSnapshotSequence.
     */
    fun renderSequence(
        poseBuilder: PoseBuilder,
        definition: SkeletonDefinition,
        camera: Camera = Camera(poseBuilder.metadata.camera),
        environment: EnvironmentDefinition = poseBuilder.metadata.environment,
        frameCount: Int = 10,
        width: Int = 512,
        height: Int = 512,
        showGround: Boolean = true,
        transparentBackground: Boolean = true,
        backgroundColor: Int = android.graphics.Color.WHITE
    ): ExerciseSnapshotSequence {
        val snapshots = ArrayList<ExerciseSnapshot>(frameCount)
        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1).coerceAtLeast(1)
            val context = PoseContext(progress = progress, side = Side.LEFT, definition = definition)
            val pose = poseBuilder.build(context)

            val bitmap = renderPose(
                pose = pose,
                camera = camera,
                environment = environment,
                width = width,
                height = height,
                showGround = showGround,
                transparentBackground = transparentBackground,
                backgroundColor = backgroundColor
            )
            snapshots.add(ExerciseSnapshot(i, progress, bitmap, pose))
        }
        return ExerciseSnapshotSequence(snapshots)
    }

    private fun drawGround(canvas: Canvas, camera: Camera) {
        val gridColor = convertColor(androidx.compose.ui.graphics.Color(0x5A3A445C))
        paintStroke.color = gridColor
        paintStroke.strokeWidth = 1f
        paintStroke.style = Paint.Style.STROKE
        for (i in 0 until skeletonBuffer.gridLineCount) {
            val l = skeletonBuffer.gridLines[i]
            canvas.drawLine(l.p1.x, l.p1.y, l.p2.x, l.p2.y, paintStroke)
        }

        val shadowColor = convertColor(androidx.compose.ui.graphics.Color(0x9605080C))
        paintFill.color = shadowColor
        paintFill.style = Paint.Style.FILL
        val style = engine.style
        val shadowPoints = skeletonBuffer.shadowPoints
        for (i in 0 until shadowPoints.size) {
            val p = shadowPoints[i]
            compensator.computeScale(p, camera.zoom, scaleBuffer)
            val sx = style.shadowRadiusX * scaleBuffer.shadowScale
            val sy = style.shadowRadiusY * scaleBuffer.shadowScale
            rectF.set(p.x - sx, p.y - sy, p.x + sx, p.y + sy)
            canvas.drawOval(rectF, paintFill)
        }
    }

    private fun drawEnvironment(
        canvas: Canvas,
        environment: EnvironmentDefinition,
        camera: Camera,
        width: Float,
        height: Float
    ) {
        val props = environment.props
        for (i in 0 until props.size) {
            val prop = props[i]
            when (prop) {
                is BoxProp -> drawBoxyProp(canvas, prop.center, prop.width, prop.height, prop.depth, camera, width, height) { it }
                is StepProp -> drawBoxyProp(canvas, prop.center, prop.width, prop.height, prop.depth, camera, width, height) {
                    androidx.compose.ui.graphics.Color(it.red * 0.8f, it.green * 0.95f, it.blue * 0.9f, it.alpha)
                }
                is BenchProp -> drawBoxyProp(canvas, prop.center, prop.width, prop.height, prop.depth, camera, width, height) {
                    androidx.compose.ui.graphics.Color(it.red * 0.75f, it.green * 0.7f, it.blue * 0.65f, it.alpha)
                }
                is WallProp -> drawBoxyProp(canvas, prop.center, prop.width, prop.height, prop.depth, camera, width, height) {
                    it.copy(alpha = 0.5f)
                }
            }
        }
    }

    private fun drawBoxyProp(
        canvas: Canvas,
        center: Vector3,
        pWidth: Float,
        pHeight: Float,
        pDepth: Float,
        camera: Camera,
        width: Float,
        height: Float,
        tint: (androidx.compose.ui.graphics.Color) -> androidx.compose.ui.graphics.Color
    ) {
        val style = engine.style
        val hw = pWidth / 2f
        val hh = pHeight / 2f
        val hd = pDepth / 2f

        // Set corner positions
        propCorners[0].set(center.x - hw, center.y - hh, center.z - hd)
        propCorners[1].set(center.x + hw, center.y - hh, center.z - hd)
        propCorners[2].set(center.x + hw, center.y + hh, center.z - hd)
        propCorners[3].set(center.x - hw, center.y + hh, center.z - hd)
        propCorners[4].set(center.x - hw, center.y - hh, center.z + hd)
        propCorners[5].set(center.x + hw, center.y - hh, center.z + hd)
        propCorners[6].set(center.x + hw, center.y + hh, center.z + hd)
        propCorners[7].set(center.x - hw, center.y + hh, center.z + hd)

        for (i in 0 until 8) {
            camera.project(propCorners[i], width, height, propProjected[i])
        }

        // Calculate average depths
        for (i in 0 until 6) {
            val corners = FACE_INDICES[i]
            val d0 = propProjected[corners[0]].depth
            val d1 = propProjected[corners[1]].depth
            val d2 = propProjected[corners[2]].depth
            val d3 = propProjected[corners[3]].depth
            propFaceSortItems[i].faceIndex = i
            propFaceSortItems[i].avgDepth = (d0 + d1 + d2 + d3) / 4f
        }

        // Sorting faces from far to near (descending average depth)
        for (i in 1 until 6) {
            val item = propFaceSortItems[i]
            val depth = item.avgDepth
            var j = i - 1
            while (j >= 0 && propFaceSortItems[j].avgDepth < depth) {
                propFaceSortItems[j + 1] = propFaceSortItems[j]
                j--
            }
            propFaceSortItems[j + 1] = item
        }

        // Draw sorted faces
        for (idx in 0 until 6) {
            val item = propFaceSortItems[idx]
            val fIdx = item.faceIndex
            val corners = FACE_INDICES[fIdx]
            val avgDepth = item.avgDepth

            val baseColor = getZColor(avgDepth, false, style)
            val color = tint(baseColor)
            val strokeC = convertColor(androidx.compose.ui.graphics.Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, color.alpha))
            val fillC = convertColor(androidx.compose.ui.graphics.Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, color.alpha))

            androidPath.reset()
            androidPath.moveTo(propProjected[corners[0]].x, propProjected[corners[0]].y)
            androidPath.lineTo(propProjected[corners[1]].x, propProjected[corners[1]].y)
            androidPath.lineTo(propProjected[corners[2]].x, propProjected[corners[2]].y)
            androidPath.lineTo(propProjected[corners[3]].x, propProjected[corners[3]].y)
            androidPath.close()

            paintFill.color = fillC
            paintFill.style = Paint.Style.FILL
            canvas.drawPath(androidPath, paintFill)

            paintStroke.color = strokeC
            paintStroke.strokeWidth = style.outlineWidth
            paintStroke.style = Paint.Style.STROKE
            canvas.drawPath(androidPath, paintStroke)
        }
    }

    private fun drawSkeleton(canvas: Canvas, camera: Camera) {
        val style = engine.style
        var itemCount = 0

        // Populate bones
        for (i in 0 until skeletonBuffer.boneCount) {
            val b = skeletonBuffer.bones[i]
            compensator.computeScale(b.p1, camera.zoom, scaleBuffer)
            val thickness = b.thickness * scaleBuffer.thicknessScale
            renderItems[itemCount++].populateBone(b, thickness, scaleBuffer.outlineScale)
        }

        // Populate joints
        for (j in skeletonBuffer.indicators) {
            compensator.computeScale(j.point, camera.zoom, scaleBuffer)
            val radius = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                        scaleBuffer.radiusScale
            renderItems[itemCount++].populateJoint(j, radius, scaleBuffer.outlineScale)
        }

        // Populate faces
        for (i in 0 until skeletonBuffer.faceCount) {
            renderItems[itemCount++].populateFace(skeletonBuffer.faces[i])
        }

        // Initialize indices
        for (i in 0 until itemCount) indices[i] = i

        // Insertion sort descending by depth
        for (i in 1 until itemCount) {
            val idx = indices[i]
            val depth = renderItems[idx].depth
            var j = i - 1
            while (j >= 0 && renderItems[indices[j]].depth < depth) {
                indices[j + 1] = indices[j]
                j--
            }
            indices[j + 1] = idx
        }

        // Draw sorted items
        for (i in 0 until itemCount) {
            val item = renderItems[indices[i]]
            when (item.type) {
                RenderItem.Type.BONE -> {
                    val b = item.bone!!
                    val color = convertColor(getZColor(item.depth, b.isForeground, style))
                    val outlineWidth = style.outlineWidth * item.outlineScale

                    // Draw outline bone
                    val outlineColor = convertColor(androidx.compose.ui.graphics.Color(0xFF0A0F14))
                    paintStroke.color = outlineColor
                    paintStroke.strokeWidth = item.thickness + (outlineWidth * 2f)
                    paintStroke.strokeCap = Paint.Cap.ROUND
                    paintStroke.style = Paint.Style.STROKE
                    canvas.drawLine(b.p1.x, b.p1.y, b.p2.x, b.p2.y, paintStroke)

                    // Draw inner bone
                    paintStroke.color = color
                    paintStroke.strokeWidth = item.thickness
                    canvas.drawLine(b.p1.x, b.p1.y, b.p2.x, b.p2.y, paintStroke)
                }
                RenderItem.Type.JOINT -> {
                    val j = item.joint!!
                    val color = convertColor(getZColor(item.depth, j.isIndicator, style))
                    val outline = 2f * item.outlineScale

                    // Draw outline circle
                    val outlineColor = convertColor(androidx.compose.ui.graphics.Color(0xFF0A0F14))
                    paintFill.color = outlineColor
                    paintFill.style = Paint.Style.FILL
                    canvas.drawCircle(j.point.x, j.point.y, item.radius + outline, paintFill)

                    // Draw inner circle
                    paintFill.color = color
                    canvas.drawCircle(j.point.x, j.point.y, item.radius, paintFill)
                }
                RenderItem.Type.FACE -> {
                    val f = item.face!!
                    val color = getZColor(f.avgDepth, false, style)
                    val strokeC = convertColor(androidx.compose.ui.graphics.Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f))
                    val fillC = convertColor(androidx.compose.ui.graphics.Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f))

                    androidPath.reset()
                    val facePoints = f.points
                    for (idx in 0 until facePoints.size) {
                        val p = facePoints[idx]
                        if (idx == 0) androidPath.moveTo(p.x, p.y) else androidPath.lineTo(p.x, p.y)
                    }
                    androidPath.close()

                    paintFill.color = fillC
                    paintFill.style = Paint.Style.FILL
                    canvas.drawPath(androidPath, paintFill)

                    paintStroke.color = strokeC
                    paintStroke.strokeWidth = style.outlineWidth
                    paintStroke.style = Paint.Style.STROKE
                    canvas.drawPath(androidPath, paintStroke)
                }
                else -> {}
            }
        }
    }

    private class RenderItem {
        enum class Type { NONE, BONE, JOINT, FACE }
        var type = Type.NONE
        var depth = 0f

        var bone: ProjectedBone? = null
        var thickness = 0f
        var outlineScale = 0f

        var joint: ProjectedJoint? = null
        var radius = 0f

        var face: ProjectedFace? = null

        fun populateBone(b: ProjectedBone, thick: Float, outline: Float) {
            type = Type.BONE
            bone = b
            depth = b.avgDepth
            thickness = thick
            outlineScale = outline
        }

        fun populateJoint(j: ProjectedJoint, rad: Float, outline: Float) {
            type = Type.JOINT
            joint = j
            depth = j.point.depth
            radius = rad
            outlineScale = outline
        }

        fun populateFace(f: ProjectedFace) {
            type = Type.FACE
            face = f
            depth = f.avgDepth
        }
    }

    private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): androidx.compose.ui.graphics.Color {
        val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
        val baseC = lerp(style.farColor, style.secondaryColor, t)
        return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
    }

    private fun lerp(
        start: androidx.compose.ui.graphics.Color,
        stop: androidx.compose.ui.graphics.Color,
        fraction: Float
    ): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(
            red = start.red + (stop.red - start.red) * fraction,
            green = start.green + (stop.green - start.green) * fraction,
            blue = start.blue + (stop.blue - start.blue) * fraction,
            alpha = start.alpha + (stop.alpha - start.alpha) * fraction
        )
    }

    private fun convertColor(composeColor: androidx.compose.ui.graphics.Color): Int {
        val alpha = (composeColor.alpha * 255f).toInt().coerceIn(0, 255)
        val red = (composeColor.red * 255f).toInt().coerceIn(0, 255)
        val green = (composeColor.green * 255f).toInt().coerceIn(0, 255)
        val blue = (composeColor.blue * 255f).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(alpha, red, green, blue)
    }
}
