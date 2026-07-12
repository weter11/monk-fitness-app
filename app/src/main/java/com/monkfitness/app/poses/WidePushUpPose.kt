package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class WidePushUpPose : BasePushUpPose() {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Wide drops closer to the floor
        val height = lerp(60f, 18f, context.progress)
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
        // B-leg: hip is the parent, ankle is the child — this is a DIFFERENT triangle
        // traversal than the F-leg's (ankle-parent, hip-child), so it needs its own
        // derivation, not a relabeling of the F-leg's kX/kY.
        val bX = (thighL * thighL - shinL * shinL + legTargetLen * legTargetLen) / (2f * legTargetLen)
        val bY = -sqrt((thighL * thighL - bX * bX).coerceAtLeast(0f))

        kneeB!!.localPosition.set(bX, bY, 0f)
        ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)

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

        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * 1.9f)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * 1.9f)

        // Pole vectors (0.2, 0.8, -2.0) ensure elbows bend sideways along the Z-axis
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleABuffer.set(0.2f, 0.8f, -2.0f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, polePBuffer.set(0.2f, 0.8f, 2.0f), def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), axisZ, theta, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), axisZ, theta, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, theta, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, theta)
        val handDirA = tempV1.set(-1f, 0f, -0.4f).normalize() // Wide lateral splay
        palmA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition.set(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(axisZ, theta)
        val handDirP = tempV1.set(-1f, 0f, 0.4f).normalize()
        palmP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition.set(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
