package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class DeadBugPose : PoseBuilder {
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

    private fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    override fun build(context: PoseContext): SkeletonPose {
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
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

        // 2. Continuous Contrallateral Sine Wave Modulation
        val cycle = context.progress * 2f * PI.toFloat()
        val rawSin = sin(cycle)
        val actA = if (rawSin > 0f) smootherStep(0f, 1f, rawSin) else 0f
        val actP = if (rawSin < 0f) smootherStep(0f, 1f, -rawSin) else 0f

        // 3. ARM TARGETS
        val totalArmLen = def.upperArmLength + def.forearmLength
        // Neutral arm: extended straight up (along positive Y-axis)
        val neutralHandA = Vector3(shoulderA.x, shoulderA.y + totalArmLen, shoulderA.z)
        val neutralHandP = Vector3(shoulderP.x, shoulderP.y + totalArmLen, shoulderP.z)

        // Extended arm: lowered backward near the floor (along negative X-axis)
        val extendedHandA = Vector3(shoulderA.x - totalArmLen * 0.94f, 15f, shoulderA.z)
        val extendedHandP = Vector3(shoulderP.x - totalArmLen * 0.94f, 15f, shoulderP.z)

        // Interpolate target positions
        val targetHandA = lerp(neutralHandA, extendedHandA, actA)
        val targetHandP = lerp(neutralHandP, extendedHandP, actP)

        // Solve Arm IK
        val armA = solveIK(shoulderA, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 0f), def.armIKConstraint, armABuffer)
        val armP = solveIK(shoulderP, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 0f), def.armIKConstraint, armPBuffer)

        // 4. LEG TARGETS
        val totalLegLen = def.thighLength + def.shinLength
        // Neutral leg: tabletop position (thigh vertical, knee bent, shin horizontal pointing positive X)
        val neutralAnkleF = Vector3(hipF.x + def.shinLength * 0.8f, hipF.y + def.thighLength * 0.9f, hipF.z)
        val neutralAnkleB = Vector3(hipB.x + def.shinLength * 0.8f, hipB.y + def.thighLength * 0.9f, hipB.z)

        // Extended leg: extended forward near the floor (along positive X-axis)
        val extendedAnkleF = Vector3(hipF.x + totalLegLen * 0.94f, 15f, hipF.z)
        val extendedAnkleB = Vector3(hipB.x + totalLegLen * 0.94f, 15f, hipB.z)

        // Interpolate target positions (contrallateral: actP controls Left Leg F, actA controls Right Leg B)
        val targetAnkleF = lerp(neutralAnkleF, extendedAnkleF, actP)
        val targetAnkleB = lerp(neutralAnkleB, extendedAnkleB, actA)

        // Solve Leg IK
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
        jointsBuffer.getJoint(Joint.TOE_F).set(legF.end.x + def.footLength, legF.end.y, legF.end.z)

        jointsBuffer.setJoint(Joint.HIP_B, hipB)
        jointsBuffer.setJoint(Joint.KNEE_B, legB.joint)
        jointsBuffer.setJoint(Joint.ANKLE_B, legB.end)
        jointsBuffer.getJoint(Joint.TOE_B).set(legB.end.x + def.footLength, legB.end.y, legB.end.z)

        jointsBuffer.setJoint(Joint.SHOULDER_A, shoulderA)
        jointsBuffer.setJoint(Joint.ELBOW_A, armA.joint)
        jointsBuffer.setJoint(Joint.HAND_A, armA.end)

        jointsBuffer.setJoint(Joint.SHOULDER_P, shoulderP)
        jointsBuffer.setJoint(Joint.ELBOW_P, armP.joint)
        jointsBuffer.setJoint(Joint.HAND_P, armP.end)

        return jointsBuffer
    }
}
