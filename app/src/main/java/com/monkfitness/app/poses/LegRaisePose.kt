package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class LegRaisePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition

        // 1. Supine Core Positioning
        val pelvisX = 15f
        val pelvisY = 12f
        val pelvis = Vector3(pelvisX, pelvisY, 0f)

        val chestX = pelvisX - def.torsoLength
        val chest = Vector3(chestX, pelvisY, 0f)

        val neckEnd = Vector3(chestX - def.neckLength, pelvisY, 0f)
        val headPos = Vector3(chestX - def.neckLength - 18f, pelvisY, 0f)

        val hipF = Vector3(pelvisX, pelvisY, -def.hipWidth)
        val hipB = Vector3(pelvisX, pelvisY, def.hipWidth)

        val shoulderA = Vector3(chestX, pelvisY, -def.shoulderWidth)
        val shoulderP = Vector3(chestX, pelvisY, def.shoulderWidth)

        // 2. Continuous Leg Elevation Angle Calculation
        // Smooth C2 cosine wave mapping progress 0.0 -> 0.5 (up) -> 1.0 (down)
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f
        val maxElevation = 85f * PI.toFloat() / 180f
        val theta = lerp(0f, maxElevation, u)

        // 3. ARM TARGETS (Resting flat beside glutes)
        val totalArmLen = def.upperArmLength + def.forearmLength
        val targetHandA = Vector3(pelvisX + 15f, 10f, -def.shoulderWidth * 1.2f)
        val targetHandP = Vector3(pelvisX + 15f, 10f, def.shoulderWidth * 1.2f)

        // Solve Arm IK (slightly flexed elbow pointing upward/outward)
        val armA = solveIK(shoulderA, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, armABuffer)
        val armP = solveIK(shoulderP, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, armPBuffer)

        // 4. LEG TARGETS (Double legs elevating together in perfect unison)
        val L = (def.thighLength + def.shinLength) * 0.96f
        val targetAnkleF = Vector3(hipF.x + L * cos(theta), hipF.y + L * sin(theta), hipF.z)
        val targetAnkleB = Vector3(hipB.x + L * cos(theta), hipB.y + L * sin(theta), hipB.z)

        // Solve Leg IK (knees straight, flexed forward slightly)
        val legF = solveIK(hipF, targetAnkleF, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, legFBuffer)
        val legB = solveIK(hipB, targetAnkleB, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, legBBuffer)

        // 5. Build joint positions into SkeletonPose
        jointsBuffer.setJoint(Joint.PELVIS, pelvis)
        jointsBuffer.setJoint(Joint.CHEST, chest)
        jointsBuffer.setJoint(Joint.NECK_END, neckEnd)
        jointsBuffer.setJoint(Joint.HEAD_POS, headPos)

        jointsBuffer.setJoint(Joint.HIP_F, hipF)
        jointsBuffer.setJoint(Joint.KNEE_F, legF.joint)
        jointsBuffer.setJoint(Joint.ANKLE_F, legF.end)
        jointsBuffer.getJoint(Joint.TOE_F).set(legF.end.x + def.footLength * cos(theta), legF.end.y + def.footLength * sin(theta), legF.end.z)

        jointsBuffer.setJoint(Joint.HIP_B, hipB)
        jointsBuffer.setJoint(Joint.KNEE_B, legB.joint)
        jointsBuffer.setJoint(Joint.ANKLE_B, legB.end)
        jointsBuffer.getJoint(Joint.TOE_B).set(legB.end.x + def.footLength * cos(theta), legB.end.y + def.footLength * sin(theta), legB.end.z)

        jointsBuffer.setJoint(Joint.SHOULDER_A, shoulderA)
        jointsBuffer.setJoint(Joint.ELBOW_A, armA.joint)
        jointsBuffer.setJoint(Joint.HAND_A, armA.end)

        jointsBuffer.setJoint(Joint.SHOULDER_P, shoulderP)
        jointsBuffer.setJoint(Joint.ELBOW_P, armP.joint)
        jointsBuffer.setJoint(Joint.HAND_P, armP.end)

        return jointsBuffer
    }
}
