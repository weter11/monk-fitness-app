package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class SquatPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP
    )

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val definition = context.definition

        // progress 0 (up) to 1 (down)
        val ankleHeight = definition.foot.ankleHeight
        val pelvisHeight = lerp(definition.thighLength + definition.shinLength + ankleHeight + 10f, 40f + ankleHeight, progress)
        val pelvis = Vector3(0f, pelvisHeight, 0f)

        val hipF = pelvis + Vector3(0f, 0f, -definition.hipWidth)
        val hipB = pelvis + Vector3(0f, 0f, definition.hipWidth)

        val toeF = Vector3(20f + 20f * progress, ankleHeight, -definition.hipWidth * 1.5f)
        val toeB = Vector3(20f + 20f * progress, ankleHeight, definition.hipWidth * 1.5f)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(1f, 0f, 0f), IKConstraint.LegConstraint)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(1f, 0f, 0f), IKConstraint.LegConstraint)

        val chestLean = lerp(0.1f, 0.4f, progress)
        val chest = pelvis + Vector3(sin(chestLean) * definition.torsoLength, cos(chestLean) * definition.torsoLength, 0f)

        val shoulderA = chest + Vector3(0f, 0f, -definition.shoulderWidth)
        val shoulderP = chest + Vector3(0f, 0f, definition.shoulderWidth)

        val armLean = lerp(0.5f, 1.5f, progress)
        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val handA = shoulderA + Vector3(sin(armLean) * totalArmLen, -cos(armLean) * totalArmLen, 0f)
        val handP = shoulderP + Vector3(sin(armLean) * totalArmLen, -cos(armLean) * totalArmLen, 0f)

        val armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint)

        val headDir = Vector3(0.2f, 0.9f, 0f).normalize()
        val neckEnd = chest + headDir * definition.neckLength
        val headPos = chest + headDir * (definition.neckLength + 18f)

        return SkeletonPose(
            joints = mapOf(
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
            ))
    }
}
