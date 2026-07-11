package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class YTRaisesPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private val zeroVector = Vector3(0f, 0f, 0f)
    private val identityRotation = JointRotation()
    private val axisZ = Vector3(0f, 0f, 1f)
    private val tempV3 = Vector3()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // Prone position root is Pelvis
        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F)); kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F)); ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F)); heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B)); heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Pull factors based on a smooth sine wave
        val progress = context.progress
        val angleFactor = sin(progress.toDouble() * Math.PI).toFloat()

        val raiseFactorY = sin(progress.toDouble() * 2.0 * Math.PI).toFloat().coerceAtLeast(0f)
        val raiseFactorT = (-sin(progress.toDouble() * 2.0 * Math.PI).toFloat()).coerceAtLeast(0f)
        val raiseY = lerp(0f, 30f, raiseFactorY + raiseFactorT)

        // Flat prone position resting on floor (y = 10f)
        val pelvisY = 10f
        val pelvisX = 0f
        val leanAngle = 0f

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, leanAngle)

        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)
        neck!!.localPosition = Vector3(-def.neckLength, 0f, 0f)
        head!!.localPosition = Vector3(-18f, 0f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Propagate spine FK
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Leg IK (completely flat on the floor pointing backwards)
        val targetAnkleF = Vector3(def.thighLength + def.shinLength, 10f, -def.hipWidth)
        val targetAnkleB = Vector3(def.thighLength + def.shinLength, 10f, def.hipWidth)

        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, legFBuffer)
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, legBBuffer)

        rotAround(tempV3.set(legFIK.joint).subtract(hipF!!.worldPosition), axisZ, leanAngle, kneeF!!.localPosition)
        rotAround(tempV3.set(legFIK.end).subtract(legFIK.joint), axisZ, leanAngle, ankleF!!.localPosition)
        rotAround(tempV3.set(legBIK.joint).subtract(hipB!!.worldPosition), axisZ, leanAngle, kneeB!!.localPosition)
        rotAround(tempV3.set(legBIK.end).subtract(legBIK.joint), axisZ, leanAngle, ankleB!!.localPosition)

        ankleF!!.localRotation.set(axisZ, leanAngle)
        ankleB!!.localRotation.set(axisZ, leanAngle)
        heelF!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f)
        toeF!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f)
        toeB!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)

        // 3. Arm IK (Y-T lifts on the floor)
        val armAngle = lerp(30f * PI.toFloat() / 180f, 90f * PI.toFloat() / 180f, angleFactor)
        val dirX = -cos(armAngle)
        val dirZ = sin(armAngle)

        val shoulderX = chest!!.worldPosition.x
        val targetHandA = Vector3(shoulderX + dirX * 135f, 10f + raiseY, -def.shoulderWidth - dirZ * 135f)
        val targetHandP = Vector3(shoulderX + dirX * 135f, 10f + raiseY, def.shoulderWidth + dirZ * 135f)

        val armAIK = solveIK(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, armPBuffer)

        rotAround(tempV3.set(armAIK.joint).subtract(shoulderA!!.worldPosition), axisZ, leanAngle, elbowA!!.localPosition)
        rotAround(tempV3.set(armAIK.end).subtract(armAIK.joint), axisZ, leanAngle, handA!!.localPosition)
        rotAround(tempV3.set(armPIK.joint).subtract(shoulderP!!.worldPosition), axisZ, leanAngle, elbowP!!.localPosition)
        rotAround(tempV3.set(armPIK.end).subtract(armPIK.joint), axisZ, leanAngle, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, leanAngle)
        handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
