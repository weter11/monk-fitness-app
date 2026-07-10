package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class SquatPose : PoseBuilder {
    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SLOW_DOWN_FAST_UP
    )

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val definition = context.definition

        // progress 0 (up) to 1 (down)
        val ankleHeight = definition.foot.ankleHeight
        val pelvisHeight = lerp(definition.thighLength + definition.shinLength + ankleHeight + 10f, 40f + ankleHeight, progress)
        val pelvis = tempV1.set(0f, pelvisHeight, 0f)

        val hipF = tempV2.set(0f, 0f, -definition.hipWidth).add(pelvis)
        val hipB = Vector3(0f, 0f, definition.hipWidth).add(pelvis)

        val toeF = Vector3(20f + 20f * progress, ankleHeight, -definition.hipWidth * 1.5f)
        val toeB = Vector3(20f + 20f * progress, ankleHeight, definition.hipWidth * 1.5f)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(1f, 0f, 0f), IKConstraint.LegConstraint, legFIK)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(1f, 0f, 0f), IKConstraint.LegConstraint, legBIK)

        val chestLean = lerp(0.1f, 0.4f, progress)
        val chest = tempV3.set(sin(chestLean) * definition.torsoLength, cos(chestLean) * definition.torsoLength, 0f).add(pelvis)

        val shoulderA = Vector3(0f, 0f, -definition.shoulderWidth).add(chest)
        val shoulderP = Vector3(0f, 0f, definition.shoulderWidth).add(chest)

        val armLean = lerp(0.5f, 1.5f, progress)
        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val handA = Vector3(sin(armLean) * totalArmLen, -cos(armLean) * totalArmLen, 0f).add(shoulderA)
        val handP = Vector3(sin(armLean) * totalArmLen, -cos(armLean) * totalArmLen, 0f).add(shoulderP)

        val armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint, armAIK)
        val armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint, armPIK)

        val headDir = tempV1.set(0.2f, 0.9f, 0f).normalize().copy()
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
