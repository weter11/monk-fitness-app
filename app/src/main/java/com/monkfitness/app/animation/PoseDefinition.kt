package com.monkfitness.app.animation

data class TorsoBox(
    val hLf: Vector3, val hLb: Vector3,
    val hRf: Vector3, val hRb: Vector3,
    val sLf: Vector3, val sLb: Vector3,
    val sRf: Vector3, val sRb: Vector3
)

data class SkeletonPose(
    val joints: Map<Joint, Vector3>,
    val torsoBox: TorsoBox? = null
) {
    fun getJoint(id: Joint): Vector3 = joints[id] ?: Vector3(0f, 0f, 0f)
}
