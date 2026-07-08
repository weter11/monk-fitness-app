package com.monkfitness.app.animation

import kotlin.math.*

class PerspectiveCompensation(
    private val definition: SkeletonDefinition
) {
    /**
     * Stage 1: 3D Pose Correction (Before Projection)
     * Corrects foot orientation based on the shank (tibia).
     */
    fun preProcessPose(pose: SkeletonPose): SkeletonPose {
        val joints = pose.joints.toMutableMap()

        // Correct Fore (Left) Foot
        adjustFootOrientation(joints, Joint.KNEE_F, Joint.ANKLE_F, Joint.TOE_F)
        // Correct Back (Right) Foot
        adjustFootOrientation(joints, Joint.KNEE_B, Joint.ANKLE_B, Joint.TOE_B)

        return SkeletonPose(joints)
    }

    private fun adjustFootOrientation(
        joints: MutableMap<Joint, Vector3>,
        kneeId: Joint,
        ankleId: Joint,
        toeId: Joint
    ) {
        val knee = joints[kneeId] ?: return
        val ankle = joints[ankleId] ?: return

        val shank = (ankle - knee).normalize()

        // Target: foot perpendicular to shank, pointing forward (positive X)
        // Project world-forward onto the plane perpendicular to shank
        val forward = Vector3(1f, 0f, 0f)
        var footDir = forward - shank * forward.dot(shank)

        if (footDir.mag() < 1e-3) {
            // Shank is parallel to forward, fallback to pointing down
            footDir = Vector3(0f, -1f, 0f) - shank * Vector3(0f, -1f, 0f).dot(shank)
        }
        footDir = footDir.normalize()

        // Clamp foot pitch: avoid extreme angles relative to horizontal
        // In this coordinate system, Y is up, X is forward.
        val pitch = atan2(footDir.y, sqrt(footDir.x * footDir.x + footDir.z * footDir.z))
        val maxPitch = 45f * PI.toFloat() / 180f
        val clampedPitch = pitch.coerceIn(-maxPitch, maxPitch)

        if (abs(pitch - clampedPitch) > 1e-3) {
            // Reconstruct direction with clamped pitch
            val horizontalDir = Vector3(footDir.x, 0f, footDir.z).normalize()
            footDir = horizontalDir * cos(clampedPitch) + Vector3(0f, 1f, 0f) * sin(clampedPitch)
        }

        // Avoid unnatural twisting: keep foot mostly in the same vertical plane as the shank if possible,
        // or just ensure it's not rotating wildly.
        // The current forward-projection approach already handles this.

        val footLen = definition.footLength
        joints[toeId] = ankle + footDir * footLen
    }

    /**
     * Stage 2: 2D Projected Points Correction
     * Maintains visual foot length.
     */
    fun compensateFootPerspective(
        ankleProj: ProjectedPoint,
        toeProj: ProjectedPoint,
        camera: Camera
    ): ProjectedPoint {
        val dx = toeProj.x - ankleProj.x
        val dy = toeProj.y - ankleProj.y
        val currentDist = sqrt(dx * dx + dy * dy)

        if (currentDist < 1e-3) return toeProj

        // The expected visual length if there was no perspective distortion
        // (Simplified: footLength * zoom * reference_scale)
        // Or just try to keep it close to a reasonable value.
        // The prompt says "Apply only a small correction (10–20%)".

        val idealDist = definition.footLength * ankleProj.scale * camera.zoom

        // We don't want to force it to idealDist, but move it TOWARDS it.
        val ratio = idealDist / currentDist
        val correctionStrength = 0.15f // 15% correction
        val targetRatio = 1.0f + (ratio - 1.0f) * correctionStrength

        return ProjectedPoint(
            x = ankleProj.x + dx * targetRatio,
            y = ankleProj.y + dy * targetRatio,
            scale = toeProj.scale,
            depth = toeProj.depth
        )
    }

    /**
     * Bone Thickness Compensation
     * Depth-based thickness adjustment (±15%)
     */
    fun compensateThickness(thickness: Float, depth: Float): Float {
        // Depth usually ranges from -170 (near) to 170 (far) in this engine based on getZColor
        // Actually Camera.kt: val z2 = zr * cp - v.y * sp; val sc = focalLength / (focalLength + z2)
        // Positive depth means further away.

        // Normalize depth for compensation.
        // Let's assume depth 0 is neutral.
        val depthFactor = -(depth / 200f).coerceIn(-1f, 1f)
        val adjustment = 1.0f + (depthFactor * 0.15f)
        return thickness * adjustment
    }

    /**
     * Head Scale Compensation
     * Prevents excessive shrinking.
     */
    fun compensateHeadScale(scale: Float, depth: Float): Float {
        val depthFactor = (depth / 200f).coerceAtLeast(0f)
        // If depth is positive (far), boost scale slightly
        val boost = 1.0f + (depthFactor * 0.1f)
        return scale * boost.coerceAtMost(1.15f)
    }

    /**
     * Hand Perspective Compensation
     * Similar to head, prevent excessive shrinking of the hand indicator.
     */
    fun compensateHandScale(scale: Float, depth: Float): Float {
        val depthFactor = (depth / 200f).coerceAtLeast(0f)
        val boost = 1.0f + (depthFactor * 0.08f)
        return scale * boost.coerceAtMost(1.12f)
    }
}
