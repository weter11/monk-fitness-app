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
    private var palmA: SkeletonNode? = null
    private var knucklesA: SkeletonNode? = null
    private var fingertipsA: SkeletonNode? = null
    
    private var shoulderP: SkeletonNode? = null
    private var elbowP: SkeletonNode? = null
    private var handP: SkeletonNode? = null
    private var palmP: SkeletonNode? = null
    private var knucklesP: SkeletonNode? = null
    private var fingertipsP: SkeletonNode? = null
    
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
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        // Side B (Relative to Pelvis)
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))

        roots = listOf(ankleF!!)
    }

    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val def = context.definition
        ensureHierarchy(def)

        // 1. Driving values
        val height = lerp(60f, 25f, progress)
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

        // Fix: Use isolated Vector3() instances to prevent memory overwrite of left shoulder
        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)

        // 4. IK (Polished Biomechanics)
        val maxDrivingHeight = (60f - ankleHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta)

        // Fix: Instantiate target vectors cleanly so IK paths do not bleed into each other
        val targetHandA = Vector3(handAnchorX, 0f, -def.shoulderWidth * 1.5f)
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, -1f), def.armIKConstraint, armAIK)

        val targetHandP = Vector3(handAnchorX, 0f, def.shoulderWidth * 1.5f)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, 1f), def.armIKConstraint, armPIK)

        // 5. Hierarchy Update (Transform IK to local space)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint).subtract(shoulderAW), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end).subtract(armA.joint), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint).subtract(shoulderPW), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end).subtract(armP.joint), Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, handP!!.localPosition)

        // 6. Flat Hands Enforcement (No Hacks)
        // We counter-rotate the hands against the chest pitch so they lay perfectly flat on the ground.
        val handDirA = Vector3(-1f, 0f, -0.2f).normalize()
        val localDirA = rotAround(handDirA, Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, Vector3())
        palmA!!.localPosition = Vector3(localDirA.x * 6f, localDirA.y * 6f, localDirA.z * 6f)
        knucklesA!!.localPosition = Vector3(localDirA.x * 6f, localDirA.y * 6f, localDirA.z * 6f)
        fingertipsA!!.localPosition = Vector3(localDirA.x * 10f, localDirA.y * 10f, localDirA.z * 10f)

        val handDirP = Vector3(-1f, 0f, 0.2f).normalize()
        val localDirP = rotAround(handDirP, Vector3(0f, 0f, 1f), -chest!!.worldRotation.angle, Vector3())
        palmP!!.localPosition = Vector3(localDirP.x * 6f, localDirP.y * 6f, localDirP.z * 6f)
        knucklesP!!.localPosition = Vector3(localDirP.x * 6f, localDirP.y * 6f, localDirP.z * 6f)
        fingertipsP!!.localPosition = Vector3(localDirP.x * 10f, localDirP.y * 10f, localDirP.z * 10f)

        // 7. Final Pass
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        
        // Ensure WRIST matches HAND perfectly in the final buffer
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))

        return jointsBuffer
    }
}
