package com.monkfitness.app.animation

/**
 * Mutable axis-angle rotation to avoid allocations.
 */
class JointRotation(
    val axis: Vector3 = Vector3(0f, 0f, 1f),
    var angle: Float = 0f // radians
) {
    fun set(axis: Vector3, angle: Float) {
        this.axis.set(axis)
        this.angle = angle
    }

    fun set(x: Float, y: Float, z: Float, angle: Float) {
        this.axis.set(x, y, z)
        this.angle = angle
    }

    fun copyFrom(other: JointRotation) {
        this.axis.set(other.axis)
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
    val worldPosition: Vector3 = Vector3(0f, 0f, 0f)

    val worldRotation: JointRotation = JointRotation()

    // Persistent scratch buffers to completely avoid allocations during FK traversal
    private val pX = Vector3()
    private val pY = Vector3()
    private val pZ = Vector3()
    private val lX = Vector3()
    private val lY = Vector3()
    private val lZ = Vector3()
    private val wX = Vector3()
    private val wY = Vector3()
    private val wZ = Vector3()

    fun addChild(node: SkeletonNode): SkeletonNode {
        node.parent = this
        children.add(node)
        return node
    }

    /**
     * Compute world transform inheriting from parent via full 3D matrix concatenation.
     */
    fun updateWorldTransforms(parentWorldPos: Vector3, parentWorldRotation: JointRotation) {
        // Position propagation: Rotate local offset by parent's world rotation
        if (parentWorldRotation.angle != 0f) {
            SkeletonMath.rotAround(localPosition, parentWorldRotation.axis, parentWorldRotation.angle, worldPosition)
        } else {
            worldPosition.set(localPosition)
        }
        worldPosition.add(parentWorldPos)

        // Rotation propagation: Concatenate parent's world rotation with local rotation
        SkeletonMath.rotationToMatrix(parentWorldRotation, pX, pY, pZ)
        SkeletonMath.rotationToMatrix(localRotation, lX, lY, lZ)
        SkeletonMath.multiplyMatrices(pX, pY, pZ, lX, lY, lZ, wX, wY, wZ)
        SkeletonMath.getRotationFromMatrix(wX, wY, wZ, worldRotation)

        for (child in children) {
            child.updateWorldTransforms(worldPosition, worldRotation)
        }
    }

    /**
     * Flatten the hierarchy into the target joint map.
     */
    fun flatten(target: SkeletonPose) {
        target.setJoint(joint, worldPosition)
        target.setJointRotation(joint, worldRotation)
        for (child in children) {
            child.flatten(target)
        }
    }
}
