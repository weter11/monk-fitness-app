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
    protected val axisX = Vector3(1f, 0f, 0f)
    protected val axisY = Vector3(0f, 1f, 0f)
    protected val axisZ = Vector3(0f, 0f, 1f)
    protected val tempV1 = Vector3()
    protected val tempV2 = Vector3()
    protected val tempV3 = Vector3()
    protected val tempPoleWorld = Vector3()

    // Scratch rotations/matrices for composing a full 3-D chest orientation (no hot-path allocation).
    private val chestRotX = JointRotation(Vector3(1f, 0f, 0f), 0f)
    private val chestRotY = JointRotation(Vector3(0f, 1f, 0f), 0f)
    private val chestRotZ = JointRotation(Vector3(0f, 0f, 1f), 0f)
    // Column buffers: Rz, Ry, (Rz·Ry), Rx, final — reused across the two multiplies.
    private val czX = Vector3(); private val czY = Vector3(); private val czZ = Vector3()
    private val cyX = Vector3(); private val cyY = Vector3(); private val cyZ = Vector3()
    private val cmX = Vector3(); private val cmY = Vector3(); private val cmZ = Vector3()
    private val cxX = Vector3(); private val cxY = Vector3(); private val cxZ = Vector3()
    private val cfX = Vector3(); private val cfY = Vector3(); private val cfZ = Vector3()

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

    /**
     * Authors a thoracic **twist** of the chest about its local +Y (spine long) axis as a
     * real 3-D local rotation. The chest is the IK root for both arms, so the whole upper
     * chain (rib cage, neck, head and both shoulders) follows the twist via FK.
     */
    protected fun buildChestTwist(chest: SkeletonNode, twistRad: Float) {
        chest.localRotation.set(axisY, twistRad)
    }

    /**
     * Authors a thoracic **side-bend** of the chest about its local +X (lateral) axis as a
     * real 3-D local rotation. Captured and propagated to the shoulders/arms/neck/head by FK.
     */
    protected fun buildChestSideBend(chest: SkeletonNode, sideBendRad: Float) {
        chest.localRotation.set(axisX, sideBendRad)
    }

    /**
     * Authors a **full 3-D** chest orientation by composing sagittal lean (local +Z),
     * thoracic twist (local +Y) and side-bend (local +X) into a single axis-angle
     * [JointRotation]: `R = Rz(lean) · Ry(twist) · Rx(sideBend)`. Any subset may be zero.
     * Reuses the existing matrix-multiply utilities (no duplicated rotation math) so the
     * combined rotation is exact and allocation-free.
     */
    protected fun buildChestOrientation(
        chest: SkeletonNode,
        leanRad: Float,
        twistRad: Float,
        sideBendRad: Float
    ) {
        chestRotZ.set(axisZ, leanRad)
        chestRotY.set(axisY, twistRad)
        chestRotX.set(axisX, sideBendRad)
        // Rz
        SkeletonMath.rotationToMatrix(chestRotZ, czX, czY, czZ)
        // Ry
        SkeletonMath.rotationToMatrix(chestRotY, cyX, cyY, cyZ)
        // Rz · Ry -> intermediate
        SkeletonMath.multiplyMatrices(czX, czY, czZ, cyX, cyY, cyZ, cmX, cmY, cmZ)
        // Rx
        SkeletonMath.rotationToMatrix(chestRotX, cxX, cxY, cxZ)
        // (Rz·Ry) · Rx -> final
        SkeletonMath.multiplyMatrices(cmX, cmY, cmZ, cxX, cxY, cxZ, cfX, cfY, cfZ)
        SkeletonMath.getRotationFromMatrix(cfX, cfY, cfZ, chest.localRotation)
    }

    protected fun buildRigidSegment(parent: SkeletonNode, child: SkeletonNode, offsetX: Float, offsetY: Float, offsetZ: Float) {
        child.localPosition.set(offsetX, offsetY, offsetZ)
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
    }

    /**
     * Authors a **lower-spine (lumbar / pelvis-tilt)** rotation on the LUMBAR segment of the
     * two-segment spine (PELVIS -> LUMBAR -> CHEST). Because the lumbar sits below the chest,
     * FK carries the whole thorax + shoulder girdle + arms with it, and
     * `SkeletonPoseFinalizer.reconstructChestFrame` composes this lower-spine rotation with the
     * thoracic frame — so patterns where the pelvis/lumbar and thorax move differently
     * (hip hinge, good morning, deadlift, cat-cow, thoracic opener) become real joint motion
     * instead of one overall trunk bend (Issue E).
     *
     * The lumbar defaults to a pass-through (identity), so leaving it unset reproduces the old
     * single PELVIS->CHEST bend exactly. Common axes: local +Z sagittal flexion/extension,
     * +Y axial rotation, +X lateral (side) bend.
     */
    protected fun buildLumbarFlexion(lumbar: SkeletonNode, flexionRad: Float, axis: Vector3 = axisZ) {
        lumbar.localRotation.set(axis, flexionRad)
    }

    /**
     * Authors a **two-segment spine curve**: a lower-spine (lumbar) rotation and a thoracic
     * (chest) rotation about the same [axis], letting the two segments differ. Passing equal
     * values reproduces a single overall bend split across the spine; passing e.g. a lumbar of
     * zero with a thoracic value expresses "thoracic extends while the lumbar stays" (Issue E).
     */
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
    }

    /**
     * Authors a **2-DOF wrist** articulation on the hand node (UNI-8): flexion/extension composed
     * with radial/ulnar deviation into one exact local rotation the FK traversal propagates and the
     * hand completion honours. Combining the two axes is now first-class instead of one silently
     * overwriting the other. Leaving both at zero keeps the wrist at identity (rigid extension of
     * the forearm). Delegates to [SkeletonMath.buildWristRotation] (no duplicated rotation math).
     */
    protected fun buildWristArticulation(hand: SkeletonNode, flexion: Float, deviation: Float) {
        SkeletonMath.buildWristRotation(flexion, deviation, hand.localRotation)
    }

    /**
     * Authors a **2-DOF ankle** articulation on the ankle node (UNI-8): dorsi/plantar-flexion
     * composed with inversion/eversion into one exact local rotation. Combining the two axes is
     * now first-class instead of one silently overwriting the other. Leaving both at zero keeps the
     * ankle at identity (flat foot). Delegates to [SkeletonMath.buildAnkleRotation].
     */
    protected fun buildAnkleArticulation(ankle: SkeletonNode, dorsiflexion: Float, inversion: Float) {
        SkeletonMath.buildAnkleRotation(dorsiflexion, inversion, ankle.localRotation)
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
     * Authors hip **flexion/extension** (sagittal plane, about the mediolateral Z axis) on a hip
     * ball-joint node (UNI-10). This is the acetabular ball joint; FK carries the whole leg with it.
     * The single, documented authoring path replaces ad-hoc raw `hip.localRotation` writes.
     */
    protected fun buildHipFlexion(hip: SkeletonNode, flexionRad: Float) {
        hip.localRotation.set(axisZ, flexionRad)
    }

    /**
     * Authors hip **abduction/adduction** (frontal plane, about the antero-posterior Y axis) on a
     * hip ball-joint node (UNI-10). [sideSign] (-1 left / +1 right) mirrors the motion across the
     * mid-line so a positive value abducts (spreads) both legs symmetrically.
     */
    protected fun buildHipAbduction(hip: SkeletonNode, abductionRad: Float, sideSign: Float) {
        hip.localRotation.set(axisY, abductionRad * sideSign)
    }

    /**
     * Authors hip **internal/external (femoral axial) rotation** about the femur's long X axis on
     * a hip ball-joint node (UNI-10). This is the acetabular ball joint's axial DOF, kept *separate*
     * from the IK pole (which only selects the knee-bend plane). [sideSign] (-1 left / +1 right)
     * mirrors the twist across the mid-line.
     */
    protected fun buildHipRotation(hip: SkeletonNode, rotationRad: Float, sideSign: Float) {
        hip.localRotation.set(axisX, rotationRad * sideSign)
    }

    /**
     * Authors a **full 3-DOF hip** orientation by composing flexion, abduction and femoral axial
     * rotation into a single exact [JointRotation] (UNI-10), mirroring [buildChestOrientation].
     * Any subset may be zero. [sideSign] (-1 left / +1 right) mirrors abduction and axial rotation.
     * Delegates to [SkeletonMath.buildHipRotation] (no duplicated rotation math). ROM vocabulary is
     * [HipRomLimits]; enforcement stays in the validator's `HIP_ROM_LIMIT` rule.
     */
    protected fun buildHipOrientation(
        hip: SkeletonNode,
        flexionRad: Float,
        abductionRad: Float,
        rotationRad: Float,
        sideSign: Float
    ) {
        SkeletonMath.buildHipRotation(flexionRad, abductionRad, rotationRad, sideSign, hip.localRotation)
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

    // --- Frame-relative IK overloads: the pole is authored in the limb-root's LOCAL frame
    //     (chest/pelvis) and is transformed into world space via the parent's current world
    //     rotation. The analytical solver is unchanged. Phase 1 (F4): DEPRECATED — IK is
    //     world-only; convert the pole (or deriveDefaultPole) before solving. Kept until Phase 3. ---

    @Deprecated(
        "Phase 1: IK is world-only. Convert the pole with SkeletonMath.toWorldDirection (or deriveDefaultPole) and call the world-space solveArmIK overload.",
        ReplaceWith(
            "solveArmIK(shoulderW, targetHand, upperArmLen, forearmLen, SkeletonMath.toWorldDirection(poleLocal, parentRotation, tempPoleWorld), constraint, result)",
            "com.monkfitness.app.animation.SkeletonMath"
        )
    )
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

    @Deprecated(
        "Phase 1: IK is world-only. Convert the pole with SkeletonMath.toWorldDirection (or deriveDefaultPole) and call the world-space solveLegIK overload.",
        ReplaceWith(
            "solveLegIK(hipW, targetAnkle, thighLen, shinLen, SkeletonMath.toWorldDirection(poleLocal, parentRotation, tempPoleWorld), constraint, result)",
            "com.monkfitness.app.animation.SkeletonMath"
        )
    )
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

    /**
     * Phase 2 (F2/F7) — declares the coarse posture intent the [ConstraintSolver] should honour
     * (seeding the root/pelvis height) and the contact-conflict precedence order. This is the
     * pose-side half of moving root ownership into the engine: instead of hand-computing `pelvisY`/
     * `pelvisX`, the pose names the posture (SEATED_NEAR_FLOOR / HANGING_UNDER_BAR / STANDING /
     * CUSTOM) and lets the solver derive the exact pelvis; when the solver owns posture
     * ([EngineFlags.SOLVER_OWNS_POSTURE]) the seed replaces the authored root arithmetic.
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
        pose.postureIntent = PostureIntent(kind, tolerance)
        pose.contactPrecedence.clear()
        for (j in precedence) pose.contactPrecedence.add(j.name)
    }

    // Common Support helpers building SupportContact collections allocation-free
    protected fun leftFoot(): SupportContact = SupportContact.LEFT_FOOT
    protected fun rightFoot(): SupportContact = SupportContact.RIGHT_FOOT
    protected fun bothFeet(): Set<SupportContact> = setOf(leftFoot(), rightFoot())
    protected fun leftKnee(): SupportContact = SupportContact.LEFT_KNEE
    protected fun rightKnee(): SupportContact = SupportContact.RIGHT_KNEE
    protected fun hands(): Set<SupportContact> = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
}
