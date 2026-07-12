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

        // kneeX is defined relative to the pelvis at 60f (constant), aligning torso and arm coordinates perfectly with standard pushups
        val kneeX = 60f + (def.thighLength * cos(theta))
        val shinPitch = (Math.PI / 4.0).toFloat() // Shins point 45 degrees up

        // 1. Root Anchoring
        val ankleX = kneeX + def.shinLength * cos(shinPitch)
        val ankleY = kneeHeight + def.shinLength * sin(shinPitch)
        ankleF!!.localPosition.set(ankleX, ankleY, -def.hipWidth)
        ankleF!!.localRotation.set(axisZ, shinPitch)

        val footDir = rotAround(tempV1.set(1f, -1f, 0f).normalize(), axisZ, -shinPitch, tempV2)
        heelF!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)

        // 2. Main Plank (Side F)
        kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
        kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

        hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        // 3. Perfect Symmetry (Side B)
        // Because Side B builds downwards from Pelvis, Thigh B local rotation is 0 (to inherit -theta).
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        hipB!!.localRotation.set(axisZ, 0f)

        // Shin B must counter-rotate the -theta to match the 45 degree upward pitch
        kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(axisZ, shinPitch + theta)
        ankleB!!.localPosition.set(def.shinLength, 0f, 0f)

        val headDir = tempV1.set(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition.set(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val maxDrivingHeight = (60f - kneeHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / rigidBodyLen).coerceIn(-1f, 1f))

        // Corrected hand position to sit exactly beneath shoulders instead of floating past the head
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta)

        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * 1.5f)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleABuffer.set(1f, 0.5f, -1f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, polePBuffer.set(1f, 0.5f, 1f), def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), axisZ, theta, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), axisZ, theta, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, theta, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, theta)
        val handDirA = tempV1.set(-1f, 0f, -0.2f).normalize()
        palmA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition.set(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(axisZ, theta)
        val handDirP = tempV1.set(-1f, 0f, 0.2f).normalize()
        palmP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition.set(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
