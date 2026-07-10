package com.monkfitness.app.animation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp

/**
 * SkeletonRenderer is a passive rendering component in the animation engine v1.0 pipeline.
 * It applies pre-computed ScreenSpaceScale values (computed by ScreenSpaceCompensation)
 * to visual parameters without performing additional perspective math or geometry modification.
 *
 * Consistent with first-class joint rotations, SkeletonRenderer never infers rotations or orientations
 * from joint positions; all transformations are derived strictly via Forward Kinematics traversal.
 * ScreenSpaceCompensation is now the single source of truth for perspective zoom scaling.
 */
@Composable
fun SkeletonRenderer(
    pose: SkeletonPose,
    camera: Camera,
    engine: SkeletonEngine,
    modifier: Modifier = Modifier,
    environment: EnvironmentDefinition = EnvironmentDefinition(),
    showGround: Boolean = true,
    highlightedJoint: Joint? = null,
    screenSpaceSettings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    val style = engine.style
    val finalizer = remember(engine.definition) { SkeletonPoseFinalizer(engine.definition) }
    val projector = remember { SkeletonProjector() }
    val compensator = remember(screenSpaceSettings) { ScreenSpaceCompensation(screenSpaceSettings) }

    val skeletonBuffer = remember { ProjectedSkeleton() }
    val renderItems = remember { Array(100) { RenderItem() } }
    val indices = remember { IntArray(100) }
    val scaleBuffer = remember { ScreenSpaceScale() }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalizedPose = finalizer.finalize(pose)
        projector.project(finalizedPose, camera, engine, width, height, skeletonBuffer)

        // Rendering Pipeline:
        // 1. drawBackground()
        drawBackground()

        // 2. drawGround()
        if (showGround && environment.groundVisible) {
            drawGroundPassive(skeletonBuffer, style, compensator, camera.zoom, scaleBuffer)
        }

        // 3. drawEnvironment()
        drawEnvironmentPassive(environment, camera, style, width, height, path)

        // 4. drawSkeleton()
        var itemCount = 0

        for (i in 0 until skeletonBuffer.boneCount) {
            val b = skeletonBuffer.bones[i]
            compensator.computeScale(b.p1, camera.zoom, scaleBuffer)
            val thickness = b.thickness * scaleBuffer.thicknessScale
            renderItems[itemCount++].populateBone(b, thickness, scaleBuffer.outlineScale)
        }

        for (j in skeletonBuffer.indicators) {
            compensator.computeScale(j.point, camera.zoom, scaleBuffer)
            val radius = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                        scaleBuffer.radiusScale
            renderItems[itemCount++].populateJoint(j, radius, scaleBuffer.outlineScale)
        }

        for (i in 0 until skeletonBuffer.faceCount) {
            renderItems[itemCount++].populateFace(skeletonBuffer.faces[i])
        }

        // Initialize indices
        for (i in 0 until itemCount) indices[i] = i

        // In-place Insertion Sort of indices based on renderItems[idx].depth
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

        if (highlightedJoint != null) {
            val p = skeletonBuffer.joints[highlightedJoint.index]
            compensator.computeScale(p, camera.zoom, scaleBuffer)
            drawCircle(Color.White.copy(alpha = 0.5f), 12f * scaleBuffer.radiusScale, Offset(p.x, p.y))
        }

        for (i in 0 until itemCount) {
            val item = renderItems[indices[i]]
            when (item.type) {
                RenderItem.Type.BONE -> {
                    val b = item.bone!!
                    val color = getZColor(item.depth, b.isForeground, style)
                    val outline = style.outlineWidth * item.outlineScale
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness + (outline * 2f), Color(0xFF0A0F14))
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness, color)
                }
                RenderItem.Type.JOINT -> {
                    val j = item.joint!!
                    val color = getZColor(item.depth, j.isIndicator, style)
                    val outline = 2f * item.outlineScale
                    drawCircle(Color(0xFF0A0F14), item.radius + outline, Offset(j.point.x, j.point.y))
                    drawCircle(color, item.radius, Offset(j.point.x, j.point.y))
                }
                RenderItem.Type.FACE -> {
                    val f = item.face!!
                    val color = getZColor(f.avgDepth, false, style)
                    val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f)
                    val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f)
                    path.reset()
                    val facePoints = f.points
                    for (idx in 0 until facePoints.size) {
                        val p = facePoints[idx]
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, fillC)
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
                }
                else -> {}
            }
        }

        // 5. drawForeground()
        drawForeground()
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

private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): Color {
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val baseC = lerp(style.farColor, style.secondaryColor, t)
    return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearBone(start: Offset, end: Offset, thickness: Float, color: Color) {
    drawLine(color = color, start = start, end = end, strokeWidth = thickness, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroundPassive(
    skeleton: ProjectedSkeleton,
    style: SkeletonStyle,
    compensator: ScreenSpaceCompensation,
    zoom: Float,
    scaleBuffer: ScreenSpaceScale
) {
    val gridColor = Color(0x5A3A445C)
    for (i in 0 until skeleton.gridLineCount) {
        val l = skeleton.gridLines[i]
        drawLine(gridColor, Offset(l.p1.x, l.p1.y), Offset(l.p2.x, l.p2.y), strokeWidth = 1f)
    }
    val shadowColor = Color(0x9605080C)
    val shadowPoints = skeleton.shadowPoints
    for (i in 0 until shadowPoints.size) {
        val p = shadowPoints[i]
        compensator.computeScale(p, zoom, scaleBuffer)
        val sx = style.shadowRadiusX * scaleBuffer.shadowScale
        val sy = style.shadowRadiusY * scaleBuffer.shadowScale
        drawOval(shadowColor, Offset(p.x - sx, p.y - sy), androidx.compose.ui.geometry.Size(sx * 2f, sy * 2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackground() {
    // Left empty for future compatibility, fulfilling the rendering pipeline order.
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawForeground() {
    // Left empty for future compatibility, fulfilling the rendering pipeline order.
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEnvironmentPassive(
    environment: EnvironmentDefinition,
    camera: Camera,
    style: SkeletonStyle,
    width: Float,
    height: Float,
    path: Path
) {
    for (prop in environment.props) {
        when (prop) {
            is BoxProp -> drawBoxyProp(prop.center, prop.width, prop.height, prop.depth, camera, style, width, height, path) { it }
            is StepProp -> drawBoxyProp(prop.center, prop.width, prop.height, prop.depth, camera, style, width, height, path) {
                // StepProp: slight green/teal tint
                Color(it.red * 0.8f, it.green * 0.95f, it.blue * 0.9f, it.alpha)
            }
            is BenchProp -> drawBoxyProp(prop.center, prop.width, prop.height, prop.depth, camera, style, width, height, path) {
                // BenchProp: slight reddish/wood/orange-brown tint
                Color(it.red * 0.75f, it.green * 0.7f, it.blue * 0.65f, it.alpha)
            }
            is WallProp -> drawBoxyProp(prop.center, prop.width, prop.height, prop.depth, camera, style, width, height, path) {
                // WallProp: concrete/light-gray and semi-transparent
                it.copy(alpha = 0.5f)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBoxyProp(
    center: Vector3,
    pWidth: Float,
    pHeight: Float,
    pDepth: Float,
    camera: Camera,
    style: SkeletonStyle,
    width: Float,
    height: Float,
    path: Path,
    tint: (Color) -> Color
) {
    val hw = pWidth / 2f
    val hh = pHeight / 2f
    val hd = pDepth / 2f

    // 8 corners of the cuboid in world space
    val c0 = Vector3(center.x - hw, center.y - hh, center.z - hd)
    val c1 = Vector3(center.x + hw, center.y - hh, center.z - hd)
    val c2 = Vector3(center.x + hw, center.y + hh, center.z - hd)
    val c3 = Vector3(center.x - hw, center.y + hh, center.z - hd)
    val c4 = Vector3(center.x - hw, center.y - hh, center.z + hd)
    val c5 = Vector3(center.x + hw, center.y - hh, center.z + hd)
    val c6 = Vector3(center.x + hw, center.y + hh, center.z + hd)
    val c7 = Vector3(center.x - hw, center.y + hh, center.z + hd)

    // Project all 8 corners through Camera
    val p0 = ProjectedPoint(); camera.project(c0, width, height, p0)
    val p1 = ProjectedPoint(); camera.project(c1, width, height, p1)
    val p2 = ProjectedPoint(); camera.project(c2, width, height, p2)
    val p3 = ProjectedPoint(); camera.project(c3, width, height, p3)
    val p4 = ProjectedPoint(); camera.project(c4, width, height, p4)
    val p5 = ProjectedPoint(); camera.project(c5, width, height, p5)
    val p6 = ProjectedPoint(); camera.project(c6, width, height, p6)
    val p7 = ProjectedPoint(); camera.project(c7, width, height, p7)

    class PropFace(val pts: List<ProjectedPoint>) {
        val avgDepth = (pts[0].depth + pts[1].depth + pts[2].depth + pts[3].depth) / 4f
    }

    // 6 faces of the box
    val faces = listOf(
        PropFace(listOf(p0, p1, p2, p3)), // front (z = -hd)
        PropFace(listOf(p5, p4, p7, p6)), // back (z = +hd)
        PropFace(listOf(p4, p0, p3, p7)), // left (x = -hw)
        PropFace(listOf(p1, p5, p6, p2)), // right (x = +hw)
        PropFace(listOf(p3, p2, p6, p7)), // top (y = +hh)
        PropFace(listOf(p4, p5, p1, p0))  // bottom (y = -hh)
    )

    // Painter's algorithm: sort faces from back to front (descending depth)
    val sortedFaces = faces.sortedByDescending { it.avgDepth }

    for (f in sortedFaces) {
        val baseColor = getZColor(f.avgDepth, false, style)
        val color = tint(baseColor)
        val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, color.alpha)
        val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, color.alpha)

        path.reset()
        path.moveTo(f.pts[0].x, f.pts[0].y)
        path.lineTo(f.pts[1].x, f.pts[1].y)
        path.lineTo(f.pts[2].x, f.pts[2].y)
        path.lineTo(f.pts[3].x, f.pts[3].y)
        path.close()

        drawPath(path, fillC)
        drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
    }
}
