package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp

class BirdDogPose : PoseBuilder {
    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val side = context.side
        val definition = context.definition

        // Quadruped base
        val pelvis = Vector3(50f, 45f, 0f)
        val chest = pelvis + Vector3(-definition.torsoLength, 0f, 0f)

        val hipF = pelvis + Vector3(0f, 0f, -definition.hipWidth)
        val hipB = pelvis + Vector3(0f, 0f, definition.hipWidth)

        val shoulderA = chest + Vector3(0f, 0f, -definition.shoulderWidth) // Right
        val shoulderP = chest + Vector3(0f, 0f, definition.shoulderWidth) // Left

        val ankleHeight = definition.foot.ankleHeight
        // Target positions for "neutral" quadruped
        val baseHandR = shoulderA + Vector3(0f, -45f, 0f)
        val baseHandL = shoulderP + Vector3(0f, -45f, 0f)
        val baseKneeR = hipF + Vector3(0f, -45f, 0f)
        val baseKneeL = hipB + Vector3(0f, -45f, 0f)

        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val totalLegLen = definition.thighLength + definition.shinLength

        // Extended positions
        val extHand = if (side == Side.RIGHT) {
            shoulderP + Vector3(-totalArmLen, 10f, 0f) // Left arm extends
        } else {
            shoulderA + Vector3(-totalArmLen, 10f, 0f) // Right arm extends
        }

        val extKnee = if (side == Side.RIGHT) {
            hipF + Vector3(totalLegLen, 10f, 0f) // Right leg extends
        } else {
            hipB + Vector3(totalLegLen, 10f, 0f) // Left leg extends
        }

        // Lerp based on progress
        val handR = if (side == Side.LEFT) lerp(baseHandR, extHand, progress) else baseHandR
        val handL = if (side == Side.RIGHT) lerp(baseHandL, extHand, progress) else baseHandL

        val kneeR = if (side == Side.RIGHT) lerp(baseKneeR, extKnee, progress) else baseKneeR
        val kneeL = if (side == Side.LEFT) lerp(baseKneeL, extKnee, progress) else baseKneeL

        // Toe/Ankle positions
        val toeF = kneeR + Vector3(10f, -10f + ankleHeight, 0f)
        val toeB = kneeL + Vector3(10f, -10f + ankleHeight, 0f)

        val armA = solveIK(shoulderA, handR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderP, handL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f), IKConstraint.LegConstraint)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f), IKConstraint.LegConstraint)

        val headDir = Vector3(-1f, 0.1f, 0f).normalize()
        val neckEnd = chest + headDir * definition.neckLength
        val headPos = chest + headDir * (definition.neckLength + 18f)

        return SkeletonPose(
            mapOf(
                Joint.PELVIS to pelvis,
                Joint.HIP_F to hipF,
                Joint.HIP_B to hipB,
                Joint.KNEE_F to legF.joint,
                Joint.ANKLE_F to legF.end,
                Joint.TOE_F to toeF,
                Joint.KNEE_B to legB.joint,
                Joint.ANKLE_B to legB.end,
                Joint.TOE_B to toeB,
                Joint.CHEST to chest,
                Joint.SHOULDER_A to shoulderA,
                Joint.SHOULDER_P to shoulderP,
                Joint.ELBOW_A to armA.joint,
                Joint.HAND_A to armA.end,
                Joint.ELBOW_P to armP.joint,
                Joint.HAND_P to armP.end,
                Joint.NECK_END to neckEnd,
                Joint.HEAD_POS to headPos
            )
        )
    }
}
