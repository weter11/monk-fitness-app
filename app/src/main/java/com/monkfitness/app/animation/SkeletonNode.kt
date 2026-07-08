package com.monkfitness.app.animation

import kotlin.math.*

class SkeletonNode(
    val joint: Joint,
    var localPosition: Vector3 = Vector3(0f, 0f, 0f),
    var parent: SkeletonNode? = null
) {
    val children = mutableListOf<SkeletonNode>()

    fun addChild(node: SkeletonNode): SkeletonNode {
        node.parent = this
        children.add(node)
        return node
    }

    fun getGlobalPosition(): Vector3 {
        return getGlobalTransform().position
    }

    data class Transform(val position: Vector3, val rotationAxis: Vector3, val rotationAngle: Float)

    private fun getGlobalTransform(): Transform {
        val parentTransform = parent?.getGlobalTransform() ?: Transform(Vector3(0f, 0f, 0f), Vector3(0f, 1f, 0f), 0f)

        // Rotate local position by parent's total rotation
        val rotatedLocalPos = if (parentTransform.rotationAngle != 0f) {
             SkeletonMath.rotAround(localPosition, parentTransform.rotationAxis, parentTransform.rotationAngle)
        } else {
            localPosition
        }

        return Transform(
            position = parentTransform.position + rotatedLocalPos,
            rotationAxis = Vector3(0f, 0f, 1f), // Simplified for this engine
            rotationAngle = parentTransform.rotationAngle + rotationAngle
        )
    }

    private var rotationAngle: Float = 0f

    fun setLocalRotation(angle: Float) {
        rotationAngle = angle
    }

    /**
     * Flatten the hierarchy into a joint map for SkeletonPose.
     */
    fun flatten(map: MutableMap<Joint, Vector3>) {
        map[joint] = getGlobalPosition()
        children.forEach { it.flatten(map) }
    }
}
