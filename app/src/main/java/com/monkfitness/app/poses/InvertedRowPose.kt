package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class InvertedRowPose : BasePushUpPose() {
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Pull factor based on a smooth sine wave to prevent any velocity or acceleration jumps
        val pullFactor = sin(context.progress.toDouble() * 2.0 * Math.PI - Math.PI / 2.0).toFloat() * 0.5f + 0.5f

        // chestY goes from fully lowered (45f) to fully contracted (105f)
        val chestY = lerp(45f, 105f, pullFactor)

        // Keep the leg slightly bent (205f < 205.8f limit) to satisfy leg IK constraints
        val L_leg = 205f
        val L = L_leg + def.torsoLength
        val cosTheta = sqrt(max(0f, L * L - chestY * chestY)) / L
        val sinTheta = chestY / L
        val theta = atan2(sinTheta, cosTheta)

        // Flat feet anchored at footX
        val footX = 220f
        val ankleHeight = 0f

        ankleF!!.localPosition.set(footX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(axisZ, -theta)

        // Rotate horizontal foot direction backward by -theta to get local foot direction
        val worldFootDir = tempV1.set(1f, 0f, 0f)
        val localFootDir = rotAround(worldFootDir, axisZ, theta, tempV2)

        heelF!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        // Target hips relative to foot along the plank line
        val pelvisX = footX - L_leg * cosTheta
        val pelvisY = L_leg * sinTheta
        val targetHipF = tempV1.set(pelvisX, pelvisY, -def.hipWidth)
        val targetHipB = tempV2.set(pelvisX, pelvisY, def.hipWidth)

        // Solve leg IK (shin first, then thigh)
        val legA = solveIK(
            root = ankleF!!.localPosition, // Ankle is root of the leg chain in base push-up hierarchy
            target = targetHipF,
            L1 = def.shinLength,
            L2 = def.thighLength,
            pole = tempV3.set(-1f, 1f, 0f),
            constraint = def.legIKConstraint,
            result = legFIK
        )

        val legB = solveIK(
            root = Vector3(footX, ankleHeight, def.hipWidth), // Ankle B is back leg root
            target = targetHipB,
            L1 = def.shinLength,
            L2 = def.thighLength,
            pole = tempV3.set(-1f, 1f, 0f),
            constraint = def.legIKConstraint,
            result = legBIK
        )

        // Map leg IK solutions to local positions in hierarchy
        // In BasePushUpPose hierarchy, kneeF is child of ankleF, hipF is child of kneeF
        rotAround(tempV3.set(legA.joint.x - footX, legA.joint.y - ankleHeight, legA.joint.z + def.hipWidth), axisZ, theta, kneeF!!.localPosition)
        rotAround(tempV3.set(legA.end.x - legA.joint.x, legA.end.y - legA.joint.y, legA.end.z - legA.joint.z), axisZ, theta, hipF!!.localPosition)

        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        val headDir = tempV1.set(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition.set(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        // Back leg: kneeB is child of hipB, ankleB is child of kneeB
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        // Local position of kneeB relative to hipB:
        rotAround(tempV3.set(legB.joint.x - targetHipB.x, legB.joint.y - targetHipB.y, legB.joint.z - targetHipB.z), axisZ, theta, kneeB!!.localPosition)
        // Local position of ankleB relative to kneeB (from ankle root to knee joint):
        rotAround(tempV3.set(footX - legB.joint.x, ankleHeight - legB.joint.y, def.hipWidth - legB.joint.z), axisZ, theta, ankleB!!.localPosition)

        // First FK pass to compute correct world positions for the body skeleton
        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        // Retrieve computed world positions for the shoulders
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        // Target hands at the row bar height (120f)
        val handY = 120f
        val targetHandA = targetHandABuffer.set(0f, handY, -def.shoulderWidth)
        val targetHandP = targetHandPBuffer.set(0f, handY, def.shoulderWidth)

        // Solve arm IK
        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleABuffer.set(-1f, -0.5f, -1f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, polePBuffer.set(-1f, -0.5f, 1f), def.armIKConstraint, armPIK)

        // Map IK solutions back to local positions relative to rotated shoulders
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), axisZ, theta, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), axisZ, theta, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, theta, handP!!.localPosition)

        // Local hand rotations and palm/finger positions
        handA!!.localRotation.set(axisZ, theta)
        val handDirA = tempV1.set(1f, 0f, 0f)
        palmA!!.localPosition.set(handDirA.x * def.hand.palmLength * 0.5f, handDirA.y * def.hand.palmLength * 0.5f, handDirA.z * def.hand.palmLength * 0.5f)
        knucklesA!!.localPosition.set(handDirA.x * def.hand.palmLength * 0.5f, handDirA.y * def.hand.palmLength * 0.5f, handDirA.z * def.hand.palmLength * 0.5f)
        fingertipsA!!.localPosition.set(handDirA.x * def.hand.fingerLength, handDirA.y * def.hand.fingerLength, handDirA.z * def.hand.fingerLength)

        handP!!.localRotation.set(axisZ, theta)
        val handDirP = tempV1.set(1f, 0f, 0f)
        palmP!!.localPosition.set(handDirP.x * def.hand.palmLength * 0.5f, handDirP.y * def.hand.palmLength * 0.5f, handDirP.z * def.hand.palmLength * 0.5f)
        knucklesP!!.localPosition.set(handDirP.x * def.hand.palmLength * 0.5f, handDirP.y * def.hand.palmLength * 0.5f, handDirP.z * def.hand.palmLength * 0.5f)
        fingertipsP!!.localPosition.set(handDirP.x * def.hand.fingerLength, handDirP.y * def.hand.fingerLength, handDirP.z * def.hand.fingerLength)

        // Flatten hierarchy into pose buffer
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
