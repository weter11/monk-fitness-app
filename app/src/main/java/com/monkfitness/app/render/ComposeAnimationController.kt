package com.monkfitness.app.render

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.monkfitness.app.animation.AnimationController
import com.monkfitness.app.animation.LoopMode
import com.monkfitness.app.animation.LoopSample
import com.monkfitness.app.animation.MAX_YAW_LIMIT
import com.monkfitness.app.animation.PoseMetadata
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.YawLimits
import com.monkfitness.app.animation.clampedYawOffset
import com.monkfitness.app.animation.coerceYawLimits
import com.monkfitness.app.animation.loopSample

/** Compose state that delegates every decision — the clamp, the limits, the loop sample — to the core. */
@Stable
private class ComposeAnimationController : AnimationController {
    override var progress: Float by mutableFloatStateOf(0f)
    override var side: Side by mutableStateOf(Side.RIGHT)
    override var cameraYawOffset: Float by mutableFloatStateOf(0f)
        private set
    override var yawLimits: YawLimits = YawLimits(MAX_YAW_LIMIT, MAX_YAW_LIMIT)
        set(value) {
            field = coerceYawLimits(value)
            // A surface that shrinks (a shorter hero at a larger text scale) may put the current
            // viewpoint outside the new limits; the figure would then be cropped until the next drag.
            cameraYawOffset = cameraYawOffset.coerceIn(-field.negative, field.positive)
        }

    override fun onRotate(delta: Float) {
        cameraYawOffset = clampedYawOffset(cameraYawOffset, delta, yawLimits)
    }
}

private fun ComposeAnimationController.applySample(sample: LoopSample) {
    side = sample.side
    progress = sample.progress
}

@Composable
fun rememberAnimationController(
    metadata: PoseMetadata,
    alternating: Boolean = false // Still needed for side-switching exercises
): AnimationController {
    val controller = remember { ComposeAnimationController() }
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
                    controller.applySample(loopSample(metadata, totalProgress, alternating = true))
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
                    controller.applySample(loopSample(metadata, p, alternating = false))
                }
            }
        }
        LoopMode.HOLD -> {
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
                controller.applySample(loopSample(metadata, breathTime, alternating = false))
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
                controller.applySample(loopSample(metadata, p, alternating = false))
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
                controller.applySample(loopSample(metadata, p, alternating = false))
            }
        }
    }
    return controller
}
