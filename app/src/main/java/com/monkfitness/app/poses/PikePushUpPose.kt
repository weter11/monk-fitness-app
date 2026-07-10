package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class PikePushUpPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.FAST_DOWN_SLOW_UP
    )

    private var roots: List<SkeletonNode>? = null
    private var ankleF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var hipF: SkeletonNode? = null; private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null
    private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        ankleF = SkeletonNode(Joint.ANKLE_F)
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        kneeF = ankleF!!.addChild(SkeletonNode(Joint.KNEE_F)); hipF = kneeF!!.addChild(SkeletonNode(Joint.HIP_F))
        pelvis = hipF!!.addChild(SkeletonNode(Joint.PELVIS)); chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))
        roots = listOf(ankleF!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val totalLegLen = def.shinLength + def.thighLength

        // 1. Anchors for Pike Push-up
        val ankleX = 125f // Feet are stepped closer to the hands
        val ankleHeight = 25f // On tiptoes

        // Chest trajectory: Dips down and slightly forward
        val chestX = lerp(0f, 25f, context.progress)
        val chestY = lerp(135f, 35f, context.progress)

        // 2. Kinematic Triangle Solver (Find the Pelvis peak dynamically)
        val dx = chestX - ankleX
        val dy = chestY - ankleHeight
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

        val r1 = totalLegLen
        val r2 = def.torsoLength

        // Clamp distance to ensure physical geometry doesn't collapse (NaN prevention)
        val dClamped = d.coerceIn(abs(r1 - r2), r1 + r2 - 0.1f)

        val a = (r1 * r1 - r2 * r2 + dClamped * dClamped) / (2f * dClamped)
        val h = sqrt(max(0f, r1 * r1 - a * a))

        val dirX = dx / dClamped
        val dirY = dy / dClamped

        // Normal pointing UP to enforce the inverted 'V' peak
        val nx = dirY
        val ny = -dirX

        val px = ankleX + dirX * a + nx * h
        val py = ankleHeight + dirY * a + ny * h

        // 3. Derive global pitches from the triangle
        val legVecX = px - ankleX
        val legVecY = py - ankleHeight
        val legPitch = atan2(-legVecY, -legVecX) // Relative to -X axis

        val torsoVecX = chestX - px
        val torsoVecY = chestY - py
        val torsoGlobalPitch = atan2(-torsoVecY, -torsoVecX)

        // 4. Apply transformations to the Scene Graph
        ankleF!!.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), legPitch)

        // Tiptoes: Pointing straight down (perpendicular)
        val worldFootDir = Vector3(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, Vector3(0f, 0f, 1f), -legPitch, Vector3())
        heelF!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        // Torso inherits Ankle rotation, so we offset it by legPitch
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), torsoGlobalPitch - legPitch)

        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        // Head tucks sharply to look at the toes (Pike form)
        val headDir = Vector3(-1f, -0.6f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        // Flush hierarchy to get accurate Shoulder world coordinates
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 5. Hands and Arms IK
        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)
        val shoulderPW = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)

        // Hands are locked firmly on the ground slightly behind the top-position chest
        val handAnchorX = -10f
        val targetHandA = Vector3(handAnchorX, 0f, -def.shoulderWidth * 1.2f)
        val targetHandP = Vector3(handAnchorX, 0f, def.shoulderWidth * 1.2f)

        // Pole vector flares elbows slightly outward and upward
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, -1.5f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, 1.5f), def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(Vector3(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), -torsoGlobalPitch, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), Vector3(0f, 0f, 1f), -torsoGlobalPitch, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(Vector3(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), -torsoGlobalPitch, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), Vector3(0f, 0f, 1f), -torsoGlobalPitch, handP!!.localPosition)

        // 6. Flat Palms
        handA!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoGlobalPitch)
        val handDirA = Vector3(-1f, 0f, -0.1f).normalize()
        palmA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition = Vector3(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoGlobalPitch)
        val handDirP = Vector3(-1f, 0f, 0.1f).normalize()
        palmP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition = Vector3(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}