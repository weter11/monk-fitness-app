package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class CatCowPose : PoseBuilder {
    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()

    private val zeroVector = Vector3(0f, 0f, 0f)
    private val identityRotation = JointRotation()
    private val axisZ = Vector3(0f, 0f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(
            defaultYaw = 1.19f,
            defaultPitch = 0.22f,
            defaultZoom = 1.3f
        ),
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SINE
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

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

        // 1. Cyclic factor using sine wave for perfect velocity/acceleration continuity at boundaries
        val progress = context.progress
        val factor = sin(progress.toDouble() * 2.0 * Math.PI - Math.PI / 2.0).toFloat() * 0.5f + 0.5f

        // 2. Tabletop Core Anchoring
        val basePelvisX = -30f
        // pelvisY rises during Cat (factor = 0) and sinks during Cow (factor = 1)
        val pelvisY = lerp(130f, 110f, factor)

        // Tilt torso slightly during Cow/Cat for extreme realism
        val torsoPitch = -PI.toFloat() / 2f + lerp(-0.08f, 0.08f, factor)

        pelvis!!.localPosition = Vector3(basePelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Head looks down during Cat, looks up/forward during Cow
        val headDir = Vector3(lerp(-0.4f, 0.4f, factor), lerp(0.8f, 1.2f, factor), 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 3. Base & Extensions Targets (completely static to ensure 0% sliding)
        val baseHandX = basePelvisX + def.torsoLength
        val baseHandY = 0f
        val baseAnkleX = basePelvisX - def.shinLength
        val baseAnkleY = 15f

        val targetHandA = Vector3(baseHandX, baseHandY, -def.shoulderWidth)
        val targetHandP = Vector3(baseHandX, baseHandY, def.shoulderWidth)

        val targetAnkleF = Vector3(baseAnkleX, baseAnkleY, -def.hipWidth)
        val targetAnkleB = Vector3(baseAnkleX, baseAnkleY, def.hipWidth)

        // 4. IK Solving
        val armA = solveIK(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(-1f, 0f, -0.5f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(-1f, 0f, 0.5f), def.armIKConstraint, armPIK)

        val legF = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, -1f, -0.5f), def.legIKConstraint, legFIK)
        val legB = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, -1f, 0.5f), def.legIKConstraint, legBIK)

        // 5. Transform global IK solutions back to local coordinates
        rotAround(Vector3(armA.joint.x - shoulderA!!.worldPosition.x, armA.joint.y - shoulderA!!.worldPosition.y, armA.joint.z - shoulderA!!.worldPosition.z), axisZ, -torsoPitch, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, -torsoPitch, handA!!.localPosition)
        rotAround(Vector3(armP.joint.x - shoulderP!!.worldPosition.x, armP.joint.y - shoulderP!!.worldPosition.y, armP.joint.z - shoulderP!!.worldPosition.z), axisZ, -torsoPitch, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, -torsoPitch, handP!!.localPosition)

        rotAround(Vector3(legF.joint.x - hipF!!.worldPosition.x, legF.joint.y - hipF!!.worldPosition.y, legF.joint.z - hipF!!.worldPosition.z), axisZ, -torsoPitch, kneeF!!.localPosition)
        rotAround(Vector3(legF.end.x - legF.joint.x, legF.end.y - legF.joint.y, legF.end.z - legF.joint.z), axisZ, -torsoPitch, ankleF!!.localPosition)
        rotAround(Vector3(legB.joint.x - hipB!!.worldPosition.x, legB.joint.y - hipB!!.worldPosition.y, legB.joint.z - hipB!!.worldPosition.z), axisZ, -torsoPitch, kneeB!!.localPosition)
        rotAround(Vector3(legB.end.x - legB.joint.x, legB.end.y - legB.joint.y, legB.end.z - legB.joint.z), axisZ, -torsoPitch, ankleB!!.localPosition)

        // 6. Extremities orientations and coordinates
        handA!!.localRotation.set(axisZ, -torsoPitch)
        handP!!.localRotation.set(axisZ, -torsoPitch)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        ankleF!!.localRotation.set(axisZ, -torsoPitch)
        ankleB!!.localRotation.set(axisZ, -torsoPitch)
        heelF!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
