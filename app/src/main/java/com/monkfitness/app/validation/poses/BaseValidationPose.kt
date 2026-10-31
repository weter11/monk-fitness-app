package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.EnvironmentDefinition
import com.monkfitness.app.animation.Extremity
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.ContactSpec
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
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
import com.monkfitness.app.animation.ValidationStampMerge
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget

/**
 * Base class for Engineering Validation poses: a parallel implementation of the production base that
 * deliberately does not extend it and uses no motion drivers, breathing or loops. A validation pose is a
 * frozen snapshot — [build] ignores [PoseContext.state] and returns the same static skeleton every time —
 * and depends only on the rendering primitives, never on the exercise/workout/catalog systems.
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

    /**
     * Declares the gaze as a world-space `HeadTarget` intent that the engine-owned resolver turns into
     * the neck/head offsets: the pose only *declares* a target, so the head has a single writer.
     */
    protected fun buildGaze(
        neck: SkeletonNode,
        head: SkeletonNode,
        neckLength: Float,
        gazeDir: Vector3,
        targetDistance: Float = 100f
    ) {
        tempV1.set(gazeDir)
        if (tempV1.mag() < 1e-4f) tempV1.set(0f, 1f, 0f) else tempV1.normalize()
        val nw = neck.worldPosition
        tempV2.set(nw.x + tempV1.x * targetDistance, nw.y + tempV1.y * targetDistance, nw.z + tempV1.z * targetDistance)
        IntentBuilder(jointsBuffer).headTarget(tempV2.copy())
    }

    /**
     * Records one articulation into the intent carrier. The caller still writes the node, so build-time
     * FK stays intact while the finalizer re-derives the rotation idempotently.
     */
    protected fun declareJointIntent(joint: Joint, rotation: JointRotation) {
        IntentBuilder(jointsBuffer).joint(joint, rotation)
    }

    /**
     * Authors a wrist articulation as the extremity intent: composes the 2-DOF rotation (never dropping a
     * DOF) for build-time FK and records it for the finalizer to consume.
     */
    protected fun buildWristArticulation(
        extremity: Extremity,
        flexion: Float,
        deviation: Float,
        handNode: SkeletonNode
    ) {
        SkeletonMath.buildWristRotation(flexion, deviation, handNode.localRotation)
        IntentBuilder(jointsBuffer).extremity(extremity, JointRotation(handNode.localRotation.axis, handNode.localRotation.angle))
    }

    /** The ankle equivalent of [buildWristArticulation]: 2-DOF foot plant, recorded as an intent. */
    protected fun buildAnkleArticulation(
        extremity: Extremity,
        dorsiflexion: Float,
        inversion: Float,
        ankleNode: SkeletonNode
    ) {
        SkeletonMath.buildAnkleRotation(dorsiflexion, inversion, ankleNode.localRotation)
        IntentBuilder(jointsBuffer).extremity(extremity, JointRotation(ankleNode.localRotation.axis, ankleNode.localRotation.angle))
    }

    /**
     * The declarative spine curve, mirroring the production base. The axis-less default resolves from the
     * current build definition's anatomical forward — there is no hardcoded default here — and an explicit
     * axis remains the author's override.
     */
    protected fun buildSpineCurve(
        lower: SkeletonNode,
        chest: SkeletonNode,
        lowerRad: Float,
        thoracicRad: Float,
        axis: Vector3 = anatomicalForward()
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

    // The named DOF paths below replace raw localRotation writes, so authoring can express hip rotation,
    // girdle motion and 2-DOF wrist/ankle articulation unambiguously.

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
        declareJointIntent(clavicle.joint, JointRotation(clavicle.localRotation.axis, clavicle.localRotation.angle))
    }

    /**
     * Opts an extremity out of engine-derived orientation and into an author override, so the engine
     * preserves the authored endpoints verbatim. Exists so a future instrument can probe that path.
     */
    protected fun overrideExtremityOrientation(pose: SkeletonPose, extremity: com.monkfitness.app.animation.Extremity) {
        pose.overrideExtremityOrientation(extremity)
    }

    // Straight reference limbs opt into a true 1.0 extension ratio instead of the 0.98 safety cap so they
    // render perfectly straight; cached so repeated builds allocate nothing on the hot path.
    private var armFullExtensionConstraint: IKConstraint? = null
    private var legFullExtensionConstraint: IKConstraint? = null

    protected fun armStraightConstraint(def: SkeletonDefinition): IKConstraint =
        armFullExtensionConstraint ?: def.armIKConstraint.fullyExtended().also { armFullExtensionConstraint = it }

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
        // The fresh-window re-arm is build-window bookkeeping, not realization, so it runs in both
        // configurations: a reused carrier must never open a new authoring cycle still holding the
        // previous frame's solver readings.
        val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
        if (jointsBuffer.isTransformsUpdated) {
            jointsBuffer.boneLengthsVerified = true
            jointsBuffer.straightIntentDropped = false
            jointsBuffer.isTransformsUpdated = false
        }
        // The end joint and the full IK context travel in the carrier so the engine-owned stage can
        // reproduce this solve.
        jointsBuffer.limbTargets.add(
            WorldTarget(
                endNode.joint,
                Vector3(targetWorldPos.x, targetWorldPos.y, targetWorldPos.z),
                Vector3(pole.x, pole.y, pole.z),
                straight,
                contact,
                length1,
                length2,
                constraint
            )
        )
        // Registering the fixed contact is unconditional — only realization below is gated — so the
        // global solver can reposition the root and re-bake the limb.
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
        // Realization runs only while the engine stage is off; registration above is unconditional.
        if (IK_STAGE_ACTIVE) return
        jointsBuffer.registerLimbRealization(endNode.joint, authoringWindow = true)

        // A zero-length pole means the pose omitted one: derive a default so the bend plane is defined.
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
        // Propagating the solver's clamp into the pose means reachability needs no per-pose bookkeeping.
        jointsBuffer.maxIkClampAmount =
            ValidationStampMerge.clamp(jointsBuffer.maxIkClampAmount, ikResult.clampAmount)
        // This is the only production producer of `straight = true` (validation poses).
        jointsBuffer.straightIntentDropped =
            ValidationStampMerge.dropped(
                jointsBuffer.straightIntentDropped,
                if (straight) ikResult.straightIntentDropped else false
            )
        // Bone lengths must survive the solve exactly; the result is AND-folded into one stamp.
        val bonesOk = SkeletonMath.bonesExact(rootWorldPos, ikResult.joint, ikResult.end, length1, length2)
        jointsBuffer.boneLengthsVerified =
            ValidationStampMerge.verified(jointsBuffer.boneLengthsVerified, bonesOk)
        // Offsets go into the parent's true local frame — no hand-fed inverse-Z scalar.
        tempV1.set(ikResult.joint).subtract(rootWorldPos)
        SkeletonMath.toLocalDirection(tempV1, parentRot, middleNode.localPosition)
        tempV1.set(ikResult.end).subtract(ikResult.joint)
        SkeletonMath.toLocalDirection(tempV1, parentRot, endNode.localPosition)

    }

    /**
     * Declares the coarse posture intent the constraint solver should honour (seeding the root height)
     * and the contact-conflict precedence, index 0 winning. Opt-in: an instrument calls it only when it
     * wants the engine-owned root seed.
     */
    protected fun declarePosture(
        kind: PostureIntent.Kind,
        tolerance: Float = 0f,
        precedence: List<Joint> = emptyList()
    ) {
        // Carriers are private-set, so only the builder may write them.
        IntentBuilder(jointsBuffer).posture(kind, tolerance, precedence)
    }

    protected fun finalizePose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /** The static snapshot: subclasses ignore animation state and describe a frozen skeleton. */
    protected abstract fun buildStatic(definition: SkeletonDefinition): SkeletonPose

    final override fun build(context: PoseContext): SkeletonPose {
        // A reused pose instance is built more than once, so every carrier is reset before re-authoring:
        // neither the solver nor the finalizer may replay a previous build's state.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // Captured so the axis-less spine default resolves definition-owned; authoring-lifetime only.
        buildDefinition = context.definition
        return buildStatic(context.definition)
    }

    /** The definition of the [build] in progress; written only by the final [build] template. */
    private var buildDefinition: SkeletonDefinition? = null

    /**
     * The build's definition-owned anatomical forward. Fails fast when no build has run: an axis-less
     * default outside the authoring lifecycle has no definition to derive from and must not pick a
     * constant.
     */
    private fun anatomicalForward(): Vector3 {
        val definition = buildDefinition
        check(definition != null) {
            "R13 defaults: axis-less spine authoring requires the final build(context) " +
                "template to have supplied the current SkeletonDefinition"
        }
        return definition.anatomicalForward
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
