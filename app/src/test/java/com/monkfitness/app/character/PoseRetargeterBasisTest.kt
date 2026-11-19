package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The only rows that can see the basis: a rigid engine motion must move every bone as one rigid
 * transform, and the two pinned transport cases must reproduce the declared correction.
 */
class PoseRetargeterBasisTest {

    private val rig = RetargetFixtures.assetRig()
    private val boneByJoint = RetargetFixtures.boneByJoint(rig)

    @Test
    fun aRigidEngineMotionMovesEveryBoneAsOneRigidTransform() {
        val rotation = RetargetFixtures.axisAngle(Vector3(1f, 0f, 0f), 90f)
        val translation = Vector3(100f, 0f, 0f)
        val pivot = RetargetFixtures.bind.getValue(Joint.PELVIS)
        val pose = RetargetFixtures.rigidPose(rotation, translation, pivot)
        val expected = RetargetFixtures.expectedRigidAsset(rig, rotation, translation)

        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        var worst = 0f
        for (index in 0 until matrices.size / 16) {
            worst = maxOf(
                worst,
                RetargetFixtures.worstMatrixDelta(
                    matrices.copyOfRange(index * 16, index * 16 + 16),
                    expected
                )
            )
        }
        assertTrue("every bone must equal the one rigid asset transform (worst $worst)", worst < 2e-4f)

        val identityBasis = PoseRetargeter(rig, boneByJoint, Quat.IDENTITY, RetargetFixtures.definition)
            .boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        var worstIdentity = 0f
        for (index in 0 until identityBasis.size / 16) {
            worstIdentity = maxOf(
                worstIdentity,
                RetargetFixtures.worstMatrixDelta(
                    identityBasis.copyOfRange(index * 16, index * 16 + 16),
                    expected
                )
            )
        }
        assertTrue("dropping the basis must change the frame (worst $worstIdentity)", worstIdentity > 1e-2f)
    }

    @Test
    fun aTransportedBindDirectionNeedsNoCorrection() {
        val parent = RetargetFixtures.axisAngle(Vector3(0f, 0f, 1f), 90f)
        val rig = chain()
        val pose = poseFor(rig, parent, published = Vector3(0f, 1f, 0f))
        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)

        val scapula = RetargetFixtures.boneMatrix(matrices, rig, Joint.SCAPULA_A, BONES)
        val world = RetargetFixtures.worldRotation(scapula)
        val correction = BoneMap.basis * world * parent.conjugate() * BoneMap.basis.conjugate()

        assertEquals("the transported direction already equals the published one", 0f, RetargetFixtures.angleDegrees(correction), 1e-2f)
        val direction = RetargetFixtures.direction(scapula)
        assertEquals("the frame must land on the published direction", 0f, RetargetFixtures.angleBetween(direction, Vector3(0f, 1f, 0f)), 1e-2f)
    }

    @Test
    fun aNonCommutingParentSwingReproducesTheDeclaredCorrection() {
        val parent = RetargetFixtures.axisAngle(Vector3(0f, 0f, 1f), 45f)
        val rig = chain()
        val published = normalize(Vector3(0.3f, 0.9f, 0.3f))
        val pose = poseFor(rig, parent, published)
        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)

        val scapula = RetargetFixtures.boneMatrix(matrices, rig, Joint.SCAPULA_A, BONES)
        val world = RetargetFixtures.worldRotation(scapula)
        val correction = BoneMap.basis * world * parent.conjugate() * BoneMap.basis.conjugate()

        assertEquals("declared correction angle", 64.7606f, RetargetFixtures.angleDegrees(correction), 0.02f)
        val axis = RetargetFixtures.axisOf(correction)
        val expectedAxis = normalize(Vector3(0.94280904f, -0.23570226f, -0.23570226f))
        assertEquals("declared correction axis", 1f, axis.x * expectedAxis.x + axis.y * expectedAxis.y + axis.z * expectedAxis.z, 1e-3f)

        val targetAsset = rotate(BoneMap.basis.conjugate(), published)
        val assetCorrection = BoneMap.basis.conjugate() * correction * BoneMap.basis
        val untransportedEngine = shortest(Vector3(0f, 0f, -1f), published)
        val untransported = BoneMap.basis.conjugate() * untransportedEngine * BoneMap.basis
        val bindDirection = Vector3(1f, 0f, 0f)

        assertEquals("correct composition", 0f, miss(assetCorrection * parent, bindDirection, targetAsset), 1e-2f)
        assertEquals("reversed order P·D", 3.4211f, miss(parent * assetCorrection, bindDirection, targetAsset), 0.01f)
        assertEquals("untransported correction, reversed order", 42.7997f, miss(parent * untransported, bindDirection, targetAsset), 0.02f)
        assertEquals("untransported correction, composed D·P", 45f, miss(untransported * parent, bindDirection, targetAsset), 0.02f)
        assertEquals("dropped conjugation", 86.3181f, miss(correction * parent, bindDirection, targetAsset), 0.02f)
    }

    private fun chain(): GltfRig = RetargetFixtures.chainRig(
        names = listOf("Hips", "Chest", "Neck", "LeftShoulder", "LeftScapula"),
        parents = listOf(-1, 0, 1, 1, 3),
        translations = listOf(
            Vector3(0f, 0.95f, 0f),
            Vector3(0f, 0.56f, 0f),
            Vector3(0f, 0.06f, 0f),
            Vector3(0f, 0f, 0f),
            Vector3(0.1f, 0f, 0f)
        )
    )

    private fun poseFor(rig: GltfRig, parentAsset: Quat, published: Vector3): com.monkfitness.app.animation.SkeletonPose {
        val chest = RetargetFixtures.bind.getValue(Joint.CHEST)
        val neckDirection = rotate(BoneMap.basis, rotate(parentAsset, Vector3(0f, 1f, 0f)))
        val scapula = chest
        val shoulder = Vector3(
            scapula.x + published.x * 46f,
            scapula.y + published.y * 46f,
            scapula.z + published.z * 46f
        )
        return RetargetFixtures.pose(
            mapOf(
                Joint.CHEST to chest,
                Joint.NECK_END to Vector3(chest.x + neckDirection.x * 120f, chest.y + neckDirection.y * 120f, chest.z + neckDirection.z * 120f),
                Joint.CLAVICLE_A to scapula,
                Joint.SCAPULA_A to scapula,
                Joint.SHOULDER_A to shoulder
            )
        )
    }

    private fun miss(rotation: Quat, direction: Vector3, target: Vector3): Float =
        RetargetFixtures.angleBetween(RetargetFixtures.rotate(rotation, direction), target)

    private fun shortest(from: Vector3, to: Vector3): Quat {
        val a = normalize(from)
        val b = normalize(to)
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
        if (dot > 1f - 1e-6f) return Quat.IDENTITY
        val axis = cross(a, b)
        return Quat(axis.x, axis.y, axis.z, 1f + dot).normalized()
    }

    private fun normalize(v: Vector3): Vector3 {
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Vector3(v.x / length, v.y / length, v.z / length)
    }

    private fun cross(a: Vector3, b: Vector3): Vector3 = Vector3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    )

    private fun rotate(q: Quat, v: Vector3): Vector3 = RetargetFixtures.rotate(q, v)

    private companion object {
        val BONES = mapOf(
            Joint.PELVIS to 0,
            Joint.CHEST to 1,
            Joint.NECK_END to 2,
            Joint.CLAVICLE_A to 3,
            Joint.SCAPULA_A to 4
        )
    }
}
