package com.monkfitness.app.animation

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
    val contacts: MutableList<ContactSpec> = mutableListOf(),
    // UNI-8 — a SECOND, independent wrist/ankle rotation (the other DOF of a
    // 2-DOF joint: e.g. radial/ulnar deviation on top of wrist pronation/supination,
    // or inversion/eversion on top of ankle dorsi/plantar-flexion). The primary
    // articulation lives in `rotations[joint]` (set via the node's `localRotation`);
    // this map carries the secondary so the finalizer can compose the two instead
    // of collapsing them into one axis-angle. Empty => single-DOF (current behaviour).
    val secondaryRotations: MutableMap<Joint, JointRotation> = mutableMapOf()
) {

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

    // UNI-8 — secondary wrist/ankle rotation accessors. A missing entry reads
    // as identity (single-DOF), so unset joints are unchanged. The returned
    // identity is shared and read-only.
    fun getSecondaryRotation(id: Joint): JointRotation =
        secondaryRotations[id] ?: IDENTITY_ROTATION

    fun setSecondaryRotation(id: Joint, r: JointRotation) {
        secondaryRotations[id] = JointRotation().also { it.copyFrom(r) }
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
        this.secondaryRotations.clear()
        this.secondaryRotations.putAll(other.secondaryRotations)
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
