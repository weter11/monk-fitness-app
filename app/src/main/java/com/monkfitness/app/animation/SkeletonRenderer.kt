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

        // 1. Camera Projection Stage
        val projectedJoints = Joint.entries.associateWith { id ->
            camera.project(finalizedPose.getJoint(id), width, height)
        }
        val skeleton = ProjectedSkeleton(projectedJoints)

        if (showGround) {
            drawGround(skeleton, camera, style)
        }

        val items = mutableListOf<DrawableItem>()

        // 2. Rendering & ScreenSpaceCompensation Stage
        for (bone in engine.bones) {
            val p1 = skeleton.getJoint(bone.parentJoint)
            val p2 = skeleton.getJoint(bone.childJoint)

            val avgPoint = ProjectedPoint(
                (p1.x + p2.x) / 2f,
                (p1.y + p2.y) / 2f,
                (p1.depth + p2.depth) / 2f,
                (p1.perspectiveScale + p2.perspectiveScale) / 2f
            )

            val scale = compensator.computeScale(avgPoint)
            val thickness = bone.thickness * scale.thicknessScale * camera.zoom

            val isForeground = bone.colorMultiplier >= 1.0f
            items.add(
                DrawableItem.BoneItem(
                    p1, p2, thickness, bone.colorMultiplier, avgPoint.depth, isForeground
                )
            )
        }

        // Add head
        val headPoint = skeleton.getJoint(Joint.HEAD_POS)
        val headScale = compensator.computeScale(headPoint)
        items.add(
            DrawableItem.HeadItem(
                headPoint,
                style.headRadius * headScale.radiusScale * camera.zoom,
                1.0f,
                headPoint.depth,
                false
            )
        )

        // Add active hand circle (indicator)
        val wristA = skeleton.getJoint(Joint.WRIST_A)
        val handScale = compensator.computeScale(wristA)
        items.add(
            DrawableItem.HeadItem(
                wristA,
                style.jointRadius * handScale.radiusScale * camera.zoom,
                1.05f,
                wristA.depth,
                true
            )
        )

        // Torso Box (always passive, depends only on projected points)
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

        // Add highlight
        highlightedJoint?.let { jointId ->
            val p = skeleton.getJoint(jointId)
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = 12f * p.perspectiveScale * camera.zoom,
                center = Offset(p.x, p.y)
            )
        }

        for (item in items) {
            when (item) {
                is DrawableItem.BoneItem -> {
                    val color = getZColor(item.depth, item.isForeground, style)
                    // Apply outline scale if we had distinct outline rendering
                    val outlineWidth = style.outlineWidth // could be scaled by compensator if needed

                    drawLinearBone(
                        Offset(item.p1.x, item.p1.y),
                        Offset(item.p2.x, item.p2.y),
                        item.thickness + (outlineWidth * 2f),
                        Color(0xFF0A0F14)
                    )
                    drawLinearBone(
                        Offset(item.p1.x, item.p1.y),
                        Offset(item.p2.x, item.p2.y),
                        item.thickness,
                        color
                    )
                }
                is DrawableItem.HeadItem -> {
                    val color = getZColor(item.depth, item.isForeground, style)

                    drawCircle(
                        color = Color(0xFF0A0F14),
                        radius = item.radius + 2f,
                        center = Offset(item.p.x, item.p.y)
                    )
                    drawCircle(
                        color = color,
                        radius = item.radius,
                        center = Offset(item.p.x, item.p.y)
                    )
                }
                is DrawableItem.FaceItem -> {
                    val color = getZColor(item.depth, false, style)
                    val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f)
                    val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f)

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
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val farColor = style.farColor
    val nearColor = style.secondaryColor
    val baseC = lerp(farColor, nearColor, t)
    return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
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
    skeleton: ProjectedSkeleton,
    camera: Camera,
    style: SkeletonStyle
) {
    val width = size.width
    val height = size.height
    val gridColor = Color(0x5A3A445C)

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

    val shadowColor = Color(0x9605080C)
    val shadowJoints = listOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)

    for (id in shadowJoints) {
        val p = skeleton.getJoint(id)
        // Adjust shadow size by perspective
        val sx = style.shadowRadiusX * p.perspectiveScale * camera.zoom
        val sy = style.shadowRadiusY * p.perspectiveScale * camera.zoom

        drawOval(
            color = shadowColor,
            topLeft = Offset(p.x - sx, p.y - sy),
            size = androidx.compose.ui.geometry.Size(sx * 2f, sy * 2f)
        )
    }
}
