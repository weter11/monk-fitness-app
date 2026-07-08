package com.monkfitness.app.animation

data class SkeletonPose(
    val joints: Map<Joint, Vector3>,
    val hints: Map<Joint, Vector3> = emptyMap()
) {
    fun getJoint(id: Joint): Vector3 = joints[id] ?: Vector3(0f, 0f, 0f)
    fun getHint(id: Joint): Vector3? = hints[id]
}
