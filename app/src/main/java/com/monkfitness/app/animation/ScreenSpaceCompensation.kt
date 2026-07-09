package com.monkfitness.app.animation

import kotlin.math.*

data class ScreenSpaceSettings(
    val perspectiveStrength: Float = 0.15f,
    val thicknessStrength: Float = 0.15f,
    val jointScaleStrength: Float = 0.12f,
    val minimumScale: Float = 0.85f,
    val maximumScale: Float = 1.15f,
    val neutralDepth: Float = 0f,
    val depthScaleRange: Float = 200f
) {
    companion object {
        val DEFAULT = ScreenSpaceSettings()
    }
}

class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    /**
     * Computes a depth-based scale factor.
     * Positive depth means further away in the Camera projection used here.
     * We want items closer (negative depth) to be larger.
     */
    fun getScaleFactor(depth: Float, strength: Float): Float {
        // Normalize depth: 0 is neutral.
        // In our Camera.kt: z2 = zr * cp - v.y * sp
        // Items in front usually have negative z2?
        // Let's check Camera.project: val sc = focalLength / (focalLength + z2)
        // If z2 is large positive, sc is small (far).
        // If z2 is negative (approaching -focalLength), sc is large (near).

        // We want an additional compensation scale.
        // If we want to BOOST things that are far away (to counteract perspective shrinking)
        // or BOOST things that are near (to emphasize perspective).
        // The goal of compensation usually is to soften the extreme perspective effects
        // or to provide a consistent look.

        // PerspectiveCompensation.kt used:
        // val depthFactor = -(depth / 200f).coerceIn(-1f, 1f)
        // val adjustment = 1.0f + (depthFactor * 0.15f)

        val normalizedDepth = ((settings.neutralDepth - depth) / settings.depthScaleRange)
            .coerceIn(-1f, 1f)

        val factor = 1.0f + (normalizedDepth * strength)
        return factor.coerceIn(settings.minimumScale, settings.maximumScale)
    }

    fun getThicknessScale(depth: Float): Float = getScaleFactor(depth, settings.thicknessStrength)
    fun getJointScale(depth: Float): Float = getScaleFactor(depth, settings.jointScaleStrength)
    fun getPerspectiveScale(depth: Float): Float = getScaleFactor(depth, settings.perspectiveStrength)
}
