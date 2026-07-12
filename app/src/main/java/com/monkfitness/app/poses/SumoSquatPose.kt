package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class SumoSquatPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        // Controlled bodyweight sumo squat tempo: 1.5s eccentric descent, 0.5s pause, 1.0s concentric ascent
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
    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    // Pre-allocated scratch buffers to achieve 100% zero-allocation on the rendering loop
    private val axisZ = Vector3(0f, 0f, 1f)
    private val axisY = Vector3(0f, 1f, 0f)
    private val axisX = Vector3(1f, 0f, 0f)
    private val zeroVector = Vector3(0f, 0f, 0f)
    private val identityRotation = JointRotation()
    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()
    private val tempV4 = Vector3()
    private val targetAnkleF = Vector3()
    private val targetAnkleB = Vector3()
    private val leftToeDir = Vector3()
    private val rightToeDir = Vector3()
    private val handTargetA = Vector3()
    private val handTargetP = Vector3()
    private val legFPole = Vector3(1f, 0f, -2.0f)
    private val legBPole = Vector3(1f, 0f, 2.0f)
    private val armAPole = Vector3(0f, 0f, -1f)
    private val armPPole = Vector3(0f, 0f, 1f)

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        pelvis = SkeletonNode(Joint.PELVIS); chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
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

        val standH = def.shinLength + def.thighLength + 25f
        val squatH = 60f

        val pelvisY = lerp(standH, squatH, context.progress)
        val pelvisX = lerp(0f, -10f, context.progress) // Less shifting needed for wide base
        val leanAngle = lerp(0f, 0.15f, context.progress) // Upright torso

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        targetAnkleF.set(0f, 25f, -def.hipWidth * 2.8f)
        targetAnkleB.set(0f, 25f, def.hipWidth * 2.8f)

        // Wide track pole vectors
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, legFBuffer)
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, legBBuffer)

        rotAround(tempV1.set(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), axisZ, leanAngle, kneeF!!.localPosition)
        rotAround(tempV1.set(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), axisZ, leanAngle, ankleF!!.localPosition)
        rotAround(tempV1.set(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z), axisZ, leanAngle, kneeB!!.localPosition)
        rotAround(tempV1.set(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z), axisZ, leanAngle, ankleB!!.localPosition)

        ankleF!!.localRotation.set(axisZ, leanAngle)
        ankleB!!.localRotation.set(axisZ, leanAngle)

        // 45-degree outward toe flare (rotated around global Y-axis)
        rotAround(axisX, axisY, -0.785f, leftToeDir)
        rotAround(axisX, axisY, 0.785f, rightToeDir)
        heelF!!.localPosition.set(leftToeDir.x * -def.foot.footLength * 0.29f, 0f, leftToeDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition.set(leftToeDir.x * def.foot.footLength * 0.71f, 0f, leftToeDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition.set(rightToeDir.x * -def.foot.footLength * 0.29f, 0f, rightToeDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition.set(rightToeDir.x * def.foot.footLength * 0.71f, 0f, rightToeDir.z * def.foot.footLength * 0.71f)

        // Arms drop vertically toward crotch
        val handTargetX = pelvisX + 10f
        val handTargetY = lerp(pelvisY, pelvisY - 20f, context.progress)

        handTargetA.set(handTargetX, handTargetY, -10f)
        handTargetP.set(handTargetX, handTargetY, 10f)

        val armAIK = solveIK(shoulderA!!.worldPosition, handTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, handTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, armPBuffer)

        rotAround(tempV1.set(armAIK.joint.x - shoulderA!!.worldPosition.x, armAIK.joint.y - shoulderA!!.worldPosition.y, armAIK.joint.z - shoulderA!!.worldPosition.z), axisZ, leanAngle, elbowA!!.localPosition)
        rotAround(tempV1.set(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z), axisZ, leanAngle, handA!!.localPosition)
        rotAround(tempV1.set(armPIK.joint.x - shoulderP!!.worldPosition.x, armPIK.joint.y - shoulderP!!.worldPosition.y, armPIK.joint.z - shoulderP!!.worldPosition.z), axisZ, leanAngle, elbowP!!.localPosition)
        rotAround(tempV1.set(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z), axisZ, leanAngle, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, leanAngle)
        handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
