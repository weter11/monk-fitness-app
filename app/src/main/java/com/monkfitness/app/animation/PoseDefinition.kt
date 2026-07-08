package com.monkfitness.app.animation

/**
 * Encapsulates the joint positions for a specific frame.
 * Now optionally owns a Scene Graph hierarchy.
 */
data class SkeletonPose(
    val joints: Map<Joint, Vector3>,
    val roots: List<SkeletonNode> = emptyList()
) {
    fun getJoint(id: Joint): Vector3 = joints[id] ?: Vector3(0f, 0f, 0f)

    companion object {
        /**
         * Factory method to build a pose from a hierarchy.
         */
        fun fromHierarchy(roots: List<SkeletonNode>): SkeletonPose {
            val joints = mutableMapOf<Joint, Vector3>()
            for (root in roots) {
                root.updateWorldTransforms(Vector3(0f, 0f, 0f), 0f)
                root.flatten(joints)
            }
            return SkeletonPose(joints, roots)
        }
    }
}
