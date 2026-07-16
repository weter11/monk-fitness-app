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

            // The engine derives heel/toe from the shank + the neutral ankle articulation. The
            // planted flat foot is intentionally NOT hand-authored here; any visual shortfall is
            // an engine limitation left exposed.

            // 2. Main Plank (Side F)
            kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Perfect Symmetry (Side B)
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // Phase 6 (W15/G7): symmetry reset via the documented helper (no-op flexion).
            buildHipFlexion(hipB!!, 0f)

            // Shin B must counter-rotate the -theta to match the 45 degree upward pitch
            kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, shinPitch + theta)
            ankleB!!.localPosition.set(def.shinLength, 0f, 0f)
        } else {
            // Feet Pivot push-up leg orientation (Standard, Wide, Decline, Diamond, Military)
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // The engine derives heel/toe from the shank + the neutral ankle articulation. The
            // planted flat foot is intentionally NOT hand-authored here; any visual shortfall is
            // an engine limitation left exposed.

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
        val armA = bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, SkeletonMath.toWorldDirection(armAPoleLocal, chest!!.worldRotation, Vector3()), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, armPPoleLocal)
        val armP = bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, SkeletonMath.toWorldDirection(armPPoleLocal, chest!!.worldRotation, Vector3()), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation. The flat planted palm is intentionally NOT hand-authored here; any visual
        // shortfall is an engine limitation left exposed.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
