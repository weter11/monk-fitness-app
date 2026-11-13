package com.monkfitness.app.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlin.math.*

@Stable
interface AnimationController {
    val progress: Float
    val side: Side
    val cameraYawOffset: Float

    /**
     * How far [cameraYawOffset] may go either side of the authored view without the surface cropping
     * the figure, as magnitudes. The hero sets them from the frame it fitted; a caller that draws no
     * hero keeps the gesture's full reach.
     */
    var yawLimits: YawLimits

    fun onRotate(delta: Float)
}

private class BaseAnimationController : AnimationController {
    override var progress: Float by mutableFloatStateOf(0f)
    override var side: Side by mutableStateOf(Side.RIGHT)
    override var cameraYawOffset: Float by mutableFloatStateOf(0f)
        private set
    override var yawLimits: YawLimits = YawLimits(MAX_YAW_LIMIT, MAX_YAW_LIMIT)
        set(value) {
            field = YawLimits(
                negative = value.negative.coerceIn(0f, MAX_YAW_LIMIT),
                positive = value.positive.coerceIn(0f, MAX_YAW_LIMIT)
            )
            // A surface that shrinks (a shorter hero at a larger text scale) may put the current
            // viewpoint outside the new limits; the figure would then be cropped until the next drag.
            cameraYawOffset = cameraYawOffset.coerceIn(-field.negative, field.positive)
        }

    override fun onRotate(delta: Float) {
        cameraYawOffset = clampedYawOffset(cameraYawOffset, delta, yawLimits)
    }

    private companion object {
        const val MAX_YAW_LIMIT = 1.5708f
    }
}

/**
 * Where a drag lands: the clamp is the surface's own edge, so a drag past it stays on the limit rather
 * than wrapping, springing back or accumulating invisible debt beyond it — the offset is a function of
 * the offset the user can see, so the same drag back turns the figure back immediately, however small
 * the allowance is.
 */
internal fun clampedYawOffset(current: Float, delta: Float, limits: YawLimits): Float =
    (current + delta).coerceIn(-limits.negative.coerceAtLeast(0f), limits.positive.coerceAtLeast(0f))

@Composable
fun rememberAnimationController(
    metadata: PoseMetadata,
    alternating: Boolean = false // Still needed for side-switching exercises
): AnimationController {
    val controller = remember { BaseAnimationController() }
    val transition = rememberInfiniteTransition(label = "SkeletonAnimation")
    val durationMs = (metadata.durationSeconds * 1000).toInt()

    when (metadata.loopMode) {
        LoopMode.LOOP -> {
            if (alternating) {
                val totalProgress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs * 2, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "AlternatingLoop"
                )

                LaunchedEffect(totalProgress) {
                    controller.side = if (totalProgress < 1f) Side.RIGHT else Side.LEFT
                    val p = if (totalProgress < 1f) totalProgress else totalProgress - 1f
                    val t = if (p < 0.5f) p * 2f else (1f - p) * 2f
                    controller.progress = MotionCurves.transform(metadata.motionCurve, t)
                }
            } else {
                val p by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs / 2, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "SimpleLoop"
                )
                LaunchedEffect(p) {
                    controller.progress = p
                    controller.side = Side.RIGHT
                }
            }
        }
        LoopMode.HOLD -> {
            val inFraction = metadata.breathInFraction
            val holdFraction = metadata.breathHoldFraction
            val outFraction = metadata.breathOutFraction
            val inEnd = inFraction
            val holdEnd = inEnd + holdFraction
            val outEnd = holdEnd + outFraction

            val breathTime by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = durationMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "BreathingCycle"
            )
            LaunchedEffect(breathTime) {
                controller.side = Side.RIGHT
                controller.progress = when {
                    breathTime < inEnd -> {
                        val t = if (inEnd > 0f) breathTime / inEnd else 0f
                        MotionCurves.transform(metadata.motionCurve, t)
                    }
                    breathTime < holdEnd -> 1f
                    breathTime < outEnd -> {
                        val t = if (outFraction > 0f) (breathTime - holdEnd) / outFraction else 0f
                        1f - MotionCurves.transform(metadata.motionCurve, t)
                    }
                    else -> 0f
                }
            }
        }
        LoopMode.PING_PONG -> {
            val p by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "PingPongLoop"
            )
            LaunchedEffect(p) {
                controller.progress = p
                controller.side = Side.RIGHT
            }
        }
        LoopMode.ONCE -> {
            val p by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable( // Keep it infinite but clamped for now or use Animatable
                    animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "Once"
            )
            LaunchedEffect(p) {
                controller.progress = p
                controller.side = Side.RIGHT
            }
        }
    }
    return controller
}
