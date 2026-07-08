package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp

class PushUpPose : PoseBuilder {
    override fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose {
        // progress 0 (up) to 1 (down)

        val height = lerp(60f, 25f, progress)
        val pelvis = Vector3(60f, height, 0f)
        val chest = pelvis + Vector3(-definition.torsoLength, 0f, 0f)

        val hipF = pelvis + Vector3(0f, 0f, definition.hipWidth)
        val hipB = pelvis + Vector3(0f, 0f, -definition.hipWidth)

        val totalLegLen = definition.thighLength + definition.shinLength
        val toeF = Vector3(60f + totalLegLen, 0f, definition.hipWidth)
        val toeB = Vector3(60f + totalLegLen, 0f, -definition.hipWidth)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint)

        val shoulderA = chest + Vector3(0f, 0f, definition.shoulderWidth)
        val shoulderP = chest + Vector3(0f, 0f, -definition.shoulderWidth)

        val handA = chest + Vector3(0f, -height, definition.shoulderWidth * 1.5f)
        val handP = chest + Vector3(0f, -height, -definition.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, Vector3(1f, 0f, 1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, Vector3(1f, 0f, -1f), IKConstraint.ArmConstraint)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        val neckEnd = chest + headDir * definition.neckLength
        val headPos = chest + headDir * (definition.neckLength + definition.headRadius)

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
