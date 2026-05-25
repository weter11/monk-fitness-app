package com.monkfitness.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.monkfitness.app.data.model.ExerciseAnimationProfile
import kotlin.math.abs

@Composable
fun ExerciseAnimatedVisual(
    profile: ExerciseAnimationProfile,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "exercise-visual")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "exercise-visual-phase"
    )
    val strokeWidth = with(LocalDensity.current) { 6.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val swing = phase * 2f - 1f
        val lift = abs(swing)

        drawCircle(
            color = secondary.copy(alpha = 0.08f),
            radius = size.minDimension * (0.24f + 0.04f * lift),
            center = center
        )

        when (profile) {
            ExerciseAnimationProfile.PUSH_UP -> drawPushUp(primary, outline, strokeWidth, swing)
            ExerciseAnimationProfile.LOWER_BODY -> drawLowerBody(primary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.SUSPENSION_PULL -> drawSuspensionPull(primary, secondary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.PLANK_FLOW -> drawPlankFlow(primary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.GLUTE_BRIDGE -> drawGluteBridge(primary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.QUADRUPED_FLOW -> drawQuadrupedFlow(primary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.FLOOR_STRETCH -> drawFloorStretch(primary, outline, strokeWidth, swing)
            ExerciseAnimationProfile.FULL_BODY_FLOW -> drawFullBodyFlow(primary, outline, strokeWidth, swing, lift)
            ExerciseAnimationProfile.UPPER_BODY_POSTURE -> drawUpperBodyPosture(primary, secondary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.HIP_MOBILITY -> drawHipMobility(primary, outline, strokeWidth, swing)
            ExerciseAnimationProfile.HORSE_STANCE -> drawHorseStance(primary, outline, strokeWidth, lift)
            ExerciseAnimationProfile.NECK_MOBILITY -> drawNeckMobility(primary, secondary, outline, strokeWidth, swing)
            ExerciseAnimationProfile.JUMPING_JACK -> drawJumpingJack(primary, outline, strokeWidth, lift)
        }
    }
}

private fun DrawScope.drawPushUp(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    swing: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.25f, 0.45f + 0.03f * swing),
        shoulder = point(0.38f, 0.56f + 0.04f * swing),
        hip = point(0.57f, 0.57f - 0.02f * swing),
        leftHand = point(0.24f, 0.79f),
        rightHand = point(0.38f, 0.79f),
        leftFoot = point(0.75f, 0.78f),
        rightFoot = point(0.86f, 0.78f),
        leftElbow = point(0.28f, 0.66f + 0.04f * swing),
        rightElbow = point(0.36f, 0.68f + 0.04f * swing)
    )
}

private fun DrawScope.drawLowerBody(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.5f, 0.23f + 0.01f * lift),
        shoulder = point(0.5f, 0.38f + 0.02f * lift),
        hip = point(0.5f, 0.56f + 0.05f * lift),
        leftHand = point(0.37f, 0.48f),
        rightHand = point(0.63f, 0.48f),
        leftFoot = point(0.38f, 0.9f),
        rightFoot = point(0.64f, 0.9f),
        leftKnee = point(0.42f, 0.73f - 0.05f * lift),
        rightKnee = point(0.6f, 0.73f - 0.05f * lift)
    )
}

private fun DrawScope.drawSuspensionPull(
    color: Color,
    secondary: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    val leftHand = point(0.38f, 0.12f)
    val rightHand = point(0.62f, 0.12f)

    drawLine(
        color = secondary.copy(alpha = 0.55f),
        start = point(0.24f, 0.12f),
        end = point(0.76f, 0.12f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(color = accent, start = leftHand, end = point(0.38f, 0.24f), strokeWidth = strokeWidth / 2f)
    drawLine(color = accent, start = rightHand, end = point(0.62f, 0.24f), strokeWidth = strokeWidth / 2f)

    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.5f, 0.28f + 0.05f * lift),
        shoulder = point(0.5f, 0.42f + 0.05f * lift),
        hip = point(0.5f, 0.61f + 0.05f * lift),
        leftHand = leftHand,
        rightHand = rightHand,
        leftFoot = point(0.44f, 0.84f - 0.05f * lift),
        rightFoot = point(0.56f, 0.84f - 0.05f * lift)
    )
}

private fun DrawScope.drawPlankFlow(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.28f, 0.49f),
        shoulder = point(0.4f, 0.57f),
        hip = point(0.58f, 0.57f - 0.02f * lift),
        leftHand = point(0.24f, 0.79f),
        rightHand = point(0.39f, 0.79f),
        leftFoot = point(0.83f, 0.79f),
        rightFoot = point(0.87f, 0.74f - 0.12f * lift),
        leftKnee = point(0.69f, 0.72f),
        rightKnee = point(0.74f, 0.68f - 0.1f * lift),
        leftElbow = point(0.29f, 0.69f),
        rightElbow = point(0.38f, 0.69f)
    )
}

private fun DrawScope.drawGluteBridge(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.24f, 0.67f),
        shoulder = point(0.35f, 0.66f),
        hip = point(0.56f, 0.61f - 0.08f * lift),
        leftHand = point(0.24f, 0.8f),
        rightHand = point(0.36f, 0.8f),
        leftFoot = point(0.68f, 0.82f),
        rightFoot = point(0.8f, 0.82f),
        leftKnee = point(0.67f, 0.68f),
        rightKnee = point(0.78f, 0.68f)
    )
}

private fun DrawScope.drawQuadrupedFlow(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.28f, 0.42f + 0.01f * lift),
        shoulder = point(0.4f, 0.5f),
        hip = point(0.57f, 0.58f - 0.03f * lift),
        leftHand = point(0.26f, 0.62f - 0.1f * lift),
        rightHand = point(0.47f, 0.74f),
        leftFoot = point(0.61f, 0.86f),
        rightFoot = point(0.82f, 0.73f - 0.05f * lift),
        leftKnee = point(0.59f, 0.74f),
        rightKnee = point(0.74f, 0.74f),
        rightElbow = point(0.44f, 0.66f)
    )
}

private fun DrawScope.drawFloorStretch(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    swing: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.3f, 0.46f - 0.05f * swing),
        shoulder = point(0.4f, 0.57f - 0.04f * swing),
        hip = point(0.56f, 0.67f + 0.03f * swing),
        leftHand = point(0.22f, 0.75f),
        rightHand = point(0.4f, 0.73f),
        leftFoot = point(0.67f, 0.87f),
        rightFoot = point(0.79f, 0.85f),
        leftKnee = point(0.67f, 0.74f),
        rightKnee = point(0.78f, 0.74f)
    )
}

private fun DrawScope.drawFullBodyFlow(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    swing: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.52f, 0.24f + 0.03f * lift),
        shoulder = point(0.52f, 0.38f + 0.04f * lift),
        hip = point(0.52f, 0.56f + 0.06f * lift),
        leftHand = point(0.34f, 0.48f - 0.08f * swing),
        rightHand = point(0.7f, 0.48f - 0.08f * swing),
        leftFoot = point(0.38f, 0.9f),
        rightFoot = point(0.66f, 0.9f),
        leftKnee = point(0.43f, 0.73f - 0.04f * lift),
        rightKnee = point(0.62f, 0.73f - 0.04f * lift)
    )
}

private fun DrawScope.drawUpperBodyPosture(
    color: Color,
    secondary: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    val leftHand = point(0.32f - 0.05f * lift, 0.54f - 0.18f * lift)
    val rightHand = point(0.68f + 0.05f * lift, 0.54f - 0.18f * lift)

    drawGround(accent, strokeWidth)
    drawLine(
        color = secondary.copy(alpha = 0.45f),
        start = leftHand,
        end = rightHand,
        strokeWidth = strokeWidth / 2f,
        cap = StrokeCap.Round
    )
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.5f, 0.22f),
        shoulder = point(0.5f, 0.38f),
        hip = point(0.5f, 0.56f),
        leftHand = leftHand,
        rightHand = rightHand,
        leftFoot = point(0.44f, 0.9f),
        rightFoot = point(0.56f, 0.9f)
    )
}

private fun DrawScope.drawHipMobility(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    swing: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.48f, 0.22f),
        shoulder = point(0.48f, 0.38f),
        hip = point(0.48f, 0.56f),
        leftHand = point(0.36f, 0.46f),
        rightHand = point(0.6f, 0.46f),
        leftFoot = point(0.4f, 0.9f),
        rightFoot = point(0.73f, 0.81f - 0.12f * swing),
        leftKnee = point(0.42f, 0.73f),
        rightKnee = point(0.61f, 0.68f - 0.09f * swing)
    )
}

private fun DrawScope.drawHorseStance(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.5f, 0.23f),
        shoulder = point(0.5f, 0.38f),
        hip = point(0.5f, 0.58f + 0.02f * lift),
        leftHand = point(0.34f, 0.5f),
        rightHand = point(0.66f, 0.5f),
        leftFoot = point(0.22f, 0.9f),
        rightFoot = point(0.78f, 0.9f),
        leftKnee = point(0.34f, 0.75f),
        rightKnee = point(0.66f, 0.75f)
    )
}

private fun DrawScope.drawNeckMobility(
    color: Color,
    secondary: Color,
    accent: Color,
    strokeWidth: Float,
    swing: Float
) {
    val head = point(0.5f + 0.03f * swing, 0.22f)

    drawGround(accent, strokeWidth)
    drawCircle(
        color = secondary.copy(alpha = 0.2f),
        center = point(0.5f, 0.22f),
        radius = size.minDimension * 0.12f,
        style = Stroke(width = strokeWidth / 2f)
    )
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = head,
        shoulder = point(0.5f, 0.38f),
        hip = point(0.5f, 0.56f),
        leftHand = point(0.4f, 0.46f),
        rightHand = point(0.6f, 0.46f),
        leftFoot = point(0.44f, 0.9f),
        rightFoot = point(0.56f, 0.9f)
    )
}

private fun DrawScope.drawJumpingJack(
    color: Color,
    accent: Color,
    strokeWidth: Float,
    lift: Float
) {
    drawGround(accent, strokeWidth)
    drawStickFigure(
        color = color,
        strokeWidth = strokeWidth,
        head = point(0.5f, 0.22f),
        shoulder = point(0.5f, 0.38f),
        hip = point(0.5f, 0.56f),
        leftHand = point(0.3f - 0.08f * lift, 0.56f - 0.28f * lift),
        rightHand = point(0.7f + 0.08f * lift, 0.56f - 0.28f * lift),
        leftFoot = point(0.42f - 0.14f * lift, 0.9f),
        rightFoot = point(0.58f + 0.14f * lift, 0.9f)
    )
}

private fun DrawScope.drawStickFigure(
    color: Color,
    strokeWidth: Float,
    head: Offset,
    shoulder: Offset,
    hip: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftFoot: Offset,
    rightFoot: Offset,
    leftElbow: Offset = lerp(shoulder, leftHand, 0.5f),
    rightElbow: Offset = lerp(shoulder, rightHand, 0.5f),
    leftKnee: Offset = lerp(hip, leftFoot, 0.5f),
    rightKnee: Offset = lerp(hip, rightFoot, 0.5f)
) {
    val headRadius = size.minDimension * 0.07f

    drawCircle(color = color, radius = headRadius, center = head)
    drawLine(
        color = color,
        start = Offset(head.x, head.y + headRadius),
        end = shoulder,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(color = color, start = shoulder, end = hip, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawJointChain(color, shoulder, leftElbow, leftHand, strokeWidth)
    drawJointChain(color, shoulder, rightElbow, rightHand, strokeWidth)
    drawJointChain(color, hip, leftKnee, leftFoot, strokeWidth)
    drawJointChain(color, hip, rightKnee, rightFoot, strokeWidth)
}

private fun DrawScope.drawJointChain(
    color: Color,
    start: Offset,
    middle: Offset,
    end: Offset,
    strokeWidth: Float
) {
    drawLine(color = color, start = start, end = middle, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color = color, start = middle, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

private fun DrawScope.drawGround(
    color: Color,
    strokeWidth: Float
) {
    drawLine(
        color = color,
        start = point(0.12f, 0.92f),
        end = point(0.88f, 0.92f),
        strokeWidth = strokeWidth / 2f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.point(x: Float, y: Float): Offset = Offset(size.width * x, size.height * y)
