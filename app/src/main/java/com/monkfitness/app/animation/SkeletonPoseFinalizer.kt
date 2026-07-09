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
    private val outputPose = SkeletonPose()
    private val tempDir = Vector3()
    private val tempForwardHint = Vector3()
    private val tempFootDir = Vector3()
    private val tempHorizontalDir = Vector3()
    private val handJointsBuffer = HandJoints()
    private val tempV1 = Vector3()

    /**
     * Finalizes the 3D pose by adding calculated biomechanical joints.
     * Updates and returns a persistent output buffer.
     */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        outputPose.copyFrom(pose)

        // Correct Feet (Left/Fore and Right/Back)
        adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
        adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)

        // Correct Hands (Left/Active and Right/Passive)
        adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
        adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

        return outputPose
    }

    private fun adjustHandOrientation(
        pose: SkeletonPose,
        elbowId: Joint,
        handId: Joint,
        wristId: Joint,
        palmId: Joint,
        knucklesId: Joint,
        fingertipsId: Joint
    ) {
        val elbow = pose.getJoint(elbowId)
        val hand = pose.getJoint(handId)

        // Use the PoseBuilder provided hand position as the wrist.
        val wrist = pose.getJoint(wristId)
        wrist.set(hand)

        // Hand direction follows forearm direction
        tempDir.set(wrist).subtract(elbow).normalize()

        val handDef = definition.hand
        handDef.computeHandJoints(wrist, tempDir, handJointsBuffer)

        pose.getJoint(palmId).set(handJointsBuffer.palm)
        pose.getJoint(knucklesId).set(handJointsBuffer.knuckles)
        pose.getJoint(fingertipsId).set(handJointsBuffer.fingertips)
    }

    private fun adjustFootOrientation(
        pose: SkeletonPose,
        kneeId: Joint,
        ankleId: Joint,
        heelId: Joint,
        toeId: Joint
    ) {
        val knee = pose.getJoint(kneeId)
        val ankle = pose.getJoint(ankleId)
        val providedToe = pose.getJoint(toeId)

        // We use a copy of shank to avoid mutating ankle/knee
        val shank = (ankle - knee).normalize()

        // Use provided toe as direction hint, fallback to world forward (X+)
        if ((providedToe - ankle).mag() > 1e-3) {
            tempForwardHint.set(providedToe).subtract(ankle).normalize()
        } else {
            tempForwardHint.set(1f, 0f, 0f)
        }

        // Target: foot perpendicular to shank
        // footDir = forwardHint - shank * forwardHint.dot(shank)
        tempFootDir.set(shank).multiply(tempForwardHint.dot(shank))
        tempFootDir.set(tempForwardHint.x - tempFootDir.x, tempForwardHint.y - tempFootDir.y, tempForwardHint.z - tempFootDir.z)

        if (tempFootDir.mag() < 1e-3) {
            // Shank is parallel to hint, fallback to world down relative to shank
            // footDir = Vector3(0f, -1f, 0f) - shank * Vector3(0f, -1f, 0f).dot(shank)
            val worldDown = Vector3(0f, -1f, 0f)
            tempFootDir.set(shank).multiply(worldDown.dot(shank))
            tempFootDir.set(worldDown.x - tempFootDir.x, worldDown.y - tempFootDir.y, worldDown.z - tempFootDir.z)
        }
        tempFootDir.normalize()

        // Clamp foot pitch to anatomical limits
        val pitch = atan2(tempFootDir.y, sqrt(tempFootDir.x * tempFootDir.x + tempFootDir.z * tempFootDir.z))
        val clampedPitch = pitch.coerceIn(definition.foot.minPitch, definition.foot.maxPitch)

        if (abs(pitch - clampedPitch) > 1e-3) {
            tempHorizontalDir.set(tempFootDir.x, 0f, tempFootDir.z).normalize()
            tempFootDir.set(
                tempHorizontalDir.x * cos(clampedPitch),
                sin(clampedPitch),
                tempHorizontalDir.z * cos(clampedPitch)
            )
        }

        val foot = definition.foot
        foot.computeHeelToe(ankle, tempFootDir, pose.getJoint(heelId), pose.getJoint(toeId))
    }
}
