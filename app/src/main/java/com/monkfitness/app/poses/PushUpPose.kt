package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class PushUpPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP
    )

    // Persistent Scene Graph hierarchy
    private var roots: List<SkeletonNode>? = null

    // Cached node references
    private var ankleF: SkeletonNode? = null
    private var kneeF: SkeletonNode? = null
    private var hipF: SkeletonNode? = null
    private var pelvis: SkeletonNode? = null
    private var chest: SkeletonNode? = null
    private var neck: SkeletonNode? = null
    private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null
    private var elbowA: SkeletonNode? = null
    private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null
    private var elbowP: SkeletonNode? = null
    private var handP: SkeletonNode? = null
    private var hipB: SkeletonNode? = null
    private var kneeB: SkeletonNode? = null
    private var ankleB: SkeletonNode? = null

    // Reuse target pose to avoid allocations
    private val jointsBuffer = SkeletonPose()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // Side F (Rooted at Ankle)
        ankleF = SkeletonNode(Joint.ANKLE_F)
        kneeF = ankleF!!.addChild(SkeletonNode(Joint.KNEE_F))
        hipF = kneeF!!.addChild(SkeletonNode(Joint.HIP_F))

        pelvis = hipF!!.addChild(SkeletonNode(Joint.PELVIS))
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))

        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))

        // Side B (Relative to Pelvis)
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))

        roots = listOf(ankleF!!)
    }

    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val shoulderAW = Vector3()
    private val shoulderPW = Vector3()
    private val targetHandA = Vector3()
    private val targetHandP = Vector3()
    private val zAxis = Vector3(0f, 0f, 1f)
    private val poleA = Vector3(1f, 0.5f, -1f)
    private val poleP = Vector3(1f, 0.5f, 1f)

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val def = context.definition
        ensureHierarchy(def)

        // 1. Driving values
        val height = lerp(60f, 20f, progress)
        val totalLegLen = def.shinLength + def.thighLength
        val ankleHeight = def.foot.ankleHeight
        val drivingHeight = (height - ankleHeight).coerceAtLeast(0f)
        val theta = asin((drivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val horizontalDist = totalLegLen * cos(theta)
        val ankleX = 60f + horizontalDist

        // 2. Local Transforms
        ankleF!!.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), -theta)

        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition = headDir * def.neckLength
        head!!.localPosition = headDir * 18f

        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        // 3. Preliminary FK pass
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val chestW = chest!!.worldPosition
        rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), zAxis, chest!!.worldRotation.angle, shoulderAW).add(chestW)
        rotAround(tempV1.set(0f, 0f, def.shoulderWidth), zAxis, chest!!.worldRotation.angle, shoulderPW).add(chestW)

        // 4. IK
        // POLISH 1: Anchor Hands. Calculate the absolute X position of the chest when standing at peak height.
        // This stops the hands from "ice skating" backward as the chest hinges toward the ground.
        val maxDrivingHeight = (60f - ankleHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val handAnchorX = (60f + totalLegLen * cos(maxTheta)) - (totalLegLen + def.torsoLength) * cos(maxTheta)

        targetHandA.set(handAnchorX, 0f, -def.shoulderWidth * 1.3f)
        targetHandP.set(handAnchorX, 0f, def.shoulderWidth * 1.3f)

        // POLISH 3: Elbow Flare. Adjusted the pole vectors from (1,0,-1) to (1,0.5,-1) to flare elbows up and back.
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, armPIK)

        // 5. Hierarchy Update (Transform IK to local space)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint).subtract(shoulderAW), zAxis, -chest!!.worldRotation.angle, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end).subtract(armA.joint), zAxis, -chest!!.worldRotation.angle, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint).subtract(shoulderPW), zAxis, -chest!!.worldRotation.angle, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end).subtract(armP.joint), zAxis, -chest!!.worldRotation.angle, handP!!.localPosition)

        // 6. Final Pass
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)

        return jointsBuffer
    }
}
