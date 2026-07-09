package com.monkfitness.app.animation

data class ScreenSpaceSettings(
    val thicknessStrength: Float = 0.15f,
    val radiusStrength: Float = 0.12f,
    val outlineStrength: Float = 0.05f,
    val shadowStrength: Float = 0.10f,
    val alphaStrength: Float = 0.0f
) {
    companion object {
        val DEFAULT = ScreenSpaceSettings()
    }
}

data class ScreenSpaceScale(
    val radiusScale: Float,
    val thicknessScale: Float,
    val outlineScale: Float,
    val shadowScale: Float,
    val alphaScale: Float
) {
    companion object {
        val IDENTITY = ScreenSpaceScale(1f, 1f, 1f, 1f, 1f)
    }
}

/**
 * ScreenSpaceCompensation is a pure post-processing stage.
 * It transforms a projected point (with camera-provided perspectiveScale)
 * into a set of visual scaling factors.
 * It has no knowledge of anatomy or camera implementation.
 */
class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    /**
     * Computes visual scales for a projected point.
     * Uses the perspectiveScale provided by the Camera.
     */
    fun computeScale(point: ProjectedPoint): ScreenSpaceScale {
        // We use the camera's natural perspective scale as the base.
        // The 'strengths' in settings determine how much of that perspective
        // is applied to specific visual attributes.
        // A strength of 1.0 means the attribute follows perspective exactly.
        // A strength of 0.0 means the attribute remains constant.

        val p = point.perspectiveScale

        return ScreenSpaceScale(
            radiusScale = 1.0f + (p - 1.0f) * settings.radiusStrength,
            thicknessScale = 1.0f + (p - 1.0f) * settings.thicknessStrength,
            outlineScale = 1.0f + (p - 1.0f) * settings.outlineStrength,
            shadowScale = 1.0f + (p - 1.0f) * settings.shadowStrength,
            alphaScale = 1.0f + (p - 1.0f) * settings.alphaStrength
        )
    }
}
