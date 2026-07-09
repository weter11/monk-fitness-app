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

class ScreenSpaceScale {
    var radiusScale: Float = 1f
    var thicknessScale: Float = 1f
    var outlineScale: Float = 1f
    var shadowScale: Float = 1f
    var alphaScale: Float = 1f

    fun update(p: Float, settings: ScreenSpaceSettings) {
        radiusScale = 1.0f + (p - 1.0f) * settings.radiusStrength
        thicknessScale = 1.0f + (p - 1.0f) * settings.thicknessStrength
        outlineScale = 1.0f + (p - 1.0f) * settings.outlineStrength
        shadowScale = 1.0f + (p - 1.0f) * settings.shadowStrength
        alphaScale = 1.0f + (p - 1.0f) * settings.alphaStrength
    }
}

/**
 * ScreenSpaceCompensation is a pure post-processing stage.
 */
class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    /**
     * Updates the provided ScreenSpaceScale buffer using the perspectiveScale from the point.
     */
    fun computeScale(point: ProjectedPoint, buffer: ScreenSpaceScale) {
        buffer.update(point.perspectiveScale, settings)
    }

    /**
     * Batch process joints directly on indexed arrays in-place with zero allocations.
     */
    fun computeScales(joints: Array<ProjectedPoint>, scales: Array<ScreenSpaceScale>) {
        for (i in joints.indices) {
            scales[i].update(joints[i].perspectiveScale, settings)
        }
    }
}
