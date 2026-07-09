package com.monkfitness.app.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlin.math.*

@Deprecated("Use LoopMode", ReplaceWith("LoopMode"))
enum class AnimationMode {
    LOOP, // Repeats progress 0 -> 1 -> 0
    HOLD  // Progress driven by breathing cycle 0 -> 1 -> 0
}

@Stable
interface AnimationController {
    val progress: Float
    val side: Side
    val cameraYawOffset: Float
    fun onRotate(delta: Float)
}

private class BaseAnimationController : AnimationController {
    override var progress: Float by mutableFloatStateOf(0f)
    override var side: Side by mutableStateOf(Side.RIGHT)
    override var cameraYawOffset: Float by mutableFloatStateOf(0f)

    override fun onRotate(delta: Float) {
        cameraYawOffset = (cameraYawOffset + delta).coerceIn(-1.5708f, 1.5708f)
    }
}

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
                    controller.progress = if (p < 0.5f) {
                        val t = p * 2f
                        t * t * (3 - 2 * t)
                    } else {
                        val t = (1f - p) * 2f
                        t * t * (3 - 2 * t)
                    }
                }
            } else {
                val p by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs / 2, easing = FastOutSlowInEasing),
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
            val breathTime by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 8000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "BreathingCycle"
            )
            LaunchedEffect(breathTime) {
                controller.side = Side.RIGHT
                controller.progress = when {
                    breathTime < 0.40f -> smoothstep(breathTime / 0.40f)
                    breathTime < 0.52f -> 1f
                    breathTime < 0.92f -> 1f - smoothstep((breathTime - 0.52f) / 0.40f)
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

@Deprecated("Use PoseMetadata version", ReplaceWith("rememberAnimationController(PoseMetadata(loopMode = mode, durationSeconds = durationMs / 1000f), alternating)"))
@Composable
fun rememberAnimationController(
    mode: AnimationMode,
    durationMs: Int = 3000,
    alternating: Boolean = false
): AnimationController {
    val loopMode = when(mode) {
        AnimationMode.LOOP -> LoopMode.LOOP
        AnimationMode.HOLD -> LoopMode.HOLD
    }
    return rememberAnimationController(
        PoseMetadata(loopMode = loopMode, durationSeconds = durationMs / 1000f),
        alternating
    )
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
