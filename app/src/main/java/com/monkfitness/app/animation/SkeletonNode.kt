package com.monkfitness.app.animation

/**
 * Mutable axis-angle rotation to avoid allocations.
 */
class JointRotation(
    var axis: Vector3 = Vector3(0f, 0f, 1f),
    var angle: Float = 0f // radians
) {
    fun set(axis: Vector3, angle: Float) {
        this.axis = axis
        this.angle = angle
    }

    fun copyFrom(other: JointRotation) {
        this.axis = other.axis
        this.angle = other.angle
    }
}

/**
 * Lightweight node for hierarchical transforms (Forward Kinematics).
 * Optimized for zero-allocation per frame updates.
 */
class SkeletonNode(
    val joint: Joint,
    var parent: SkeletonNode? = null,
    var localPosition: Vector3 = Vector3(0f, 0f, 0f),
    val localRotation: JointRotation = JointRotation(),
    val children: MutableList<SkeletonNode> = mutableListOf()
) {
    var worldPosition: Vector3 = Vector3(0f, 0f, 0f)
        private set

    val worldRotation: JointRotation = JointRotation()

    fun addChild(node: SkeletonNode): SkeletonNode {
        node.parent = this
        children.add(node)
        return node
    }

    /**
     * Compute world transform inheriting from parent.
     */
    fun updateWorldTransforms(parentWorldPos: Vector3, parentWorldRotation: JointRotation) {
        // Position propagation: Rotate local offset by parent's world rotation
        val rotatedPos = if (parentWorldRotation.angle != 0f) {
            SkeletonMath.rotAround(localPosition, parentWorldRotation.axis, parentWorldRotation.angle)
        } else {
            localPosition
        }
        worldPosition = parentWorldPos + rotatedPos

        // Rotation propagation (cumulative)
        worldRotation.axis = parentWorldRotation.axis
        worldRotation.angle = parentWorldRotation.angle + localRotation.angle

        for (child in children) {
            child.updateWorldTransforms(worldPosition, worldRotation)
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
