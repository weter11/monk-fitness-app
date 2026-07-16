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
 * Encapsulates the joint positions and rotations for a specific frame.
 * Now owns a Scene Graph hierarchy and provides backward compatibility.
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

    // W1 — explicit ownership of each extremity's heel/toe or palm/fingertip geometry. Defaults to
    // AUTOMATIC (engine derives) for every extremity, restoring the pose→engine boundary. A pose
    // opts a single extremity into MANUAL_OVERRIDE only when it deliberately authors the endpoints.
    // Ownership is intentionally NOT inferred from endpoint-node existence (the factory always
    // creates those nodes), so the engine's derivation is never silently disabled again.
    private val extremityOrientation =
        Array(Extremity.entries.size) { ExtremityOrientationMode.AUTOMATIC }

    /** Ownership mode of [extremity]'s orientation. Defaults to [ExtremityOrientationMode.AUTOMATIC]. */
    fun getExtremityOrientationMode(extremity: Extremity): ExtremityOrientationMode =
        extremityOrientation[extremity.ordinal]

    /**
     * Declares that [extremity]'s heel/toe or palm/fingertip geometry is authored by the pose and
     * must be preserved verbatim (see [ExtremityOrientationMode.MANUAL_OVERRIDE]). Poses that do not
     * call this get the default automatic engine derivation.
     */
    fun overrideExtremityOrientation(extremity: Extremity) {
        extremityOrientation[extremity.ordinal] = ExtremityOrientationMode.MANUAL_OVERRIDE
    }

    /** True when [extremity]'s geometry should be derived by the engine (the default). */
    fun isExtremityAutomatic(extremity: Extremity): Boolean =
        extremityOrientation[extremity.ordinal] == ExtremityOrientationMode.AUTOMATIC

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
        this.contacts.clear()
        this.contacts.addAll(other.contacts)
        // W1 — preserve explicit extremity-ownership when a pose is copied into the finalizer's
        // working buffer, so an authored MANUAL_OVERRIDE is honoured after the copy.
        for (i in extremityOrientation.indices) {
            this.extremityOrientation[i] = other.extremityOrientation[i]
        }
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
