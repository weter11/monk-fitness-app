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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.monkfitness.app.data.model.ExerciseAnimationProfile
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ExerciseAnimatedVisual(
    profile: ExerciseAnimationProfile,
    modifier: Modifier = Modifier
) {
    if (profile == ExerciseAnimationProfile.PUSH_UP) {
        PushUpCharacterAnimation(modifier = modifier)
        return
    }

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
            ExerciseAnimationProfile.PUSH_UP -> Unit
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

@Composable
private fun PushUpCharacterAnimation(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "push-up-character")
    val drive by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "push-up-drive"
    )
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = modifier.fillMaxSize()) {
        val skeleton = buildPushUpSkeleton(size = size, drive = drive)
        drawPushUpScene(
            skeleton = skeleton,
            fill = primary,
            outline = outline,
            shadow = secondary.copy(alpha = 0.16f)
        )
    }
}

/**
 * Full 22-point rig used by the procedural push-up animation.
 */
private data class PushUpSkeleton(
    val head: Offset,
    val neck: Offset,
    val midSpine: Offset,
    val pelvis: Offset,
    val leftShoulder: Offset,
    val rightShoulder: Offset,
    val leftElbow: Offset,
    val rightElbow: Offset,
    val leftWrist: Offset,
    val rightWrist: Offset,
    val leftHand: Offset,
    val rightHand: Offset,
    val leftHip: Offset,
    val rightHip: Offset,
    val leftKnee: Offset,
    val rightKnee: Offset,
    val leftAnkle: Offset,
    val rightAnkle: Offset,
    val leftHeel: Offset,
    val rightHeel: Offset,
    val leftToe: Offset,
    val rightToe: Offset
) {
    val orderedPoints: List<Offset>
        get() = listOf(
            head,
            neck,
            midSpine,
            pelvis,
            leftShoulder,
            rightShoulder,
            leftElbow,
            rightElbow,
            leftWrist,
            rightWrist,
            leftHand,
            rightHand,
            leftHip,
            rightHip,
            leftKnee,
            rightKnee,
            leftAnkle,
            rightAnkle,
            leftHeel,
            rightHeel,
            leftToe,
            rightToe
        )
}

/**
 * Standard 2-bone IK solver.
 * Root and target define the limb, and the hint chooses which side the joint bends toward.
 */
private fun solveTwoBoneIk(
    root: Offset,
    target: Offset,
    upperLength: Float,
    lowerLength: Float,
    hint: Offset
): Offset {
    val targetVector = target - root
    val targetDistance = max(targetVector.magnitude(), 0.001f)
    val clampedDistance = targetDistance.coerceIn(
        minimumValue = abs(upperLength - lowerLength) + 0.001f,
        maximumValue = upperLength + lowerLength - 0.001f
    )
    val direction = targetVector.safeNormalized(fallback = Offset(1f, 0f))
    val cosine = (
        (upperLength * upperLength) +
            (clampedDistance * clampedDistance) -
            (lowerLength * lowerLength)
        ) / (2f * upperLength * clampedDistance)
    val bendAngle = acos(cosine.coerceIn(-1f, 1f))
    val normal = perpendicular(direction)
    val bendSign = if (cross(targetVector, hint - root) >= 0f) 1f else -1f
    val jointDirection =
        direction.scaled(cos(bendAngle)) +
            normal.scaled(sin(bendAngle) * bendSign)
    return root + jointDirection.scaled(upperLength)
}

private fun buildPushUpSkeleton(
    size: Size,
    drive: Float
): PushUpSkeleton {
    val width = size.width
    val height = size.height
    val floorY = height * 0.82f
    val pelvisY = lerpValue(height * 0.51f, height * 0.63f, drive)

    // The hands and toes are static anchors; the pelvis vertical travel drives the rest of the rig.
    val leftHand = Offset(width * 0.27f, floorY)
    val rightHand = Offset(width * 0.38f, floorY)
    val leftToe = Offset(width * 0.80f, floorY)
    val rightToe = Offset(width * 0.89f, floorY)

    val leftWrist = leftHand + Offset(width * 0.018f, -height * 0.014f)
    val rightWrist = rightHand + Offset(width * 0.018f, -height * 0.014f)

    val pelvis = Offset(width * 0.61f, pelvisY)
    val neck = Offset(width * 0.44f, pelvisY - height * (0.095f - 0.02f * drive))
    val spineDirection = (pelvis - neck).safeNormalized(fallback = Offset(1f, 0f))
    val spineNormal = perpendicular(spineDirection)

    val shoulderSpread = height * 0.062f
    val hipSpread = height * 0.056f
    val leftShoulder = neck + spineNormal.scaled(shoulderSpread * 0.5f)
    val rightShoulder = neck - spineNormal.scaled(shoulderSpread * 0.5f)
    val leftHip = pelvis + spineNormal.scaled(hipSpread * 0.5f)
    val rightHip = pelvis - spineNormal.scaled(hipSpread * 0.5f)

    // Mid-spine is procedurally bowed to avoid a rigid plank-like torso.
    val midSpine =
        midpoint(neck, pelvis) +
            spineNormal.scaled(height * (0.014f + 0.03f * drive)) +
            Offset(0f, height * (0.012f + 0.018f * drive))

    val headDirection =
        ((neck - pelvis) + Offset(-width * 0.03f, -height * 0.04f))
            .safeNormalized(fallback = Offset(-1f, -0.25f))
    val head = neck + headDirection.scaled(height * 0.09f)

    val upperArm = height * 0.155f
    val lowerArm = height * 0.155f
    val leftElbow = solveTwoBoneIk(
        root = leftShoulder,
        target = leftWrist,
        upperLength = upperArm,
        lowerLength = lowerArm,
        hint = leftShoulder + Offset(-width * 0.06f, -height * (0.05f + 0.06f * drive))
    )
    val rightElbow = solveTwoBoneIk(
        root = rightShoulder,
        target = rightWrist,
        upperLength = upperArm,
        lowerLength = lowerArm,
        hint = rightShoulder + Offset(-width * 0.03f, -height * (0.07f + 0.05f * drive))
    )

    val footLength = width * 0.08f
    val leftFootPitch = lerpValue(0.40f, 0.70f, drive)
    val rightFootPitch = lerpValue(0.36f, 0.64f, drive)
    val leftHeel = leftToe + Offset(-cos(leftFootPitch) * footLength, -sin(leftFootPitch) * footLength)
    val rightHeel = rightToe + Offset(-cos(rightFootPitch) * footLength, -sin(rightFootPitch) * footLength)
    val leftAnkle = midpoint(leftHeel, leftToe) + Offset(width * 0.004f, -height * 0.032f)
    val rightAnkle = midpoint(rightHeel, rightToe) + Offset(width * 0.004f, -height * 0.032f)

    val upperLeg = height * 0.175f
    val lowerLeg = height * 0.18f
    val leftKnee = solveTwoBoneIk(
        root = leftHip,
        target = leftAnkle,
        upperLength = upperLeg,
        lowerLength = lowerLeg,
        hint = leftHip + Offset(-width * 0.02f, -height * (0.10f + 0.03f * drive))
    )
    val rightKnee = solveTwoBoneIk(
        root = rightHip,
        target = rightAnkle,
        upperLength = upperLeg,
        lowerLength = lowerLeg,
        hint = rightHip + Offset(width * 0.01f, -height * (0.10f + 0.03f * drive))
    )

    return PushUpSkeleton(
        head = head,
        neck = neck,
        midSpine = midSpine,
        pelvis = pelvis,
        leftShoulder = leftShoulder,
        rightShoulder = rightShoulder,
        leftElbow = leftElbow,
        rightElbow = rightElbow,
        leftWrist = leftWrist,
        rightWrist = rightWrist,
        leftHand = leftHand,
        rightHand = rightHand,
        leftHip = leftHip,
        rightHip = rightHip,
        leftKnee = leftKnee,
        rightKnee = rightKnee,
        leftAnkle = leftAnkle,
        rightAnkle = rightAnkle,
        leftHeel = leftHeel,
        rightHeel = rightHeel,
        leftToe = leftToe,
        rightToe = rightToe
    )
}

private fun DrawScope.drawPushUpScene(
    skeleton: PushUpSkeleton,
    fill: Color,
    outline: Color,
    shadow: Color
) {
    check(skeleton.orderedPoints.size == 22) { "Push-up rig must contain 22 points." }
    val strokeWidth = size.minDimension * 0.01f
    val farFill = fill.copy(alpha = 0.78f)

    drawGround(outline.copy(alpha = 0.4f), strokeWidth)
    drawSoftShadow(skeleton = skeleton, color = shadow)

    val torso = buildTorsoPath(skeleton, size.minDimension)
    val head = buildHeadPath(skeleton.head, size.minDimension * 0.065f, size.minDimension * 0.072f)
    val rightArm = buildOrganicLimbPath(
        points = listOf(skeleton.rightShoulder, skeleton.rightElbow, skeleton.rightWrist, skeleton.rightHand),
        radii = listOf(size.minDimension * 0.032f, size.minDimension * 0.028f, size.minDimension * 0.02f, size.minDimension * 0.013f)
    )
    val leftArm = buildOrganicLimbPath(
        points = listOf(skeleton.leftShoulder, skeleton.leftElbow, skeleton.leftWrist, skeleton.leftHand),
        radii = listOf(size.minDimension * 0.034f, size.minDimension * 0.03f, size.minDimension * 0.022f, size.minDimension * 0.014f)
    )
    val rightLeg = buildOrganicLimbPath(
        points = listOf(skeleton.rightHip, skeleton.rightKnee, skeleton.rightAnkle, skeleton.rightHeel, skeleton.rightToe),
        radii = listOf(size.minDimension * 0.04f, size.minDimension * 0.03f, size.minDimension * 0.02f, size.minDimension * 0.016f, size.minDimension * 0.008f)
    )
    val leftLeg = buildOrganicLimbPath(
        points = listOf(skeleton.leftHip, skeleton.leftKnee, skeleton.leftAnkle, skeleton.leftHeel, skeleton.leftToe),
        radii = listOf(size.minDimension * 0.04f, size.minDimension * 0.03f, size.minDimension * 0.02f, size.minDimension * 0.016f, size.minDimension * 0.008f)
    )

    drawPath(path = rightLeg, color = farFill)
    drawPath(path = rightArm, color = farFill)
    drawPath(path = torso, color = fill)
    drawPath(path = leftLeg, color = fill)
    drawPath(path = leftArm, color = fill)
    drawPath(path = head, color = fill)

    drawPath(path = rightLeg, color = outline.copy(alpha = 0.45f), style = Stroke(width = strokeWidth))
    drawPath(path = rightArm, color = outline.copy(alpha = 0.45f), style = Stroke(width = strokeWidth))
    drawPath(path = torso, color = outline.copy(alpha = 0.55f), style = Stroke(width = strokeWidth))
    drawPath(path = leftLeg, color = outline.copy(alpha = 0.55f), style = Stroke(width = strokeWidth))
    drawPath(path = leftArm, color = outline.copy(alpha = 0.55f), style = Stroke(width = strokeWidth))
    drawPath(path = head, color = outline.copy(alpha = 0.55f), style = Stroke(width = strokeWidth))
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

private fun DrawScope.drawSoftShadow(
    skeleton: PushUpSkeleton,
    color: Color
) {
    val shadowCenter = midpoint(skeleton.neck, skeleton.pelvis) + Offset(size.width * 0.03f, size.height * 0.18f)
    val radiusX = size.width * 0.26f
    val radiusY = size.height * 0.06f
    drawPath(
        path = buildHeadPath(shadowCenter, radiusX, radiusY),
        color = color
    )
}

private fun buildTorsoPath(
    skeleton: PushUpSkeleton,
    scale: Float
): Path {
    val spineDirection = (skeleton.pelvis - skeleton.neck).safeNormalized(fallback = Offset(1f, 0f))
    val spineNormal = perpendicular(spineDirection)
    val leftShoulder = skeleton.leftShoulder + spineNormal.scaled(scale * 0.012f)
    val rightShoulder = skeleton.rightShoulder - spineNormal.scaled(scale * 0.012f)
    val leftRib = skeleton.midSpine + spineNormal.scaled(scale * 0.03f)
    val rightRib = skeleton.midSpine - spineNormal.scaled(scale * 0.03f)
    val leftHip = skeleton.leftHip + spineNormal.scaled(scale * 0.014f)
    val rightHip = skeleton.rightHip - spineNormal.scaled(scale * 0.014f)
    val neckFront = skeleton.neck - spineDirection.scaled(scale * 0.03f)

    return Path().apply {
        moveTo(leftShoulder.x, leftShoulder.y)
        cubicTo(
            (leftShoulder + spineDirection.scaled(scale * 0.08f)).x,
            (leftShoulder + spineDirection.scaled(scale * 0.08f)).y,
            (leftRib - spineDirection.scaled(scale * 0.05f)).x,
            (leftRib - spineDirection.scaled(scale * 0.05f)).y,
            leftRib.x,
            leftRib.y
        )
        cubicTo(
            (leftRib + spineDirection.scaled(scale * 0.06f)).x,
            (leftRib + spineDirection.scaled(scale * 0.06f)).y,
            (leftHip - spineDirection.scaled(scale * 0.04f)).x,
            (leftHip - spineDirection.scaled(scale * 0.04f)).y,
            leftHip.x,
            leftHip.y
        )
        cubicTo(
            (leftHip + spineNormal.scaled(scale * 0.02f)).x,
            (leftHip + spineNormal.scaled(scale * 0.02f)).y,
            (rightHip + spineNormal.scaled(scale * 0.02f)).x,
            (rightHip + spineNormal.scaled(scale * 0.02f)).y,
            rightHip.x,
            rightHip.y
        )
        cubicTo(
            (rightHip - spineDirection.scaled(scale * 0.04f)).x,
            (rightHip - spineDirection.scaled(scale * 0.04f)).y,
            (rightRib + spineDirection.scaled(scale * 0.06f)).x,
            (rightRib + spineDirection.scaled(scale * 0.06f)).y,
            rightRib.x,
            rightRib.y
        )
        cubicTo(
            (rightRib - spineDirection.scaled(scale * 0.05f)).x,
            (rightRib - spineDirection.scaled(scale * 0.05f)).y,
            (rightShoulder + spineDirection.scaled(scale * 0.08f)).x,
            (rightShoulder + spineDirection.scaled(scale * 0.08f)).y,
            rightShoulder.x,
            rightShoulder.y
        )
        cubicTo(
            (rightShoulder - spineDirection.scaled(scale * 0.05f)).x,
            (rightShoulder - spineDirection.scaled(scale * 0.05f)).y,
            (neckFront - spineNormal.scaled(scale * 0.02f)).x,
            (neckFront - spineNormal.scaled(scale * 0.02f)).y,
            neckFront.x,
            neckFront.y
        )
        cubicTo(
            (neckFront + spineNormal.scaled(scale * 0.02f)).x,
            (neckFront + spineNormal.scaled(scale * 0.02f)).y,
            (leftShoulder - spineDirection.scaled(scale * 0.05f)).x,
            (leftShoulder - spineDirection.scaled(scale * 0.05f)).y,
            leftShoulder.x,
            leftShoulder.y
        )
        close()
    }
}

private fun buildHeadPath(
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
            0 -> (points[1] - points[0]).safeNormalized(fallback = Offset(1f, 0f))
            points.lastIndex -> (points[index] - points[index - 1]).safeNormalized(fallback = Offset(1f, 0f))
            else -> ((points[index + 1] - points[index - 1]).safeNormalized(fallback = Offset(1f, 0f)))
        }
    }

    val leftEdge = points.indices.map { index ->
        points[index] + perpendicular(tangents[index]).scaled(radii[index])
    }
    val rightEdge = points.indices.map { index ->
        points[index] - perpendicular(tangents[index]).scaled(radii[index])
    }

    return Path().apply {
        moveTo(leftEdge.first().x, leftEdge.first().y)
        for (index in 0 until points.lastIndex) {
            val segmentLength = distance(points[index], points[index + 1])
            val handleLength = segmentLength * 0.35f
            val control1 = leftEdge[index] + tangents[index].scaled(handleLength)
            val control2 = leftEdge[index + 1] - tangents[index + 1].scaled(handleLength)
            cubicTo(
                control1.x,
                control1.y,
                control2.x,
                control2.y,
                leftEdge[index + 1].x,
                leftEdge[index + 1].y
            )
        }

        val endTangent = tangents.last()
        val endLeft = leftEdge.last()
        val endRight = rightEdge.last()
        cubicTo(
            (endLeft + endTangent.scaled(radii.last() * 0.8f)).x,
            (endLeft + endTangent.scaled(radii.last() * 0.8f)).y,
            (endRight + endTangent.scaled(radii.last() * 0.8f)).x,
            (endRight + endTangent.scaled(radii.last() * 0.8f)).y,
            endRight.x,
            endRight.y
        )

        for (index in points.lastIndex downTo 1) {
            val segmentLength = distance(points[index], points[index - 1])
            val handleLength = segmentLength * 0.35f
            val control1 = rightEdge[index] - tangents[index].scaled(handleLength)
            val control2 = rightEdge[index - 1] + tangents[index - 1].scaled(handleLength)
            cubicTo(
                control1.x,
                control1.y,
                control2.x,
                control2.y,
                rightEdge[index - 1].x,
                rightEdge[index - 1].y
            )
        }

        val startTangent = tangents.first()
        val startLeft = leftEdge.first()
        val startRight = rightEdge.first()
        cubicTo(
            (startRight - startTangent.scaled(radii.first() * 0.8f)).x,
            (startRight - startTangent.scaled(radii.first() * 0.8f)).y,
            (startLeft - startTangent.scaled(radii.first() * 0.8f)).x,
            (startLeft - startTangent.scaled(radii.first() * 0.8f)).y,
            startLeft.x,
            startLeft.y
        )
        close()
    }
}

private fun perpendicular(vector: Offset): Offset = Offset(-vector.y, vector.x)

private fun midpoint(a: Offset, b: Offset): Offset = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

private fun cross(a: Offset, b: Offset): Float = (a.x * b.y) - (a.y * b.x)

private fun Offset.magnitude(): Float = sqrt((x * x) + (y * y))

private fun Offset.safeNormalized(fallback: Offset): Offset {
    val length = magnitude()
    return if (length < 0.0001f) fallback else Offset(x / length, y / length)
}

private fun Offset.scaled(scale: Float): Offset = Offset(x * scale, y * scale)

private fun distance(a: Offset, b: Offset): Float = (b - a).magnitude()

private fun lerpValue(start: Float, end: Float, t: Float): Float = start + (end - start) * t

private fun DrawScope.point(x: Float, y: Float): Offset = Offset(size.width * x, size.height * y)
