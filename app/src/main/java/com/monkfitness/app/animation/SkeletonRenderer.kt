package com.monkfitness.app.animation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
            items.add(
                DrawableItem.BoneItem(
                    p1, p2, bone.thickness, bone.colorMultiplier, (p1.depth + p2.depth) / 2f
                )
            )
        }

        // Add head
        val headPos = camera.project(pose.getJoint(Joint.HEAD_POS), width, height)
        items.add(
            DrawableItem.HeadItem(
                headPos, SkeletonEngine.HEADR, 1.0f, headPos.depth
            )
        )

        // Add active hand circle (as in p5.js)
        val handA = camera.project(pose.getJoint(Joint.HAND_A), width, height)
        items.add(
            DrawableItem.HeadItem(
                handA, 7f, 1.05f, handA.depth
            )
        )

        // Sort by depth (back to front)
        items.sortByDescending { it.depth }

        for (item in items) {
            val color = getSegShade(item.depth, item.colorMultiplier)
            when (item) {
                is DrawableItem.BoneItem -> {
                    val sc = (item.p1.scale + item.p2.scale) / 2f
                    drawLinearBone(
                        Offset(item.p1.x, item.p1.y),
                        Offset(item.p2.x, item.p2.y),
                        item.thickness * sc * camera.zoom * 0.78f,
                        color
                    )
                }
                is DrawableItem.HeadItem -> {
                    drawCircle(
                        color = color,
                        radius = item.radius * item.p.scale * camera.zoom * 0.9f,
                        center = Offset(item.p.x, item.p.y)
                    )
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
        depth: Float
    ) : DrawableItem(depth, colorMultiplier)

    class HeadItem(
        val p: ProjectedPoint,
        val radius: Float,
        colorMultiplier: Float,
        depth: Float
    ) : DrawableItem(depth, colorMultiplier)
}

private fun getSegShade(depth: Float, mult: Float): Color {
    // map depth from 170..-170 to 0..1 (near = light)
    val t = ((depth + 170f) / 340f).coerceIn(0f, 1f)
    val nearColor = Color(0xFFF6DBB2)
    val farColor = Color(0xFF4A3E48)

    val c = lerp(farColor, nearColor, t)
    return Color(
        red = (c.red * mult).coerceIn(0f, 1f),
        green = (c.green * mult).coerceIn(0f, 1f),
        blue = (c.blue * mult).coerceIn(0f, 1f),
        alpha = 1.0f
    )
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
    val shadowColor = Color(0x780A0C14) // rgba(10, 12, 20, 120/255)
    val shadowPoints = listOf(Joint.TOE_F, Joint.TOE_B, Joint.HAND_P)
    for (id in shadowPoints) {
        val pt = pose.getJoint(id)
        val p = camera.project(Vector3(pt.x, 0f, pt.z), width, height)
        drawOval(
            color = shadowColor,
            topLeft = Offset(p.x - 28f * p.scale * camera.zoom, p.y - 8f * p.scale * camera.zoom),
            size = androidx.compose.ui.geometry.Size(56f * p.scale * camera.zoom, 16f * p.scale * camera.zoom)
        )
    }

    val handA = pose.getJoint(Joint.HAND_A)
    if (handA.y < 40f) {
        val p = camera.project(Vector3(handA.x, 0f, handA.z), width, height)
        val alpha = ((40f - handA.y) / 34f).coerceIn(0f, 1f) * (110f / 255f)
        drawOval(
            color = Color(0x0A0C14).copy(alpha = alpha),
            topLeft = Offset(p.x - 20f * p.scale * camera.zoom, p.y - 6f * p.scale * camera.zoom),
            size = androidx.compose.ui.geometry.Size(40f * p.scale * camera.zoom, 12f * p.scale * camera.zoom)
        )
    }
}
