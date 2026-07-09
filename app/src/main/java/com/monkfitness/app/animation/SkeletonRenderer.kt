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
    val finalizedPose = remember(pose, finalizer) { finalizer.finalize(pose) }
    val compensator = remember(screenSpaceSettings) { ScreenSpaceCompensation(screenSpaceSettings) }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        if (showGround) {
            drawGround(finalizedPose, camera, style, width, height)
        }

        val items = mutableListOf<DrawableItem>()

        // Add bones
        for (bone in engine.bones) {
            val p1 = camera.project(finalizedPose.getJoint(bone.parentJoint), width, height)
            val p2 = camera.project(finalizedPose.getJoint(bone.childJoint), width, height)

            val avgDepth = (p1.depth + p2.depth) / 2f

            // Apply screen-space depth compensation to thickness
            val depthScale = compensator.getThicknessScale(avgDepth)
            val compensatedThickness = bone.thickness * depthScale

            val isForeground = bone.colorMultiplier >= 1.0f
            items.add(
                DrawableItem.BoneItem(
                    p1, p2, compensatedThickness, bone.colorMultiplier, avgDepth, isForeground
                )
            )
        }

        // Add head
        val headPos = camera.project(finalizedPose.getJoint(Joint.HEAD_POS), width, height)
        val headDepthScale = compensator.getJointScale(headPos.depth)
        items.add(
            DrawableItem.HeadItem(
                headPos, style.headRadius * headDepthScale, 1.0f, headPos.depth, false
            )
        )

        // Add active hand circle (centered on wrist/palm junction)
        val wristA = camera.project(finalizedPose.getJoint(Joint.WRIST_A), width, height)
        val handDepthScale = compensator.getJointScale(wristA.depth)
        items.add(
            DrawableItem.HeadItem(
                wristA, style.jointRadius * handDepthScale, 1.05f, wristA.depth, true
            )
        )

        // Calculate torso box faces on-the-fly
        val hipF = finalizedPose.getJoint(Joint.HIP_F)
        val hipB = finalizedPose.getJoint(Joint.HIP_B)
        val shoulderA = finalizedPose.getJoint(Joint.SHOULDER_A)
        val shoulderP = finalizedPose.getJoint(Joint.SHOULDER_P)
        val pelvis = finalizedPose.getJoint(Joint.PELVIS)
        val chest = finalizedPose.getJoint(Joint.CHEST)

        val lean = (chest - pelvis).normalize()
        val shVec = (shoulderA - shoulderP).normalize()
        val chestNorm = lean.cross(shVec).normalize()

        val offC = chestNorm * style.torsoChestDepth
        val offH = chestNorm * style.torsoHipDepth

        val pHLf = camera.project(hipF + offH, width, height)
        val pHLb = camera.project(hipF - offH, width, height)
        val pHRf = camera.project(hipB + offH, width, height)
        val pHRb = camera.project(hipB - offH, width, height)
        val pSLf = camera.project(shoulderA + offC, width, height)
        val pSLb = camera.project(shoulderA - offC, width, height)
        val pSRf = camera.project(shoulderP + offC, width, height)
        val pSRb = camera.project(shoulderP - offC, width, height)

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

        // Sort by depth (back to front)
        items.sortByDescending { it.depth }

        // Add highlight if requested
        highlightedJoint?.let { jointId ->
            val pt = finalizedPose.getJoint(jointId)
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
                    val color = getZColor(item.depth, item.isForeground, style)
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
                    val color = getZColor(item.depth, item.isForeground, style)
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
                    val color = getZColor(item.depth, false, style)
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
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
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

private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): Color {
    // map depth from 170..-170 to 0..1
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val farColor = style.farColor
    val nearColor = style.secondaryColor

    val baseC = lerp(farColor, nearColor, t)
    return if (isForeground) {
        lerp(baseC, style.primaryColor, 0.3f)
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
    style: SkeletonStyle,
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
    val shadowPoints = listOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)
    for (id in shadowPoints) {
        val pt = pose.getJoint(id)
        val p = camera.project(Vector3(pt.x, 0f, pt.z), width, height)
        drawOval(
            color = shadowColor,
            topLeft = Offset(p.x - style.shadowRadiusX * p.scale * camera.zoom, p.y - style.shadowRadiusY * p.scale * camera.zoom),
            size = androidx.compose.ui.geometry.Size(style.shadowRadiusX * 2f * p.scale * camera.zoom, style.shadowRadiusY * 2f * p.scale * camera.zoom)
        )
    }
}
