package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class HamstringStretchPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.25f),
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
    private val armAPoleLocal = Vector3(); private val armPPoleLocal = Vector3()

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

        // 1. Static Seated Root Anchor
        val pelvisX = -30f
        val pelvisY = 15f // Rest perfectly flat on the ground

        // Torso dynamically folds forward towards the leg
        val torsoPitch = lerp(0.1f, 0.9f, context.progress)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        val headDir = Vector3(0.1f, 1f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Asymmetric Leg Geometry
        // Leg F (Front Leg): Stretched perfectly straight forward
        val targetAnkleF = Vector3(pelvisX + def.thighLength + def.shinLength - 5f, 15f, -def.hipWidth)
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, legFBuffer)

        // Leg B (Tucked Leg): Ankle pulled close to groin, knee falls outwards (Side Z)
        val targetAnkleB = Vector3(pelvisX + 35f, 15f, def.hipWidth * 0.5f)
        // Pole vector heavily points to +Z to force the knee outwards in a seated butterfly fold
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 0f, 2f), def.legIKConstraint, legBBuffer)

        rotAround(Vector3(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), Vector3(0f, 0f, 1f), torsoPitch, kneeF!!.localPosition)
        rotAround(Vector3(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), Vector3(0f, 0f, 1f), torsoPitch, ankleF!!.localPosition)
        rotAround(Vector3(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z), Vector3(0f, 0f, 1f), torsoPitch, kneeB!!.localPosition)
        rotAround(Vector3(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z), Vector3(0f, 0f, 1f), torsoPitch, ankleB!!.localPosition)

        // Front foot points to sky, back foot lays flat sideways
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), torsoPitch - 1.57f)
        ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), torsoPitch)

        heelF!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)

        // 3. Dynamic Forward Reach
        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), torsoPitch, Vector3()).add(chestW)
        val shoulderPW = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), torsoPitch, Vector3()).add(chestW)

        // Arms reach from the chest down toward the extended front ankle
        val startHandX = chestW.x + 30f
        val startHandY = chestW.y + 20f
        val reachX = targetAnkleF.x - 10f
        val reachY = targetAnkleF.y + 20f

        val handTargetX = lerp(startHandX, reachX, context.progress)
        val handTargetY = lerp(startHandY, reachY, context.progress)

        val targetHandA = Vector3(handTargetX, handTargetY, -def.shoulderWidth * 0.8f)
        val targetHandP = Vector3(handTargetX, handTargetY, def.shoulderWidth * 0.8f)

        // Pole vectors flare elbows slightly outward and upward
        armAPoleLocal.set(0f, 1f, -1f)
        SkeletonMath.toLocalDirection(armAPoleLocal, chest!!.worldRotation, armAPoleLocal)
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleLocal, chest!!.worldRotation, def.armIKConstraint, armABuffer)
        armPPoleLocal.set(0f, 1f, 1f)
        SkeletonMath.toLocalDirection(armPPoleLocal, chest!!.worldRotation, armPPoleLocal)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleLocal, chest!!.worldRotation, def.armIKConstraint, armPBuffer)

        rotAround(Vector3(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), -torsoPitch, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), Vector3(0f, 0f, 1f), -torsoPitch, handA!!.localPosition)
        rotAround(Vector3(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), -torsoPitch, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), Vector3(0f, 0f, 1f), -torsoPitch, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch); handP!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
