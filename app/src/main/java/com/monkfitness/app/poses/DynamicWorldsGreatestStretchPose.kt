package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class DynamicWorldsGreatestStretchPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.1f),
        durationSeconds = 3.5f, loopMode = LoopMode.LOOP,
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

        // 1. Static Deep Lunge Anchoring
        val pelvisX = -10f
        val pelvisY = 55f
        val leanAngle = 0.5f // Torso leans heavily forward over the front knee

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -leanAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. 3D Thoracic Twist Roll Mathematics
        // Progress smoothly drives the twist from slightly tucked (-0.2 rads) to fully open to the sky (1.6 rads)
        val twist = lerp(-0.2f, 1.6f, context.progress)

        // Roll shoulders around the local X-axis (Spine Vector) FIRST
        val sARoll = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(1f, 0f, 0f), twist, Vector3())
        val sPRoll = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(1f, 0f, 0f), twist, Vector3())

        // Then apply the global 2D torso pitch
        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(sARoll, Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)
        val shoulderPW = rotAround(sPRoll, Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)

        // 3. Legs: Front Foot Flat, Back Foot Extended on Toes
        val targetAnkleF = Vector3(65f, 25f, -def.hipWidth)
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, legFBuffer)

        // Back ankle floats at 40f so the toe points strictly into the ground (plantar flexion)
        val targetAnkleB = Vector3(-110f, 40f, def.hipWidth)
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 1f, 0.2f), def.legIKConstraint, legBBuffer)

        rotAround(Vector3(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, kneeF!!.localPosition)
        rotAround(Vector3(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, ankleF!!.localPosition)
        rotAround(Vector3(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, kneeB!!.localPosition)
        rotAround(Vector3(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, ankleB!!.localPosition)

        val footPitchB = 0.6f // Point back toe down
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)
        ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle - footPitchB)

        heelF!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)

        // 4. Arms Kinematics
        // Support Arm (Side P) is planted firmly on the ground next to the front foot
        val handTargetP = Vector3(55f, 0f, def.shoulderWidth)
        val armPIK = solveIK(shoulderPW, handTargetP, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 2f), def.armIKConstraint, armPBuffer)

        // Dynamic Arm (Side A) sweeps from floor/instep up to the ceiling
        val handStartA = Vector3(50f, 15f, -def.shoulderWidth * 1.5f)
        val handEndA = Vector3(chestW.x + 20f, chestW.y + 110f, -def.shoulderWidth * 2f)
        val handTargetA = Vector3(lerp(handStartA.x, handEndA.x, context.progress), lerp(handStartA.y, handEndA.y, context.progress), lerp(handStartA.z, handEndA.z, context.progress))
        val armAIK = solveIK(shoulderAW, handTargetA, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, -2f), def.armIKConstraint, armABuffer)

        // Assigning proper local hierarchical coordinates ensuring the roll is accounted for
        shoulderA!!.localPosition.set(sARoll.x, sARoll.y, sARoll.z)
        shoulderP!!.localPosition.set(sPRoll.x, sPRoll.y, sPRoll.z)

        rotAround(Vector3(armAIK.joint.x - shoulderAW.x, armAIK.joint.y - shoulderAW.y, armAIK.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, elbowA!!.localPosition)
        rotAround(Vector3(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, handA!!.localPosition)
        rotAround(Vector3(armPIK.joint.x - shoulderPW.x, armPIK.joint.y - shoulderPW.y, armPIK.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, elbowP!!.localPosition)
        rotAround(Vector3(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle); handP!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
