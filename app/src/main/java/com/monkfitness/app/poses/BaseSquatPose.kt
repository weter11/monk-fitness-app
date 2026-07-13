package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

abstract class BaseSquatPose : BasePose() {

    abstract val squatH: Float
    abstract val pelvisXEnd: Float
    abstract val leanAngleEnd: Float
    abstract val armLeanEnd: Float

    // Symmetrical scratch/buffers
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

        val standH = def.shinLength + def.thighLength + 25f
        val tProgress = context.progress // Use context.progress directly to preserve exact visual curves and prevent double-easing

        val pelvisY = SkeletonMath.lerp(standH, squatH, tProgress)
        val pelvisX = SkeletonMath.lerp(0f, pelvisXEnd, tProgress)
        val leanAngle = SkeletonMath.lerp(0f, leanAngleEnd, tProgress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f); head!!.localPosition.set(0f, 18f, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        val targetAnkleF = tempV1.set(0f, 25f, -def.hipWidth * 1.5f)
        val targetAnkleB = tempV2.set(0f, 25f, def.hipWidth * 1.5f)

        val legFIK = bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, tempV3.set(1f, 0f, -0.2f), def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)
        val legBIK = bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, tempV3.set(1f, 0f, 0.2f), def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, leanAngle); ankleB!!.localRotation.set(axisZ, leanAngle)
        heelF!!.localPosition.set(-def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * 0.71f, 0f, 0f)

        // Arm counterbalance reach
        val handTargetX = SkeletonMath.lerp(0f, armLeanEnd * 40f, tProgress)
        val handTargetY = SkeletonMath.lerp(pelvisY + def.torsoLength, pelvisY + def.torsoLength - 10f, tProgress)

        val armAIK = bakeIkLimb(shoulderA!!.worldPosition, tempV1.set(handTargetX, handTargetY, -def.shoulderWidth * 1.2f), def.upperArmLength, def.forearmLength, tempV3.set(0f, -1f, -1f), def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        val armPIK = bakeIkLimb(shoulderP!!.worldPosition, tempV2.set(handTargetX, handTargetY, def.shoulderWidth * 1.2f), def.upperArmLength, def.forearmLength, tempV3.set(0f, -1f, 1f), def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
