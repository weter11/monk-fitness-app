package com.monkfitness.app.animation

import com.monkfitness.app.animation.SkeletonPose.IntentBuilder

/**
 * BasePose serves as a generalized biomechanical framework for all exercise poses,
 * abstracting and consolidating shared scratch buffers, body construction helpers,
 * IK wrappers, motion driver helpers, and support definitions.
 */
abstract class BasePose : PoseBuilder {

    // Common scratch buffers (allocation-free, thread-safe, reusable)
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

    // Common body construction helpers
    protected fun buildTorso(pelvis: SkeletonNode, chest: SkeletonNode, torsoLength: Float) {
        chest.localPosition.set(-torsoLength, 0f, 0f)
    }


    /**
     * Phase 7 (Gap 7 / F8 / W17) — declares the gaze as a world-space [HeadTarget] intent that
     * the Finalizer ([SkeletonPoseFinalizer.resolveHeadTarget]) resolves into the neck/head local
     * offsets. The head is written by the Finalizer resolver alone — the pose only *declares* the
     * target. This path was verified byte-identical to the legacy direction path
     * (`HeadTargetBaselineTest`, maxDeviation ~6e-5 across every gaze pose family), so the legacy
     * `buildHead` fallback that previously ran when the `HEAD_TARGET_ENABLED` flag was off has been
     * removed (Phase 7 complete). The head-orientation math is now inlined in the resolver.
     *
     * @param gazeDir the authored world-space gaze direction; a synthetic [HeadTarget] is recorded
     *   a fixed distance along it from the neck's current world position for the Finalizer to resolve.
     */
    protected fun buildGaze(
        neck: SkeletonNode,
        head: SkeletonNode,
        neckLength: Float,
        gazeDir: Vector3,
        targetDistance: Float = 100f
    ) {
        // Record the additive intent carrier (synthetic target along the authored gaze direction).
        tempV1.set(gazeDir)
        if (tempV1.mag() < 1e-4f) tempV1.set(0f, 1f, 0f) else tempV1.normalize()
        val nw = neck.worldPosition
        tempV2.set(nw.x + tempV1.x * targetDistance, nw.y + tempV1.y * targetDistance, nw.z + tempV1.z * targetDistance)
        // The head is resolved by SkeletonPoseFinalizer.resolveHeadTarget (single source of truth).
        // Declared through the sole-mutator IntentBuilder (B0 compile guard: §1.1 carriers are
        // private-set on SkeletonPose, so only the builder may write them).
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
     * Authors a thoracic **twist** of the chest about its local +Y (spine long) axis as a
     * real 3-D local rotation. The chest is the IK root for both arms, so the whole upper
     * chain (rib cage, neck, head and both shoulders) follows the twist via FK.
     */
    /**
     * B2 (RFC_BRANCH_B_IMPLEMENTATION §2) — every trunk/hip/girdle/extremity helper now records its
     * intent into the §1.1 `jointIntents` (chest/hip/girdle/ankle/wrist) and `spineIntent` carriers
     * via the sole-mutator `IntentBuilder`, in addition to writing the node's `localRotation`. The
     * Finalizer ([SkeletonPoseFinalizer.applyIntentCarriers]) consumes these carriers and re-derives
     * the node rotations, making them live (the dead→live flip that completes B2). The node write is
     * retained so build-time logic that reads a node's world transform (e.g. arm IK under a rotating
     * chest) keeps working; the Finalizer re-application is idempotent, so output stays byte-identical.
     */
    protected fun declareJointIntent(joint: Joint, rotation: JointRotation) {
        IntentBuilder(jointsBuffer).joint(joint, rotation)
    }

    /**
     * Branch C (RFC_BRANCH_C_EXTREMITY_ARTICULATION) — authors a **wrist articulation** (grip) as
     * the §1.3 [Extremity] intent. Composes the 2-DOF local rotation from [flexion] (flexion/
     * extension about the mediolateral Z axis) and [deviation] (radial/ulnar deviation about the
     * antero-posterior Y axis) via [SkeletonMath.buildWristRotation], so combined DOFs never drop
     * one axis. The mixed-mode contract (mirroring Branch B): the wrist node's `localRotation` is
     * written for build-time FK (and so the carrier value is byte-identical to the node path), and
     * the articulation is recorded into `extremityArticulations[HAND_A/HAND_P]` for the Finalizer
     * (W1) to consume as the single source of truth.
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
     * Branch C — authors an **ankle articulation** (foot plant) as the §1.3 [Extremity] intent.
     * Composes the 2-DOF local rotation from [dorsiflexion] (dorsi/plantar-flexion about the
     * mediolateral Z axis) and [inversion] (inversion/eversion about the long X axis) via
     * [SkeletonMath.buildAnkleRotation]. Mixed-mode: writes the ankle node `localRotation` for
     * build-time FK and records `extremityArticulations[FOOT_F/FOOT_B]` for W1 to consume.
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

    protected fun buildChestTwist(chest: SkeletonNode, twistRad: Float) {
        chest.localRotation.set(axisY, twistRad)
        declareJointIntent(Joint.CHEST, JointRotation(axisY, twistRad))
    }

    /**
     * Authors a **clavicular** rotation on the proximal shoulder-girdle node, between the
     * chest and the scapula (CHEST -> CLAVICLE -> SCAPULA -> SHOULDER). Elevation/depression
     * (transverse X), protraction/retraction (vertical Y) and axial rotation (long Z) are real
     * joint rotations that lift and carry the whole shoulder (glenoid) on overhead reaches —
     * closing the clavicle-is-a-dead-node gap (UNI-7). Writes the composed rotation into the
     * clavicle's [SkeletonNode.localRotation]; FK propagates it to the scapula and shoulder.
     * [sideSign] mirrors elevation across the body mid-line (-1 left/active, +1 right/passive).
     */
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
     * Authors a **two-segment spine curve**: a lower-spine rotation and a thoracic
     * (chest) rotation about the same [axis], letting the two segments differ. Passing equal
     * values reproduces a single overall bend split across the spine; passing e.g. a lower
     * value of zero with a thoracic value expresses "thoracic extends while the lower back
     * stays" (Issue E).
     *
     * [lower] is the node that carries the lower-spine rotation. In the standard skeleton the
     * hips attach to the PELVIS, so the lower trunk tilt MUST live on `pelvis` (not on `LUMBAR`)
     * to keep the hips/legs inheriting the bend — otherwise planted feet drift. Pass
     * `nodes.pelvis` here for pelvis-rooted trunks and `nodes.lumbar` only when the hips are
     * themselves children of the lumbar. [chest] is always the CHEST node.
     *
     * Phase 5 (W13/G4, W14/G5): this is the SINGLE authorized way to express a trunk lean.
     * A pose must never hand-write both `pelvis.localRotation` and `chest.localRotation` as
     * independent angles (migration rule B7 / frozen A2) — call this once instead.
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
        // B2: the single declarative spine curve (lower + thoracic about a shared axis). The
        // Finalizer consumes `spineIntent` to re-derive the pelvis/lumbar/chest rotation; the
        // concrete node writes are also recorded per-joint in `jointIntents` so the unified
        // consumer covers both segments unambiguously.
        IntentBuilder(jointsBuffer).spine(lowerRad, thoracicRad, axis)
        declareJointIntent(lower.joint, JointRotation(axis, lowerRad))
        declareJointIntent(chest.joint, JointRotation(axis, thoracicRad))
    }

    /**
     * W1 — opt an [extremity] out of engine-derived orientation and into an explicit author
     * override. After this call the engine preserves the endpoint local positions the pose wrote on
     * the HEEL/TOE (or PALM/KNUCKLES/FINGERTIPS) nodes verbatim, instead of deriving them from the
     * limb + ankle/wrist articulation. Use only for stylized extremities the default derivation
     * cannot express (pointed toe, curled grip). Poses that describe pure anatomy never call this —
     * they get the default automatic derivation, restoring the pose→engine ownership boundary.
     */
    protected fun overrideExtremityOrientation(pose: SkeletonPose, extremity: Extremity) {
        pose.overrideExtremityOrientation(extremity)
    }

    /**
     * Declares the root-relative (pelvis-frame) forward direction of an extremity.
     * Exercise intent: use when the constraint system admits multiple valid orientations
     * and the exercise needs to select one (e.g. planted hand on the floor). The Finalizer
     * transforms this to world space using the solved pelvis rotation and projects it onto
     * the support plane.
     */
    protected fun setHeading(extremity: Extremity, rootRelativeDir: Vector3) {
        SkeletonPose.HeadingBuilder(jointsBuffer).set(extremity, rootRelativeDir)
    }

    /**
     * Authors hip **flexion/extension** (sagittal plane, about the mediolateral Z axis) on a hip
     * ball-joint node (UNI-10). This is the acetabular ball joint; FK carries the whole leg with it.
     * The single, documented authoring path replaces ad-hoc raw `hip.localRotation` writes.
     */
    protected fun buildHipFlexion(hip: SkeletonNode, flexionRad: Float) {
        hip.localRotation.set(axisZ, flexionRad)
        // B2: hip flexion intent (jointIntents).
        declareJointIntent(hip.joint, JointRotation(axisZ, flexionRad))
    }

    /**
     * Authors hip **internal/external (femoral axial) rotation** about the femur's long X axis on
     * a hip ball-joint node (UNI-10). This is the acetabular ball joint's axial DOF, kept *separate*
     * from the IK pole (which only selects the knee-bend plane). [sideSign] (-1 left / +1 right)
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
        // Phase 1 (F5): reset the bone-length stamp once per build. `isTransformsUpdated` is set
        // true by the previous frame's finalize; the first limb baked this build clears it and
        // re-arms the optimistic `true` so the AND across limbs starts fresh.
        if (jointsBuffer.isTransformsUpdated) {
            jointsBuffer.boneLengthsVerified = true
            jointsBuffer.isTransformsUpdated = false
        }
        // B1 (IkStage extraction) — forward the end joint + world target into the §1.1
        // `limbTargets` carrier so the engine-owned IkStage can consume it (dead→live flip).
        // `bakeIkLimb` remains the sole solver while IK_STAGE_ACTIVE is false, so
        // this record is additive and byte-identical on its own.
        // B1 (IkStage extraction) — forward the end joint + full IK context into the §1.1
        // `limbTargets` carrier so the engine-owned IkStage can reproduce this solve byte-for-byte
        // (dead→live flip). `bakeIkLimb` remains the sole solver while IK_STAGE_ACTIVE is
        // false, so this record is additive and byte-identical on its own.
        jointsBuffer.limbTargets.add(
            WorldTarget(
                endNode.joint,
                Vector3(targetWorldPos.x, targetWorldPos.y, targetWorldPos.z),
                Vector3(pole.x, pole.y, pole.z),
                straight,
                contact
            )
        )

        // Phase 1 (F6): a zero-length pole means the pose omitted one — derive the default world
        // pole so the bend plane is always well-defined (the engine owns this, not the pose).
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

        // Store the limb offsets in the parent's true local frame so they survive the parent's
        // full 3D world rotation exactly — no hand-fed inverse-Z scalar.
        tempV1.set(ikResult.joint).subtract(rootWorldPos)
        SkeletonMath.toLocalDirection(tempV1, parentRot, middleNode.localPosition)

        tempV1.set(ikResult.end).subtract(ikResult.joint)
        SkeletonMath.toLocalDirection(tempV1, parentRot, endNode.localPosition)

        // PR-04: if this limb carries a fixed support contact, register it so the global
        // constraint solver can reposition the root and re-bake the limb to honor the contact.
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

        return ikResult
    }

    // Common Motion helpers (internally utilizing stateless MotionDrivers)
    protected fun phase(progress: Float): Float = progress
    protected fun downMotion(progress: Float): Float = MotionDrivers.PushPhase(progress)
    protected fun alternating(progress: Float): AlternatingMotion = MotionDrivers.alternating(progress)
    protected fun parabolicFootLift(t: Float): Float = MotionDrivers.ParabolicLift(t)

    /**
     * Phase 2 (F2/F7) — declares the coarse posture intent the [ConstraintSolver] should honour
     * (seeding the root/pelvis height) and the contact-conflict precedence order. This is the
     * pose-side half of moving root ownership into the engine: instead of hand-computing `pelvisY`/
     * `pelvisX`, the pose names the posture (SEATED_NEAR_FLOOR / HANGING_UNDER_BAR / STANDING /
     * CUSTOM) and lets the solver derive the exact pelvis; when the solver owns posture
     * the solver (when it owns posture) seeds the pelvis from the declared PostureIntent, replacing the authored root arithmetic.
     *
     * [precedence] lists contact end-joint names in priority order (index 0 wins); an empty list
     * means all contacts are equal. [tolerance] scopes how strictly the solver must honour the
     * intent before the PELVIS_INTENT rule flags a residual.
     */
    protected fun declarePosture(
        pose: SkeletonPose,
        kind: PostureIntent.Kind,
        tolerance: Float = 0f,
        precedence: List<Joint> = emptyList()
    ) {
        // Route through the sole-mutator IntentBuilder (B0 compile guard).
        IntentBuilder(pose).posture(kind, tolerance, precedence)
    }

    // Common Support helpers building SupportContact collections allocation-free
    protected fun leftFoot(): SupportContact = SupportContact.LEFT_FOOT
    protected fun rightFoot(): SupportContact = SupportContact.RIGHT_FOOT
    protected fun bothFeet(): Set<SupportContact> = setOf(leftFoot(), rightFoot())
    protected fun leftKnee(): SupportContact = SupportContact.LEFT_KNEE
    protected fun rightKnee(): SupportContact = SupportContact.RIGHT_KNEE
    protected fun hands(): Set<SupportContact> = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
}

/**
 * B4 — authors a pelvis tilt (root rotation) and records it as a [Joint.PELVIS] joint intent on
 * [buffer] so the Finalizer (B2) consumes it idempotently. Package-level (not a [BasePose] member)
 * so both [BasePose] subclasses and poses that implement [PoseBuilder] directly can call it.
 *
 * Mirrors the mixed-mode form of [buildHipFlexion]/[buildChestTwist]: the node is still written for
 * build-time FK and the carrier reproduces the rotation, so output is byte-identical to a bare
 * `pelvis.localRotation.set`. Closes the last bare `pelvis.localRotation.set` gap in the B4 migration.
 */
fun declarePelvisTilt(pelvis: SkeletonNode, buffer: SkeletonPose, axis: Vector3, angle: Float) {
    pelvis.localRotation.set(axis, angle)
    SkeletonPose.IntentBuilder(buffer).joint(Joint.PELVIS, JointRotation(axis, angle))
}

/**
 * Package-level IK bake that mirrors [BasePose.bakeIkLimb] so poses implementing [PoseBuilder]
 * directly (the upper-body / dynamic family) can route through the engine's single IK solver path.
 *
 * This closes the H2 stabilization gap: those poses previously called [SkeletonMath.solveIK]
 * directly, which skipped the §1.1 `limbTargets` carrier, the `maxIkClampAmount` recording, and the
 * `boneLengthsVerified` stamp — so the engine-owned [IkStage] and the reachability/contact
 * diagnostics had no data for them. Routing through this helper reproduces the exact same
 * `middleNode`/`endNode` local positions (byte-identical for identity-rotation parents) while
 * populating those carriers, making the family consistent with every other production pose.
 */
private val bakeIkScratch1 = Vector3()
private val bakeIkScratchPole = Vector3()

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
    if (buffer.isTransformsUpdated) {
        buffer.boneLengthsVerified = true
        buffer.isTransformsUpdated = false
    }
    buffer.limbTargets.add(
        WorldTarget(
            endNode.joint,
            Vector3(targetWorldPos.x, targetWorldPos.y, targetWorldPos.z),
            Vector3(pole.x, pole.y, pole.z),
            straight,
            contact
        )
    )
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
    if (ikResult.clampAmount > buffer.maxIkClampAmount) {
        buffer.maxIkClampAmount = ikResult.clampAmount
    }
    val bonesOk = SkeletonMath.bonesExact(rootWorldPos, ikResult.joint, ikResult.end, length1, length2)
    buffer.boneLengthsVerified = buffer.boneLengthsVerified && bonesOk

    bakeIkScratch1.set(ikResult.joint).subtract(rootWorldPos)
    SkeletonMath.toLocalDirection(bakeIkScratch1, parentRot, middleNode.localPosition)
    bakeIkScratch1.set(ikResult.end).subtract(ikResult.joint)
    SkeletonMath.toLocalDirection(bakeIkScratch1, parentRot, endNode.localPosition)

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
    return ikResult
}
