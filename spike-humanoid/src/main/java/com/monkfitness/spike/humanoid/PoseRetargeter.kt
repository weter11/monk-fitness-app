package com.monkfitness.spike.humanoid

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonPose

/**
 * Drives a glTF skin from an engine [SkeletonPose], without clips and without touching the engine.
 *
 * The mapping is a rest-delta retarget in world space:
 *
 *     R_target_world = R_source_world * R_target_rest_world
 *
 * The engine's rest convention is identity local rotation on every bone, so its rest world rotation
 * is identity and the source rotation is used directly (a basis quaternion absorbs a rig whose axes
 * differ). Rotations only — bone lengths and the rest offsets come from the asset, so a squat bent
 * to 100° reads as 100° on any rig whose bind pose matches its rest pose.
 *
 * Filament expects `inverse(renderableWorld) * jointWorld * inverseBind` (the same product gltfio's
 * Animator computes), which is exactly what [boneMatrices] returns.
 */
class PoseRetargeter(
    private val rig: GltfRig,
    private val boneByJoint: Map<Joint, Int>,
    /** Source → target axis correction; identity when both rigs are Y-up with the same handedness. */
    private val basis: Quat = Quat.IDENTITY
) {
    private val jointByNode: Map<Int, Joint> = boneByJoint.entries.associate { (joint, node) -> node to joint }
    private val restWorldRotations: Array<Quat> = rig.restWorldRotations()
    private val restWorldPositions: Array<FloatArray> = rig.restWorldTranslations()

    val mappedJointCount: Int get() = boneByJoint.size
    val unmappedJointCount: Int get() = Joint.entries.size - boneByJoint.size

    /** Height of the visual rig in asset units, from the rest pose — used to derive the unit scale. */
    fun restHeight(): Float {
        var maxY = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        restWorldPositions.forEach { p ->
            if (p[1] > maxY) maxY = p[1]
            if (p[1] < minY) minY = p[1]
        }
        return (maxY - minY).coerceAtLeast(1e-3f)
    }

    /**
     * Bone matrices in skin order, ready for `RenderableManager.setBonesAsMatrices`.
     *
     * @param pose the frame produced by `SkeletonPipeline`
     * @param restPelvisY engine-space pelvis height of the neutral stance
     * @param scale engine units → asset units (see [MonkJointResolver.restPelvisHeight])
     * @param meshWorld world transform of the renderable the skin belongs to
     */
    fun boneMatrices(
        pose: SkeletonPose,
        restPelvisY: Float,
        scale: Float,
        meshWorld: FloatArray = Mat4.identity(),
        out: FloatArray? = null
    ): FloatArray {
        val nodeCount = rig.nodes.size
        val worldRotation = arrayOfNulls<Quat>(nodeCount)
        val worldPosition = arrayOfNulls<FloatArray>(nodeCount)
        val inverseMesh = Mat4.inverse(meshWorld)

        fun walk(index: Int, parentRotation: Quat, parentPosition: FloatArray) {
            val node = rig.nodes[index]
            val joint = jointByNode[index]

            val targetWorldRotation =
                if (joint != null) {
                    val rotation = pose.getJointRotation(joint)
                    val sourceWorld = Quat.fromAxisAngle(
                        rotation.axis.x, rotation.axis.y, rotation.axis.z, rotation.angle
                    )
                    val corrected = basis * sourceWorld * basis.conjugate()
                    corrected * restWorldRotations[index]
                } else {
                    restWorldRotations[index]
                }

            // Rest offsets are asset units already: scaling them would break the bind pose the
            // inverse bind matrices were authored against. Only the engine's root displacement is
            // converted from engine units into asset units.
            val localTranslation = floatArrayOf(
                node.translation[0],
                node.translation[1],
                node.translation[2]
            )
            if (joint == Joint.PELVIS) {
                val pelvis = pose.getJoint(Joint.PELVIS)
                localTranslation[0] += (pelvis.x - 0f) * scale
                localTranslation[1] += (pelvis.y - restPelvisY) * scale
                localTranslation[2] += (pelvis.z - 0f) * scale
            }

            val rotated = rotate(parentRotation, localTranslation)
            val position = floatArrayOf(
                parentPosition[0] + rotated[0],
                parentPosition[1] + rotated[1],
                parentPosition[2] + rotated[2]
            )
            worldRotation[index] = targetWorldRotation
            worldPosition[index] = position

            node.children.forEach { walk(it, targetWorldRotation, position) }
        }

        rig.sceneRoots.forEach { walk(it, Quat.IDENTITY, FloatArray(3)) }

        val jointCount = rig.jointNodes.size
        val result = out ?: FloatArray(jointCount * 16)
        require(result.size >= jointCount * 16) { "out must hold ${jointCount * 16} floats" }

        for (k in 0 until jointCount) {
            val node = rig.jointNodes[k]
            val rotation = worldRotation[node] ?: Quat.IDENTITY
            val position = worldPosition[node] ?: FloatArray(3)
            val jointWorld = Mat4.compose(position, rotation, floatArrayOf(1f, 1f, 1f))
            val skinned = Mat4.multiply(jointWorld, rig.inverseBinds[k])
            val bone = Mat4.multiply(inverseMesh, skinned)
            bone.copyInto(result, k * 16)
        }
        return result
    }

    private fun rotate(q: Quat, v: FloatArray): FloatArray {
        val m = q.toMatrix3()
        return floatArrayOf(
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
            m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
            m[2] * v[0] + m[5] * v[1] + m[8] * v[2]
        )
    }
}
