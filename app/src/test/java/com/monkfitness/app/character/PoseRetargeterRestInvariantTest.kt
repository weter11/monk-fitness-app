package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The neutral frame must reproduce the identity on every skin joint: mapped bones and the crown and
 * finger bones that ride them. The fixture is the full X1 pose — a pelvis-only pose would flatten
 * every segment and pass without exercising a single direction.
 */
class PoseRetargeterRestInvariantTest {

    @Test
    fun theNeutralPoseReproducesTheIdentityOnEveryBone() {
        val rig = RetargetFixtures.assetRig()
        val matrices = RetargetFixtures.retargeter(rig)
            .boneMatrices(RetargetFixtures.x1Pose(), RetargetFixtures.unitsPerMetre)

        assertEquals("the rest invariant must cover the whole skin", 34, matrices.size / 16)
        var worst = 0f
        for (index in 0 until matrices.size / 16) {
            val bone = matrices.copyOfRange(index * 16, index * 16 + 16)
            worst = maxOf(worst, RetargetFixtures.worstMatrixDelta(bone, Mat4.identity()))
        }
        assertTrue("a rest frame must not deform the mesh (worst |value| $worst)", worst < 1e-3f)
    }

    @Test
    fun theRestInvariantIsBlindToTheBasis() {
        val rig = RetargetFixtures.assetRig()
        val boneByJoint = RetargetFixtures.boneByJoint(rig)
        val identity = PoseRetargeter(rig, boneByJoint, Quat.IDENTITY, RetargetFixtures.definition)
            .boneMatrices(RetargetFixtures.x1Pose(), RetargetFixtures.unitsPerMetre)
        val yaw = PoseRetargeter(rig, boneByJoint, BoneMap.basis, RetargetFixtures.definition)
            .boneMatrices(RetargetFixtures.x1Pose(), RetargetFixtures.unitsPerMetre)
        val tilt = PoseRetargeter(rig, boneByJoint, RetargetFixtures.axisAngle(Vector3(0f, 0f, 1f), 37f), RetargetFixtures.definition)
            .boneMatrices(RetargetFixtures.x1Pose(), RetargetFixtures.unitsPerMetre)

        // Identity composed with identity is identity for any basis: this pins the limitation that
        // makes the basis test, not this one, the guard for the runtime transform.
        for (index in 0 until yaw.size / 16) {
            val block = index * 16
            assertTrue("identity basis deformed bone $index", Mat4.approximatelyIdentity(identity.copyOfRange(block, block + 16), 1e-3f))
            assertTrue("yaw basis deformed bone $index", Mat4.approximatelyIdentity(yaw.copyOfRange(block, block + 16), 1e-3f))
            assertTrue("tilt basis deformed bone $index", Mat4.approximatelyIdentity(tilt.copyOfRange(block, block + 16), 1e-3f))
        }
    }

    @Test
    fun theMappedAndUndrivableJointCountsMatchTheMap() {
        val rig = RetargetFixtures.assetRig()
        val retargeter = RetargetFixtures.retargeter(rig)
        assertEquals("the engine's driven joints", 23, retargeter.mappedJointCount)
        assertEquals("declared joints without a bone", 10, retargeter.unmappedJointCount)
    }

    @Test
    fun aCoincidentSegmentInheritsItsParentInsteadOfInventingARoll() {
        val rig = RetargetFixtures.assetRig()
        val retargeter = RetargetFixtures.retargeter(rig)
        val shifted = Vector3(0f, 0f, 12f)
        val bind = RetargetFixtures.bind
        val pose = RetargetFixtures.pose(
            mapOf(
                Joint.CLAVICLE_A to Vector3(
                    bind.getValue(Joint.CLAVICLE_A).x + shifted.x,
                    bind.getValue(Joint.CLAVICLE_A).y + shifted.y,
                    bind.getValue(Joint.CLAVICLE_A).z + shifted.z
                ),
                Joint.SCAPULA_A to Vector3(
                    bind.getValue(Joint.SCAPULA_A).x + shifted.x,
                    bind.getValue(Joint.SCAPULA_A).y + shifted.y,
                    bind.getValue(Joint.SCAPULA_A).z + shifted.z
                )
            )
        )
        val matrices = retargeter.boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        for (value in matrices) assertTrue("retarget produced a non-finite value", value.isFinite())

        val shoulder = RetargetFixtures.boneMatrix(matrices, rig, Joint.CLAVICLE_A, RetargetFixtures.boneByJoint(rig))
        val chest = RetargetFixtures.boneMatrix(matrices, rig, Joint.CHEST, RetargetFixtures.boneByJoint(rig))
        val shoulderWorld = RetargetFixtures.worldRotation(shoulder)
        val chestWorld = RetargetFixtures.worldRotation(chest)
        assertEquals(
            "a zero-length segment must carry its parent's frame",
            0f,
            RetargetFixtures.angleDegrees(chestWorld.conjugate() * shoulderWorld),
            1e-3f
        )
    }
}
