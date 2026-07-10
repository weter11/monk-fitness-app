package com.monkfitness.app.animation

import kotlin.math.*

enum class MotionCurve {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    SINE,
    FAST_DOWN_SLOW_UP,
    SLOW_DOWN_FAST_UP
}

object MotionCurves {
    fun transform(curve: MotionCurve, t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return when (curve) {
            MotionCurve.LINEAR -> clamped
            MotionCurve.EASE_IN -> clamped * clamped
            MotionCurve.EASE_OUT -> clamped * (2f - clamped)
            MotionCurve.EASE_IN_OUT -> clamped * clamped * (3f - 2f * clamped)
            MotionCurve.SINE -> sin(clamped * (Math.PI.toFloat() / 2f))
            MotionCurve.FAST_DOWN_SLOW_UP -> clamped * clamped * clamped
            MotionCurve.SLOW_DOWN_FAST_UP -> 1f - (1f - clamped) * (1f - clamped) * (1f - clamped)
        }
    }
}
