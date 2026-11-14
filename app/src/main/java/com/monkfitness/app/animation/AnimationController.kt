package com.monkfitness.app.animation

/**
 * Playback state of one rep: what the renderer draws and what the rotation gesture turns. Pure
 * Kotlin — the observable Compose state lives on the UI side, which implements this contract.
 */
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

internal const val MAX_YAW_LIMIT = 1.5708f

/** Clamps a declared allowance to the reach the gesture can use at all. */
internal fun coerceYawLimits(value: YawLimits): YawLimits = YawLimits(
    negative = value.negative.coerceIn(0f, MAX_YAW_LIMIT),
    positive = value.positive.coerceIn(0f, MAX_YAW_LIMIT)
)

/**
 * Where a drag lands: the clamp is the surface's own edge, so a drag past it stays on the limit rather
 * than wrapping, springing back or accumulating invisible debt beyond it — the offset is a function of
 * the offset the user can see, so the same drag back turns the figure back immediately, however small
 * the allowance is.
 */
internal fun clampedYawOffset(current: Float, delta: Float, limits: YawLimits): Float =
    (current + delta).coerceIn(-limits.negative.coerceAtLeast(0f), limits.positive.coerceAtLeast(0f))

/** The rep at [time] within its loop: how far through the motion and which side is playing. */
internal data class LoopSample(val progress: Float, val side: Side)

/**
 * Maps a loop's own clock onto the rep. [time] runs over the mode's period — 0..2 for an alternating
 * loop, 0..1 otherwise — so the caller owns only the tween that produces it.
 */
internal fun loopSample(metadata: PoseMetadata, time: Float, alternating: Boolean): LoopSample =
    when (metadata.loopMode) {
        LoopMode.LOOP -> if (alternating) {
            val side = if (time < 1f) Side.RIGHT else Side.LEFT
            val p = if (time < 1f) time else time - 1f
            val t = if (p < 0.5f) p * 2f else (1f - p) * 2f
            LoopSample(MotionCurves.transform(metadata.motionCurve, t), side)
        } else {
            LoopSample(time, Side.RIGHT)
        }
        LoopMode.HOLD -> LoopSample(breathProgress(metadata, time), Side.RIGHT)
        LoopMode.PING_PONG, LoopMode.ONCE -> LoopSample(time, Side.RIGHT)
    }

/** A hold breathes in, holds and breathes out; the phases are fractions of the whole cycle. */
private fun breathProgress(metadata: PoseMetadata, time: Float): Float {
    val outFraction = metadata.breathOutFraction
    val inEnd = metadata.breathInFraction
    val holdEnd = inEnd + metadata.breathHoldFraction
    val outEnd = holdEnd + outFraction

    return when {
        time < inEnd -> {
            val t = if (inEnd > 0f) time / inEnd else 0f
            MotionCurves.transform(metadata.motionCurve, t)
        }
        time < holdEnd -> 1f
        time < outEnd -> {
            val t = if (outFraction > 0f) (time - holdEnd) / outFraction else 0f
            1f - MotionCurves.transform(metadata.motionCurve, t)
        }
        else -> 0f
    }
}
