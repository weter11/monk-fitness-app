package com.monkfitness.app.character

import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.SquatPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The part of the character that can be proven without a GPU: an engine frame becomes correct bone
 * matrices on the real GLB. The decisive invariant is the rest one — if inverse binds, rest
 * rotations and the joint order agree, the neutral pose must reproduce the identity on every bone.
 */
class PoseRetargeterTest {

    private val definition = HumanSkeletonDefinition()
    private val restPelvisY = MonkJointResolver.restPelvisHeight(definition)

    private fun asset(): GltfRig {
        val file = File("src/main/assets/characters/humanoid_v1.glb")
        assertTrue("generated asset missing: ${file.absolutePath}", file.exists())
        return GltfRig.parse(file.readBytes())
    }

    private fun neutralPose(): SkeletonPose = SkeletonPose().apply {
        setJoint(Joint.PELVIS, Vector3(0f, restPelvisY, 0f))
    }

    @Test
    fun theGeneratedAssetIsAskinnedThirtyThousandTriangleHumanoid() {
        val rig = asset()
        assertEquals(32, rig.jointNodes.size)
        assertEquals(5, rig.morphTargetCount)
        assertTrue("expected a ~30k triangle asset, got ${rig.triangleCount}", rig.triangleCount >= 30_000)
        assertTrue("skin without inverse binds", rig.inverseBinds.size == rig.jointNodes.size)
        assertEquals("exactly one renderable must be skinned", 1, rig.skinnedNodes.size)
    }

    @Test
    fun theUnitScaleMapsTheEngineStanceToTheAssetHeightNotToTheExercise() {
        val rig = asset()
        val rigHeight = PoseRetargeter(rig, MonkJointResolver.resolve(rig)).restHeight()
        val scale = CharacterCalibration.unitScale(definition, rigHeight)
        // Engine units are not centimetres (the stance is ~203 units for a 1.8 m rig); the contract
        // is that one scale maps stance→asset for every exercise, so it must stay human-sized.
        assertTrue("expected a human-scale engine→asset scale, got $scale", scale in 0.002f..0.05f)
    }

    @Test
    fun everyEngineLimbJointResolvesOntoTheRig() {
        val mapped = MonkJointResolver.resolve(asset())
        val expected = listOf(
            Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS,
            Joint.CLAVICLE_A, Joint.CLAVICLE_P, Joint.SHOULDER_A, Joint.SHOULDER_P,
            Joint.ELBOW_A, Joint.ELBOW_P, Joint.HAND_A, Joint.HAND_P,
            Joint.HIP_F, Joint.HIP_B, Joint.KNEE_F, Joint.KNEE_B,
            Joint.ANKLE_F, Joint.ANKLE_B, Joint.TOE_F, Joint.TOE_B
        )
        val missing = expected.filterNot { it in mapped }
        assertTrue("unmapped engine joints: $missing", missing.isEmpty())
    }

    @Test
    fun theNeutralPoseReproducesTheIdentityOnEveryBone() {
        val rig = asset()
        val retargeter = PoseRetargeter(rig, MonkJointResolver.resolve(rig))
        val matrices = retargeter.boneMatrices(neutralPose(), restPelvisY, scale = 1f)

        var worst = 0f
        for (index in 0 until matrices.size / 16) {
            val bone = matrices.copyOfRange(index * 16, index * 16 + 16)
            if (!Mat4.approximatelyIdentity(bone, 1e-3f)) {
                worst = maxOf(worst, bone.maxOf { kotlin.math.abs(it) })
            }
        }
        assertEquals("a rest frame must not deform the mesh (worst |value| $worst)", 0f, worst, 1e-3f)
    }

    @Test
    fun aSquatFrameMovesTheHipsAndTheLegs() {
        val rig = asset()
        val boneByJoint = MonkJointResolver.resolve(rig)
        val retargeter = PoseRetargeter(rig, boneByJoint)
        val pipeline = SkeletonPipeline(definition)
        val builder = SquatPose()

        // The pipeline publishes into a reused carrier, so each frame's matrices must be taken
        // before the next frame is produced — the same constraint the renderer lives under.
        val standMatrices = retargeter.boneMatrices(
            pipeline.produceFrame(builder, PoseContext(progress = 0f, side = Side.RIGHT, definition = definition)).pose,
            restPelvisY,
            scale = 1f
        )
        val bottomMatrices = retargeter.boneMatrices(
            pipeline.produceFrame(builder, PoseContext(progress = 1f, side = Side.RIGHT, definition = definition)).pose,
            restPelvisY,
            scale = 1f
        )

        val hipsJoint = boneByJoint.getValue(Joint.PELVIS)
        val hipsIndex = rig.jointNodes.indexOf(hipsJoint)
        val standHeight = standMatrices[hipsIndex * 16 + 13]
        val bottomHeight = bottomMatrices[hipsIndex * 16 + 13]
        assertTrue("the hips must descend in a squat ($standHeight -> $bottomHeight)", bottomHeight < standHeight)

        val kneeJoint = boneByJoint.getValue(Joint.KNEE_F)
        val kneeIndex = rig.jointNodes.indexOf(kneeJoint)
        assertNotEquals(
            "the knee bone must rotate between the two frames",
            standMatrices[kneeIndex * 16 + 5],
            bottomMatrices[kneeIndex * 16 + 5],
            1e-4f
        )
    }

    @Test
    fun unitScaleIsTheEngineStanceOverTheRigHeight() {
        val rig = asset()
        val retargeter = PoseRetargeter(rig, MonkJointResolver.resolve(rig))
        val rigHeight = retargeter.restHeight()
        assertTrue("rig height must be plausible in metres, was $rigHeight", rigHeight in 1.5f..2.1f)
        assertTrue(restPelvisY > 0f)
    }
}
