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
 * joint (hand/foot/knee/elbow/head). Authored by the pose; resolved by IK/ConstraintSolver.
 *
 * @param joint the joint this target pins.
 * @param world the world-space position the joint should occupy.
 */
data class WorldTarget(
    val joint: Joint,
    val world: Vector3
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
 *   `contactPrecedence`, `postureIntent`, `extremityOverrides`, `motion`, `camera`,
 *   `environment`.
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

    /** World-space targets for limb end-effectors / intermediate joints (hand/foot/knee/elbow/head). */
    val limbTargets: MutableList<WorldTarget> = mutableListOf()

    /**
     * Ordered list of [ContactSpec] ids declaring which contacts win when the solver must resolve
     * a conflict (finding F7). Earlier entries take precedence. Empty means "all contacts equal".
     */
    val contactPrecedence: MutableList<String> = mutableListOf()

    /** Typed coarse posture intent (finding F2); interpreted by the [ConstraintSolver]. */
    var postureIntent: PostureIntent = PostureIntent(PostureIntent.Kind.CUSTOM)

    /**
     * Phase 0 — explicit opt-out set of extremities whose heel/toe or palm/fingertip geometry the
     * pose authors verbatim and the engine must NOT derive (stylized only). This is the canonical
     * §1.1 carrier; it is the source of truth for the W1 [ExtremityOrientationMode] plumbing below.
     */
    val extremityOverrides: MutableSet<Extremity> = mutableSetOf()

    /** Motion driver describing how the pose interpolates across the frame (§1.1). */
    var motion: Any? = null

    /** Camera framing hint authored by the pose (§1.1). */
    var camera: Any? = null

    /** Environment hint authored by the pose (§1.1). */
    var environment: Any? = null

    // ---- §1.2 STATE SECTION (written by Engine, read by Validation) -----------------------

    /** IK/Solver stamp: a straight-limb intent could not be honoured (the limb was re-baked bent). */
    var straightIntentDropped: Boolean = false

    /** IK stamp: every solved limb exactly preserved its bone lengths (invariant F5). */
    var boneLengthsVerified: Boolean = false

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
        this.motion = other.motion
        this.camera = other.camera
        this.environment = other.environment
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
    }

    companion object {
        // Cached identity rotation to avoid allocations
        private val IDENTITY_ROTATION = JointRotation()
        private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

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
    }
}
