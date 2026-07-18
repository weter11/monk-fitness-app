package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class SupermanPose : PoseBuilder {
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
        motionCurve = MotionCurve.EASE_IN_OUT
    )

    override fun build(context: PoseContext): SkeletonPose {
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val progress = context.progress
        val definition = context.definition

        // Prone position
        // progress 0 (resting) to 1 (extended)

        val pelvis = tempV1.set(0f, 10f, 0f)
        val chestLean = lerp(0f, -0.2f, progress).toDouble()
        val chest = tempV2.set(-definition.torsoLength * cos(chestLean).toFloat(), -definition.torsoLength * sin(chestLean).toFloat(), 0f).add(pelvis)

        val hipF = tempV3.set(0f, 0f, -definition.hipWidth).add(pelvis)
        val hipB = Vector3(0f, 0f, definition.hipWidth).add(pelvis)

        val totalLegLen = definition.thighLength + definition.shinLength
        val legLean = lerp(0f, 0.3f, progress).toDouble()
        val toeF = Vector3(totalLegLen * cos(legLean).toFloat(), totalLegLen * sin(legLean).toFloat(), 0f).add(hipF)
        val toeB = Vector3(totalLegLen * cos(legLean).toFloat(), totalLegLen * sin(legLean).toFloat(), 0f).add(hipB)

        val legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, legFIK)
        val legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, legBIK)

        val shoulderA = Vector3(0f, 0f, -definition.shoulderWidth).add(chest)
        val shoulderP = Vector3(0f, 0f, definition.shoulderWidth).add(chest)

        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val armLean = lerp(0.1f, -0.4f, progress).toDouble()
        val handA = Vector3(-totalArmLen * cos(armLean).toFloat(), -totalArmLen * sin(armLean).toFloat(), 0f).add(shoulderA)
        val handP = Vector3(-totalArmLen * cos(armLean).toFloat(), -totalArmLen * sin(armLean).toFloat(), 0f).add(shoulderP)

        val armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, -1f), IKConstraint.ArmConstraint, armAIK)
        val armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, 1f), IKConstraint.ArmConstraint, armPIK)

        val headDir = tempV1.set(-1f, 0.3f, 0f).normalize().copy()
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

        // Phase E (L1 bridge removal): this pose authors joints as world positions; populate the
        // roots hierarchy from them so finalize no longer takes the deleted legacy bridge.
        SkeletonPose.fromJointPositions(definition, jointsBuffer, jointsBuffer)
        return jointsBuffer
    }
}
