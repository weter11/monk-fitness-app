package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class SupermanPose : PoseBuilder {
    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val definition = context.definition

        // Prone position
        // progress 0 (resting) to 1 (extended)

        val pelvis = Vector3(0f, 10f, 0f)
        val chestLean = lerp(0f, -0.2f, progress).toDouble()
        val chest = pelvis + Vector3(-definition.torsoLength * cos(chestLean).toFloat(), -definition.torsoLength * sin(chestLean).toFloat(), 0f)

        val hipF = pelvis + Vector3(0f, 0f, definition.hipWidth)
        val hipB = pelvis + Vector3(0f, 0f, -definition.hipWidth)

        val totalLegLen = definition.thighLength + definition.shinLength
        val legLean = lerp(0f, 0.3f, progress).toDouble()
        val toeF = hipF + Vector3(totalLegLen * cos(legLean).toFloat(), totalLegLen * sin(legLean).toFloat(), 0f)
        val toeB = hipB + Vector3(totalLegLen * cos(legLean).toFloat(), totalLegLen * sin(legLean).toFloat(), 0f)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint)

        val shoulderA = chest + Vector3(0f, 0f, definition.shoulderWidth)
        val shoulderP = chest + Vector3(0f, 0f, -definition.shoulderWidth)

        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val armLean = lerp(0.1f, -0.4f, progress).toDouble()
        val handA = shoulderA + Vector3(-totalArmLen * cos(armLean).toFloat(), -totalArmLen * sin(armLean).toFloat(), 0f)
        val handP = shoulderP + Vector3(-totalArmLen * cos(armLean).toFloat(), -totalArmLen * sin(armLean).toFloat(), 0f)

        val armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, 1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, -1f), IKConstraint.ArmConstraint)

        val headDir = Vector3(-1f, 0.3f, 0f).normalize()
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
