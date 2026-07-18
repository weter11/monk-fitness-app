package com.monkfitness.app.animation

/**
 * The four extremities whose heel/toe or palm/fingertip geometry the engine derives.
 *
 * W1 (Restore Engine Ownership of Extremity Orientation): ownership of an extremity's
 * orientation is now expressed **explicitly** per-extremity on the [SkeletonPose], rather than
 * inferred from whether the endpoint nodes (HEEL/TOE, PALM/FINGERTIPS) happen to exist in the
 * hierarchy. The skeleton factory always creates those nodes, so node-existence was never a
 * valid ownership signal.
 */
enum class Extremity {
    FOOT_F,
    FOOT_B,
    HAND_A,
    HAND_P
}

/**
 * Who owns an extremity's heel/toe or palm/fingertip geometry (W1).
 *
 * - [AUTOMATIC] — the pose describes anatomy only (limb target + ankle/wrist articulation) and the
 *   **engine derives** the extremity geometry from the limb direction, the relative ankle/wrist
 *   articulation and the [FootDefinition]/[HandDefinition] anatomy. This is the default and the
 *   restored architectural boundary: *pose authors describe anatomy, the engine derives geometry.*
 * - [MANUAL_OVERRIDE] — the pose has **explicitly** authored the endpoint local positions and asks
 *   the engine to preserve them verbatim (e.g. a stylized pointed toe or curled grip that the
 *   default derivation cannot express). The engine leaves the authored values untouched.
 */
enum class ExtremityOrientationMode {
    AUTOMATIC,
    MANUAL_OVERRIDE
}

/**
 * Phase 0 (Architecture v2 §1.1) — typed coarse posture intent declared by a pose and
 * interpreted by the [ConstraintSolver]. It names the global body arrangement the pose is
 * aiming for so the Solver can derive an exact pelvis/root without the pose hand-computing
 * root arithmetic. Resolves finding F2.
 *
 * @param kind the coarse posture family.
 * @param tolerance how strictly the solver must honour the intent before flagging a residual
 *   (passed through as a soft bound; zero means "follow authored shape exactly").
 */
data class PostureIntent(
    val kind: Kind,
    val tolerance: Float = 0f
) {
    enum class Kind {
        /** Sitting low with the pelvis near the floor (e.g. squat, seated stretch). */
        SEATED_NEAR_FLOOR,
        /** Hanging suspended beneath an overhead bar (e.g. dead hang, pull-up). */
        HANGING_UNDER_BAR,
        /** Upright, feet on the floor (e.g. standing press, march). */
        STANDING,
        /** Pose supplies its own posture semantics; solver must not impose a template. */
        CUSTOM
    }
}

/**
 * Phase 0 (Architecture v2 §1.1) — a single declarative spine curve: one call replaces the
 * legacy coupled pelvis+chest dual writes. Authored by the pose, consumed by the engine.
 *
 * @param lumbarRad lumbar flexion/extension in radians (signed).
 * @param thoracicRad thoracic flexion/extension in radians (signed).
 * @param axis the shared bend axis in the chest/pelvis parent frame.
 */
data class SpineCurve(
    val lumbarRad: Float = 0f,
    val thoracicRad: Float = 0f,
    val axis: Vector3 = Vector3(1f, 0f, 0f)
)

/**
 * Phase 0 (Architecture v2 §1.1) — a relative articulation of a single joint with respect to
 * its parent (chest/hip/girdle/ankle/wrist). Replaces raw `joint.localRotation.set` authoring
 * so the engine owns the geometry derivation.
 */
data class RelativeArticulation(
    val joint: Joint,
    val rotation: JointRotation
)

/**
 * Phase 0 (Architecture v2 §1.1) — a world-space target for a limb end-effector or intermediate
 * joint (hand/foot/knee/elbow/head). Authored by the pose; resolved by IK/ConstraintSolver and, in
 * B1, by the pipeline-owned `IkStage`.
 *
 * The carrier carries the full IK-solving context (not just the joint + world point) so the
 * `IkStage` can reproduce `bakeIkLimb` byte-for-byte without re-deriving intent the pose already
 * expressed: the `pole` (bend-plane direction), `straight` flag (rigid-segment solve) and the
 * optional `contact` (fixed support). The default values make the builder-facing
 * `IntentBuilder.limbTarget(joint, world)` ergonomic for non-contact, non-straight targets.
 *
 * @param joint the joint this target pins.
 * @param world the world-space position the joint should occupy.
 * @param pole the authored IK bend-plane pole (zero vector ⇒ the stage derives the default pole).
 * @param straight true ⇒ solve as a rigid (straight) limb segment.
 * @param contact non-null ⇒ this target is a fixed support contact (the pose also registers a
 *   `ContactSpec`; the stage reuses the exact `contact` so the ConstraintSolver re-bake matches).
 */
data class WorldTarget(
    val joint: Joint,
    val world: Vector3,
    val pole: Vector3 = Vector3(),
    val straight: Boolean = false,
    val contact: ContactConstraint? = null
)

/**
 * Phase 7 (Gap 7 / F8 / W17) — gaze-as-target carrier (§1.1 intent).
 *
 * The pose declares a world-space point the head should look at, instead of hand-authoring a
 * counter-rotated UP direction. The Finalizer ([SkeletonPoseFinalizer.resolveHeadTarget])
 * resolves neck/head from this target and is the sole head writer (Phase 7 complete — the legacy
 * gaze-direction path has been removed).
 *
 * @param world the world-space point the gaze should track.
 * @param upBias the neutral gaze-up bias used when deriving the head direction from the target
 *   (defaults to world up, matching the legacy upright gaze).
 */
data class HeadTarget(
    val world: Vector3,
    val upBias: Vector3 = Vector3(0f, 1f, 0f)
)

/**
 * Encapsulates the joint positions and rotations for a specific frame.
 *
 * ## Architecture v2 — single intent + state carrier (Phase 0)
 *
 * This is the one and only carrier of both pose **intent** (§1.1, written by Pose, read by
 * Engine) and derived **state** (§1.2, written by Engine, read by Validation). See
 * `docs/ARCHITECTURE_V2.md`. No component edits another's section.
 *
     * - **Intent section (§1.1):** `jointIntents`, `spineIntent`, `limbTargets`, `contacts`,
     *   `contactPrecedence`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`.
 * - **State section (§1.2):** `nodes` (the `joints`/`rotations`/`roots` triple),
 *   `maxIkClampAmount`, `straightIntentDropped`, `rootTranslationDelta`, `rootRotationDelta`,
 *   `boneLengthsVerified`.
 */
class SkeletonPose(
    val joints: Array<Vector3> = Array(Joint.entries.size) { Vector3() },
    val rotations: Array<JointRotation> = Array(Joint.entries.size) { JointRotation() },
    var roots: List<SkeletonNode> = emptyList(),
    var isTransformsUpdated: Boolean = false,
    var maxIkClampAmount: Float = 0f,
    // UNI-6 — how far the global solver displaced the root from its authored transform, so the
    // PELVIS_INTENT rule can surface unexpected root motion. Zero for non-contact (no-op) poses.
    var rootTranslationDelta: Float = 0f,
    var rootRotationDelta: Float = 0f,
    // Fixed support contacts registered by contact-bearing `bakeIkLimb` calls (PR-04). The
    // global constraint solver consumes these to reposition the root so every contact holds.
    val contacts: MutableList<ContactSpec> = mutableListOf()
) {

    // ---- §1.1 INTENT SECTION (written by Pose, read by Engine) ----------------------------

    /** Per-joint relative articulations (chest/hip/girdle/ankle/wrist) declared by the pose. */
    val jointIntents: MutableList<RelativeArticulation> = mutableListOf()

    /** Single declarative spine curve; replaces coupled pelvis+chest dual writes. */
    var spineIntent: SpineCurve = SpineCurve()
        private set

    /** World-space targets for limb end-effectors / intermediate joints (hand/foot/knee/elbow/head). */
    val limbTargets: MutableList<WorldTarget> = mutableListOf()

    /**
     * Ordered list of [ContactSpec] ids declaring which contacts win when the solver must resolve
     * a conflict (finding F7). Earlier entries take precedence. Empty means "all contacts equal".
     */
    val contactPrecedence: MutableList<String> = mutableListOf()

    /** Typed coarse posture intent (finding F2); interpreted by the [ConstraintSolver]. */
    var postureIntent: PostureIntent = PostureIntent(PostureIntent.Kind.CUSTOM)
        private set

    /**
     * Phase 0 — explicit opt-out set of extremities whose heel/toe or palm/fingertip geometry the
     * pose authors verbatim and the engine must NOT derive (stylized only). This is the canonical
     * §1.1 carrier; it is the source of truth for the W1 [ExtremityOrientationMode] plumbing below.
     */
    val extremityOverrides: MutableSet<Extremity> = mutableSetOf()

    /**
     * Branch C (RFC_BRANCH_C_EXTREMITY_ARTICULATION) — the §1.3 **Interaction / Articulation
     * Intent** carrier: the authored wrist/ankle rotation of each extremity, expressed
     * *relative to its parent segment* (forearm for the wrist, shank for the ankle), keyed by
     * [Extremity] (HAND_A / HAND_P / FOOT_F / FOOT_B). This is the single source of truth for the
     * W1 extremity geometry derivation; the Finalizer ([SkeletonPoseFinalizer]) reads it instead of
     * reading the [Joint.HAND_*]/[Joint.ANKLE_*] node `localRotation` back (the old Pose-write /
     * Finalizer-read round-trip through the node is retired). The value is exactly the
     * `JointRotation` `getJointRotation` used to return for the wrist/ankle node, so reading the
     * carrier is byte-identical to reading the node for the AUTOMATIC path. Never carries a world
     * rotation (the ancestor chain is already removed by the authoring helpers).
     */
    val extremityArticulations: MutableMap<Extremity, JointRotation> = mutableMapOf()

    /**
     * Phase 7 (Gap 7 / F8 / W17) — gaze-as-target (COMPLETE). The pose declares where the head
     * should look in world space; the Finalizer ([SkeletonPoseFinalizer.resolveHeadTarget])
     * resolves neck/head from this target, reusing [buildHead] math, and is now the sole head
     * writer (the legacy direction path was removed after byte-identity was proven). `null` means
     * "no gaze target declared" — a non-gaze pose whose head is left as authored.
     */
    var headTarget: HeadTarget? = null
        private set

    // ---- §1.2 STATE SECTION (written by Engine, read by Validation) -----------------------

    /** IK/Solver stamp: a straight-limb intent could not be honoured (the limb was re-baked bent). */
    var straightIntentDropped: Boolean = false

    /**
     * IK stamp: every solved limb exactly preserved its bone lengths (invariant F5). Optimistic
     * default `true`; each `bakeIkLimb` ANDs its per-limb check in, so a single violated limb
     * flips the whole pose to `false`. The IK path resets it at the start of each build.
     */
    var boneLengthsVerified: Boolean = true

    // B5 (RFC_BRANCH_B_IMPLEMENTATION) — §1.2 STATE stamps consumed by the validator.
    // Produced by the engine (SkeletonMath / Finalizer) from the solved skeleton; the validator
    // reads them directly and never re-derives geometry (no toLocalDirection/angleBetweenDegrees/atan2).

    /**
     * Engine-produced hip ROM stamp: the femur direction expressed in the pelvis frame, decomposed
     * into the four independent anatomical angles the [HipRomLimits] bound. One entry per hip
     * (Joint.HIP_F / Joint.HIP_B); absent until the engine writes it. Mirrors the validator's old
     * `validateHipRom` math exactly so the rule is byte-identical when reading the stamp.
     */
    val hipRomStamps: MutableMap<Joint, HipRomStamp> = mutableMapOf()

    /**
     * Engine-produced bilateral-symmetry stamp: the maximum knee-deviation magnitude difference
     * (left vs right) and the maximum elbow-deviation magnitude difference, in the same 2-D
     * perpendicular-deviation units the old `validateBilateralSymmetry` used. A sign flip (knees
     * bending opposite ways) is captured by the `oppositeBend` flag. Absent (0u, no violation)
     * until the engine writes it.
     */
    var bilateralSymmetryDelta: Float = 0f
    var bilateralOppositeBend: Boolean = false

    // W1 — explicit ownership of each extremity's heel/toe or palm/fingertip geometry, derived
    // from [extremityOverrides]. Defaults to AUTOMATIC (engine derives) for every extremity,
    // restoring the pose→engine boundary. Ownership is intentionally NOT inferred from
    // endpoint-node existence (the factory always creates those nodes).

    /** Ownership mode of [extremity]'s orientation. Defaults to [ExtremityOrientationMode.AUTOMATIC]. */
    fun getExtremityOrientationMode(extremity: Extremity): ExtremityOrientationMode =
        if (extremityOverrides.contains(extremity)) {
            ExtremityOrientationMode.MANUAL_OVERRIDE
        } else {
            ExtremityOrientationMode.AUTOMATIC
        }

    /**
     * Declares that [extremity]'s heel/toe or palm/fingertip geometry is authored by the pose and
     * must be preserved verbatim (see [ExtremityOrientationMode.MANUAL_OVERRIDE]). Poses that do not
     * call this get the default automatic engine derivation.
     */
    fun overrideExtremityOrientation(extremity: Extremity) {
        extremityOverrides.add(extremity)
    }

    /** True when [extremity]'s geometry should be derived by the engine (the default). */
    fun isExtremityAutomatic(extremity: Extremity): Boolean =
        !extremityOverrides.contains(extremity)

    /** True when at least one fixed contact was registered and the constraint pass should run. */
    fun hasContacts(): Boolean = contacts.isNotEmpty()
    fun getJoint(id: Joint): Vector3 = joints[id.index]

    fun setJoint(id: Joint, v: Vector3) {
        joints[id.index].set(v)
    }

    fun getJointRotation(id: Joint): JointRotation = rotations[id.index]

    fun setJointRotation(id: Joint, r: JointRotation) {
        rotations[id.index].copyFrom(r)
    }

    fun copyFrom(other: SkeletonPose) {
        for (i in joints.indices) {
            joints[i].set(other.joints[i])
            rotations[i].copyFrom(other.rotations[i])
        }
        this.roots = other.roots
        this.isTransformsUpdated = other.isTransformsUpdated
        this.maxIkClampAmount = other.maxIkClampAmount
        this.rootTranslationDelta = other.rootTranslationDelta
        this.rootRotationDelta = other.rootRotationDelta
        this.straightIntentDropped = other.straightIntentDropped
        this.boneLengthsVerified = other.boneLengthsVerified
        this.spineIntent = other.spineIntent
        this.postureIntent = other.postureIntent
        this.headTarget = other.headTarget
        this.contacts.clear()
        this.contacts.addAll(other.contacts)
        this.jointIntents.clear()
        this.jointIntents.addAll(other.jointIntents)
        this.limbTargets.clear()
        this.limbTargets.addAll(other.limbTargets)
        this.contactPrecedence.clear()
        this.contactPrecedence.addAll(other.contactPrecedence)
        this.extremityOverrides.clear()
        this.extremityOverrides.addAll(other.extremityOverrides)
        this.extremityArticulations.clear()
        for (e in other.extremityArticulations) {
            this.extremityArticulations[e.key] = e.value
        }
    }

    /**
     * Branch B (RFC_DECLARATIVE_POSE_AUTHORING §3) — the **sole mutator** of the §1.1 intent
     * carriers. A pose declares intent exclusively through this builder; it never assigns
     * `spineIntent` / `postureIntent` / `headTarget` directly
     * (those setters are `private set` on [SkeletonPose], so any `pose.spineIntent = …` outside this
     * builder fails to compile — the B0 compile-time guard, RFC_BRANCH_B_IMPLEMENTATION §2 B0).
     *
     * B0 introduced this builder as the sole mutator; B1 made `limbTargets` live and consumed it:
     * every `bakeIkLimb` forwards its end joint + world target into the carrier, and the pipeline-owned
     * `IkStage` reads it (gated by `IK_STAGE_ACTIVE`, default false). The remaining dead
     * subset (`spineIntent`, `jointIntents`) is consumed in B2 (Finalizer) and B3 (posture). The
     * builder remains additive substrate + a compile guard; no pose behavior changes when flags are off.
     */
    class IntentBuilder(private val pose: SkeletonPose) {

        /**
         * Declares the single spine curve (lumbar + thoracic about a shared axis). Backs
         * `spineIntent`, consumed by the Finalizer in B2.
         */
        fun spine(lumbarRad: Float, thoracicRad: Float, axis: Vector3 = Vector3(1f, 0f, 0f)): IntentBuilder {
            pose.spineIntent = SpineCurve(lumbarRad, thoracicRad, axis)
            return this
        }

        /**
         * Declares a single relative joint articulation (chest/hip/girdle/ankle/wrist) B2 consumer.
         */
        fun joint(joint: Joint, rotation: JointRotation): IntentBuilder {
            pose.jointIntents.add(RelativeArticulation(joint, rotation))
            return this
        }

        /** Declares a world-space limb end-effector / intermediate target. B1 consumer (IkStage). */
        fun limbTarget(joint: Joint, world: Vector3): IntentBuilder {
            pose.limbTargets.add(WorldTarget(joint, world))
            return this
        }

        /** Declares the gaze-as-target intent. Already consumed by [SkeletonPoseFinalizer.resolveHeadTarget]. */
        fun headTarget(world: Vector3, upBias: Vector3 = Vector3(0f, 1f, 0f)): IntentBuilder {
            pose.headTarget = HeadTarget(world, upBias)
            return this
        }

        /**
         * Declares the coarse posture intent (consumed by [ConstraintSolver], already live) and the
         * contact-conflict precedence order.
         */
        fun posture(kind: PostureIntent.Kind, tolerance: Float = 0f, precedence: List<Joint> = emptyList()): IntentBuilder {
            pose.postureIntent = PostureIntent(kind, tolerance)
            pose.contactPrecedence.clear()
            for (j in precedence) pose.contactPrecedence.add(j.name)
            return this
        }

        /** Registers a fixed support contact (consumed by [ConstraintSolver]). */
        fun contact(spec: ContactSpec): IntentBuilder {
            pose.contacts.add(spec)
            return this
        }

        /** Opts an extremity out of engine-derived orientation (W1 / B2 consumer). */
        fun overrideExtremity(extremity: Extremity): IntentBuilder {
            pose.extremityOverrides.add(extremity)
            return this
        }

        /**
         * Branch C — declares the §1.3 articulation (wrist/ankle rotation, relative to the parent
         * segment) of [extremity]. Consumed by the Finalizer's W1 derivation in place of the
         * [Joint.HAND_*]/[Joint.ANKLE_*] node `localRotation` (the sole source of truth for
         * extremity orientation; replaces the old Pose-write / Finalizer-read node round-trip).
         */
        fun extremity(extremity: Extremity, rotation: JointRotation): IntentBuilder {
            pose.extremityArticulations[extremity] = rotation
            return this
        }

        /**
         * Clears every §1.1 intent carrier so a reused [SkeletonPose] buffer starts a build fresh.
         * (Structural `segment`/`clavicle`/`trunk` declarations from §3.2 are added with their
         * consuming stages in B1/B2; until then the carrier-backed surface above is the substrate.)
         */
        fun reset() {
            pose.spineIntent = SpineCurve()
            pose.jointIntents.clear()
            pose.limbTargets.clear()
            pose.extremityOverrides.clear()
            pose.extremityArticulations.clear()
            pose.postureIntent = PostureIntent(PostureIntent.Kind.CUSTOM)
            pose.contactPrecedence.clear()
            pose.contacts.clear()
            pose.headTarget = null
        }
    }

    companion object {
        // Cached identity rotation to avoid allocations
        private val IDENTITY_ROTATION = JointRotation()
        private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

        // Scratch buffers for the legacy position-driven migration helper (fromJointPositions).
        private val tempBoneVec = Vector3()
        private val tempV1 = Vector3()
        private val tempColX = Vector3()
        private val tempColY = Vector3()
        private val tempColZ = Vector3()

        /**
         * Factory method to build a pose from a Scene Graph hierarchy.
         * Updates transforms and flattens into the compatible joint map.
         */
        fun fromHierarchy(
            roots: List<SkeletonNode>,
            targetPose: SkeletonPose
        ): SkeletonPose {
            for (root in roots) {
                root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
                root.flatten(targetPose)
            }
            targetPose.roots = roots
            targetPose.isTransformsUpdated = true
            return targetPose
        }

        /**
         * Legacy position-driven migration helper (Phase E, RFC_ENGINE_CLEANUP_PLAN).
         *
         * A pose that authors joints purely as **world positions** (the pre-rotation-driven
         * contract) reaches [finalize] with an empty `roots` hierarchy, which Phase E no longer
         * supports. This helper re-homes the exact reconstruction the deleted finalizer bridge
         * performed (`ensureHierarchy` + `setupTransforms`): it builds the standard skeleton
         * hierarchy and derives each node's `localPosition`/`localRotation` from the authored
         * world positions, then flattens — producing byte-identical output to the old bridge.
         *
         * Call once at the end of a legacy `build()` before returning `jointsBuffer`.
         */
        fun fromJointPositions(
            definition: SkeletonDefinition,
            source: SkeletonPose,
            targetPose: SkeletonPose
        ): SkeletonPose {
            val nodes = SkeletonFactory.createStandardSkeleton()
            val roots = nodes.roots
            val localMat = LocalMatrixScratch()

            fun SkeletonNode.setup(parentWorldRot: JointRotation) {
                val parentNode = parent
                if (parentNode == null) {
                    localPosition.set(source.getJoint(joint))

                    val chestPos = source.getJoint(Joint.CHEST)
                    val pelvisPos = source.getJoint(Joint.PELVIS)
                    tempBoneVec.set(chestPos).subtract(pelvisPos)
                    SkeletonMath.getRotationToAlign(Vector3(0f, 1f, 0f), tempBoneVec, tempV1, localRotation)

                    worldPosition.set(localPosition)
                    worldRotation.copyFrom(localRotation)
                } else {
                    val parentPos = parentNode.worldPosition
                    val childPos = source.getJoint(joint)

                    when (joint) {
                        Joint.HIP_F -> localPosition.set(0f, 0f, -definition.hipWidth)
                        Joint.HIP_B -> localPosition.set(0f, 0f, definition.hipWidth)
                        Joint.SHOULDER_A -> localPosition.set(0f, 0f, -definition.shoulderWidth)
                        Joint.SHOULDER_P -> localPosition.set(0f, 0f, definition.shoulderWidth)
                        Joint.CLAVICLE_A, Joint.CLAVICLE_P, Joint.SCAPULA_A, Joint.SCAPULA_P ->
                            localPosition.set(0f, 0f, 0f)
                        Joint.NECK_END -> localPosition.set(0f, definition.neckLength, 0f)
                        Joint.HEAD_POS -> localPosition.set(0f, 18f, 0f)
                        else -> {
                            tempBoneVec.set(childPos).subtract(parentPos)
                            SkeletonMath.rotAround(tempBoneVec, parentWorldRot.axis, -parentWorldRot.angle, localPosition)
                        }
                    }

                    if (joint == Joint.CHEST) {
                        val chestPos = source.getJoint(Joint.CHEST)
                        val pelvisPos = source.getJoint(Joint.PELVIS)
                        val shoulderA = source.getJoint(Joint.SHOULDER_A)
                        val shoulderP = source.getJoint(Joint.SHOULDER_P)

                        val lean = tempColY.set(chestPos).subtract(pelvisPos).normalize()
                        val shVec = tempColZ.set(shoulderA).subtract(shoulderP).normalize()
                        lean.cross(shVec, tempColX).normalize()
                        tempColZ.multiply(-1f)
                        SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, worldRotation)
                    } else {
                        tempBoneVec.set(childPos).subtract(parentPos)
                        SkeletonMath.getRotationToAlign(tempBoneVec, worldRotation)
                    }

                    SkeletonMath.rotationToMatrix(parentWorldRot, localMat.parentMatX, localMat.parentMatY, localMat.parentMatZ)
                    SkeletonMath.rotationToMatrix(worldRotation, localMat.worldMatX, localMat.worldMatY, localMat.worldMatZ)
                    SkeletonMath.transposeMultiply(
                        localMat.parentMatX, localMat.parentMatY, localMat.parentMatZ,
                        localMat.worldMatX, localMat.worldMatY, localMat.worldMatZ,
                        localMat.localMatX, localMat.localMatY, localMat.localMatZ
                    )
                    SkeletonMath.getRotationFromMatrix(localMat.localMatX, localMat.localMatY, localMat.localMatZ, localRotation)

                    worldPosition.set(childPos)
                }

                for (child in children) {
                    child.setup(worldRotation)
                }
            }

            roots[0].setup(IDENTITY_ROTATION)
            return fromHierarchy(roots, targetPose)
        }

        private class LocalMatrixScratch {
            val parentMatX = Vector3(); val parentMatY = Vector3(); val parentMatZ = Vector3()
            val worldMatX = Vector3(); val worldMatY = Vector3(); val worldMatZ = Vector3()
            val localMatX = Vector3(); val localMatY = Vector3(); val localMatZ = Vector3()
        }
    }
}
