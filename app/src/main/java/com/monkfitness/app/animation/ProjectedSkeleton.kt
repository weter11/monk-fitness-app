package com.monkfitness.app.animation

/**
 * Encapsulates a skeleton after it has been projected into screen space by a Camera.
 * Holds the 2D coordinates, depth, and perspective scale for every joint.
 */
data class ProjectedSkeleton(
    val joints: Map<Joint, ProjectedPoint>
) {
    fun getJoint(id: Joint): ProjectedPoint = joints[id] ?: ProjectedPoint(0f, 0f, 0f, 1f)
}
