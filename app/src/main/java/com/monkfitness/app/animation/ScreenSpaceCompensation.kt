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

    // Legacy overload: 1.0f default zoom.
    fun update(p: Float, settings: ScreenSpaceSettings) {
        update(p, 1.0f, settings)
    }
}

/**
 * Pure post-processing stage and the single source of truth for camera-zoom and perspective-based
 * visual scaling, which keeps [Camera] a pure coordinate projector.
 */
class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    fun computeScale(point: ProjectedPoint, zoom: Float, buffer: ScreenSpaceScale) {
        buffer.update(point.perspectiveScale, zoom, settings)
    }

    /** Legacy overload: default zoom. */
    fun computeScale(point: ProjectedPoint, buffer: ScreenSpaceScale) {
        computeScale(point, 1.0f, buffer)
    }

    /** Batch, in place: one scale per joint. */
    fun computeScales(joints: Array<ProjectedPoint>, zoom: Float, scales: Array<ScreenSpaceScale>) {
        for (i in joints.indices) {
            scales[i].update(joints[i].perspectiveScale, zoom, settings)
        }
    }

    /** Legacy batch overload: default zoom. */
    fun computeScales(joints: Array<ProjectedPoint>, scales: Array<ScreenSpaceScale>) {
        computeScales(joints, 1.0f, scales)
    }
}
