package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

abstract class BaseLungePose : BasePose() {

    abstract val stepSize: Float
    abstract val stepDirection: Float // 1f for forward lunge, -1f for reverse lunge, etc.
    abstract val lateralOffsetMultiplier: Float // for lateral lunges

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis
        chest = nodes.chest
        neck = nodes.neck
        head = nodes.head
        shoulderA = nodes.shoulderA
        elbowA = nodes.elbowA
        handA = nodes.handA
        palmA = nodes.palmA
        knucklesA = nodes.knucklesA
        fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP
        elbowP = nodes.elbowP
        handP = nodes.handP
        palmP = nodes.palmP
        knucklesP = nodes.knucklesP
        fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF
        kneeF = nodes.kneeF
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Alternating Handled Automatically using MotionDrivers
        val lungeR = MotionDrivers.LeftPhase(context.progress)
        val lungeL = MotionDrivers.RightPhase(context.progress)
        val activeDrop = lungeR + lungeL

        val standH = def.thighLength + def.shinLength + 25f
        val pelvisX = activeDrop * 40f * stepDirection
        val pelvisY = standH - (activeDrop * 65f)
        val leanAngle = activeDrop * 0.15f

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f); head!!.localPosition.set(0f, 18f, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        val targetAnkleB = tempV1.set(lungeR * stepSize * stepDirection, 25f, def.hipWidth + (lateralOffsetMultiplier * activeDrop * 20f))
        val targetAnkleF = tempV2.set(lungeL * stepSize * stepDirection, 25f, -def.hipWidth - (lateralOffsetMultiplier * activeDrop * 20f))

        val legFIK = solveLegIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, tempV3.set(1f, -1f, -0.2f), def.legIKConstraint, legFBuffer)
        val legBIK = solveLegIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, tempV3.set(1f, -1f, 0.2f), def.legIKConstraint, legBBuffer)

        SkeletonMath.rotAround(tempV3.set(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z), axisZ, leanAngle, kneeF!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z), axisZ, leanAngle, ankleF!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z), axisZ, leanAngle, kneeB!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z), axisZ, leanAngle, ankleB!!.localPosition)

        val footPitchF = lungeR * 0.8f
        val footPitchB = lungeL * 0.8f

        ankleF!!.localRotation.set(axisZ, leanAngle - footPitchF)
        ankleB!!.localRotation.set(axisZ, leanAngle - footPitchB)
        heelF!!.localPosition.set(-def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * 0.71f, 0f, 0f)

        val armSwing = lungeR - lungeL
        val targetHandA = tempV1.set(pelvisX + (armSwing * 30f) + 10f, pelvisY + def.torsoLength - 20f + (abs(armSwing) * 10f), -def.shoulderWidth * 1.5f)
        val targetHandP = tempV2.set(pelvisX + (-armSwing * 30f) + 10f, pelvisY + def.torsoLength - 20f + (abs(armSwing) * 10f), def.shoulderWidth * 1.5f)

        val armA = solveArmIK(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, tempV3.set(0f, -1f, -1f), def.armIKConstraint, armABuffer)
        val armP = solveArmIK(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, tempV3.set(0f, -1f, 1f), def.armIKConstraint, armPBuffer)

        SkeletonMath.rotAround(tempV3.set(armA.joint.x - shoulderA!!.worldPosition.x, armA.joint.y - shoulderA!!.worldPosition.y, armA.joint.z - shoulderA!!.worldPosition.z), axisZ, leanAngle, elbowA!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, leanAngle, handA!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(armP.joint.x - shoulderP!!.worldPosition.x, armP.joint.y - shoulderP!!.worldPosition.y, armP.joint.z - shoulderP!!.worldPosition.z), axisZ, leanAngle, elbowP!!.localPosition)
        SkeletonMath.rotAround(tempV3.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, leanAngle, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
