package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Position-driven retarget: a bone's world rotation is the correction that turns its parent-carried
 * bind direction onto the published segment, so only positions and the asset basis decide the frame.
 */
class PoseRetargeter(
    private val rig: GltfRig,
    private val boneByJoint: Map<Joint, Int>,
    private val basis: Quat,
    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
) {
    private val jointByNode: Map<Int, Joint> =
        boneByJoint.entries.associate { (joint, node) -> node to joint }
    private val restWorldRotations: Array<Quat> = rig.restWorldRotations()
    private val restWorldPositions: Array<FloatArray> = rig.restWorldTranslations()
    private val bindPose = X1Bind.bindJoints(definition)
    private val segments: Map<Joint, Pair<Joint, Joint>> = boneSegments()
    private val bindDirections: Map<Joint, FloatArray> = bindDirections()
    private val pelvisNode: Int? = boneByJoint[Joint.PELVIS]
    private val restPelvisY: Float = bindPose.getValue(Joint.PELVIS).y

    // Reused across frames: the render path calls this once per frame and never re-enters it.
    private val worldRotations = Array(rig.nodes.size) { Quat.IDENTITY }
    private val worldPositions = Array(rig.nodes.size) { FloatArray(3) }

    val mappedJointCount: Int get() = boneByJoint.size
    val unmappedJointCount: Int get() = Joint.entries.size - boneByJoint.size

    /** Bone matrices in skin order, ready for `RenderableManager.setBonesAsMatrices`. */
    fun boneMatrices(
        pose: SkeletonPose,
        unitsPerMetre: Float,
        meshWorld: FloatArray = Mat4.identity(),
        out: FloatArray? = null
    ): FloatArray {
        require(unitsPerMetre > 0f) { "unitsPerMetre must be positive, was $unitsPerMetre" }
        // Rest offsets already live in asset units; only the published root displacement crosses from
        // engine units, and it is divided by the scale the rig was generated at.
        val jointCount = rig.jointNodes.size
        val result = out ?: FloatArray(jointCount * 16)
        require(result.size >= jointCount * 16) { "out must hold ${jointCount * 16} floats" }

        val root = pelvisNode ?: rig.sceneRoots.firstOrNull()
        if (root != null) walk(root, Quat.IDENTITY, FloatArray(3), pose, unitsPerMetre)

        val inverseMesh = Mat4.inverse(meshWorld)
        val unitScale = floatArrayOf(1f, 1f, 1f)
        for (k in 0 until jointCount) {
            val node = rig.jointNodes[k]
            val jointWorld = Mat4.compose(worldPositions[node], worldRotations[node], unitScale)
            val skinned = Mat4.multiply(jointWorld, rig.inverseBinds[k])
            val bone = Mat4.multiply(inverseMesh, skinned)
            bone.copyInto(result, k * 16)
        }
        return result
    }

    private fun walk(
        index: Int,
        parentRotation: Quat,
        parentPosition: FloatArray,
        pose: SkeletonPose,
        unitsPerMetre: Float
    ) {
        val node = rig.nodes[index]
        val joint = jointByNode[index]
        val position = worldPositions[index]
        val world: Quat

        if (joint == Joint.PELVIS) {
            val rotation = pose.getJointRotation(joint)
            val rootDelta = Quat.fromAxisAngle(
                rotation.axis.x, rotation.axis.y, rotation.axis.z, rotation.angle
            )
            world = basis.conjugate() * rootDelta * basis * restWorldRotations[index]
            position[0] = restWorldPositions[index][0]
            position[1] = restWorldPositions[index][1]
            position[2] = restWorldPositions[index][2]
            val pelvis = pose.getJoint(joint)
            val delta = rotate(
                basis.conjugate(),
                floatArrayOf(pelvis.x, pelvis.y - restPelvisY, pelvis.z)
            )
            position[0] += delta[0] / unitsPerMetre
            position[1] += delta[1] / unitsPerMetre
            position[2] += delta[2] / unitsPerMetre
        } else {
            val parent = node.parent
            val offset = if (parent >= 0) {
                floatArrayOf(
                    restWorldPositions[index][0] - restWorldPositions[parent][0],
                    restWorldPositions[index][1] - restWorldPositions[parent][1],
                    restWorldPositions[index][2] - restWorldPositions[parent][2]
                )
            } else {
                restWorldPositions[index].copyOf()
            }
            val rotated = rotate(parentRotation, offset)
            position[0] = parentPosition[0] + rotated[0]
            position[1] = parentPosition[1] + rotated[1]
            position[2] = parentPosition[2] + rotated[2]

            val segment = if (joint != null) segments[joint] else null
            world = if (joint == null || segment == null) {
                parentRotation
            } else {
                val bindDirection = bindDirections.getValue(joint)
                if (length(bindDirection) < 1e-6f) {
                    // A coincident child has no direction of its own; it rides the parent's frame.
                    parentRotation
                } else {
                    val transported = rotate(basis, rotate(parentRotation, bindDirection))
                    val from = pose.getJoint(segment.first)
                    val to = pose.getJoint(segment.second)
                    val published = normalize(
                        floatArrayOf(to.x - from.x, to.y - from.y, to.z - from.z)
                    )
                    val correction = if (published == null) {
                        Quat.IDENTITY
                    } else {
                        shortestRotation(transported, published)
                    }
                    basis.conjugate() * correction * basis * parentRotation
                }
            }
        }

        worldRotations[index] = world
        node.children.forEach { child -> walk(child, world, position, pose, unitsPerMetre) }
    }

    private fun boneSegments(): Map<Joint, Pair<Joint, Joint>> = buildMap {
        put(Joint.LUMBAR, Joint.LUMBAR to Joint.CHEST)
        put(Joint.CHEST, Joint.CHEST to Joint.NECK_END)
        put(Joint.NECK_END, Joint.NECK_END to Joint.HEAD_POS)
        // The head has no published successor, so it shares its parent's segment and stays rigid.
        put(Joint.HEAD_POS, Joint.NECK_END to Joint.HEAD_POS)

        for (side in SIDES) {
            put(side.clavicle, side.clavicle to side.scapula)
            put(side.scapula, side.scapula to side.shoulder)
            put(side.shoulder, side.shoulder to side.elbow)
            put(side.elbow, side.elbow to side.hand)
            // The hand's segment ends at the fingertips rather than at a rig node: the engine has no
            // bone there, but the direction is published and it orients the wrist.
            put(side.hand, side.hand to side.fingertips)
        }

        for (leg in LEGS) {
            put(leg.hip, leg.hip to leg.knee)
            put(leg.knee, leg.knee to leg.ankle)
            put(leg.ankle, leg.ankle to leg.toe)
            put(leg.toe, leg.ankle to leg.toe)
        }
    }

    private fun bindDirections(): Map<Joint, FloatArray> = buildMap {
        segments.forEach { (bone, segment) ->
            val from = bindPose.getValue(segment.first)
            val to = bindPose.getValue(segment.second)
            val direction = rotate(
                basis.conjugate(),
                floatArrayOf(to.x - from.x, to.y - from.y, to.z - from.z)
            )
            put(bone, normalize(direction) ?: FloatArray(3))
        }
    }

    /** The shorter of the two rotations taking [from] onto [to], with a fixed tie-break for antipodes. */
    private fun shortestRotation(from: FloatArray, to: FloatArray): Quat {
        val a = normalize(from) ?: return Quat.IDENTITY
        val b = normalize(to) ?: return Quat.IDENTITY
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        if (dot > 1f - 1e-6f) return Quat.IDENTITY
        if (dot < -1f + 1e-6f) {
            // Half turn about the axis least aligned with `a`, so a straight segment does not get an
            // arbitrary roll from numerical noise.
            val ax = abs(a[0])
            val ay = abs(a[1])
            val az = abs(a[2])
            val axis = when {
                ax <= ay && ax <= az -> normalize(cross(a, floatArrayOf(1f, 0f, 0f)))
                ay <= az -> normalize(cross(a, floatArrayOf(0f, 1f, 0f)))
                else -> normalize(cross(a, floatArrayOf(0f, 0f, 1f)))
            } ?: return Quat.IDENTITY
            return Quat(axis[0], axis[1], axis[2], 0f)
        }
        val axis = cross(a, b)
        return Quat(axis[0], axis[1], axis[2], 1f + dot).normalized()
    }

    private fun rotate(q: Quat, v: FloatArray): FloatArray {
        val m = q.toMatrix3()
        return floatArrayOf(
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
            m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
            m[2] * v[0] + m[5] * v[1] + m[8] * v[2]
        )
    }

    private fun normalize(v: FloatArray): FloatArray? {
        val length = length(v)
        if (length < 1e-6f) return null
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    private fun length(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private class ArmJoints(
        val clavicle: Joint,
        val scapula: Joint,
        val shoulder: Joint,
        val elbow: Joint,
        val hand: Joint,
        val fingertips: Joint
    )

    private class LegJoints(val hip: Joint, val knee: Joint, val ankle: Joint, val toe: Joint)

    private companion object {
        val SIDES = listOf(
            ArmJoints(Joint.CLAVICLE_A, Joint.SCAPULA_A, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, Joint.FINGERTIPS_A),
            ArmJoints(Joint.CLAVICLE_P, Joint.SCAPULA_P, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, Joint.FINGERTIPS_P)
        )

        val LEGS = listOf(
            LegJoints(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.TOE_F),
            LegJoints(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.TOE_B)
        )
    }
}
