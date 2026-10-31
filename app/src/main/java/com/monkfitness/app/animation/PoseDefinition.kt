package com.monkfitness.app.animation

/**
 * The four extremities whose heel/toe or palm/fingertip geometry the engine derives.
 *
 * Ownership is declared explicitly per extremity on the [SkeletonPose], never inferred from
 * whether the endpoint nodes (HEEL/TOE, PALM/FINGERTIPS) exist: the skeleton factory always
 * creates those nodes, so node-existence is not an ownership signal.
 */
enum class Extremity {
    FOOT_F,
    FOOT_B,
    HAND_A,
    HAND_P
}

/**
 * Who owns an extremity's heel/toe or palm/fingertip geometry.
 *
 * [AUTOMATIC] (default): the pose describes anatomy only (limb target + ankle/wrist articulation)
 * and the engine derives the geometry from the limb direction, the relative articulation and the
 * [FootDefinition]/[HandDefinition] anatomy. [MANUAL_OVERRIDE]: the pose authored the endpoint
 * positions (e.g. a stylized pointed toe or curled grip the derivation cannot express) and the
 * engine preserves them verbatim.
 */
enum class ExtremityOrientationMode {
    AUTOMATIC,
    MANUAL_OVERRIDE
}

/**
 * Typed coarse posture intent declared by a pose and interpreted by the [ConstraintSolver]. It
 * names the global body arrangement the pose aims for so the solver can derive an exact
 * pelvis/root without the pose hand-computing root arithmetic.
 *
 * `tolerance` is a soft bound: zero means "follow the authored shape exactly".
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
 * A single declarative spine curve; one call replaces the coupled pelvis+chest dual writes.
 * Angles are signed radians, `axis` is the shared bend axis in the chest/pelvis parent frame.
 */
data class SpineCurve(
    val lumbarRad: Float = 0f,
    val thoracicRad: Float = 0f,
    val axis: Vector3 = Vector3(1f, 0f, 0f)
)

/**
 * A relative articulation of a single joint with respect to its parent
 * (chest/hip/girdle/ankle/wrist), so the engine owns the geometry derivation.
 */
data class RelativeArticulation(
    val joint: Joint,
    val rotation: JointRotation
)

/**
 * A world-space target for a limb end-effector or intermediate joint
 * (hand/foot/knee/elbow/head), resolved by IK/ConstraintSolver.
 *
 * It carries the full IK-solving context so the stage can reproduce `bakeIkLimb` byte-for-byte
 * without re-deriving intent the pose already expressed: `pole` is the bend-plane direction
 * (zero vector ⇒ the stage derives the default pole), `straight` solves a rigid segment, and a
 * non-null `contact` is a fixed support contact reused verbatim so the solver re-bake matches.
 *
 * `length1`/`length2`/`constraint` are the authoring bake's own declared inputs. The NaN/null
 * defaults mark the shorthand surface (`IntentBuilder.limbTarget(joint, world)`, hand-built
 * fixtures) as NOT production-realizable: a target whose realization context is absent fails
 * fast rather than silently recovering bone lengths or the constraint from the definition.
 */
data class WorldTarget(
    val joint: Joint,
    val world: Vector3,
    val pole: Vector3 = Vector3(),
    val straight: Boolean = false,
    val contact: ContactConstraint? = null,
    val length1: Float = Float.NaN,
    val length2: Float = Float.NaN,
    val constraint: IKConstraint? = null
)

/**
 * Gaze-as-target: the pose declares a world-space point the head should look at, instead of
 * hand-authoring a counter-rotated UP direction. `upBias` is the neutral gaze-up bias used when
 * deriving the head direction (defaults to world up).
 */
data class HeadTarget(
    val world: Vector3,
    val upBias: Vector3 = Vector3(0f, 1f, 0f)
)

/**
 * Joint positions and rotations for a specific frame: the one carrier of both pose intent
 * (written by Pose, read by Engine) and derived state (written by Engine, read by Validation).
 * No component edits another's section.
 */
class SkeletonPose(
    val joints: Array<Vector3> = Array(Joint.entries.size) { Vector3() },
    val rotations: Array<JointRotation> = Array(Joint.entries.size) { JointRotation() },
    var roots: List<SkeletonNode> = emptyList(),
    var isTransformsUpdated: Boolean = false,
    var maxIkClampAmount: Float = 0f,
    // How far the global solver displaced the root from its authored transform, so the
    // PELVIS_INTENT rule can surface unexpected root motion. Zero for non-contact (no-op) poses.
    var rootTranslationDelta: Float = 0f,
    var rootRotationDelta: Float = 0f,
    // Fixed support contacts registered by contact-bearing `bakeIkLimb` calls. The global
    // constraint solver consumes these to reposition the root so every contact holds.
    val contacts: MutableList<ContactSpec> = mutableListOf(),

    // Settlement Result surfaced on the carrier as an internal, unpublished section. Populated
    // exactly once by [ConstraintSolver.solve]; the Finalizer never reads or writes it, and
    // `copyFrom` does not copy it, so the published pose cannot leak it. `null` means the solver
    // was not entered (contact-less CUSTOM early-return or a pipeline path that bypassed it).
    internal var settlementResult: SettlementInfo? = null
) {

    // Authoring-cycle token for the Finalizer's publish-order guard, bumped by
    // [IntentBuilder.reset] — the per-build hygiene reset both build templates perform first. A
    // changed token means a fresh authoring pass opened over the carrier, so publishing again is
    // a new publish unit rather than a silent re-entry. Debug-gated bookkeeping, deliberately
    // absent from [copyFrom] like settlementResult.
    internal var buildCycleToken: Long = 0L

    /**
     * The environment the pose rests in, derived from `metadata.environment` once by the pipeline.
     * The [SkeletonPoseFinalizer] reads this — not a per-pose hardcoded plane — to derive the
     * support surface each declared contact rests on and flatten the extremity onto it, so the
     * same logic prevents palms slicing into the floor and limbs penetrating a prop in every pose.
     * Defaults to a flat ground at y=0, leaving poses with no environment description unaffected.
     */
    /**
     * Body points declared as environment support contacts (`metadata.support.contacts`); the
     * Finalizer flattens exactly these onto their support surface. Empty ⇒ nothing changes.
     */
    val supportedPoints: MutableSet<SupportPoint> = mutableSetOf()

    var environment: EnvironmentDefinition = EnvironmentDefinition()

    val jointIntents: MutableList<RelativeArticulation> = mutableListOf()

    var spineIntent: SpineCurve = SpineCurve()
        private set

    val limbTargets: MutableList<WorldTarget> = mutableListOf()

    /**
     * Ordered [ContactSpec] ids declaring which contacts win a solver conflict: earlier entries
     * take precedence, empty means "all contacts equal".
     */
    val contactPrecedence: MutableList<String> = mutableListOf()

    var postureIntent: PostureIntent = PostureIntent(PostureIntent.Kind.CUSTOM)
        private set

    /**
     * Explicit opt-out set of extremities whose heel/toe or palm/fingertip geometry the pose
     * authors verbatim and the engine must NOT derive (stylized only). Source of truth for the
     * [ExtremityOrientationMode] plumbing below.
     */
    val extremityOverrides: MutableSet<Extremity> = mutableSetOf()

    /**
     * The authored wrist/ankle rotation of each extremity, expressed *relative to its parent
     * segment* (forearm for the wrist, shank for the ankle). Single source of truth for the
     * extremity geometry derivation: the Finalizer reads it instead of reading the
     * [Joint.HAND_*]/[Joint.ANKLE_*] node `localRotation` back, and the value is exactly the
     * `JointRotation` the node carried, so the AUTOMATIC path stays byte-identical. Never carries
     * a world rotation (the authoring helpers already removed the ancestor chain).
     */
    val extremityArticulations: MutableMap<Extremity, JointRotation> = mutableMapOf()

    /**
     * Intended root-relative (pelvis-frame) forward direction of each extremity: the exercise
     * declares which way the hand/foot faces when the constraint system admits multiple valid
     * orientations (e.g. a planted hand). The Finalizer transforms it to world space and projects
     * it onto the support plane. Absent per extremity ⇒ fall back to the derivation chain
     * (forearm projection / toe hint).
     */
    val headings: MutableMap<Extremity, Vector3> = mutableMapOf()

    fun getHeading(extremity: Extremity): Vector3? = headings[extremity]

    /**
     * Where the head should look in world space; [SkeletonPoseFinalizer.resolveHeadTarget] is the
     * sole head writer. `null` means no gaze target — the head is left as authored.
     */
    var headTarget: HeadTarget? = null
        private set

    /** A straight-limb intent could not be honoured (the limb was re-baked bent). */
    var straightIntentDropped: Boolean = false

    /**
     * Limb-solver window execution count, counted by the realization sites (the engine-side
     * `IkStage` window once per `apply` past its gate, and the authoring/validation bake's
     * realization branch) and checked and reset by `SkeletonPipeline.runStages` at the end of each
     * frame. Exactly one implementation may realize limb intent per frame, so a second window
     * entering by any path — even producing identical output — raises this above 1 and fires the
     * pipeline's debug check.
     *
     * The window count alone cannot see two realizations of ONE limb inside ONE window (a
     * duplicated limb target, a second `bakeIkLimb` for the same joint): the frame is byte-
     * identical. That per-execution half is [limbRealizedLimbs] + [limbDuplicateRealizations].
     *
     * Internal instrumentation: never added to `copyFrom`, so published pose state cannot inherit it.
     */
    internal var limbSolverExecutions: Int = 0

    /**
     * Build-cycle key of the current realization evidence, set by [registerLimbRealization] when a
     * realization event opens a new cycle. A differing value is the deterministic re-arm evidence
     * for the counter above and for the realized-limb mask. Debug-gated; absent from `copyFrom`.
     */
    internal var limbSolverRealizationToken: Long = -1L

    /**
     * Per-execution evidence: bitmask of the limb end joints realized in the current cycle, keyed
     * by [Joint.index] (33 members, indices 0..32, so one `Long` suffices). Cleared on cycle
     * re-arm and by the pipeline's per-frame reset, so no frame inherits an earlier frame's set.
     */
    internal var limbRealizedLimbs: Long = 0L

    /**
     * Realization events in the current cycle that re-realized a limb [limbRealizedLimbs] already
     * holds (0 ⇒ the single-active-solver invariant holds). Two realizations of one limb inside
     * one window leave the same window count AND a byte-identical frame but are two executions,
     * and `SkeletonPipeline.runStages` rejects the frame on this number alone — execution
     * evidence, never output comparison. Reset with the cycle key and per frame.
     */
    internal var limbDuplicateRealizations: Int = 0

    /**
     * Every solved limb exactly preserved its bone lengths. Optimistic default `true`; each
     * `bakeIkLimb` ANDs its per-limb check in, so a single violated limb flips the whole pose to
     * `false`. The IK path resets it at the start of each build.
     */
    var boneLengthsVerified: Boolean = true

    // State stamps consumed by the validator. The engine (SkeletonMath / Finalizer) produces them
    // from the solved skeleton; the validator reads them directly and never re-derives geometry.

    /**
     * Femur direction expressed in the pelvis frame, decomposed into the four independent
     * anatomical angles [HipRomLimits] bounds. One entry per hip (Joint.HIP_F / Joint.HIP_B);
     * absent until the engine writes it. Mirrors the validator's own math so the rule is
     * byte-identical when read from the stamp.
     */
    val hipRomStamps: MutableMap<Joint, HipRomStamp> = mutableMapOf()

    /**
     * Maximum knee-deviation and elbow-deviation magnitude difference (left vs right), in 2-D
     * perpendicular-deviation units. A sign flip (knees bending opposite ways) is captured by
     * `bilateralOppositeBend`. 0 means no violation until the engine writes it.
     */
    var bilateralSymmetryDelta: Float = 0f
    var bilateralOppositeBend: Boolean = false

    // Ownership of each extremity's endpoint geometry, derived from [extremityOverrides] and
    // defaulting to AUTOMATIC (engine derives). Intentionally NOT inferred from endpoint-node
    // existence: the factory always creates those nodes.

    fun getExtremityOrientationMode(extremity: Extremity): ExtremityOrientationMode =
        if (extremityOverrides.contains(extremity)) {
            ExtremityOrientationMode.MANUAL_OVERRIDE
        } else {
            ExtremityOrientationMode.AUTOMATIC
        }

    fun overrideExtremityOrientation(extremity: Extremity) {
        extremityOverrides.add(extremity)
    }

    fun isExtremityAutomatic(extremity: Extremity): Boolean =
        !extremityOverrides.contains(extremity)

    /** True when at least one fixed contact was registered, so the constraint pass must run. */
    fun hasContacts(): Boolean = contacts.isNotEmpty()
    fun getJoint(id: Joint): Vector3 = joints[id.index]

    fun setJoint(id: Joint, v: Vector3) {
        joints[id.index].set(v)
    }

    fun getJointRotation(id: Joint): JointRotation = rotations[id.index]

    fun setJointRotation(id: Joint, r: JointRotation) {
        rotations[id.index].copyFrom(r)
    }

    /**
     * The single authoritative registration point for realization evidence. Every realization
     * site calls it exactly once per limb, immediately before that limb's solve (`bakeIkLimb` in
     * both its member and package-level forms, `BaseValidationPose.bakeIkLimb`, `IkStage.apply`).
     * Keeping registration in one function — rather than a counter write per site — is what makes
     * "one authoritative execution-evidence path" checkable.
     *
     * Cycle-local: a realization whose cycle key differs from [buildCycleToken] opens a fresh
     * cycle — mask and duplicate count cleared and, for an authoring site, the window count
     * RE-ARMED to 1 rather than accumulated, so two consecutive builds can never sum into one
     * ownership failure. Realizing a limb the cycle already holds increments
     * [limbDuplicateRealizations]. Debug-gated: release builds carry no evidence.
     *
     * `authoringWindow` is true for the bake sites, whose realization runs inside `build()` — one
     * window per cycle however many limbs it realizes. The engine stage passes false.
     */
    internal fun registerLimbRealization(joint: Joint, authoringWindow: Boolean) {
        if (!com.monkfitness.app.BuildConfig.DEBUG) return
        if (limbSolverRealizationToken != buildCycleToken) {
            limbSolverRealizationToken = buildCycleToken
            limbRealizedLimbs = 0L
            limbDuplicateRealizations = 0
            if (authoringWindow) limbSolverExecutions = 1
        }
        val bit = 1L shl joint.index
        if (limbRealizedLimbs and bit != 0L) limbDuplicateRealizations++
        limbRealizedLimbs = limbRealizedLimbs or bit
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
        this.environment = other.environment
        this.supportedPoints.clear()
        this.supportedPoints.addAll(other.supportedPoints)
        this.headings.clear()
        for ((k, v) in other.headings) {
            this.headings[k] = v.copy()
        }
    }

    /**
     * The sole mutator of the intent carriers. A pose declares intent exclusively through this
     * builder; the `private set` setters on [SkeletonPose] make any direct assignment elsewhere
     * fail to compile.
     */
    class IntentBuilder(private val pose: SkeletonPose) {

        fun spine(lumbarRad: Float, thoracicRad: Float, axis: Vector3 = Vector3(1f, 0f, 0f)): IntentBuilder {
            pose.spineIntent = SpineCurve(lumbarRad, thoracicRad, axis)
            return this
        }

        fun joint(joint: Joint, rotation: JointRotation): IntentBuilder {
            pose.jointIntents.add(RelativeArticulation(joint, rotation))
            return this
        }

        fun limbTarget(joint: Joint, world: Vector3): IntentBuilder {
            pose.limbTargets.add(WorldTarget(joint, world))
            return this
        }

        fun headTarget(world: Vector3, upBias: Vector3 = Vector3(0f, 1f, 0f)): IntentBuilder {
            pose.headTarget = HeadTarget(world, upBias)
            return this
        }

        /**
         * Declares the coarse posture intent and the contact-conflict precedence order (earlier
         * joints win).
         */
        fun posture(kind: PostureIntent.Kind, tolerance: Float = 0f, precedence: List<Joint> = emptyList()): IntentBuilder {
            pose.postureIntent = PostureIntent(kind, tolerance)
            pose.contactPrecedence.clear()
            for (j in precedence) pose.contactPrecedence.add(j.name)
            return this
        }

        fun contact(spec: ContactSpec): IntentBuilder {
            pose.contacts.add(spec)
            return this
        }

        fun overrideExtremity(extremity: Extremity): IntentBuilder {
            pose.extremityOverrides.add(extremity)
            return this
        }

        /**
         * Declares the wrist/ankle articulation of [extremity], relative to its parent segment —
         * the sole source of truth for extremity orientation.
         */
        fun extremity(extremity: Extremity, rotation: JointRotation): IntentBuilder {
            pose.extremityArticulations[extremity] = rotation
            return this
        }

        /** Clears every intent carrier so a reused [SkeletonPose] buffer starts a build fresh. */
        fun reset() {
            // Bump the authoring-cycle token: this reset is the "fresh build pass" boundary the
            // finalizer's publish-order guard recognizes as re-arm evidence (debug-gated).
            if (com.monkfitness.app.BuildConfig.DEBUG) pose.buildCycleToken++
            pose.spineIntent = SpineCurve()
            pose.jointIntents.clear()
            pose.limbTargets.clear()
            pose.extremityOverrides.clear()
            pose.extremityArticulations.clear()
            pose.headings.clear()
            pose.environment = EnvironmentDefinition()
            pose.supportedPoints.clear()
            pose.postureIntent = PostureIntent(PostureIntent.Kind.CUSTOM)
            pose.contactPrecedence.clear()
            pose.contacts.clear()
            pose.headTarget = null
        }
    }

    /**
     * Declares root-relative (pelvis-frame) heading for an extremity: use when the constraint
     * system admits multiple valid orientations and the exercise selects one. The Finalizer
     * transforms it to world space and projects it onto the support plane.
     */
    class HeadingBuilder(private val pose: SkeletonPose) {
        fun set(extremity: Extremity, rootRelativeDirection: Vector3): HeadingBuilder {
            pose.headings[extremity] = rootRelativeDirection.copy()
            return this
        }
    }

    companion object {
        // Cached identity rotation to avoid allocations
        private val IDENTITY_ROTATION = JointRotation()
        private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

        // Scratch buffers for the position-driven migration helper (fromJointPositions).
        private val tempBoneVec = Vector3()
        private val tempV1 = Vector3()
        private val tempColX = Vector3()
        private val tempColY = Vector3()
        private val tempColZ = Vector3()

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
         * Migration helper for poses that author joints purely as world positions: such a pose
         * reaches finalize with an empty `roots` hierarchy, which the engine no longer supports.
         * Builds the standard skeleton hierarchy and derives each node's
         * `localPosition`/`localRotation` from the authored world positions, then flattens.
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

    fun isSupported(point: SupportPoint): Boolean = supportedPoints.contains(point)

}

/**
 * The Settlement Result: the world-space settled root, the settled-contact information and the
 * contact-conflict outcome. Sole producer is [ConstraintSolver.solve], exactly once; no other
 * subsystem may alter it, and it is superseded at publication — [SkeletonPose.copyFrom] does not
 * copy it, so it never leaks into published pose state.
 *
 * `declaredContactJoints` is named `declared`, not `settled`, on purpose: the solver does not
 * track per-contact settlement outcomes, it re-bakes each declared contact's limb toward its
 * target under a bounded iteration. `conflictOutcomeJoint` is the precedence winner (earliest
 * entry in `contactPrecedence`), or null when that list is empty (uniform-mean path, every
 * contact equal) or no contacts were declared.
 */
data class SettlementInfo(
    val settledRootWorld: Vector3,
    val declaredContactJoints: List<Joint>,
    val conflictOutcomeJoint: Joint?
)
