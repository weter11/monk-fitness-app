package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class CatCowPose : PoseBuilder {
    private val jointsBuffer = SkeletonPose()
    private val pelvisVal = Vector3()
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
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP
    )

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val definition = context.definition

        // Quadruped base
        // progress 0 (Cat - rounded) to 1 (Cow - arched)

        val ankleHeight = definition.foot.ankleHeight
        val pelvisPos = lerp(45f, 40f, progress) + ankleHeight
        val pelvis = pelvisVal.set(50f, pelvisPos, 0f)

        val chestPos = lerp(45f, 35f, progress) + ankleHeight
        val chest = tempV2.set(-definition.torsoLength, chestPos - pelvisPos, 0f).add(pelvis)

        val hipF = tempV3.set(0f, 0f, -definition.hipWidth).add(pelvis)
        val hipB = Vector3(0f, 0f, definition.hipWidth).add(pelvis) // tempV3 is occupied

        val kneeBaseR = Vector3(50f, ankleHeight, -definition.hipWidth)
        val kneeBaseL = Vector3(50f, ankleHeight, definition.hipWidth)

        val legF = solveIK(hipF, kneeBaseR, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f), IKConstraint.LegConstraint, legFIK)
        val legB = solveIK(hipB, kneeBaseL, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f), IKConstraint.LegConstraint, legBIK)

        val shoulderA = Vector3(0f, 0f, -definition.shoulderWidth).add(chest)
        val shoulderP = Vector3(0f, 0f, definition.shoulderWidth).add(chest)

        val handBaseR = Vector3(0f, -chestPos, 0f).add(shoulderA)
        val handBaseL = Vector3(0f, -chestPos, 0f).add(shoulderP)

        val armA = solveIK(shoulderA, handBaseR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint, armAIK)
        val armP = solveIK(shoulderP, handBaseL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint, armPIK)

        val headPitch = lerp(-0.5f, 0.5f, progress)
        val headDir = tempV3.set(-cos(headPitch), sin(headPitch), 0f).normalize()
        val neckEnd = Vector3(headDir.x, headDir.y, headDir.z).multiply(definition.neckLength).add(chest)
        val headPos = headDir.multiply(definition.neckLength + 18f).add(chest)

        jointsBuffer.setJoint(Joint.PELVIS, pelvis)
        jointsBuffer.setJoint(Joint.HIP_F, hipF)
        jointsBuffer.setJoint(Joint.HIP_B, hipB)
        jointsBuffer.setJoint(Joint.KNEE_F, legF.joint)
        jointsBuffer.setJoint(Joint.ANKLE_F, legF.end)
        jointsBuffer.setJoint(Joint.TOE_F, tempV3.set(kneeBaseR).add(Vector3(10f, 0f, 0f)))
        jointsBuffer.setJoint(Joint.KNEE_B, legB.joint)
        jointsBuffer.setJoint(Joint.ANKLE_B, legB.end)
        jointsBuffer.setJoint(Joint.TOE_B, tempV1.set(kneeBaseL).add(Vector3(10f, 0f, 0f)))
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
