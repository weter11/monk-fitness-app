package com.monkfitness.app.animation

import kotlin.math.*

/**
 * BasePose serves as a generalized biomechanical framework for all exercise poses,
 * abstracting and consolidating shared scratch buffers, body construction helpers,
 * IK wrappers, motion driver helpers, and support definitions.
 */
abstract class BasePose : PoseBuilder {

    // Common scratch buffers (allocation-free, thread-safe, reusable)
    protected val zeroVector = Vector3(0f, 0f, 0f)
    protected val identityRotation = JointRotation()
    protected val axisZ = Vector3(0f, 0f, 1f)
    protected val tempV1 = Vector3()
    protected val tempV2 = Vector3()
    protected val tempV3 = Vector3()
    protected val tempPoleWorld = Vector3()

    protected val legScratch = SkeletonMath.NearStraightLimbResult()
    protected val jointsBuffer = SkeletonPose()

    // Common body construction helpers
    protected fun buildTorso(pelvis: SkeletonNode, chest: SkeletonNode, torsoLength: Float) {
        chest.localPosition.set(-torsoLength, 0f, 0f)
    }

    protected fun buildHead(neck: SkeletonNode, head: SkeletonNode, neckLength: Float, headDir: Vector3) {
        neck.localPosition.set(headDir.x * neckLength, headDir.y * neckLength, headDir.z * neckLength)
        head.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)
    }

    protected fun buildPelvis(pelvis: SkeletonNode, hipF: SkeletonNode, hipB: SkeletonNode, hipWidth: Float) {
        hipF.localPosition.set(0f, 0f, -hipWidth)
        hipB.localPosition.set(0f, 0f, hipWidth)
    }

    protected fun buildShoulders(shoulderA: SkeletonNode, shoulderP: SkeletonNode, shoulderWidth: Float) {
        shoulderA.localPosition.set(0f, 0f, -shoulderWidth)
        shoulderP.localPosition.set(0f, 0f, shoulderWidth)
    }

    protected fun buildRigidSegment(parent: SkeletonNode, child: SkeletonNode, offsetX: Float, offsetY: Float, offsetZ: Float) {
        child.localPosition.set(offsetX, offsetY, offsetZ)
    }

    // Common IK helpers (wrappers around the actual IK solver to avoid code duplication)
    protected fun solveArmIK(
        shoulderW: Vector3,
        targetHand: Vector3,
        upperArmLen: Float,
        forearmLen: Float,
        pole: Vector3,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveIK(shoulderW, targetHand, upperArmLen, forearmLen, pole, constraint, result)
    }

    protected fun solveLegIK(
        hipW: Vector3,
        targetAnkle: Vector3,
        thighLen: Float,
        shinLen: Float,
        pole: Vector3,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveIK(hipW, targetAnkle, thighLen, shinLen, pole, constraint, result)
    }

    // Straight / rigid-segment IK wrappers: a limb pinned collinear to its target.
    protected fun solveStraightArmIK(
        shoulderW: Vector3,
        targetHand: Vector3,
        upperArmLen: Float,
        forearmLen: Float,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveStraightLimb(shoulderW, targetHand, upperArmLen, forearmLen, constraint, result)
    }

    protected fun solveStraightLegIK(
        hipW: Vector3,
        targetAnkle: Vector3,
        thighLen: Float,
        shinLen: Float,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveStraightLimb(hipW, targetAnkle, thighLen, shinLen, constraint, result)
    }

    protected fun bakeIkLimb(
        rootWorldPos: Vector3,
        targetWorldPos: Vector3,
        length1: Float,
        length2: Float,
        parentRotation: JointRotation,
        poleLocal: Vector3,
        constraint: IKConstraint,
        middleNode: SkeletonNode,
        endNode: SkeletonNode,
        ikBuffer: SkeletonMath.IKResult,
        straight: Boolean = false
    ): SkeletonMath.IKResult {
        val worldPole = SkeletonMath.toWorldDirection(poleLocal, parentRotation, tempPoleWorld)
        return bakeIkLimb(rootWorldPos, targetWorldPos, length1, length2, worldPole, constraint, parentRotation, middleNode, endNode, ikBuffer, straight)
    }

    protected fun solveNearStraightLeg(
        shinLen: Float,
        thighLen: Float,
        targetFlexionDegrees: Float
    ): SkeletonMath.NearStraightLimbResult {
        return SkeletonMath.solveNearStraightLimb(shinLen, thighLen, targetFlexionDegrees, legScratch)
    }

    protected fun bakeIkLimb(
        rootWorldPos: Vector3,
        targetWorldPos: Vector3,
        length1: Float,
        length2: Float,
        pole: Vector3,
        constraint: IKConstraint,
        parentRotation: JointRotation,
        middleNode: SkeletonNode,
        endNode: SkeletonNode,
        ikBuffer: SkeletonMath.IKResult,
        straight: Boolean = false
    ): SkeletonMath.IKResult {
        val ikResult = if (straight) {
            SkeletonMath.solveStraightLimb(rootWorldPos, targetWorldPos, length1, length2, constraint, ikBuffer)
        } else {
            SkeletonMath.solveIK(rootWorldPos, targetWorldPos, length1, length2, pole, constraint, ikBuffer)
        }

        // Single source of truth: automatically propagate the solver's clamp amount into the
        // pose so reachability is detected without per-pose manual bookkeeping.
        if (ikResult.clampAmount > jointsBuffer.maxIkClampAmount) {
            jointsBuffer.maxIkClampAmount = ikResult.clampAmount
        }

        // Store the limb offsets in the parent's true local frame so they survive the parent's
        // full 3D world rotation exactly — no hand-fed inverse-Z scalar.
        tempV1.set(ikResult.joint).subtract(rootWorldPos)
        SkeletonMath.toLocalDirection(tempV1, parentRotation, middleNode.localPosition)

        tempV1.set(ikResult.end).subtract(ikResult.joint)
        SkeletonMath.toLocalDirection(tempV1, parentRotation, endNode.localPosition)

        return ikResult
    }

    // --- Frame-relative IK overloads: the pole is authored in the limb-root's LOCAL frame
    //     (chest/pelvis) and is transformed into world space via the parent's current world
    //     rotation. The analytical solver is unchanged. ---

    protected fun solveArmIK(
        shoulderW: Vector3,
        targetHand: Vector3,
        upperArmLen: Float,
        forearmLen: Float,
        poleLocal: Vector3,
        parentRotation: JointRotation,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveIK(shoulderW, targetHand, upperArmLen, forearmLen, poleLocal, parentRotation, constraint, result)
    }

    protected fun solveLegIK(
        hipW: Vector3,
        targetAnkle: Vector3,
        thighLen: Float,
        shinLen: Float,
        poleLocal: Vector3,
        parentRotation: JointRotation,
        constraint: IKConstraint,
        result: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        return SkeletonMath.solveIK(hipW, targetAnkle, thighLen, shinLen, poleLocal, parentRotation, constraint, result)
    }

    // Common Motion helpers (internally utilizing stateless MotionDrivers)
    protected fun phase(progress: Float): Float = progress
    protected fun downMotion(progress: Float): Float = MotionDrivers.PushPhase(progress)
    protected fun alternating(progress: Float): AlternatingMotion = MotionDrivers.alternating(progress)
    protected fun parabolicFootLift(t: Float): Float = MotionDrivers.ParabolicLift(t)

    // Common Support helpers building SupportContact collections allocation-free
    protected fun leftFoot(): SupportContact = SupportContact.LEFT_FOOT
    protected fun rightFoot(): SupportContact = SupportContact.RIGHT_FOOT
    protected fun bothFeet(): Set<SupportContact> = setOf(leftFoot(), rightFoot())
    protected fun leftKnee(): SupportContact = SupportContact.LEFT_KNEE
    protected fun rightKnee(): SupportContact = SupportContact.RIGHT_KNEE
    protected fun hands(): Set<SupportContact> = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
}
