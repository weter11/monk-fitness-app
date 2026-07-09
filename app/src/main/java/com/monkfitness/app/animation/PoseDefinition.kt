package com.monkfitness.app.animation

/**
 * Encapsulates the joint positions for a specific frame.
 * Now owns a Scene Graph hierarchy and provides backward compatibility.
 */
data class SkeletonPose(
    val joints: Map<Joint, Vector3>,
    val roots: List<SkeletonNode> = emptyList()
) {
    fun getJoint(id: Joint): Vector3 = joints[id] ?: Vector3(0f, 0f, 0f)

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
            targetJoints: MutableMap<Joint, Vector3>
        ): SkeletonPose {
            for (root in roots) {
                root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
                root.flatten(targetJoints)
            }
            return SkeletonPose(targetJoints, roots)
        }
    }
}
