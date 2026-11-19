package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

/** The root displacement is `B⁻¹ · Δp / s`: basis first, then divide by the generation scale. */
class PoseRetargeterPelvisTranslationTest {

    private val rig = RetargetFixtures.assetRig()
    private val boneByJoint = RetargetFixtures.boneByJoint(rig)
    private val retargeter = RetargetFixtures.retargeter(rig)
    private val rest = RetargetFixtures.bind.getValue(Joint.PELVIS).y
    private val scale = RetargetFixtures.unitsPerMetre

    private fun hipsFor(x: Float, y: Float, z: Float): FloatArray {
        val pose = RetargetFixtures.x1Pose().apply { setJoint(Joint.PELVIS, Vector3(x, y, z)) }
        val matrices = retargeter.boneMatrices(pose, scale)
        return RetargetFixtures.boneMatrix(matrices, rig, Joint.PELVIS, boneByJoint)
    }

    private fun assertTranslation(expected: FloatArray, actual: FloatArray) {
        assertEquals("asset X", expected[0], actual[12], 1e-4f)
        assertEquals("asset Y", expected[1], actual[13], 1e-4f)
        assertEquals("asset Z", expected[2], actual[14], 1e-4f)
    }

    @Test
    fun aVerticalDisplacementDividesByTheGenerationScale() {
        val hips = hipsFor(0f, rest + 235f, 0f)
        assertTranslation(floatArrayOf(0f, 0.95f, 0f), hips)
    }

    @Test
    fun aForwardDisplacementTakesTheBasisBeforeTheScale() {
        // Engine +X forward is asset +Z; dividing without B⁻¹ would land on asset +X, i.e. engine −Z.
        val hips = hipsFor(235f, rest, 0f)
        assertTranslation(floatArrayOf(0f, 0f, 0.95f), hips)
    }

    @Test
    fun aCombinedDisplacementKeepsBothAxes() {
        val hips = hipsFor(235f, rest + 235f, 0f)
        assertTranslation(floatArrayOf(0f, 0.95f, 0.95f), hips)
    }
}
