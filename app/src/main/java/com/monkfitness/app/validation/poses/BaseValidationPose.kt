package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.EnvironmentDefinition
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.ContactSpec
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.LoopMode
import com.monkfitness.app.animation.MotionCurve
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PoseMetadata
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.Vector3

/**
 * Base class for Engineering Validation poses.
 *
 * This is a parallel implementation of the production [com.monkfitness.app.animation.BasePose].
 * It deliberately does NOT extend it and does NOT use any [com.monkfitness.app.animation.MotionDrivers],
 * breathing, interpolation or animation loops. A validation pose is a frozen snapshot:
 * [build] ignores [PoseContext.state] entirely and returns the same static skeleton every time.
 *
 * It only depends on the shared rendering engine primitives (SkeletonFactory, SkeletonMath,
 * SkeletonPose) — never on the exercise / workout / catalog systems.
 */
abstract class BaseValidationPose : PoseBuilder {

    protected val zeroVector = Vector3(0f, 0f, 0f)
    protected val identityRotation = JointRotation()
    protected val axisZ = Vector3(0f, 0f, 1f)
    protected val tempV1 = Vector3()
    protected val tempV2 = Vector3()
    protected val tempV3 = Vector3()
    protected val tempPoleWorld = Vector3()

    protected val legScratch = SkeletonMath.NearStraightLimbResult()
    protected val jointsBuffer = SkeletonPose()

    // IK buffers
    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    // --- Shared body construction helpers -----------------------------------------

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

    // --- Shared IK helpers ---------------------------------------------------------

    // PR-11: cached full-extension variants of the definition's IK constraints. Straight
    // reference limbs opt into a true 1.0 extension ratio (instead of the 0.98 safety cap) so
    // they render perfectly straight. Cached so repeated builds allocate nothing on the hot path.
    private var armFullExtensionConstraint: IKConstraint? = null
    private var legFullExtensionConstraint: IKConstraint? = null

    /** Arm constraint opted into full extension for a genuinely straight reference arm. */
    protected fun armStraightConstraint(def: SkeletonDefinition): IKConstraint =
        armFullExtensionConstraint ?: def.armIKConstraint.fullyExtended().also { armFullExtensionConstraint = it }

    /** Leg constraint opted into full extension for a genuinely straight reference leg. */
    protected fun legStraightConstraint(def: SkeletonDefinition): IKConstraint =
        legFullExtensionConstraint ?: def.legIKConstraint.fullyExtended().also { legFullExtensionConstraint = it }

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
        straight: Boolean = false,
        contact: ContactConstraint? = null
    ) {
        val ikResult = if (straight) {
            SkeletonMath.solveStraightLimb(rootWorldPos, targetWorldPos, length1, length2, constraint, ikBuffer, contact)
        } else {
            SkeletonMath.solveIK(rootWorldPos, targetWorldPos, length1, length2, pole, constraint, ikBuffer, contact)
        }
        // Single source of truth: automatically propagate the solver's clamp amount into the
        // pose so reachability is detected without per-pose manual bookkeeping.
        if (ikResult.clampAmount > jointsBuffer.maxIkClampAmount) {
            jointsBuffer.maxIkClampAmount = ikResult.clampAmount
        }
        // Store the limb offsets in the parent's true local frame (no hand-fed inverse-Z scalar).
        val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
        tempV1.set(ikResult.joint).subtract(rootWorldPos)
        SkeletonMath.toLocalDirection(tempV1, parentRot, middleNode.localPosition)
        tempV1.set(ikResult.end).subtract(ikResult.joint)
        SkeletonMath.toLocalDirection(tempV1, parentRot, endNode.localPosition)

        // PR-04: register the fixed support contact so the global constraint solver can
        // reposition the root and re-bake the limb to honor it.
        if (contact != null) {
            val chain = ConstraintSolver.chainForEnd(endNode.joint)
            if (chain != null) {
                jointsBuffer.contacts.add(
                    ContactSpec(
                        endJoint = endNode.joint,
                        rootJoint = chain.rootJoint,
                        parentRotationJoint = chain.parentRotationJoint,
                        middleJoint = chain.middleJoint,
                        targetWorld = Vector3(targetWorldPos.x, targetWorldPos.y, targetWorldPos.z),
                        pole = Vector3(pole.x, pole.y, pole.z),
                        length1 = length1,
                        length2 = length2,
                        constraint = constraint,
                        straight = straight,
                        contact = contact
                    )
                )
            }
        }
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
        straight: Boolean = false,
        contact: ContactConstraint? = null
    ) {
        val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
        val worldPole = SkeletonMath.toWorldDirection(poleLocal, parentRot, tempPoleWorld)
        bakeIkLimb(rootWorldPos, targetWorldPos, length1, length2, worldPole, constraint, parentRot, middleNode, endNode, ikBuffer, straight, contact)
    }

    protected fun solveNearStraightLeg(
        shinLen: Float,
        thighLen: Float,
        targetFlexionDegrees: Float
    ) = SkeletonMath.solveNearStraightLimb(shinLen, thighLen, targetFlexionDegrees, legScratch)

    // --- Finalization (mirrors production poses; produces a rotation-driven snapshot) --

    protected fun finalizePose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * Produces the static snapshot. Subclasses must ignore animation state and describe a
     * single frozen skeleton.
     */
    protected abstract fun buildStatic(definition: SkeletonDefinition): SkeletonPose

    final override fun build(context: PoseContext): SkeletonPose {
        // PR-04: a reused pose instance may be built more than once; clear any fixed-contact
        // specs from a previous build before re-authoring, so the global solver never replays
        // stale contacts.
        jointsBuffer.contacts.clear()
        // Validation poses are frozen: animation progress / side / mirroring are ignored.
        return buildStatic(context.definition)
    }

    protected fun staticMetadata(
        camera: CameraDefinition,
        environment: EnvironmentDefinition = EnvironmentDefinition(ground = com.monkfitness.app.animation.GroundDefinition(visible = true, level = 0f)),
        support: SupportDefinition
    ) = PoseMetadata(
        camera = camera,
        durationSeconds = 0f,
        loopMode = LoopMode.HOLD,
        motionCurve = MotionCurve.LINEAR,
        environment = environment,
        support = support
    )
}
