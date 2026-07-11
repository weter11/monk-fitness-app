package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class HalfKneelingStretchPose : PoseBuilder {
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

        // 1. Rigid Pythagorean Pelvis Solver
        val kneeBX = -20f
        val kneeBY = 15f

        // Pelvis lunges forward to drive the stretch
        val pelvisX = lerp(0f, 25f, context.progress)
        val dx = pelvisX - kneeBX

        // Pelvis naturally drops in Y space as the X vector lengthens
        val dy = sqrt(def.thighLength * def.thighLength - dx * dx)
        val pelvisY = kneeBY + dy
        val leanAngle = lerp(0f, -0.1f, context.progress)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -leanAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Fixed Kinematics for Back Leg
        val thighVecB = Vector3(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        val shinVecB = Vector3(-def.shinLength, 0f, 0f) // Shin lies entirely flat on the ground

        rotAround(thighVecB, Vector3(0f, 0f, 1f), leanAngle, kneeB!!.localPosition)
        rotAround(shinVecB, Vector3(0f, 0f, 1f), leanAngle, ankleB!!.localPosition)

        // 3. Inverse Kinematics for Front Leg
        val targetAnkleF = Vector3(65f, 25f, -def.hipWidth)
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.5f), def.legIKConstraint, legFBuffer)

        rotAround(Vector3(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, kneeF!!.localPosition)
        rotAround(Vector3(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, ankleF!!.localPosition)

        // 4. Arms Rest on Front Knee
        val handTargetX = legFIK.joint.x - 10f
        val handTargetY = legFIK.joint.y + 15f

        val armAIK = solveIK(shoulderA!!.worldPosition, Vector3(handTargetX, handTargetY, -def.shoulderWidth * 0.8f), def.upperArmLength, def.forearmLength, Vector3(1f, 1f, -2f), def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, Vector3(handTargetX, handTargetY, def.shoulderWidth * 0.8f), def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 2f), def.armIKConstraint, armPBuffer)

        rotAround(Vector3(armAIK.joint.x - shoulderA!!.worldPosition.x, armAIK.joint.y - shoulderA!!.worldPosition.y, armAIK.joint.z - shoulderA!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, elbowA!!.localPosition)
        rotAround(Vector3(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, handA!!.localPosition)
        rotAround(Vector3(armPIK.joint.x - shoulderP!!.worldPosition.x, armPIK.joint.y - shoulderP!!.worldPosition.y, armPIK.joint.z - shoulderP!!.worldPosition.z), Vector3(0f, 0f, 1f), leanAngle, elbowP!!.localPosition)
        rotAround(Vector3(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z), Vector3(0f, 0f, 1f), leanAngle, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle); handP!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        // 5. Extremity/Foot Orientation
        val worldFootF = Vector3(0f, -1f, 0f)
        val localFootF = rotAround(worldFootF, Vector3(0f, 0f, 1f), leanAngle, Vector3())
        heelF!!.localPosition = Vector3(localFootF.x * -def.foot.footLength * 0.29f, localFootF.y * -def.foot.footLength * 0.29f, localFootF.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(localFootF.x * def.foot.footLength * 0.71f, localFootF.y * def.foot.footLength * 0.71f, localFootF.z * def.foot.footLength * 0.71f)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)

        val worldFootB = Vector3(-1f, 0f, 0f) // Back foot rests perfectly flat backward on the ground
        val localFootB = rotAround(worldFootB, Vector3(0f, 0f, 1f), leanAngle, Vector3())
        heelB!!.localPosition = Vector3(localFootB.x * -def.foot.footLength * 0.29f, localFootB.y * -def.foot.footLength * 0.29f, localFootB.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(localFootB.x * def.foot.footLength * 0.71f, localFootB.y * def.foot.footLength * 0.71f, localFootB.z * def.foot.footLength * 0.71f)
        ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), leanAngle)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
