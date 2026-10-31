package com.monkfitness.app.animation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class FootDefinition(
    val footLength: Float,
    val heelRatio: Float = 0.29f,
    val toeRatio: Float = 0.71f,
    val ankleHeight: Float = 15f,
    val minPitch: Float = -45f * PI.toFloat() / 180f,
    val maxPitch: Float = 45f * PI.toFloat() / 180f
) {
    // Allocation-free scratch buffers for the orientation-aware overload.
    private val scratchDir = Vector3()
    private val scratchHoriz = Vector3()

    /**
     * Computes heel and toe positions from the ankle and a forward direction; the ankle stays the
     * rotational pivot.
     */
    fun computeHeelToe(ankle: Vector3, forward: Vector3, outHeel: Vector3, outToe: Vector3) {
        val dir = forward.normalizedCopy()
        writeHeelToe(ankle, dir, outHeel, outToe)
    }

    /**
     * Orientation-aware overload: the authored [ankleRotation] is composed with the neutral
     * (shank-perpendicular) [neutralForward] so dorsi/plantar-flexion and inversion/eversion are
     * honoured instead of dropped. The pitch clamp then bounds the *resulting* direction — a safety
     * bound, never the only degree of freedom. Identity [ankleRotation] reproduces [computeHeelToe],
     * so flat-foot rendering is unchanged. Allocation-free.
     */
    fun computeHeelToe(
        ankle: Vector3,
        neutralForward: Vector3,
        ankleRotation: JointRotation,
        outHeel: Vector3,
        outToe: Vector3
    ) {
        // Compose the authored ankle orientation with the neutral foot direction.
        SkeletonMath.rotAround(neutralForward, ankleRotation.axis, ankleRotation.angle, scratchDir)
        scratchDir.normalize()
        // The clamp bounds the resulting foot direction; it is not the sole DOF.
        applyPitchClamp(scratchDir)
        writeHeelToe(ankle, scratchDir, outHeel, outToe)
    }

    private fun writeHeelToe(ankle: Vector3, dir: Vector3, outHeel: Vector3, outToe: Vector3) {
        outHeel.set(dir).multiply(-(footLength * heelRatio)).add(ankle)
        outToe.set(dir).multiply(footLength * toeRatio).add(ankle)
    }

    /**
     * Clamps the elevation (pitch) of [dir] into [minPitch]..[maxPitch] in place, leaving the
     * horizontal heading untouched. The output is always unit length.
     */
    private fun applyPitchClamp(dir: Vector3) {
        val pitch = atan2(dir.y, sqrt(dir.x * dir.x + dir.z * dir.z))
        val clamped = pitch.coerceIn(minPitch, maxPitch)
        if (abs(pitch - clamped) > 1e-3f) {
            scratchHoriz.set(dir.x, 0f, dir.z)
            val hMag = sqrt(scratchHoriz.x * scratchHoriz.x + scratchHoriz.z * scratchHoriz.z)
            if (hMag > 1e-6f) {
                scratchHoriz.x /= hMag
                scratchHoriz.z /= hMag
            } else {
                // Purely vertical direction: the horizontal heading is degenerate. Without a
                // fallback heading the reconstructed vector below collapses to (0, sin(clamped), 0)
                // — a NON-unit vector (|sin 45°| = 0.707) — which scales the heel/toe bones ~29%
                // short. A stable +X heading keeps the direction unit length and the derived bones
                // at their full footLength·ratio magnitude.
                scratchHoriz.set(1f, 0f, 0f)
            }
            dir.set(
                scratchHoriz.x * cos(clamped),
                sin(clamped),
                scratchHoriz.z * cos(clamped)
            )
        }
    }
}
