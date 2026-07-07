package com.monkfitness.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.SkeletonRenderer
import com.monkfitness.app.animation.rememberAnimationController
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.ui.components.animation.Joint
import com.monkfitness.app.ui.components.animation.SkeletonAnimation
import com.monkfitness.app.ui.components.animation.SkeletonOverlay
import com.monkfitness.app.ui.components.animation.SkeletonPose
import com.monkfitness.app.ui.components.animation.poseAt
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun ExerciseAnimatedVisual(
    exerciseId: String,
    animationId: String,
    animation: SkeletonAnimation,
    modifier: Modifier = Modifier
) {
    val poseConfig = PoseRegistry.getPoseConfig(animationId)

    if (poseConfig != null) {
        val controller = rememberAnimationController(
            mode = poseConfig.mode,
            alternating = poseConfig.alternating
        )
        val camera = remember { Camera() }
        val pose = poseConfig.builder.evaluate(controller.progress, controller.side)

        SkeletonRenderer(
            pose = pose,
            camera = camera,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "exercise-skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animation.durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "exercise-skeleton-progress"
    )
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier.fillMaxSize()) {
        val pose = animation.poseAt(progress).toCanvas(size)
        val torsoCenter = midpoint(pose.getValue(Joint.STERNUM), pose.getValue(Joint.PELVIS))
        val footSpan = distance(pose.getValue(Joint.L_TOE), pose.getValue(Joint.R_TOE)).coerceAtLeast(size.minDimension * 0.14f)

        drawCircle(
            color = secondary.copy(alpha = 0.08f),
            radius = size.minDimension * 0.24f,
            center = torsoCenter
        )
        drawShadow(pose = pose, color = secondary.copy(alpha = 0.14f), footSpan = footSpan)
        if (animation.showGround) {
            drawGround(pose = pose, color = outline.copy(alpha = 0.4f), strokeWidth = strokeWidth)
        }
        drawOverlays(animation.overlays, pose, outline, strokeWidth)
        drawSkeleton(pose = pose, fill = primary, outline = outline, strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawSkeleton(
    pose: Map<Joint, Offset>,
    fill: Color,
    outline: Color,
    strokeWidth: Float
) {
    val scale = size.minDimension
    val torsoCenter = midpoint(pose.getValue(Joint.STERNUM), pose.getValue(Joint.PELVIS))
    val torso = buildOrganicLimbPath(
        points = listOf(
            pose.getValue(Joint.NECK),
            pose.getValue(Joint.STERNUM),
            pose.getValue(Joint.SPINE_MID),
            pose.getValue(Joint.SPINE_LOW),
            pose.getValue(Joint.PELVIS)
        ),
        radii = listOf(scale * 0.04f, scale * 0.055f, scale * 0.06f, scale * 0.055f, scale * 0.05f)
    )
    val leftArm = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.L_SHOULDER), pose.getValue(Joint.L_ELBOW), pose.getValue(Joint.L_WRIST), pose.getValue(Joint.L_HAND)),
        radii = listOf(scale * 0.034f, scale * 0.028f, scale * 0.02f, scale * 0.014f)
    )
    val rightArm = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.R_SHOULDER), pose.getValue(Joint.R_ELBOW), pose.getValue(Joint.R_WRIST), pose.getValue(Joint.R_HAND)),
        radii = listOf(scale * 0.032f, scale * 0.027f, scale * 0.02f, scale * 0.014f)
    )
    val leftLeg = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.L_HIP), pose.getValue(Joint.L_KNEE), pose.getValue(Joint.L_ANKLE), pose.getValue(Joint.L_TOE)),
        radii = listOf(scale * 0.04f, scale * 0.03f, scale * 0.02f, scale * 0.012f)
    )
    val rightLeg = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.R_HIP), pose.getValue(Joint.R_KNEE), pose.getValue(Joint.R_ANKLE), pose.getValue(Joint.R_TOE)),
        radii = listOf(scale * 0.038f, scale * 0.03f, scale * 0.02f, scale * 0.012f)
    )
    val shoulderBridge = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.L_SHOULDER), pose.getValue(Joint.STERNUM), pose.getValue(Joint.R_SHOULDER)),
        radii = listOf(scale * 0.022f, scale * 0.03f, scale * 0.022f)
    )
    val hipBridge = buildOrganicLimbPath(
        points = listOf(pose.getValue(Joint.L_HIP), pose.getValue(Joint.PELVIS), pose.getValue(Joint.R_HIP)),
        radii = listOf(scale * 0.022f, scale * 0.03f, scale * 0.022f)
    )
    val head = buildHeadPath(top = pose.getValue(Joint.HEAD), bottom = pose.getValue(Joint.NECK), width = scale * 0.11f)

    drawCircle(color = fill.copy(alpha = 0.07f), radius = scale * 0.17f, center = torsoCenter)
    drawPath(rightLeg, fill.copy(alpha = 0.70f))
    drawPath(rightArm, fill.copy(alpha = 0.70f))
    drawPath(torso, fill)
    drawPath(shoulderBridge, fill)
    drawPath(hipBridge, fill)
    drawPath(leftLeg, fill)
    drawPath(leftArm, fill)
    drawPath(head, fill)

    val outlineColor = outline.copy(alpha = 0.58f)
    listOf(rightLeg, rightArm, torso, shoulderBridge, hipBridge, leftLeg, leftArm, head).forEach { path ->
        drawPath(path, outlineColor, style = Stroke(width = strokeWidth))
    }
}

private fun DrawScope.drawOverlays(
    overlays: Set<SkeletonOverlay>,
    pose: Map<Joint, Offset>,
    color: Color,
    strokeWidth: Float
) {
    if (SkeletonOverlay.PULL_BAR in overlays) {
        val barY = minOf(pose.getValue(Joint.L_HAND).y, pose.getValue(Joint.R_HAND).y)
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(size.width * 0.24f, barY),
            end = Offset(size.width * 0.76f, barY),
            strokeWidth = strokeWidth * 2f,
            cap = StrokeCap.Round
        )
    }
    if (SkeletonOverlay.HAND_CONNECTION in overlays) {
        drawLine(
            color = color.copy(alpha = 0.45f),
            start = pose.getValue(Joint.L_HAND),
            end = pose.getValue(Joint.R_HAND),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
    if (SkeletonOverlay.HEAD_ORBIT in overlays) {
        val center = midpoint(pose.getValue(Joint.HEAD), pose.getValue(Joint.NECK))
        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = distance(center, pose.getValue(Joint.HEAD)) + size.minDimension * 0.04f,
            center = center,
            style = Stroke(width = strokeWidth)
        )
    }
}

private fun DrawScope.drawShadow(
    pose: Map<Joint, Offset>,
    color: Color,
    footSpan: Float
) {
    val center = midpoint(pose.getValue(Joint.PELVIS), midpoint(pose.getValue(Joint.L_TOE), pose.getValue(Joint.R_TOE))) + Offset(0f, size.height * 0.12f)
    drawPath(
        path = buildEllipsePath(
            center = center,
            radiusX = footSpan * 0.55f,
            radiusY = size.minDimension * 0.05f
        ),
        color = color
    )
}

private fun DrawScope.drawGround(
    pose: Map<Joint, Offset>,
    color: Color,
    strokeWidth: Float
) {
    val groundY = max(pose.getValue(Joint.L_TOE).y, pose.getValue(Joint.R_TOE).y) + size.height * 0.02f
    drawLine(
        color = color,
        start = Offset(size.width * 0.12f, groundY),
        end = Offset(size.width * 0.88f, groundY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun SkeletonPose.toCanvas(size: Size): Map<Joint, Offset> =
    Joint.entries.associateWith { joint ->
        val point = this[joint]
        Offset(point.x * size.width, point.y * size.height)
    }

private fun buildHeadPath(
    top: Offset,
    bottom: Offset,
    width: Float
): Path {
    val center = midpoint(top, bottom)
    return buildEllipsePath(center = center, radiusX = width * 0.5f, radiusY = distance(top, bottom) * 0.65f)
}

private fun buildEllipsePath(
    center: Offset,
    radiusX: Float,
    radiusY: Float
): Path {
    val control = 0.5522848f
    return Path().apply {
        moveTo(center.x, center.y - radiusY)
        cubicTo(
            center.x + radiusX * control,
            center.y - radiusY,
            center.x + radiusX,
            center.y - radiusY * control,
            center.x + radiusX,
            center.y
        )
        cubicTo(
            center.x + radiusX,
            center.y + radiusY * control,
            center.x + radiusX * control,
            center.y + radiusY,
            center.x,
            center.y + radiusY
        )
        cubicTo(
            center.x - radiusX * control,
            center.y + radiusY,
            center.x - radiusX,
            center.y + radiusY * control,
            center.x - radiusX,
            center.y
        )
        cubicTo(
            center.x - radiusX,
            center.y - radiusY * control,
            center.x - radiusX * control,
            center.y - radiusY,
            center.x,
            center.y - radiusY
        )
        close()
    }
}

private fun buildOrganicLimbPath(
    points: List<Offset>,
    radii: List<Float>
): Path {
    require(points.size == radii.size && points.size >= 2)

    val tangents = points.indices.map { index ->
        when (index) {
            0 -> (points[1] - points[0]).safeNormalized(Offset(1f, 0f))
            points.lastIndex -> (points[index] - points[index - 1]).safeNormalized(Offset(1f, 0f))
            else -> (points[index + 1] - points[index - 1]).safeNormalized(Offset(1f, 0f))
        }
    }
    val leftEdge = points.indices.map { index -> points[index] + perpendicular(tangents[index]).scaled(radii[index]) }
    val rightEdge = points.indices.map { index -> points[index] - perpendicular(tangents[index]).scaled(radii[index]) }

    return Path().apply {
        moveTo(leftEdge.first().x, leftEdge.first().y)
        for (index in 0 until points.lastIndex) {
            val handleLength = distance(points[index], points[index + 1]) * 0.35f
            cubicTo(
                (leftEdge[index] + tangents[index].scaled(handleLength)).x,
                (leftEdge[index] + tangents[index].scaled(handleLength)).y,
                (leftEdge[index + 1] - tangents[index + 1].scaled(handleLength)).x,
                (leftEdge[index + 1] - tangents[index + 1].scaled(handleLength)).y,
                leftEdge[index + 1].x,
                leftEdge[index + 1].y
            )
        }
        cubicTo(
            (leftEdge.last() + tangents.last().scaled(radii.last() * 0.8f)).x,
            (leftEdge.last() + tangents.last().scaled(radii.last() * 0.8f)).y,
            (rightEdge.last() + tangents.last().scaled(radii.last() * 0.8f)).x,
            (rightEdge.last() + tangents.last().scaled(radii.last() * 0.8f)).y,
            rightEdge.last().x,
            rightEdge.last().y
        )
        for (index in points.lastIndex downTo 1) {
            val handleLength = distance(points[index], points[index - 1]) * 0.35f
            cubicTo(
                (rightEdge[index] - tangents[index].scaled(handleLength)).x,
                (rightEdge[index] - tangents[index].scaled(handleLength)).y,
                (rightEdge[index - 1] + tangents[index - 1].scaled(handleLength)).x,
                (rightEdge[index - 1] + tangents[index - 1].scaled(handleLength)).y,
                rightEdge[index - 1].x,
                rightEdge[index - 1].y
            )
        }
        cubicTo(
            (rightEdge.first() - tangents.first().scaled(radii.first() * 0.8f)).x,
            (rightEdge.first() - tangents.first().scaled(radii.first() * 0.8f)).y,
            (leftEdge.first() - tangents.first().scaled(radii.first() * 0.8f)).x,
            (leftEdge.first() - tangents.first().scaled(radii.first() * 0.8f)).y,
            leftEdge.first().x,
            leftEdge.first().y
        )
        close()
    }
}

private fun midpoint(a: Offset, b: Offset): Offset = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

private fun distance(a: Offset, b: Offset): Float = (b - a).magnitude()

private fun perpendicular(vector: Offset): Offset = Offset(-vector.y, vector.x)

private fun Offset.magnitude(): Float = sqrt((x * x) + (y * y))

private fun Offset.safeNormalized(fallback: Offset): Offset {
    val length = magnitude()
    return if (length < 0.0001f) fallback else Offset(x / length, y / length)
}

private fun Offset.scaled(scale: Float): Offset = Offset(x * scale, y * scale)
