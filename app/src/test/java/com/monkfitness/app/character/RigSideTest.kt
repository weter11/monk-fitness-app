package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Handedness, in signed coordinates. A magnitude check cannot see a left/right swap — a mirrored rig
 * is as far from itself as from its mirror — so every row here carries a sign: asset `+X` is the
 * anatomy's side A, asset `+Z` is anterior, and the heel row is the one the basis alone cannot fix.
 */
class RigSideTest {

    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(definition)
    private val rig: GltfRig = GltfRig.parse(
        File("src/main/assets/characters/humanoid_v2.glb").readBytes()
    )
    private val world: Array<FloatArray> = rig.restWorldTranslations()

    private fun pos(bone: String): FloatArray {
        val index = rig.nodeByName(bone) ?: error("rig has no bone $bone")
        return world[index]
    }

    @Test
    fun leftBonesSitOnPositiveAssetXAndRightBonesOnNegative() {
        val leftArm = pos("LeftArm")
        val rightArm = pos("RightArm")
        assertTrue("LeftArm must sit at +x (anatomy side A), was ${leftArm[0]}", leftArm[0] > 0.15f)
        assertTrue("RightArm must sit at −x, was ${rightArm[0]}", rightArm[0] < -0.15f)
        assertEquals("the two sides mirror in x", leftArm[0], -rightArm[0], 1e-5f)

        for (bone in listOf("LeftUpLeg", "LeftLeg", "LeftFoot", "LeftToeBase")) {
            assertTrue("$bone must sit at +x", pos(bone)[0] > 0f)
        }
        for (bone in listOf("RightUpLeg", "RightLeg", "RightFoot", "RightToeBase")) {
            assertTrue("$bone must sit at −x", pos(bone)[0] < 0f)
        }
    }

    @Test
    fun bothToesPointAnteriorlyInAssetZ() {
        val leftToe = pos("LeftToeBase")
        assertTrue("LeftToeBase must be anterior (+z), was ${leftToe[2]}", leftToe[2] > 0.05f)
        assertTrue("the toe is in front of the ankle", leftToe[2] > pos("LeftFoot")[2])
        assertTrue("RightToeBase must be anterior too", pos("RightToeBase")[2] > 0.05f)
    }

    @Test
    fun bothHeelsSitPosteriorToTheAnkleAndDoNotMirrorInX() {
        // The heel is an engine joint with no bone (BoneMap.UNMAPPED), so this sign lives in X1
        // itself: mirroring the lateral axis must not also mirror the heel's anterior/posterior one.
        val bind = X1Bind.bindJoints(definition)
        val leftHeel = X1Bind.toAsset(bind.getValue(Joint.HEEL_F), unitsPerMetre)
        val rightHeel = X1Bind.toAsset(bind.getValue(Joint.HEEL_B), unitsPerMetre)
        val leftAnkle = X1Bind.toAsset(bind.getValue(Joint.ANKLE_F), unitsPerMetre)
        val toe = X1Bind.toAsset(bind.getValue(Joint.TOE_F), unitsPerMetre)

        assertTrue("heel must be posterior in asset z, was ${leftHeel.z}", leftHeel.z < 0f)
        assertTrue("the other heel must be posterior too, was ${rightHeel.z}", rightHeel.z < 0f)
        assertEquals("both heels sit the same distance behind the ankle", leftHeel.z, rightHeel.z, 1e-6f)
        assertEquals(
            "the heel is one foot·heelRatio behind, in engine units",
            -definition.foot.footLength * definition.foot.heelRatio,
            bind.getValue(Joint.HEEL_F).x,
            1e-4f
        )
        assertTrue("the toe is anterior of the ankle", toe.z > leftAnkle.z)
        assertEquals("only the lateral axis mirrors between the heels", -leftHeel.x, rightHeel.x, 1e-6f)
    }

    @Test
    fun theEngineAndAssetSidesDisagreeOnlyByTheDeclaredBasis() {
        // Engine side A is at −Z; the asset puts it at +X. A rig that mirrored the asset without the
        // basis would satisfy the node signs above and still place the left arm on the body's right.
        val leftArm = X1Bind.bindJoints(definition).getValue(Joint.SHOULDER_A)
        assertTrue("engine side A sits at negative z", leftArm.z < 0f)
        val asset = X1Bind.toAsset(leftArm, unitsPerMetre)
        assertTrue("and at positive asset x once the basis is applied", asset.x > 0f)
        assertEquals("the basis leaves the vertical axis alone", leftArm.y / unitsPerMetre, asset.y, 1e-5f)
        val back = X1Bind.toEngine(asset, unitsPerMetre)
        assertTrue("engine round trip", abs(back.x - leftArm.x) < 1e-3f)
        assertEquals("engine round trip z", leftArm.z, back.z, 1e-3f)
    }
}
