package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class StandardPushUpPose : BasePushUpPose() {
    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Progress is natively eased by the MotionCurve from PoseMetadata
        val height = lerp(60f, 25f, context.progress)
        val shinL = def.shinLength
        val thighL = def.thighLength
        val totalLegLen = shinL + thighL
        val legTargetLen = totalLegLen * 0.97f // Slightly flexed to satisfy IK constraint and avoid locked-out leg issues

        val ankleHeight = 25f
        val drivingHeight = (height - ankleHeight).coerceAtLeast(0f)
        val theta = asin((drivingHeight / legTargetLen).coerceIn(-1f, 1f))
        val ankleX = 60f + (legTargetLen * cos(theta))

        ankleF!!.localPosition.set(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(axisZ, -theta)

        val worldFootDir = tempV1.set(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, axisZ, theta, tempV2)
        heelF!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        // Precompute local knee flexion coordinates to satisfy the leg IK constraint of 98% maximum extension
        val kX = (thighL * thighL - shinL * shinL - legTargetLen * legTargetLen) / (2f * legTargetLen)
        val kY = -sqrt((shinL * shinL - kX * kX).coerceAtLeast(0f))

        kneeF!!.localPosition.set(kX, kY, 0f)
        hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)
        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        val headDir = tempV1.set(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition.set(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        kneeB!!.localPosition.set(legTargetLen + kX, -kY, 0f)
        ankleB!!.localPosition.set(-kX, kY, 0f)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val maxDrivingHeight = (60f - ankleHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / legTargetLen).coerceIn(-1f, 1f))
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
