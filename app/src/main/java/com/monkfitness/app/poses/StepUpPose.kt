package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class StepUpPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                StepProp(
                    center = Vector3(15f, 6.0f, 0f),
                    width = 40f,
                    height = 12f,
                    depth = 40f
                )
            )
        )
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

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // Root is Pelvis for perfect step-up mechanics
        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F)); kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F)); ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F)); heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B)); heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    private fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Pelvis & Spine Positioning
        // Smooth branchless interpolation of progress to peak (0.5) and back
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f

        val pelvisY = lerp(208f, 222f, u)
        val pelvisX = lerp(-5f, 15f, u)

        // Torso leans forward slightly during the ascent and stays upright at the top
        val leanAngle = lerp(0.04f, 0.18f, sin(context.progress * PI.toFloat())) * (1f - u)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -leanAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Flush Spine FK to get precise Hip and Shoulder origins
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. LEG TARGETS
        // Working Foot (Front Leg) is completely stationary on top of the step at height = 12f + 10f = 22f
        val targetAnkleF = Vector3(20f, 22f, -def.hipWidth * 1.5f)

        // Non-working Foot (Back Leg) steps up and down with isolated vertical liftoff to prevent sliding detection
        val targetAnkleB = Vector3()
        val prog = context.progress
        if (prog <= 0.5f) {
            when {
                prog <= 0.1f -> {
                    targetAnkleB.set(-15f, 10f, def.hipWidth * 1.5f)
                }
                prog <= 0.15f -> {
                    val v = smootherStep(0.1f, 0.15f, prog)
                    targetAnkleB.set(-15f, lerp(10f, 15f, v), def.hipWidth * 1.5f)
                }
                prog <= 0.35f -> {
                    val v = smootherStep(0.15f, 0.35f, prog)
                    val tx = lerp(-15f, 10f, v)
                    val ty = lerp(15f, 22f, v)
                    val lift = sin(v * PI.toFloat()) * 12f
                    targetAnkleB.set(tx, ty + lift, def.hipWidth * 1.5f)
                }
                else -> {
                    targetAnkleB.set(10f, 22f, def.hipWidth * 1.5f)
                }
            }
        } else {
            when {
                prog <= 0.6f -> {
                    targetAnkleB.set(10f, 22f, def.hipWidth * 1.5f)
                }
                prog <= 0.85f -> {
                    val v = smootherStep(0.6f, 0.85f, prog)
                    val tx = lerp(10f, -15f, v)
                    val ty = lerp(22f, 15f, v)
                    val lift = sin(v * PI.toFloat()) * 12f
                    targetAnkleB.set(tx, ty + lift, def.hipWidth * 1.5f)
                }
                prog <= 0.9f -> {
                    val v = smootherStep(0.85f, 0.9f, prog)
                    targetAnkleB.set(-15f, lerp(15f, 10f, v), def.hipWidth * 1.5f)
                }
                else -> {
                    targetAnkleB.set(-15f, 10f, def.hipWidth * 1.5f)
                }
            }
        }

        // Solve Leg IK
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, legFBuffer)
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, legBBuffer)

        // Set Leg joint local coordinates
        rotAround(Vector3(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, kneeF!!.localPosition)
        rotAround(Vector3(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, ankleF!!.localPosition)
        rotAround(Vector3(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, kneeB!!.localPosition)
        rotAround(Vector3(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, ankleB!!.localPosition)

        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle); ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)
        heelF!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)

        // 3. ARM TARGETS (Natural balance swings)
        val handTargetX = lerp(-10f, 20f, u)
        val handTargetY = pelvisY + def.torsoLength - 50f

        val armAIK = solveIK(shoulderA!!.worldPosition, Vector3(handTargetX, handTargetY, -def.shoulderWidth * 1.2f), def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, Vector3(handTargetX, handTargetY, def.shoulderWidth * 1.2f), def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, armPBuffer)

        rotAround(Vector3(armAIK.joint.x - shoulderA!!.worldPosition.x, armAIK.joint.y - shoulderA!!.worldPosition.y, armAIK.joint.z - shoulderA!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, elbowA!!.localPosition)
        rotAround(Vector3(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, handA!!.localPosition)
        rotAround(Vector3(armPIK.joint.x - shoulderP!!.worldPosition.x, armPIK.joint.y - shoulderP!!.worldPosition.y, armPIK.joint.z - shoulderP!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, elbowP!!.localPosition)
        rotAround(Vector3(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle); handP!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
