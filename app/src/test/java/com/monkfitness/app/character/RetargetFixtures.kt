package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

internal object RetargetFixtures {

    val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
    val unitsPerMetre: Float = X1Bind.unitsPerMetre(definition)
    val bind: Map<Joint, Vector3> = X1Bind.bindJoints(definition)

    fun assetRig(): GltfRig {
        val file = File("src/main/assets/characters/humanoid_v2.glb")
        check(file.isFile) { "generated asset missing: ${file.absolutePath}" }
        return GltfRig.parse(file.readBytes())
    }

    fun boneByJoint(rig: GltfRig): Map<Joint, Int> = MonkJointResolver.resolve(rig)

    fun retargeter(rig: GltfRig = assetRig()): PoseRetargeter =
        PoseRetargeter(rig, boneByJoint(rig), BoneMap.basis, definition)

    fun x1Pose(): SkeletonPose = SkeletonPose().apply {
        bind.forEach { (joint, position) -> setJoint(joint, position) }
    }

    fun pose(overrides: Map<Joint, Vector3>, rootRotation: Quat = Quat.IDENTITY): SkeletonPose =
        SkeletonPose().apply {
            bind.forEach { (joint, position) -> setJoint(joint, overrides[joint] ?: position) }
            if (rootRotation != Quat.IDENTITY) {
                val angle = 2f * acos(rootRotation.w.coerceIn(-1f, 1f))
                val s = sqrt(1f - rootRotation.w * rootRotation.w).coerceAtLeast(1e-6f)
                setJointRotation(
                    Joint.PELVIS,
                    JointRotation(
                        Vector3(rootRotation.x / s, rootRotation.y / s, rootRotation.z / s),
                        angle
                    )
                )
            }
        }

    fun rotate(q: Quat, v: Vector3): Vector3 {
        val m = q.toMatrix3()
        return Vector3(
            m[0] * v.x + m[3] * v.y + m[6] * v.z,
            m[1] * v.x + m[4] * v.y + m[7] * v.z,
            m[2] * v.x + m[5] * v.y + m[8] * v.z
        )
    }

    fun boneMatrix(matrices: FloatArray, rig: GltfRig, joint: Joint, boneByJoint: Map<Joint, Int>): FloatArray {
        val node = boneByJoint.getValue(joint)
        val index = rig.jointNodes.indexOf(node)
        check(index >= 0) { "$joint is not a skin joint" }
        return matrices.copyOfRange(index * 16, index * 16 + 16)
    }

    fun worldRotation(matrix: FloatArray): Quat = quatOf(matrix)

    /** Rotation part of a column-major affine matrix, assuming uniform positive scale. */
    private fun quatOf(m: FloatArray): Quat {
        val r00 = m[0]; val r01 = m[4]; val r02 = m[8]
        val r10 = m[1]; val r11 = m[5]; val r12 = m[9]
        val r20 = m[2]; val r21 = m[6]; val r22 = m[10]
        val trace = r00 + r11 + r22
        val q = when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                Quat((r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, 0.25f * s)
            }
            r00 > r11 && r00 > r22 -> {
                val s = sqrt(1f + r00 - r11 - r22) * 2f
                Quat(0.25f * s, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s)
            }
            r11 > r22 -> {
                val s = sqrt(1f + r11 - r00 - r22) * 2f
                Quat((r01 + r10) / s, 0.25f * s, (r12 + r21) / s, (r02 - r20) / s)
            }
            else -> {
                val s = sqrt(1f + r22 - r00 - r11) * 2f
                Quat((r02 + r20) / s, (r12 + r21) / s, 0.25f * s, (r10 - r01) / s)
            }
        }
        return q.normalized()
    }

    fun direction(matrix: FloatArray, v: Vector3 = Vector3(1f, 0f, 0f)): Vector3 = Vector3(
        matrix[0] * v.x + matrix[4] * v.y + matrix[8] * v.z,
        matrix[1] * v.x + matrix[5] * v.y + matrix[9] * v.z,
        matrix[2] * v.x + matrix[6] * v.y + matrix[10] * v.z
    )

    fun angleDegrees(q: Quat): Float = Math.toDegrees(2.0 * acos(abs(q.w).coerceIn(0f, 1f).toDouble())).toFloat()

    fun angleBetween(a: Vector3, b: Vector3): Float {
        val na = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val nb = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        val dot = ((a.x * b.x + a.y * b.y + a.z * b.z) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(dot).toDouble()).toFloat()
    }

    fun axisOf(q: Quat): Vector3 {
        val sign = if (q.w < 0f) -1f else 1f
        val s = sqrt(1f - q.w * q.w).coerceAtLeast(1e-6f)
        return Vector3(sign * q.x / s, sign * q.y / s, sign * q.z / s)
    }

    fun matrixClose(a: FloatArray, b: FloatArray, tolerance: Float): Boolean {
        for (i in 0 until 16) if (abs(a[i] - b[i]) > tolerance) return false
        return true
    }

    fun worstMatrixDelta(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (i in 0 until 16) worst = maxOf(worst, abs(a[i] - b[i]))
        return worst
    }

    /** Nodes in a single tree; every node is a skin joint and every inverse bind is identity. */
    fun chainRig(names: List<String>, parents: List<Int>, translations: List<Vector3>): GltfRig {
        val children = Array(names.size) { ArrayList<Int>() }
        parents.forEachIndexed { index, parent -> if (parent >= 0) children[parent].add(index) }
        val nodes = names.indices.map { i ->
            GltfNode(
                index = i,
                name = names[i],
                parent = parents[i],
                translation = floatArrayOf(translations[i].x, translations[i].y, translations[i].z),
                rotation = Quat.IDENTITY,
                scale = floatArrayOf(1f, 1f, 1f),
                children = children[i].toIntArray()
            )
        }
        return GltfRig(
            nodes = nodes,
            sceneRoots = intArrayOf(0),
            jointNodes = IntArray(names.size) { it },
            skinnedNodes = intArrayOf(0),
            inverseBinds = List(names.size) { Mat4.identity() },
            triangleCount = 0,
            morphTargetCount = 0,
            animationNames = emptyList()
        )
    }

    fun rigidPose(rotation: Quat, translation: Vector3, pivot: Vector3): SkeletonPose {
        val moved = bind.mapValues { (_, position) ->
            val relative = Vector3(position.x - pivot.x, position.y - pivot.y, position.z - pivot.z)
            val rotated = rotate(rotation, relative)
            Vector3(rotated.x + pivot.x + translation.x, rotated.y + pivot.y + translation.y, rotated.z + pivot.z + translation.z)
        }
        return pose(moved, rotation)
    }

    fun expectedRigidAsset(rig: GltfRig, rotation: Quat, translation: Vector3): FloatArray {
        val qAsset = BoneMap.basis.conjugate() * rotation * BoneMap.basis
        val pelvis = requireNotNull(rig.nodeByNormalizedName("Hips")) { "asset has no Hips node" }
        val rest = rig.restWorldTransforms()[pelvis]
        val restPelvis = Vector3(rest[12], rest[13], rest[14])
        val delta = rotate(BoneMap.basis.conjugate(), translation)
        val dAsset = Vector3(delta.x / unitsPerMetre, delta.y / unitsPerMetre, delta.z / unitsPerMetre)
        val rotatedRest = rotate(qAsset, restPelvis)
        val t = floatArrayOf(
            restPelvis.x + dAsset.x - rotatedRest.x,
            restPelvis.y + dAsset.y - rotatedRest.y,
            restPelvis.z + dAsset.z - rotatedRest.z
        )
        return Mat4.compose(t, qAsset, floatArrayOf(1f, 1f, 1f))
    }

    fun jointAngleBetween(q: Quat, a: Vector3, b: Vector3): Float =
        angleBetween(rotate(q, a), b)

    fun axisAngle(axis: Vector3, degrees: Float): Quat =
        Quat.fromAxisAngle(axis.x, axis.y, axis.z, Math.toRadians(degrees.toDouble()).toFloat())
}
