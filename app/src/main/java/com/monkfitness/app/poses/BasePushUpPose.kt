package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

abstract class BasePushUpPose : BasePose() {

    // Subclasses only specify their parameters / metadata + configuration
    abstract val gripWidthMultiplier: Float
    open val handAnchorXOffset: Float = 0f
    open val poleA: Vector3 = Vector3(1f, 0.5f, -1f)
    open val poleP: Vector3 = Vector3(1f, 0.5f, 1f)
    open val handDirA: Vector3 = Vector3(-1f, 0f, -0.2f).normalize()
    open val handDirP: Vector3 = Vector3(-1f, 0f, 0.2f).normalize()

    protected var roots: List<SkeletonNode>? = null
    protected var ankleF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var hipF: SkeletonNode? = null; protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null
    protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()
    protected val geometryResult = PushUpSolverResult()

    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()
    protected val armAPoleLocal = Vector3()
    protected val armPPoleLocal = Vector3()

    // Stylized planted-palm hand proportions for push-ups.
    // These intentionally differ from the canonical open-hand HandDefinition: in a push-up the
    // palm is flat on the ground and the fingers are tucked, so the palm and knuckles coincide
    // (a short stub) and the fingertips sit at handFingertipOffset rather than the open-hand 22.
    protected val handPalmOffset = 6f
    protected val handFingertipOffset = 10f

    // Head gaze direction for the prone push-up posture (read-only, shared across frames).
    protected val pushUpHeadDirection = Vector3(-1f, 0.2f, 0f).normalize()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createPushUpSkeleton()
        roots = nodes.roots
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        kneeF = nodes.kneeF
        hipF = nodes.hipF
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
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // Target a small knee flexion for a visual and anatomically natural, barely-perceptible knee bend
        val targetFlexionDegrees = PushUpGeometrySolver.TARGET_KNEE_FLEXION_DEGREES
        val limbResult = solveNearStraightLeg(shinL, thighL, targetFlexionDegrees)
        val legTargetLen = limbResult.d

        val solverGeometry = PushUpGeometrySolver.solve(
            definition = def,
            support = metadata.support,
            gripWidthMultiplier = gripWidthMultiplier,
            progress = context.progress,
            result = geometryResult
        )

        val theta = solverGeometry.theta
        val ankleX = solverGeometry.ankleX
        val handAnchorX = solverGeometry.handAnchorX
        val ankleHeightVal = solverGeometry.ankleHeight

        val isKneePivot = metadata.support.pivot == PivotType.KNEES

        if (isKneePivot) {
            val shinPitch = PushUpGeometrySolver.SHIN_PITCH_ANGLE // Shins point 45 degrees up

            // 1. Root Anchoring
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)
            ankleF!!.localRotation.set(axisZ, shinPitch)

            val footDir = SkeletonMath.rotAround(tempV1.set(1f, -1f, 0f).normalize(), axisZ, -shinPitch, tempV2)
            heelF!!.localPosition.set(footDir.x * -def.foot.footLength * def.foot.heelRatio, footDir.y * -def.foot.footLength * def.foot.heelRatio, footDir.z * -def.foot.footLength * def.foot.heelRatio)
            toeF!!.localPosition.set(footDir.x * def.foot.footLength * def.foot.toeRatio, footDir.y * def.foot.footLength * def.foot.toeRatio, footDir.z * def.foot.footLength * def.foot.toeRatio)
            heelB!!.localPosition.set(footDir.x * -def.foot.footLength * def.foot.heelRatio, footDir.y * -def.foot.footLength * def.foot.heelRatio, footDir.z * -def.foot.footLength * def.foot.heelRatio)
            toeB!!.localPosition.set(footDir.x * def.foot.footLength * def.foot.toeRatio, footDir.y * def.foot.footLength * def.foot.toeRatio, footDir.z * def.foot.footLength * def.foot.toeRatio)

            // 2. Main Plank (Side F)
            kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Perfect Symmetry (Side B)
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            hipB!!.localRotation.set(axisZ, 0f)

            // Shin B must counter-rotate the -theta to match the 45 degree upward pitch
            kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, shinPitch + theta)
            ankleB!!.localPosition.set(def.shinLength, 0f, 0f)
        } else {
            // Feet Pivot push-up leg orientation (Standard, Wide, Decline, Diamond, Military)
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)
            ankleF!!.localRotation.set(axisZ, -theta)

            val worldFootDir = tempV1.set(0f, -1f, 0f)
            val localFootDir = SkeletonMath.rotAround(worldFootDir, axisZ, theta, tempV2)
            heelF!!.localPosition.set(localFootDir.x * -def.foot.footLength * def.foot.heelRatio, localFootDir.y * -def.foot.footLength * def.foot.heelRatio, localFootDir.z * -def.foot.footLength * def.foot.heelRatio)
            toeF!!.localPosition.set(localFootDir.x * def.foot.footLength * def.foot.toeRatio, localFootDir.y * def.foot.footLength * def.foot.toeRatio, localFootDir.z * def.foot.footLength * def.foot.toeRatio)
            heelB!!.localPosition.set(localFootDir.x * -def.foot.footLength * def.foot.heelRatio, localFootDir.y * -def.foot.footLength * def.foot.heelRatio, localFootDir.z * -def.foot.footLength * def.foot.heelRatio)
            toeB!!.localPosition.set(localFootDir.x * def.foot.footLength * def.foot.toeRatio, localFootDir.y * def.foot.footLength * def.foot.toeRatio, localFootDir.z * def.foot.footLength * def.foot.toeRatio)

            // Precompute local knee flexion coordinates (F-leg: ankle is the parent, hip is child)
            val kX = -limbResult.x
            val kY = limbResult.y

            kneeF!!.localPosition.set(kX, kY, 0f)
            hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // B-leg: hip is the parent, ankle is the child
            val bXResult = SkeletonMath.solveNearStraightLimb(thighL, shinL, targetFlexionDegrees, legScratch)
            val bX = bXResult.x
            val bY = bXResult.y

            kneeB!!.localPosition.set(bX, bY, 0f)
            ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)
        }

        buildHead(neck!!, head!!, def.neckLength, pushUpHeadDirection)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = SkeletonMath.rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = SkeletonMath.rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val finalHandAnchorX = handAnchorX + handAnchorXOffset
        val targetHandA = targetHandABuffer.set(finalHandAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(finalHandAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, armAPoleLocal)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        val armA = bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleLocal, chest!!.worldRotation, def.armIKConstraint, theta, elbowA!!, handA!!, armAIK)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, armPPoleLocal)
        val armP = bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleLocal, chest!!.worldRotation, def.armIKConstraint, theta, elbowP!!, handP!!, armPIK)

        handA!!.localRotation.set(axisZ, theta)
        palmA!!.localPosition.set(handDirA.x * handPalmOffset, handDirA.y * handPalmOffset, handDirA.z * handPalmOffset); knucklesA!!.localPosition.set(handDirA.x * handPalmOffset, handDirA.y * handPalmOffset, handDirA.z * handPalmOffset); fingertipsA!!.localPosition.set(handDirA.x * handFingertipOffset, handDirA.y * handFingertipOffset, handDirA.z * handFingertipOffset)

        handP!!.localRotation.set(axisZ, theta)
        palmP!!.localPosition.set(handDirP.x * handPalmOffset, handDirP.y * handPalmOffset, handDirP.z * handPalmOffset); knucklesP!!.localPosition.set(handDirP.x * handPalmOffset, handDirP.y * handPalmOffset, handDirP.z * handPalmOffset); fingertipsP!!.localPosition.set(handDirP.x * handFingertipOffset, handDirP.y * handFingertipOffset, handDirP.z * handFingertipOffset)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(armAIK.clampAmount, armPIK.clampAmount)
        return jointsBuffer
    }
}
