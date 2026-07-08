package com.monkfitness.app.animation

import kotlin.math.*

class PerspectiveCompensation(
    private val definition: SkeletonDefinition
) {
    /**
     * Stage 1: 3D Pose Correction (Before Projection)
     * Corrects foot orientation based on the shank (tibia) and generates Heel/Toe.
     */
    fun preProcessPose(pose: SkeletonPose): SkeletonPose {
        val joints = pose.joints.toMutableMap()

        // Correct Fore (Left) Foot
        adjustFootOrientation(joints, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
        // Correct Back (Right) Foot
        adjustFootOrientation(joints, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)

        return SkeletonPose(joints)
    }

    private fun adjustFootOrientation(
        joints: MutableMap<Joint, Vector3>,
        kneeId: Joint,
        ankleId: Joint,
        heelId: Joint,
        toeId: Joint
    ) {
        val knee = joints[kneeId] ?: return
        val ankle = joints[ankleId] ?: return

        val shank = (ankle - knee).normalize()

        // Target: foot perpendicular to shank, pointing forward (positive X)
        val forward = Vector3(1f, 0f, 0f)
        var footDir = forward - shank * forward.dot(shank)

        if (footDir.mag() < 1e-3) {
            footDir = Vector3(0f, -1f, 0f) - shank * Vector3(0f, -1f, 0f).dot(shank)
        }
        footDir = footDir.normalize()

        // Clamp foot pitch
        val pitch = atan2(footDir.y, sqrt(footDir.x * footDir.x + footDir.z * footDir.z))
        val clampedPitch = pitch.coerceIn(definition.foot.minPitch, definition.foot.maxPitch)

        if (abs(pitch - clampedPitch) > 1e-3) {
            val horizontalDir = Vector3(footDir.x, 0f, footDir.z).normalize()
            footDir = horizontalDir * cos(clampedPitch) + Vector3(0f, 1f, 0f) * sin(clampedPitch)
        }

        val foot = definition.foot
        joints[toeId] = ankle + footDir * (foot.footLength * foot.toeRatio)
        joints[heelId] = ankle - footDir * (foot.footLength * foot.heelRatio)
    }

    /**
     * Stage 2: 2D Projected Points Correction
     * Maintains visual foot length from Heel to Toe, keeping Ankle fixed.
     */
    fun compensateFootPerspective(
        ankleProj: ProjectedPoint,
        heelProj: ProjectedPoint,
        toeProj: ProjectedPoint,
        camera: Camera
    ): Pair<ProjectedPoint, ProjectedPoint> {
        val hdx = heelProj.x - ankleProj.x
        val hdy = heelProj.y - ankleProj.y
        val tdx = toeProj.x - ankleProj.x
        val tdy = toeProj.y - ankleProj.y

        val currentDist = sqrt((toeProj.x - heelProj.x).pow(2) + (toeProj.y - heelProj.y).pow(2))
        if (currentDist < 1e-3) return heelProj to toeProj

        val foot = definition.foot
        val idealDist = foot.footLength * ankleProj.scale * camera.zoom

        val ratio = idealDist / currentDist
        val targetRatio = 1.0f + (ratio - 1.0f) * 0.15f // 15% correction

        val newHeel = ProjectedPoint(
            x = ankleProj.x + hdx * targetRatio,
            y = ankleProj.y + hdy * targetRatio,
            scale = heelProj.scale,
            depth = heelProj.depth
        )
        val newToe = ProjectedPoint(
            x = ankleProj.x + tdx * targetRatio,
            y = ankleProj.y + tdy * targetRatio,
            scale = toeProj.scale,
            depth = toeProj.depth
        )

        return newHeel to newToe
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
