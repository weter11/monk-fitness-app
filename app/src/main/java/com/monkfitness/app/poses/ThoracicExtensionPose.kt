package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class ThoracicExtensionPose : PoseBuilder {
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

        // 1. Tall Kneeling Root Geometry (Pythagorean Anchoring)
        val kneeFloorX = 0f
        val kneeFloorY = 15f

        // Thigh leans forward to counterbalance thoracic extension
        val thighPitch = lerp(0.0f, 0.2f, context.progress)

        val pelvisX = kneeFloorX + def.thighLength * sin(thighPitch)
        val pelvisY = kneeFloorY + def.thighLength * cos(thighPitch)

        // Thoracic spine leans heavily backwards (Negative pitch)
        val torsoPitch = lerp(0.0f, -0.45f, context.progress)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Head tilts up further into the extension
        val headTilt = lerp(0f, -0.3f, context.progress)
        val headDir = rotAround(Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f), headTilt, Vector3()).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Fixed Legs (Shins rest flat on the floor behind the body)
        // Because the pelvis moves dynamically, we calculate the thigh/shin manually so knees never slide.
        val thighVecF = Vector3(kneeFloorX - pelvis!!.worldPosition.x, kneeFloorY - pelvis!!.worldPosition.y, 0f)
        val shinVec = Vector3(-def.shinLength, 0f, 0f) // Shins flat backward on floor

        rotAround(thighVecF, Vector3(0f, 0f, 1f), -torsoPitch, kneeF!!.localPosition)
        rotAround(thighVecF, Vector3(0f, 0f, 1f), -torsoPitch, kneeB!!.localPosition)
        rotAround(shinVec, Vector3(0f, 0f, 1f), -torsoPitch, ankleF!!.localPosition)
        rotAround(shinVec, Vector3(0f, 0f, 1f), -torsoPitch, ankleB!!.localPosition)

        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)
        ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)
        // Toes pointed flat away from body
        heelF!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(-def.foot.footLength * 0.71f, 0f, 0f)

        // 3. Hands Behind Head (Thoracic Opening)
        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), torsoPitch, Vector3()).add(chestW)
        val shoulderPW = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), torsoPitch, Vector3()).add(chestW)

        val neckW = neck!!.worldPosition

        // Hands target the base of the skull/neck
        val targetHandA = Vector3(neckW.x - 5f, neckW.y, -def.shoulderWidth * 0.4f)
        val targetHandP = Vector3(neckW.x - 5f, neckW.y, def.shoulderWidth * 0.4f)

        // Pole vectors (0, -1, ±2) heavily flare elbows outward sideways to open the chest
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -2f), def.armIKConstraint, armABuffer)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 2f), def.armIKConstraint, armPBuffer)

        rotAround(Vector3(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), -torsoPitch, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), Vector3(0f, 0f, 1f), -torsoPitch, handA!!.localPosition)
        rotAround(Vector3(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), -torsoPitch, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), Vector3(0f, 0f, 1f), -torsoPitch, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)
        handP!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
