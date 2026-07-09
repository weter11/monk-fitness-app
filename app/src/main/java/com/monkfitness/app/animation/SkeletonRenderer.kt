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
    val items = remember { mutableListOf<DrawableItem>() }
    val scaleBuffer = remember { ScreenSpaceScale() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalizedPose = finalizer.finalize(pose)
        projector.project(finalizedPose, camera, engine, width, height, skeletonBuffer)

        if (showGround) {
            drawGroundPassive(skeletonBuffer, style, camera.zoom)
        }

        items.clear()

        for (i in 0 until skeletonBuffer.boneCount) {
            val b = skeletonBuffer.bones[i]
            compensator.computeScale(b.p1, scaleBuffer)
            val thickness = b.thickness * scaleBuffer.thicknessScale * camera.zoom
            items.add(DrawableItem.BoneItem(b, thickness, scaleBuffer.outlineScale))
        }

        for (j in skeletonBuffer.indicators) {
            compensator.computeScale(j.point, scaleBuffer)
            val radius = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                        scaleBuffer.radiusScale * camera.zoom
            items.add(DrawableItem.JointItem(j, radius, scaleBuffer.outlineScale))
        }

        for (i in 0 until skeletonBuffer.faceCount) {
            items.add(DrawableItem.FaceItem(skeletonBuffer.faces[i]))
        }

        items.sortByDescending { it.depth }

        highlightedJoint?.let { id ->
            val p = skeletonBuffer.jointsMap[id.ordinal]
            drawCircle(Color.White.copy(alpha = 0.5f), 12f * p.perspectiveScale * camera.zoom, Offset(p.x, p.y))
        }

        for (item in items) {
            when (item) {
                is DrawableItem.BoneItem -> {
                    val b = item.bone
                    val color = getZColor(item.depth, b.isForeground, style)
                    val outline = style.outlineWidth * item.outlineScale
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness + (outline * 2f), Color(0xFF0A0F14))
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness, color)
                }
                is DrawableItem.JointItem -> {
                    val j = item.joint
                    val color = getZColor(item.depth, j.isIndicator, style)
                    val outline = 2f * item.outlineScale
                    drawCircle(Color(0xFF0A0F14), item.radius + outline, Offset(j.point.x, j.point.y))
                    drawCircle(color, item.radius, Offset(j.point.x, j.point.y))
                }
                is DrawableItem.FaceItem -> {
                    val f = item.face
                    val color = getZColor(f.avgDepth, false, style)
                    val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f)
                    val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f)
                    val path = Path().apply {
                        f.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                        close()
                    }
                    drawPath(path, fillC)
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
                }
            }
        }
    }
}

private sealed class DrawableItem(val depth: Float) {
    class BoneItem(val bone: ProjectedBone, val thickness: Float, val outlineScale: Float) : DrawableItem(bone.avgDepth)
    class JointItem(val joint: ProjectedJoint, val radius: Float, val outlineScale: Float) : DrawableItem(joint.point.depth)
    class FaceItem(val face: ProjectedFace) : DrawableItem(face.avgDepth)
}

private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): Color {
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val baseC = lerp(style.farColor, style.secondaryColor, t)
    return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearBone(start: Offset, end: Offset, thickness: Float, color: Color) {
    drawLine(color = color, start = start, end = end, strokeWidth = thickness, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroundPassive(skeleton: ProjectedSkeleton, style: SkeletonStyle, zoom: Float) {
    val gridColor = Color(0x5A3A445C)
    for (i in 0 until skeleton.gridLineCount) {
        val l = skeleton.gridLines[i]
        drawLine(gridColor, Offset(l.p1.x, l.p1.y), Offset(l.p2.x, l.p2.y), strokeWidth = 1f)
    }
    val shadowColor = Color(0x9605080C)
    for (p in skeleton.shadowPoints) {
        val sx = style.shadowRadiusX * p.perspectiveScale * zoom
        val sy = style.shadowRadiusY * p.perspectiveScale * zoom
        drawOval(shadowColor, Offset(p.x - sx, p.y - sy), androidx.compose.ui.geometry.Size(sx * 2f, sy * 2f))
    }
}
