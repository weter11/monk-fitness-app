package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class PikePushUpPose : BasePushUpPose() {
    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val def = context.definition
        ensureHierarchy(def)

        // 1. Driving limits and global pitch (-theta)
        val height = lerp(60f, 25f, progress)
        val totalLegLen = def.shinLength + def.thighLength
        val ankleHeight = def.foot.ankleHeight
        val drivingHeight = (height - ankleHeight).coerceAtLeast(0f)
        val theta = asin((drivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val ankleX = 60f + (totalLegLen * cos(theta))

        // 2. Ankle positioning and primary body rotation
        ankleF!!.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), -theta)

        // FIX 1: Feet pointing straight.
        // We calculate a perfectly straight forward vector (X-axis) and counter-rotate it by +theta. 
        // This stops the feet from turning inward and keeps them completely flat on the ground.
        val localFootDir = rotAround(Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f), theta, Vector3())
        heelF!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        
        heelB!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        // 3. Spinal Kinematics
        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        // 4. Force global transform calculation prior to IK Solve
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(Vector3(0f, 0f, -def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)
        val shoulderPW = rotAround(Vector3(0f, 0f, def.shoulderWidth), Vector3(0f, 0f, 1f), chest!!.worldRotation.angle, Vector3()).add(chestW)

        val maxDrivingHeight = (60f - ankleHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta)

        val targetHandA = Vector3(handAnchorX, 0f, -def.shoulderWidth * 1.5f)
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, -1f), def.armIKConstraint, armAIK)

        val targetHandP = Vector3(handAnchorX, 0f, def.shoulderWidth * 1.5f)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 0.5f, 1f), def.armIKConstraint, armPIK)

        // 5. Hierarchy Update (Applying isolated IK vectors back to Local coordinate space)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(Vector3(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), Vector3(0f, 0f, 1f), theta, elbowA!!.localPosition)
        rotAround(Vector3(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), Vector3(0f, 0f, 1f), theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(Vector3(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), Vector3(0f, 0f, 1f), theta, elbowP!!.localPosition)
        rotAround(Vector3(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), Vector3(0f, 0f, 1f), theta, handP!!.localPosition)

        // FIX 2: Flat Hands. 
        // We set the hand's local rotation axis to +theta to completely cancel out the inherited torso pitch.
        // Because of this, the local positions of the palm and knuckles directly reflect their absolute world layout.
        handA!!.localRotation.set(Vector3(0f, 0f, 1f), theta)
        val handDirA = Vector3(-1f, 0f, -0.2f).normalize()
        palmA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f)
        knucklesA!!.localPosition = Vector3(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f)
        fingertipsA!!.localPosition = Vector3(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(Vector3(0f, 0f, 1f), theta)
        val handDirP = Vector3(-1f, 0f, 0.2f).normalize()
        palmP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f)
        knucklesP!!.localPosition = Vector3(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f)
        fingertipsP!!.localPosition = Vector3(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        // 6. Flatten Scene Graph to rendering buffer
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        
        // Ensure wrists follow hands natively 
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))

        return jointsBuffer
    }
}