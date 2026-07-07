package com.monkfitness.app.animation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
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
    modifier: Modifier = Modifier,
    showGround: Boolean = true,
    highlightedJoint: Joint? = null
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        if (showGround) {
            drawGround(pose, camera, width, height)
        }

        val items = mutableListOf<DrawableItem>()

        // Add bones
        for (bone in SkeletonEngine.bones) {
            val p1 = camera.project(pose.getJoint(bone.parentJoint), width, height)
            val p2 = camera.project(pose.getJoint(bone.childJoint), width, height)
            val isForeground = bone.colorMultiplier >= 1.0f
            items.add(
                DrawableItem.BoneItem(
                    p1, p2, bone.thickness, bone.colorMultiplier, (p1.depth + p2.depth) / 2f, isForeground
                )
            )
        }

        // Add head
        val headPos = camera.project(pose.getJoint(Joint.HEAD_POS), width, height)
        items.add(
            DrawableItem.HeadItem(
                headPos, SkeletonEngine.HEADR, 1.0f, headPos.depth, false
            )
        )

        // Add active hand circle
        val handA = camera.project(pose.getJoint(Joint.HAND_A), width, height)
        items.add(
            DrawableItem.HeadItem(
                handA, 9f, 1.05f, handA.depth, true
            )
        )

        // Add torso box faces
        pose.torsoBox?.let { tb ->
            val pHLf = camera.project(tb.hLf, width, height)
            val pHLb = camera.project(tb.hLb, width, height)
            val pHRf = camera.project(tb.hRf, width, height)
            val pHRb = camera.project(tb.hRb, width, height)
            val pSLf = camera.project(tb.sLf, width, height)
            val pSLb = camera.project(tb.sLb, width, height)
            val pSRf = camera.project(tb.sRf, width, height)
            val pSRb = camera.project(tb.sRb, width, height)

            fun addFace(pts: List<ProjectedPoint>) {
                val avgDepth = pts.map { it.depth }.average().toFloat()
                items.add(DrawableItem.FaceItem(pts, avgDepth))
            }

            addFace(listOf(pSLf, pSRf, pHRf, pHLf)) // front
            addFace(listOf(pSRb, pSLb, pHLb, pHRb)) // back
            addFace(listOf(pSLb, pSLf, pHLf, pHLb)) // left
            addFace(listOf(pSRf, pSRb, pHRb, pHRf)) // right
            addFace(listOf(pSLb, pSRb, pSRf, pSLf)) // top
            addFace(listOf(pHLf, pHRf, pHRb, pHLb)) // bottom
        }

        // Sort by depth (back to front)
        items.sortByDescending { it.depth }

        // Add highlight if requested
        highlightedJoint?.let { jointId ->
            val pt = pose.getJoint(jointId)
            val p = camera.project(pt, width, height)
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = 12f * p.scale * camera.zoom,
                center = Offset(p.x, p.y)
            )
        }

        for (item in items) {
            when (item) {
                is DrawableItem.BoneItem -> {
                    val color = getZColor(item.depth, item.isForeground)
                    val sc = (item.p1.scale + item.p2.scale) / 2f
                    val thick = item.thickness * sc * camera.zoom

                    // Stroke/Outline
                    drawLinearBone(
                        Offset(item.p1.x, item.p1.y),
                        Offset(item.p2.x, item.p2.y),
                        thick + 4f,
                        Color(0xFF0A0F14)
                    )
                    // Main fill
                    drawLinearBone(
                        Offset(item.p1.x, item.p1.y),
                        Offset(item.p2.x, item.p2.y),
                        thick,
                        color
                    )
                }
                is DrawableItem.HeadItem -> {
                    val color = getZColor(item.depth, item.isForeground)
                    val rad = item.radius * item.p.scale * camera.zoom

                    // Outline
                    drawCircle(
                        color = Color(0xFF0A0F14),
                        radius = rad + 2f,
                        center = Offset(item.p.x, item.p.y)
                    )
                    // Fill
                    drawCircle(
                        color = color,
                        radius = rad,
                        center = Offset(item.p.x, item.p.y)
                    )
                }
                is DrawableItem.FaceItem -> {
                    val color = getZColor(item.depth, false)
                    val strokeC = Color(
                        red = color.red * 0.6f,
                        green = color.green * 0.6f,
                        blue = color.blue * 0.6f,
                        alpha = 1.0f
                    )
                    val fillC = Color(
                        red = color.red * 0.9f,
                        green = color.green * 0.9f,
                        blue = color.blue * 0.9f,
                        alpha = 1.0f
                    )

                    val path = Path().apply {
                        item.pts.forEachIndexed { index, p ->
                            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                        close()
                    }
                    drawPath(path, fillC)
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                }
            }
        }
    }
}

private sealed class DrawableItem(val depth: Float, val colorMultiplier: Float) {
    class BoneItem(
        val p1: ProjectedPoint,
        val p2: ProjectedPoint,
        val thickness: Float,
        colorMultiplier: Float,
        depth: Float,
        val isForeground: Boolean
    ) : DrawableItem(depth, colorMultiplier)

    class HeadItem(
        val p: ProjectedPoint,
        val radius: Float,
        colorMultiplier: Float,
        depth: Float,
        val isForeground: Boolean
    ) : DrawableItem(depth, colorMultiplier)

    class FaceItem(
        val pts: List<ProjectedPoint>,
        depth: Float
    ) : DrawableItem(depth, 1.0f)
}

private fun getZColor(depth: Float, isForeground: Boolean): Color {
    // map depth from 170..-170 to 0..1
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val farColor = Color(0xFF192337)
    val nearColor = Color(0xFFB4C8DC)

    val baseC = lerp(farColor, nearColor, t)
    return if (isForeground) {
        lerp(baseC, Color(0xFF64F0DC), 0.3f)
    } else {
        baseC
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearBone(
    start: Offset,
    end: Offset,
    thickness: Float,
    color: Color
) {
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = thickness,
        cap = StrokeCap.Round
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGround(
    pose: SkeletonPose,
    camera: Camera,
    width: Float,
    height: Float
) {
    // grid
    val gridColor = Color(0x5A3A445C) // rgba(58, 68, 92, 90/255)
    for (x in -260..260 step 65) {
        val a = camera.project(Vector3(x.toFloat(), 0f, -170f), width, height)
        val b = camera.project(Vector3(x.toFloat(), 0f, 170f), width, height)
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 1f)
    }
    for (z in -170..170 step 65) {
        val a = camera.project(Vector3(-260f, 0f, z.toFloat()), width, height)
        val b = camera.project(Vector3(260f, 0f, z.toFloat()), width, height)
        drawLine(gridColor, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 1f)
    }

    // contact shadows
    val shadowColor = Color(0x9605080C) // rgba(5, 8, 12, 150/255)
    val shadowPoints = listOf(Joint.TOE_F, Joint.TOE_B, Joint.HAND_P)
    for (id in shadowPoints) {
        val pt = pose.getJoint(id)
        val p = camera.project(Vector3(pt.x, 0f, pt.z), width, height)
        drawOval(
            color = shadowColor,
            topLeft = Offset(p.x - 30f * p.scale * camera.zoom, p.y - 9f * p.scale * camera.zoom),
            size = androidx.compose.ui.geometry.Size(60f * p.scale * camera.zoom, 18f * p.scale * camera.zoom)
        )
    }
}
