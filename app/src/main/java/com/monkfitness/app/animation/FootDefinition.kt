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
     * Procedurally computes Heel and Toe positions from Ankle and a forward direction.
     * The ankle remains the rotational pivot (origin in this context).
     */
    fun computeHeelToe(ankle: Vector3, forward: Vector3, outHeel: Vector3, outToe: Vector3) {
        val dir = forward.normalizedCopy()
        writeHeelToe(ankle, dir, outHeel, outToe)
    }

    /**
     * Orientation-aware overload. Promotes the ankle to a real joint: the authored
     * [ankleRotation] is composed with the neutral (shank-perpendicular) foot
     * [neutralForward] so dorsi/plantar-flexion and inversion/eversion are honored
     * instead of being dropped. The existing pitch clamp is applied to the *resulting*
     * foot direction as a safety bound, never as the only degree of freedom. When
     * [ankleRotation] is the identity rotation the result is identical to
     * [computeHeelToe], preserving all flat-foot rendering.
     *
     * Allocation-free: writes into [scratchDir]/[scratchHoriz] and the outputs.
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
        // Pitch clamp remains a bound on the resulting foot direction, not the sole DOF.
        applyPitchClamp(scratchDir)
        writeHeelToe(ankle, scratchDir, outHeel, outToe)
    }

    // Scratch rotation for the 2-DOF (UNI-8) overload below.
    private val scratchRot = JointRotation()

    /**
     * 2-DOF overload (UNI-8): honors a *secondary* ankle articulation on top of
     * the primary [ankleRotation] (e.g. dorsi/plantar-flexion + inversion/
     * eversion — no longer collapsed into a single axis-angle). The two rotations
     * are composed into one effective orientation via [SkeletonMath.composeRotations]
     * and applied to the neutral foot direction; the pitch clamp still bounds the
     * resulting direction. When [ankleRotation2] is the identity rotation the
     * result is identical to the single-rotation overload, so flat-foot rendering
     * is preserved.
     *
     * Allocation-free: writes into [scratchDir]/[scratchRot] and the outputs.
     */
    fun computeHeelToe(
        ankle: Vector3,
        neutralForward: Vector3,
        ankleRotation: JointRotation,
        ankleRotation2: JointRotation,
        outHeel: Vector3,
        outToe: Vector3
    ) {
        // Compose the authored ankle orientation with the neutral foot direction.
        SkeletonMath.composeRotations(ankleRotation, ankleRotation2, scratchRot)
        SkeletonMath.rotAround(neutralForward, scratchRot.axis, scratchRot.angle, scratchDir)
        scratchDir.normalize()
        // Pitch clamp remains a bound on the resulting foot direction, not the sole DOF.
        applyPitchClamp(scratchDir)
        writeHeelToe(ankle, scratchDir, outHeel, outToe)
    }

    private fun writeHeelToe(ankle: Vector3, dir: Vector3, outHeel: Vector3, outToe: Vector3) {
        outHeel.set(dir).multiply(-(footLength * heelRatio)).add(ankle)
        outToe.set(dir).multiply(footLength * toeRatio).add(ankle)
    }

    /**
     * Clamps the elevation (pitch) of [dir] into [minPitch]..[maxPitch] in place,
     * leaving the horizontal heading untouched. Used as a safety bound on the
     * articulated foot direction.
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
            }
            dir.set(
                scratchHoriz.x * cos(clamped),
                sin(clamped),
                scratchHoriz.z * cos(clamped)
            )
        }
    }
}
