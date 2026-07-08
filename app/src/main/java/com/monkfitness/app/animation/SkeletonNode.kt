package com.monkfitness.app.animation

/**
 * Lightweight node for hierarchical transforms (Forward Kinematics).
 * Uses mutable world properties to avoid per-frame allocations.
 */
class SkeletonNode(
    val joint: Joint,
    var parent: SkeletonNode? = null,
    var localPosition: Vector3 = Vector3(0f, 0f, 0f),
    var localRotationAxis: Vector3 = Vector3(0f, 0f, 1f),
    var localRotationAngle: Float = 0f, // radians
    val children: MutableList<SkeletonNode> = mutableListOf()
) {
    var worldPosition: Vector3 = Vector3(0f, 0f, 0f)
        private set

    var worldRotationAngle: Float = 0f
        private set

    fun addChild(node: SkeletonNode): SkeletonNode {
        node.parent = this
        children.add(node)
        return node
    }

    /**
     * Compute world transform inheriting from parent.
     */
    fun updateWorldTransforms(parentWorldPos: Vector3, parentWorldRotationAngle: Float) {
        // Biomechanical engine uses cumulative Z-rotation
        val rotatedPos = if (parentWorldRotationAngle != 0f) {
            SkeletonMath.rotAround(localPosition, Vector3(0f, 0f, 1f), parentWorldRotationAngle)
        } else {
            localPosition
        }
        worldPosition = parentWorldPos + rotatedPos
        worldRotationAngle = parentWorldRotationAngle + localRotationAngle

        for (child in children) {
            child.updateWorldTransforms(worldPosition, worldRotationAngle)
        }
    }

    /**
     * Flatten the hierarchy into the target joint map.
     */
    fun flatten(target: MutableMap<Joint, Vector3>) {
        target[joint] = worldPosition
        for (child in children) {
            child.flatten(target)
        }
    }
}
