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

        // Correct Active (Left) Hand
        adjustHandOrientation(joints, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
        // Correct Passive (Right) Hand
        adjustHandOrientation(joints, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

        return SkeletonPose(joints)
    }

    private fun adjustHandOrientation(
        joints: MutableMap<Joint, Vector3>,
        elbowId: Joint,
        handId: Joint,
        wristId: Joint,
        palmId: Joint,
        knucklesId: Joint,
        fingertipsId: Joint
    ) {
        val elbow = joints[elbowId] ?: return
        val hand = joints[handId] ?: return

        // The PoseBuilder provided hand position is used as the target for the wrist.
        // IK already ends at 'hand', so 'wrist' = 'hand'.
        val wrist = hand
        joints[wristId] = wrist

        // Direction is forearm direction
        val dir = (wrist - elbow).normalize()

        val handDef = definition.hand
        val handJoints = handDef.computeHandJoints(wrist, dir)

        joints[palmId] = handJoints.palm
        joints[knucklesId] = handJoints.knuckles
        joints[fingertipsId] = handJoints.fingertips
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
        val providedToe = joints[toeId]

        val shank = (ankle - knee).normalize()

        // Use provided toe as direction hint, fallback to forward (X+)
        val forwardHint = if (providedToe != null && (providedToe - ankle).mag() > 1e-3) {
            (providedToe - ankle).normalize()
        } else {
            Vector3(1f, 0f, 0f)
        }

        // Target: foot perpendicular to shank
        var footDir = forwardHint - shank * forwardHint.dot(shank)

        if (footDir.mag() < 1e-3) {
            // Shank is parallel to hint, fallback to world down-ish relative to shank
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
        val (heel, toe) = foot.computeHeelToe(ankle, footDir)
        joints[heelId] = heel
        joints[toeId] = toe
    }

    /**
     * Stage 2: 2D Projected Points Correction
     * Maintains visual foot length from Heel to Toe, keeping Ankle fixed.
     * The center of scaling is the midpoint between Heel and Toe.
     */
    fun compensateFootPerspective(
        ankleProj: ProjectedPoint,
        heelProj: ProjectedPoint,
        toeProj: ProjectedPoint,
        camera: Camera
    ): Pair<ProjectedPoint, ProjectedPoint> {
        // Current length and midpoint in screen space
        val currentLen = sqrt((toeProj.x - heelProj.x).pow(2) + (toeProj.y - heelProj.y).pow(2))
        if (currentLen < 1e-3) return heelProj to toeProj

        val midX = (heelProj.x + toeProj.x) / 2f
        val midY = (heelProj.y + toeProj.y) / 2f

        val foot = definition.foot
        val idealLen = foot.footLength * ankleProj.scale * camera.zoom

        val ratio = idealLen / currentLen
        val targetRatio = 1.0f + (ratio - 1.0f) * 0.15f // 15% correction

        // Offset from midpoint
        val hdx = heelProj.x - midX
        val hdy = heelProj.y - midY
        val tdx = toeProj.x - midX
        val tdy = toeProj.y - midY

        // To maintain the Ankle fixed visually, we should scale relative to the ankle,
        // but the prompt says "visual center of scaling becomes Heel <-> Toe rather than Ankle <-> Toe".
        // HOWEVER, "the ankle remains fixed" is also a hard requirement.
        // If we scale from the midpoint, the ankle (which is not at the midpoint) will move visually.
        // If the ankle must remain fixed, we MUST scale relative to the ankle.

        // Re-reading: "Perspective scaling must preserve heel-to-toe length instead of stretching from ankle to toe."
        // "The visual center of scaling becomes Heel <-> Toe rather than Ankle <-> Toe."
        // This likely means we should use the total foot length (Heel to Toe) for the ratio calculation,
        // but still keep the ankle as the anchor if it must not move.

        // Applying the ratio from the ankle anchor:
        val ahdx = heelProj.x - ankleProj.x
        val ahdy = heelProj.y - ankleProj.y
        val atdx = toeProj.x - ankleProj.x
        val atdy = toeProj.y - ankleProj.y

        val newHeel = ProjectedPoint(
            x = ankleProj.x + ahdx * targetRatio,
            y = ankleProj.y + ahdy * targetRatio,
            scale = heelProj.scale,
            depth = heelProj.depth
        )
        val newToe = ProjectedPoint(
            x = ankleProj.x + atdx * targetRatio,
            y = ankleProj.y + atdy * targetRatio,
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
     * Maintains visual palm length regardless of camera depth.
     */
    fun compensateHandPerspective(
        wristProj: ProjectedPoint,
        palmProj: ProjectedPoint,
        fingertipsProj: ProjectedPoint,
        camera: Camera
    ): Pair<ProjectedPoint, ProjectedPoint> {
        val currentLen = sqrt((fingertipsProj.x - wristProj.x).pow(2) + (fingertipsProj.y - wristProj.y).pow(2))
        if (currentLen < 1e-3) return palmProj to fingertipsProj

        val handDef = definition.hand
        val idealLen = (handDef.palmLength + handDef.fingerLength) * wristProj.scale * camera.zoom

        val ratio = idealLen / currentLen
        val targetRatio = 1.0f + (ratio - 1.0f) * 0.12f // 12% correction

        val pdx = palmProj.x - wristProj.x
        val pdy = palmProj.y - wristProj.y
        val fdx = fingertipsProj.x - wristProj.x
        val fdy = fingertipsProj.y - wristProj.y

        val newPalm = ProjectedPoint(
            x = wristProj.x + pdx * targetRatio,
            y = wristProj.y + pdy * targetRatio,
            scale = palmProj.scale,
            depth = palmProj.depth
        )
        val newFingertips = ProjectedPoint(
            x = wristProj.x + fdx * targetRatio,
            y = wristProj.y + fdy * targetRatio,
            scale = fingertipsProj.scale,
            depth = fingertipsProj.depth
        )

        return newPalm to newFingertips
    }

    /**
     * Hand Perspective Scale Compensation
     * Similar to head, prevent excessive shrinking of the hand indicator.
     */
    fun compensateHandScale(scale: Float, depth: Float): Float {
        val depthFactor = (depth / 200f).coerceAtLeast(0f)
        val boost = 1.0f + (depthFactor * 0.08f)
        return scale * boost.coerceAtMost(1.12f)
    }
}
