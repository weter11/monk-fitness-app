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
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonPose.IntentBuilder
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget

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
    protected val axisX = Vector3(1f, 0f, 0f)
    protected val axisY = Vector3(0f, 1f, 0f)
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
    protected var clavicleA: SkeletonNode? = null; protected var scapulaA: SkeletonNode? = null
    protected var clavicleP: SkeletonNode? = null; protected var scapulaP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        clavicleA = nodes.clavicleA; scapulaA = nodes.scapulaA
        clavicleP = nodes.clavicleP; scapulaP = nodes.scapulaP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    // --- Shared body construction helpers -----------------------------------------

    protected fun buildHead(neck: SkeletonNode, head: SkeletonNode, neckLength: Float, headDir: Vector3) {
        neck.localPosition.set(headDir.x * neckLength, headDir.y * neckLength, headDir.z * neckLength)
        head.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)
    }

    /**
     * B2 (RFC_BRANCH_B_IMPLEMENTATION §2) — records a single joint articulation into the §1.1
     * `jointIntents` carrier via the sole-mutator `IntentBuilder`. The node is still written by the
     * caller (so build-time FK stays intact); the Finalizer consumes `jointIntents` and re-derives
     * the rotation idempotently, byte-identical to the pre-B2 baseline.
     */
    protected fun declareJointIntent(joint: Joint, rotation: JointRotation) {
        IntentBuilder(jointsBuffer).joint(joint, rotation)
    }

    /**
     * B2 — the single declarative spine curve (lower + thoracic about a shared axis), mirroring
     * [com.monkfitness.app.animation.BasePose.buildSpineCurve]. Writes both nodes and records the
     * `spineIntent` plus per-joint `jointIntents` entries for the Finalizer to consume.
     */
    protected fun buildSpineCurve(
        lower: SkeletonNode,
        chest: SkeletonNode,
        lowerRad: Float,
        thoracicRad: Float,
        axis: Vector3 = axisZ
    ) {
        lower.localRotation.set(axis, lowerRad)
        chest.localRotation.set(axis, thoracicRad)
        IntentBuilder(jointsBuffer).spine(lowerRad, thoracicRad, axis)
        declareJointIntent(lower.joint, JointRotation(axis, lowerRad))
        declareJointIntent(chest.joint, JointRotation(axis, thoracicRad))
    }

    protected fun buildPelvis(pelvis: SkeletonNode, hipF: SkeletonNode, hipB: SkeletonNode, hipWidth: Float) {
        hipF.localPosition.set(0f, 0f, -hipWidth)
        hipB.localPosition.set(0f, 0f, hipWidth)
    }

    protected fun buildShoulders(shoulderA: SkeletonNode, shoulderP: SkeletonNode, shoulderWidth: Float) {
        shoulderA.localPosition.set(0f, 0f, -shoulderWidth)
        shoulderP.localPosition.set(0f, 0f, shoulderWidth)
    }

    // --- Joint-DOF authoring helpers (mirror com.monkfitness.app.animation.BasePose) ---
    // The validation base previously only set raw local offsets/rotations. These named paths
    // give the validation poses access to the same anatomical DOFs the engine exposes (UNI-7/8/10),
    // so authoring can express hip rotation, scapular/clavicular girdle motion and 2-DOF
    // wrist/ankle articulation without raw, ambiguous localRotation writes.

    protected fun buildHipRotation(hip: SkeletonNode, rotationRad: Float, sideSign: Float) {
        hip.localRotation.set(axisX, rotationRad * sideSign)
        declareJointIntent(hip.joint, JointRotation(axisX, rotationRad * sideSign))
    }

    protected fun buildScapularRotation(
        scapula: SkeletonNode,
        retraction: Float,
        depression: Float,
        sideSign: Float
    ) {
        SkeletonMath.buildScapularRotation(retraction, depression, sideSign, scapula.localRotation)
    }

    protected fun buildClavicularRotation(
        clavicle: SkeletonNode,
        elevation: Float,
        protraction: Float,
        axialRotation: Float,
        sideSign: Float
    ) {
        SkeletonMath.buildClavicularRotation(elevation, protraction, axialRotation, sideSign, clavicle.localRotation)
        // B2: girdle articulation intent (jointIntents).
        declareJointIntent(clavicle.joint, JointRotation(clavicle.localRotation.axis, clavicle.localRotation.angle))
    }

    /**
     * W1 — opt an [extremity] out of engine-derived orientation and into an explicit author
     * override (mirrors [com.monkfitness.app.animation.BasePose.overrideExtremityOrientation]). The
     * engine then preserves the endpoint local positions this pose authored verbatim. Validation
     * poses are diagnostic instruments and are NOT modified by W1; this exists so a future
     * instrument can deliberately probe the override path.
     */
    protected fun overrideExtremityOrientation(pose: SkeletonPose, extremity: com.monkfitness.app.animation.Extremity) {
        pose.overrideExtremityOrientation(extremity)
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
        // B1 (IkStage extraction) — forward the end joint + full IK context into the §1.1
        // `limbTargets` carrier so the engine-owned IkStage can reproduce this solve byte-for-byte
        // (dead→live flip). `bakeIkLimb` remains the sole solver while EngineFlags.IK_STAGE_ACTIVE is
        // false.
        jointsBuffer.limbTargets.add(
            WorldTarget(
                endNode.joint,
                Vector3(targetWorldPos.x, targetWorldPos.y, targetWorldPos.z),
                Vector3(pole.x, pole.y, pole.z),
                straight,
                contact
            )
        )

        val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
        // Phase 1 (F5): reset the bone-length stamp once per build (mirrors BasePose.bakeIkLimb).
        if (jointsBuffer.isTransformsUpdated) {
            jointsBuffer.boneLengthsVerified = true
            jointsBuffer.isTransformsUpdated = false
        }
        // Phase 1 (F6): a zero-length pole means the pose omitted one — derive the default world
        // pole so the bend plane is always well-defined.
        val worldPole = if (pole.mag() < 1e-4f) {
            SkeletonMath.deriveDefaultPole(rootWorldPos, targetWorldPos, tempPoleWorld)
        } else {
            pole
        }
        val ikResult = if (straight) {
            SkeletonMath.solveStraightLimb(rootWorldPos, targetWorldPos, length1, length2, constraint, ikBuffer, contact)
        } else {
            SkeletonMath.solveIK(rootWorldPos, targetWorldPos, length1, length2, worldPole, constraint, ikBuffer, contact)
        }
        // Single source of truth: automatically propagate the solver's clamp amount into the
        // pose so reachability is detected without per-pose manual bookkeeping.
        if (ikResult.clampAmount > jointsBuffer.maxIkClampAmount) {
            jointsBuffer.maxIkClampAmount = ikResult.clampAmount
        }
        // Phase 1 (F5): assert the solved chain preserved both bone lengths exactly and fold the
        // result into the pose's single `boneLengthsVerified` stamp (AND across all limbs).
        val bonesOk = SkeletonMath.bonesExact(rootWorldPos, ikResult.joint, ikResult.end, length1, length2)
        jointsBuffer.boneLengthsVerified = jointsBuffer.boneLengthsVerified && bonesOk
        // Store the limb offsets in the parent's true local frame (no hand-fed inverse-Z scalar).
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

    /**
     * Phase 2 (F2/F7) — declares the coarse posture intent the [ConstraintSolver] should honour
     * (seeding the root/pelvis height) and the contact-conflict precedence order. Mirrors the
     * production [com.monkfitness.app.animation.BasePose.declarePosture]; writes onto this pose's
     * [jointsBuffer]. Validation poses are diagnostic instruments and are NOT retuned, so this is
     * opt-in: a validation pose calls it only when it wants the engine-owned root seed.
     *
     * [precedence] lists contact end-joint names in priority order (index 0 wins); an empty list
     * means all contacts are equal.
     */
    protected fun declarePosture(
        kind: PostureIntent.Kind,
        tolerance: Float = 0f,
        precedence: List<Joint> = emptyList()
    ) {
        // Route through the sole-mutator IntentBuilder (B0 compile guard: §1.1 carriers are
        // private-set on SkeletonPose, so only the builder may write them).
        IntentBuilder(jointsBuffer).posture(kind, tolerance, precedence)
    }

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
