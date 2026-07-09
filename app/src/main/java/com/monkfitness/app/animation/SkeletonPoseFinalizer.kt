package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SkeletonPoseFinalizer is responsible for completing the 3D pose before it is projected to screen space.
 * It adds biomechanical details like Heel/Toe and Hand segments that are not part of the core PoseBuilder logic.
 * This stage ensures the 3D skeleton is anatomically complete.
 */
class SkeletonPoseFinalizer(
    private val definition: SkeletonDefinition
) {
    /**
     * Finalizes the 3D pose by adding calculated biomechanical joints.
     */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        val joints = pose.joints.toMutableMap()

        // Correct Feet (Left/Fore and Right/Back)
        adjustFootOrientation(joints, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
        adjustFootOrientation(joints, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)

        // Correct Hands (Left/Active and Right/Passive)
        adjustHandOrientation(joints, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
        adjustHandOrientation(joints, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

        return SkeletonPose(joints, pose.roots)
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

        // Use the PoseBuilder provided hand position as the wrist.
        val wrist = hand
        joints[wristId] = wrist

        // Hand direction follows forearm direction
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

        // Use provided toe as direction hint, fallback to world forward (X+)
        val forwardHint = if (providedToe != null && (providedToe - ankle).mag() > 1e-3) {
            (providedToe - ankle).normalize()
        } else {
            Vector3(1f, 0f, 0f)
        }

        // Target: foot perpendicular to shank
        var footDir = forwardHint - shank * forwardHint.dot(shank)

        if (footDir.mag() < 1e-3) {
            // Shank is parallel to hint, fallback to world down relative to shank
            footDir = Vector3(0f, -1f, 0f) - shank * Vector3(0f, -1f, 0f).dot(shank)
        }
        footDir = footDir.normalize()

        // Clamp foot pitch to anatomical limits
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
}
