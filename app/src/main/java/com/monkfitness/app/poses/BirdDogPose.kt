package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp

class BirdDogPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP
    )

    private val jointsBuffer = SkeletonPose()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val side = context.side
        val definition = context.definition

        // Quadruped base
        val pelvis = tempV1.set(50f, 45f, 0f)
        val chest = tempV2.set(-definition.torsoLength, 0f, 0f).add(pelvis)

        val hipF = Vector3(0f, 0f, -definition.hipWidth).add(pelvis)
        val hipB = Vector3(0f, 0f, definition.hipWidth).add(pelvis)

        val shoulderA = Vector3(0f, 0f, -definition.shoulderWidth).add(chest) // Right
        val shoulderP = Vector3(0f, 0f, definition.shoulderWidth).add(chest) // Left

        val ankleHeight = definition.foot.ankleHeight
        // Target positions for "neutral" quadruped
        val baseHandR = Vector3(0f, -45f, 0f).add(shoulderA)
        val baseHandL = Vector3(0f, -45f, 0f).add(shoulderP)
        val baseKneeR = Vector3(0f, -45f, 0f).add(hipF)
        val baseKneeL = Vector3(0f, -45f, 0f).add(hipB)

        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val totalLegLen = definition.thighLength + definition.shinLength

        // Extended positions
        val extHand = if (side == Side.RIGHT) {
            Vector3(-totalArmLen, 10f, 0f).add(shoulderP) // Left arm extends
        } else {
            Vector3(-totalArmLen, 10f, 0f).add(shoulderA) // Right arm extends
        }

        val extKnee = if (side == Side.RIGHT) {
            Vector3(totalLegLen, 10f, 0f).add(hipF) // Right leg extends
        } else {
            Vector3(totalLegLen, 10f, 0f).add(hipB) // Left leg extends
        }

        // Lerp based on progress
        val handR = if (side == Side.LEFT) lerp(baseHandR, extHand, progress, tempV3).copy() else baseHandR
        val handL = if (side == Side.RIGHT) lerp(baseHandL, extHand, progress, tempV3).copy() else baseHandL

        val kneeR = if (side == Side.RIGHT) lerp(baseKneeR, extKnee, progress, tempV3).copy() else baseKneeR
        val kneeL = if (side == Side.LEFT) lerp(baseKneeL, extKnee, progress, tempV3).copy() else baseKneeL

        // Toe/Ankle positions
        val toeF = Vector3(10f, -10f + ankleHeight, 0f).add(kneeR)
        val toeB = Vector3(10f, -10f + ankleHeight, 0f).add(kneeL)

        val armA = solveIK(shoulderA, handR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint, armAIK)
        val armP = solveIK(shoulderP, handL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint, armPIK)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f), IKConstraint.LegConstraint, legFIK)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f), IKConstraint.LegConstraint, legBIK)

        val headDir = tempV3.set(-1f, 0.1f, 0f).normalize().copy()
        val neckEnd = Vector3(headDir.x, headDir.y, headDir.z).multiply(definition.neckLength).add(chest)
        val headPos = headDir.multiply(definition.neckLength + 18f).add(chest)

        jointsBuffer.setJoint(Joint.PELVIS, pelvis)
        jointsBuffer.setJoint(Joint.HIP_F, hipF)
        jointsBuffer.setJoint(Joint.HIP_B, hipB)
        jointsBuffer.setJoint(Joint.KNEE_F, legF.joint)
        jointsBuffer.setJoint(Joint.ANKLE_F, legF.end)
        jointsBuffer.setJoint(Joint.TOE_F, toeF)
        jointsBuffer.setJoint(Joint.KNEE_B, legB.joint)
        jointsBuffer.setJoint(Joint.ANKLE_B, legB.end)
        jointsBuffer.setJoint(Joint.TOE_B, toeB)
        jointsBuffer.setJoint(Joint.CHEST, chest)
        jointsBuffer.setJoint(Joint.SHOULDER_A, shoulderA)
        jointsBuffer.setJoint(Joint.SHOULDER_P, shoulderP)
        jointsBuffer.setJoint(Joint.ELBOW_A, armA.joint)
        jointsBuffer.setJoint(Joint.HAND_A, armA.end)
        jointsBuffer.setJoint(Joint.ELBOW_P, armP.joint)
        jointsBuffer.setJoint(Joint.HAND_P, armP.end)
        jointsBuffer.setJoint(Joint.NECK_END, neckEnd)
        jointsBuffer.setJoint(Joint.HEAD_POS, headPos)

        return jointsBuffer
    }
}
