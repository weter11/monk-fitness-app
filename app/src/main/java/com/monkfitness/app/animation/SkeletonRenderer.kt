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

        if (showGround) {
            drawGroundPassive(skeletonBuffer, style, compensator, camera.zoom, scaleBuffer)
        }

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
