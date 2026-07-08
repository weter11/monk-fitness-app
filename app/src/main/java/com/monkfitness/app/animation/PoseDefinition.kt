package com.monkfitness.app.animation

data class SkeletonPose(
    val joints: Map<Joint, Vector3>
) {
    fun getJoint(id: Joint): Vector3 = joints[id] ?: Vector3(0f, 0f, 0f)
}
