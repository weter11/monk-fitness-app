package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class KneePushUpPose : BasePushUpPose() {
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

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)
        val shoulderPW = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)

        val maxDrivingHeight = (60f - kneeHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / rigidBodyLen).coerceIn(-1f, 1f))

        // Corrected hand position to sit exactly beneath shoulders instead of floating past the head
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta) + 12f

        val targetHandA = Vector3(handAnchorX, 0f, -def.shoulderWidth * 1.5f)
        val targetHandP = Vector3(handAnchorX, 0f, def.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, -1f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, 1f), def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(Vector3(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), theta, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), Vector3(0f, 0f, 1f), theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(Vector3(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), theta, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), Vector3(0f, 0f, 1f), theta, handP!!.localPosition)

        handA!!.localRotation.set(Vector3(0f, 0f, 1f), theta)
        val handDirA = Vector3(-1f, 0f, -0.2f).normalize()
        palmA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition = Vector3(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(Vector3(0f, 0f, 1f), theta)
        val handDirP = Vector3(-1f, 0f, 0.2f).normalize()
        palmP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition = Vector3(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}