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

    fun update(p: Float, zoom: Float, settings: ScreenSpaceSettings) {
        radiusScale = (1.0f + (p - 1.0f) * settings.radiusStrength) * zoom
        thicknessScale = (1.0f + (p - 1.0f) * settings.thicknessStrength) * zoom
        outlineScale = 1.0f + (p - 1.0f) * settings.outlineStrength
        shadowScale = (1.0f + (p - 1.0f) * settings.shadowStrength) * zoom
        alphaScale = 1.0f + (p - 1.0f) * settings.alphaStrength
    }

    // Legacy fallback for backward-compatibility with 1.0f default zoom
    fun update(p: Float, settings: ScreenSpaceSettings) {
        update(p, 1.0f, settings)
    }
}

/**
 * ScreenSpaceCompensation is a pure post-processing stage.
 * It is now the single source of truth for all camera-zoom and perspective-based visual scaling,
 * keeping Camera a pure mathematical coordinate projector.
 */
class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    /**
     * Updates the provided ScreenSpaceScale buffer using the perspectiveScale and dynamic zoom.
     */
    fun computeScale(point: ProjectedPoint, zoom: Float, buffer: ScreenSpaceScale) {
        buffer.update(point.perspectiveScale, zoom, settings)
    }

    /**
     * Legacy overloaded signature for backward compatibility.
     */
    fun computeScale(point: ProjectedPoint, buffer: ScreenSpaceScale) {
        computeScale(point, 1.0f, buffer)
    }

    /**
     * Batch process joints directly on indexed arrays in-place with dynamic zoom.
     */
    fun computeScales(joints: Array<ProjectedPoint>, zoom: Float, scales: Array<ScreenSpaceScale>) {
        for (i in joints.indices) {
            scales[i].update(joints[i].perspectiveScale, zoom, settings)
        }
    }

    /**
     * Legacy overloaded signature for batch processing with default zoom.
     */
    fun computeScales(joints: Array<ProjectedPoint>, scales: Array<ScreenSpaceScale>) {
        computeScales(joints, 1.0f, scales)
    }
}
