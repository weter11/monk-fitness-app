package com.monkfitness.app.animation

import com.monkfitness.app.animation.SkeletonPose.IntentBuilder

abstract class BasePose : PoseBuilder {

    // Allocation-free scratch reused across builds.
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

    protected fun buildTorso(pelvis: SkeletonNode, chest: SkeletonNode, torsoLength: Float) {
        chest.localPosition.set(-torsoLength, 0f, 0f)
    }


    /**
     * Declares the gaze as a world-space [HeadTarget] intent that
     * [SkeletonPoseFinalizer.resolveHeadTarget] resolves into the neck/head local offsets; the head
     * is written by the resolver alone.
     *
     * The recorded target is an absolute world point a fixed distance along [gazeDir] from the
     * neck's **current-build** world position, and the resolver recovers the direction by
     * subtracting that position from it. So the authored hierarchy must be propagated before the
     * read: node buffers are reused across builds, and a read taken before the caller's
     * authoring-FK pass would carry the previous build's tree state — on a builder's first build,
     * the template's zero transform — into the resolved head direction.
     */
    protected fun buildGaze(
        neck: SkeletonNode,
        head: SkeletonNode,
        neckLength: Float,
        gazeDir: Vector3,
        targetDistance: Float = 100f
    ) {
        // Must read THIS build's neck world position (see above). Propagating from the pose's own
        // root here makes the helper independent of whether the caller has already run its
        // authoring-FK pass; that pass re-runs the same idempotent propagation.
        var gazeRoot: SkeletonNode = neck
        while (gazeRoot.parent != null) gazeRoot = gazeRoot.parent!!
        gazeRoot.updateWorldTransforms(zeroVector, identityRotation)

        // Synthetic target along the authored gaze direction.
        tempV1.set(gazeDir)
        if (tempV1.mag() < 1e-4f) tempV1.set(0f, 1f, 0f) else tempV1.normalize()
        val nw = neck.worldPosition
        tempV2.set(nw.x + tempV1.x * targetDistance, nw.y + tempV1.y * targetDistance, nw.z + tempV1.z * targetDistance)
        // Declared through the sole-mutator IntentBuilder: the carriers are private-set on
        // SkeletonPose, so only the builder may write them.
        IntentBuilder(jointsBuffer).headTarget(tempV2.copy())
    }

    protected fun buildPelvis(pelvis: SkeletonNode, hipF: SkeletonNode, hipB: SkeletonNode, hipWidth: Float) {
        hipF.localPosition.set(0f, 0f, -hipWidth)
        hipB.localPosition.set(0f, 0f, hipWidth)
    }

    protected fun buildShoulders(shoulderA: SkeletonNode, shoulderP: SkeletonNode, shoulderWidth: Float) {
        shoulderA.localPosition.set(0f, 0f, -shoulderWidth)
        shoulderP.localPosition.set(0f, 0f, shoulderWidth)
    }

    /**
     * Records [rotation] as the joint's intent carrier in addition to writing the node's
     * `localRotation`. The Finalizer re-derives node rotations from the carriers, but the node
     * write has to stay: build-time logic that reads world transforms (arm IK under a rotating
     * chest) depends on it, and re-application is idempotent, so output stays byte-identical.
     */
    protected fun declareJointIntent(joint: Joint, rotation: JointRotation) {
        IntentBuilder(jointsBuffer).joint(joint, rotation)
    }

    /**
     * Clears the per-build intent carriers before authoring. Pose instances are long-lived
     * singletons reused across animation frames, so a carrier left uncleared accumulates one entry
     * per frame and grows the Finalizer/Solver consume cost linearly. Structural node trees are
     * untouched.
     * Subclasses cannot forget the reset because [build] is final.
     *
     * Also captures the build's [PoseContext.definition] so helpers with definition-owned defaults
     * can resolve them without a signature migration at every call site; the reference is
     * authoring-lifetime only and never enters [SkeletonPose] state.
     */
    final override fun build(context: PoseContext): SkeletonPose {
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        buildDefinition = context.definition
        val pose = onBuild(context)
        onPoseBuilt(pose)
        return pose
    }

    /**
     * Definition of the build in progress (single-threaded authoring, same contract as
     * [jointsBuffer]); written only by the final [build] template.
     */
    private var buildDefinition: SkeletonDefinition? = null

    /**
     * The current build's definition-owned anatomical forward. Fails fast outside the authoring
     * lifecycle: an axis-less spine default has no definition to derive from and must not silently
     * pick a constant.
     */
    private fun anatomicalForward(): Vector3 {
        val definition = buildDefinition
        check(definition != null) {
            "R13 defaults: axis-less spine authoring requires the final build(context) " +
                "template to have supplied the current SkeletonDefinition"
        }
        return definition.anatomicalForward
    }

    /**
     * Post-finalize hook for subclasses that need to adjust the built pose (e.g. override an
     * inherited heading). Runs inside the final [build] template, so carrier hygiene holds.
     */
    protected open fun onPoseBuilt(pose: SkeletonPose) {}

    /** Author this pose's geometry + intent carriers into the freshly-reset [jointsBuffer]. */
    protected abstract fun onBuild(context: PoseContext): SkeletonPose

    /**
     * Wrist grip: [flexion]/extension about the mediolateral Z axis plus radial/ulnar [deviation]
     * about the antero-posterior Y axis, composed via [SkeletonMath.buildWristRotation] so combined
     * DOFs never drop one axis. Writes the node's `localRotation` for build-time FK and records the
     * articulation for the Finalizer, which owns it.
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

    /**
     * Foot plant: [dorsiflexion] (dorsi/plantar-flexion about the mediolateral Z axis) plus
     * [inversion] (inversion/eversion about the long X axis), composed via
     * [SkeletonMath.buildAnkleRotation]. Writes the ankle node's `localRotation` for build-time FK
     * and records the articulation for the Finalizer, which owns it.
     */
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
     * Thoracic twist of the chest about its local +Y (spine long) axis. The chest is the IK root
     * for both arms, so the whole upper chain follows the twist via FK.
     */
    protected fun buildChestTwist(chest: SkeletonNode, twistRad: Float) {
        chest.localRotation.set(axisY, twistRad)
        declareJointIntent(Joint.CHEST, JointRotation(axisY, twistRad))
    }

    /**
     * Clavicular rotation on the proximal shoulder-girdle node (CHEST -> CLAVICLE -> SCAPULA ->
     * SHOULDER): [elevation] about transverse X, [protraction] about vertical Y, [axialRotation]
     * about long Z — real joint rotations that lift and carry the glenoid on overhead reaches. FK
     * propagates them to scapula and shoulder. [sideSign] mirrors elevation across the body
     * mid-line (-1 left/active, +1 right/passive).
     */
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
     * Authors a two-segment spine curve: a lower-spine rotation and a thoracic (chest) rotation
     * about the same [axis]. Equal values reproduce one overall bend split across the spine; a
     * lower value of zero with a thoracic value expresses "thoracic extends while the lower back
     * stays".
     *
     * [lower] must be `pelvis` for pelvis-rooted trunks, not `LUMBAR`: the hips attach to the
     * pelvis, so the tilt has to live there or the planted feet drift. Use `nodes.lumbar` only when
     * the hips are themselves children of the lumbar. [chest] is always the CHEST node.
     *
     * This is the single authorized way to express a trunk lean — never hand-write both
     * `pelvis.localRotation` and `chest.localRotation` as independent angles. The axis-less default
     * is the current build definition's anatomical forward; an explicit [axis] overrides it.
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
        // Both segments are also recorded per-joint below so the unified consumer covers them
        // unambiguously; `spineIntent` carries the single declarative curve.
        IntentBuilder(jointsBuffer).spine(lowerRad, thoracicRad, axis)
        declareJointIntent(lower.joint, JointRotation(axis, lowerRad))
        declareJointIntent(chest.joint, JointRotation(axis, thoracicRad))
    }

    /**
     * Opts [extremity] out of engine-derived orientation: the endpoint positions the pose wrote on
     * HEEL/TOE (or PALM/KNUCKLES/FINGERTIPS) are preserved verbatim instead of derived from the limb
     * plus articulation. Only for stylized extremities the derivation cannot express (pointed toe,
     * curled grip); pure-anatomy poses never call this.
     */
    protected fun overrideExtremityOrientation(pose: SkeletonPose, extremity: Extremity) {
        pose.overrideExtremityOrientation(extremity)
    }

    /**
     * Declares the root-relative (pelvis-frame) forward direction of an extremity. Use when the
     * constraint system admits several valid orientations and the exercise must pick one (e.g. a
     * planted hand on the floor). The Finalizer transforms it to world space with the solved pelvis
     * rotation and projects it onto the support plane.
     */
    protected fun setHeading(extremity: Extremity, rootRelativeDir: Vector3) {
        SkeletonPose.HeadingBuilder(jointsBuffer).set(extremity, rootRelativeDir)
    }

    /**
     * Hip flexion/extension in the sagittal plane, about the mediolateral Z axis. This is the
     * acetabular ball joint; FK carries the whole leg with it.
     */
    protected fun buildHipFlexion(hip: SkeletonNode, flexionRad: Float) {
        hip.localRotation.set(axisZ, flexionRad)
        declareJointIntent(hip.joint, JointRotation(axisZ, flexionRad))
    }

    /**
     * Femoral axial rotation about the femur's long X axis on the hip ball joint. Kept *separate*
     * from the IK pole, which only selects the knee-bend plane. [sideSign] (-1 left / +1 right)
     * mirrors the twist across the mid-line.
     */
    protected fun buildHipRotation(hip: SkeletonNode, rotationRad: Float, sideSign: Float) {
        hip.localRotation.set(axisX, rotationRad * sideSign)
        declareJointIntent(hip.joint, JointRotation(axisX, rotationRad * sideSign))
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
        straight: Boolean = false,
        contact: ContactConstraint? = null
    ): SkeletonMath.IKResult {
        val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
        // Build-scoped re-arm, not a merge: `isTransformsUpdated` was set by the previous frame's
        // finalize, so the first limb baked this build re-arms the optimistic `true` and the fresh
        // write window's AND across limbs starts from that default.
        if (jointsBuffer.isTransformsUpdated) {
            jointsBuffer.boneLengthsVerified = true
            // The straight-intent-dropped flag (OR identity = false) re-arms in the SAME fresh
            // window, so neither stamp opens a build carrying a previous build's reading.
            jointsBuffer.straightIntentDropped = false
            jointsBuffer.isTransformsUpdated = false
        }
        // Registration always runs — the carrier is intent, not realization. The solve, stamp folds
        // and node writes below are gated: while IK_STAGE_ACTIVE the engine stage owns realization
        // and this bake contributes zero geometry, so exactly one implementation realizes per
        // configuration.
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
        // A fixed support contact is registered so the global constraint solver can reposition the
        // root and re-bake the limb to honour it.
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
        if (IK_STAGE_ACTIVE) return ikBuffer
        // One registration path per realization site: the first realization of a build cycle
        // re-arms the cycle's window count, and each realized limb is recorded so a second
        // realization of the same limb is per-execution evidence instead of an invisible no-op.
        jointsBuffer.registerLimbRealization(endNode.joint, authoringWindow = true)

        // A zero-length pole means the pose omitted one: derive the default world pole so the bend
        // plane is always well-defined (the engine owns this, not the pose).
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

        // Propagate the solver's clamp amount into the pose so reachability is detected without
        // per-pose manual bookkeeping.
        jointsBuffer.maxIkClampAmount =
            ValidationStampMerge.clamp(jointsBuffer.maxIkClampAmount, ikResult.clampAmount)

        // Fold this solve's straight-limb fallback outcome into the carrier stamp (merge rule OR).
        // The reading is the scratch's — it describes the branch THIS solve actually took.
        jointsBuffer.straightIntentDropped =
            ValidationStampMerge.dropped(
                jointsBuffer.straightIntentDropped,
                if (straight) ikResult.straightIntentDropped else false
            )

        // Assert the solved chain preserved both bone lengths exactly and fold the result into the
        // pose's single `boneLengthsVerified` stamp (AND across all limbs).
        val bonesOk = SkeletonMath.bonesExact(rootWorldPos, ikResult.joint, ikResult.end, length1, length2)
        jointsBuffer.boneLengthsVerified =
            ValidationStampMerge.verified(jointsBuffer.boneLengthsVerified, bonesOk)

        // Store the limb offsets in the parent's true local frame so they survive its full 3D world
        // rotation exactly — no hand-fed inverse-Z scalar.
        tempV1.set(ikResult.joint).subtract(rootWorldPos)
        SkeletonMath.toLocalDirection(tempV1, parentRot, middleNode.localPosition)

        tempV1.set(ikResult.end).subtract(ikResult.joint)
        SkeletonMath.toLocalDirection(tempV1, parentRot, endNode.localPosition)

        return ikResult
    }

    // Stateless motion drivers.
    protected fun phase(progress: Float): Float = progress
    protected fun downMotion(progress: Float): Float = MotionDrivers.PushPhase(progress)
    protected fun alternating(progress: Float): AlternatingMotion = MotionDrivers.alternating(progress)
    protected fun parabolicFootLift(t: Float): Float = MotionDrivers.ParabolicLift(t)

    /**
     * The one sanctioned authoring-time planning solve: computes a limb's intermediate placement
     * (e.g. the bent-knee apex) so a pose can compose later limb targets from it inside `build()`,
     * where the realized solver result is not authoring input.
     *
     * It is not realization: no nodes and no carrier state are written, it does not count into the
     * solver-window counter, and it produces no stamp readings. Consume the result only to compose
     * declared intent values (target coordinates), never as geometry.
     *
     * Only the hip-flexor chain is admitted — its arm targets are choreographed against the front
     * knee's bent position; any other caller needs a plan amendment, and
     * `PlanningSolveInventoryTest` pins the call sites.
     */
    protected fun planLimbPlacement(
        rootWorldPos: Vector3,
        targetWorldPos: Vector3,
        length1: Float,
        length2: Float,
        pole: Vector3,
        constraint: IKConstraint,
        out: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        // Composition intent, never realization: the authored pole must be explicit (a zero pole
        // is rejected) because default-pole derivation belongs to the registered limb solvers, and
        // doing it here would mimic their ownership.
        if (pole.mag() < 1e-4f) {
            throw IllegalArgumentException(
                "planLimbPlacement requires an explicit non-zero pole (composition intent " +
                    "belongs to the pose; deriving a default here would mimic solver " +
                    "ownership — R13/§12.4b)"
            )
        }
        return SkeletonMath.solveIK(
            rootWorldPos, targetWorldPos, length1, length2, pole, constraint, out
        )
    }


    /**
     * Declares the coarse posture [kind] so the [ConstraintSolver] can seed the pelvis height
     * instead of the pose hand-computing `pelvisY`/`pelvisX`.
     *
     * [precedence] lists contact end joints in priority order (index 0 wins); an empty list means
     * all contacts are equal. [tolerance] is how strictly the solver must honour the intent before
     * the PELVIS_INTENT rule flags a residual.
     */
    protected fun declarePosture(
        pose: SkeletonPose,
        kind: PostureIntent.Kind,
        tolerance: Float = 0f,
        precedence: List<Joint> = emptyList()
    ) {
        // Sole-mutator builder: the intent carriers are private-set on SkeletonPose.
        IntentBuilder(pose).posture(kind, tolerance, precedence)
    }

    protected fun leftFoot(): SupportContact = SupportContact.LEFT_FOOT
    protected fun rightFoot(): SupportContact = SupportContact.RIGHT_FOOT
    protected fun bothFeet(): Set<SupportContact> = setOf(leftFoot(), rightFoot())
    protected fun leftKnee(): SupportContact = SupportContact.LEFT_KNEE
    protected fun rightKnee(): SupportContact = SupportContact.RIGHT_KNEE
    protected fun hands(): Set<SupportContact> = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
}

/**
 * Pelvis tilt (root rotation) recorded as a [Joint.PELVIS] intent on [buffer] so the Finalizer
 * applies it idempotently. Package-level so poses implementing [PoseBuilder] directly can use it
 * too; the node write is kept for build-time FK and the carrier reproduces the same rotation.
 */
fun declarePelvisTilt(pelvis: SkeletonNode, buffer: SkeletonPose, axis: Vector3, angle: Float) {
    pelvis.localRotation.set(axis, angle)
    SkeletonPose.IntentBuilder(buffer).joint(Joint.PELVIS, JointRotation(axis, angle))
}

/**
 * Package-level form of [BasePose.bakeIkLimb] for poses implementing [PoseBuilder] directly, so
 * they route through the engine's single IK path instead of calling [SkeletonMath.solveIK] and
 * skipping the `limbTargets` carrier, `maxIkClampAmount` and the `boneLengthsVerified` stamp —
 * without which the engine stage and the reachability/contact diagnostics have no data for them.
 * Local positions are unchanged (byte-identical for identity-rotation parents).
 */
private val bakeIkScratch1 = Vector3()
private val bakeIkScratchPole = Vector3()

/**
 * Package-level form of [BasePose.planLimbPlacement] for poses implementing [PoseBuilder]
 * directly. Same contract: composition only — no registration, no node writes, no stamps, no
 * realization count. Using the result as final geometry is a second Active Limb Solver and is
 * audited.
 */
fun planLimbPlacement(
    rootWorldPos: Vector3,
    targetWorldPos: Vector3,
    length1: Float,
    length2: Float,
    pole: Vector3,
    constraint: IKConstraint,
    out: SkeletonMath.IKResult
): SkeletonMath.IKResult {
    // See [BasePose.planLimbPlacement]: composition only, explicit pole required.
    if (pole.mag() < 1e-4f) {
        throw IllegalArgumentException(
            "planLimbPlacement requires an explicit non-zero pole (composition intent " +
                "belongs to the pose; deriving a default here would mimic solver " +
                "ownership — R13/§12.4b)"
        )
    }
    return SkeletonMath.solveIK(
        rootWorldPos, targetWorldPos, length1, length2, pole, constraint, out
    )
}

fun bakeIkLimb(
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
    buffer: SkeletonPose,
    straight: Boolean = false,
    contact: ContactConstraint? = null
): SkeletonMath.IKResult {
    val parentRot = if (middleNode.parent != null) middleNode.parent!!.worldRotation else parentRotation
    // Mirrors [BasePose.bakeIkLimb]: the first limb baked this build re-arms the optimistic `true`.
    if (buffer.isTransformsUpdated) {
        buffer.boneLengthsVerified = true
        // The dropped-flag re-arm sits in the same fresh window, as in the member path.
        buffer.straightIntentDropped = false
        buffer.isTransformsUpdated = false
    }
    buffer.limbTargets.add(
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
    if (contact != null) {
        val chain = ConstraintSolver.chainForEnd(endNode.joint)
        if (chain != null) {
            buffer.contacts.add(
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
    // Registration above is unconditional; realization runs only while the engine stage is off.
    if (IK_STAGE_ACTIVE) return ikBuffer
    buffer.registerLimbRealization(endNode.joint, authoringWindow = true)
    val worldPole = if (pole.mag() < 1e-4f) {
        SkeletonMath.deriveDefaultPole(rootWorldPos, targetWorldPos, bakeIkScratchPole)
    } else {
        pole
    }
    val ikResult = if (straight) {
        SkeletonMath.solveStraightLimb(rootWorldPos, targetWorldPos, length1, length2, constraint, ikBuffer, contact)
    } else {
        SkeletonMath.solveIK(rootWorldPos, targetWorldPos, length1, length2, worldPole, constraint, ikBuffer, contact)
    }
    buffer.maxIkClampAmount =
        ValidationStampMerge.clamp(buffer.maxIkClampAmount, ikResult.clampAmount)
    // Mirrors the member path's straight-intent fold.
    buffer.straightIntentDropped =
        ValidationStampMerge.dropped(
            buffer.straightIntentDropped,
            if (straight) ikResult.straightIntentDropped else false
        )
    val bonesOk = SkeletonMath.bonesExact(rootWorldPos, ikResult.joint, ikResult.end, length1, length2)
    buffer.boneLengthsVerified =
        ValidationStampMerge.verified(buffer.boneLengthsVerified, bonesOk)

    bakeIkScratch1.set(ikResult.joint).subtract(rootWorldPos)
    SkeletonMath.toLocalDirection(bakeIkScratch1, parentRot, middleNode.localPosition)
    bakeIkScratch1.set(ikResult.end).subtract(ikResult.joint)
    SkeletonMath.toLocalDirection(bakeIkScratch1, parentRot, endNode.localPosition)

    return ikResult
}
