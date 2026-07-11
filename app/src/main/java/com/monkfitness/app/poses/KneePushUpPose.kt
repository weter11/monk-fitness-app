package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class KneePushUpPose : BasePushUpPose() {
    private val poleFront = Vector3(1f, 0.5f, -1f)
    private val poleBack = Vector3(1f, 0.5f, 1f)
    private val handDirFront = Vector3(-1f, 0f, -0.2f).normalize()
    private val handDirBack = Vector3(-1f, 0f, 0.2f).normalize()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val height = lerp(60f, 20f, context.progress)
        val kneeHeight = 15f
        val rigidBodyLen = def.thighLength + def.torsoLength
        val drivingHeight = (height - kneeHeight).coerceAtLeast(0f)
        val theta = asin((drivingHeight / rigidBodyLen).coerceIn(-1f, 1f))

        val kneeX = 60f + (rigidBodyLen * cos(theta))
        val shinPitch = (Math.PI / 4.0).toFloat() // Shins point 45 degrees up

        // 1. Root Anchoring
        val ankleX = kneeX + def.shinLength * cos(shinPitch)
        val ankleY = kneeHeight + def.shinLength * sin(shinPitch)
        ankleF!!.localPosition = Vector3(ankleX, ankleY, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), shinPitch)

        val footDir = rotAround(Vector3(1f, -1f, 0f).normalize(), Vector3(0f, 0f, 1f), -shinPitch, Vector3())
        heelF!!.localPosition = Vector3(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition = Vector3(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)

        // 2. Main Plank (Side F)
        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        kneeF!!.localRotation.set(Vector3(0f, 0f, 1f), -theta - shinPitch)

        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)
        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        // 3. Perfect Symmetry (Side B)
        // Because Side B builds downwards from Pelvis, Thigh B local rotation is 0 (to inherit -theta).
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        hipB!!.localRotation.set(Vector3(0f, 0f, 1f), 0f)

        // Shin B must counter-rotate the -theta to match the 45 degree upward pitch
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(Vector3(0f, 0f, 1f), shinPitch + theta)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val maxDrivingHeight = (60f - kneeHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / rigidBodyLen).coerceIn(-1f, 1f))

        // Corrected hand position to sit exactly beneath shoulders instead of floating past the head
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta) + 12f

        GroundArmSupport.solve(
            definition = def,
            chest = chest!!,
            shoulderA = frontArm!!.shoulder,
            elbowA = frontArm!!.elbow,
            handA = frontArm!!.hand,
            shoulderP = backArm!!.shoulder,
            elbowP = backArm!!.elbow,
            handP = backArm!!.hand,
            palmA = frontArm!!.palm,
            knucklesA = frontArm!!.knuckles,
            fingertipsA = frontArm!!.fingertips,
            palmP = backArm!!.palm,
            knucklesP = backArm!!.knuckles,
            fingertipsP = backArm!!.fingertips,
            targetX = handAnchorX,
            settings = GroundArmSupport.Settings(
                handWidthMultiplier = 1.5f,
                poleFront = poleFront,
                poleBack = poleBack,
                handRotation = theta,
                handDirectionFront = handDirFront,
                handDirectionBack = handDirBack
            ),
            ikFront = armAIK,
            ikBack = armPIK
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
