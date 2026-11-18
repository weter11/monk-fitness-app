package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rig contract of `humanoid_v2`, measured on the asset that ships: every required bone present,
 * TRS nodes only, finite inverse binds that invert their rest world, at most four influences, one
 * skinned renderable, and the triangle budget.
 */
class RigAssetContractTest {

    private fun rig(): GltfRig {
        val file = File("src/main/assets/characters/humanoid_v2.glb")
        assertTrue("generated asset missing: ${file.absolutePath}", file.exists())
        return GltfRig.parse(file.readBytes())
    }

    @Test
    fun theAssetPassesEveryContractCheck() {
        val report = RigAssetValidator.audit(rig())
        assertTrue("rig contract failures: ${report.summary()}", report.ok)
    }

    @Test
    fun allTwentyThreeMappedBonesAreSkinJoints() {
        val report = RigAssetValidator.audit(rig())
        assertEquals("mapped bones present: ${report.mappedBones}", BoneMap.byJoint.size, report.mappedBones.size)
        assertTrue("missing mapped bones: ${report.missingBones}", report.missingBones.isEmpty())
    }

    @Test
    fun theElevenVisualOnlyJointsAreReportedNotFailed() {
        val report = RigAssetValidator.audit(rig())
        assertEquals("23 mapped + 11 visual-only skin joints", 34, report.jointCount)
        assertEquals(BoneMap.VISUAL_ONLY.toSet(), report.visualOnly.toSet())
        assertTrue("a visual-only joint must never be a failure", report.ok)
    }

    @Test
    fun theSkinIsFourInfluencesAtMostAndOneRenderable() {
        val report = RigAssetValidator.audit(rig())
        assertTrue("influences per vertex ${report.maxInfluencesPerVertex}", report.maxInfluencesPerVertex in 1..4)
        assertEquals(1, report.skinnedRenderables)
        assertTrue("finite inverse binds", report.nonFiniteInverseBinds.isEmpty())
        assertTrue("TRS nodes only, no raw matrix: ${report.matrixNodes}", report.matrixNodes.isEmpty())
        assertTrue("no duplicate node names", report.duplicateNames.isEmpty())
    }

    @Test
    fun theMorphTargetsKeepTheirNamesAndOrder() {
        val rig = rig()
        assertEquals(5, rig.morphTargetCount)
        assertEquals(
            listOf("ShoulderWidth", "ChestVolume", "WaistWidth", "LimbThickness", "HeadScale"),
            rig.morphTargetNames
        )
    }

    @Test
    fun theRigCarriesTheScapulaPairTheEngineDrives() {
        val rig = rig()
        val resolved = MonkJointResolver.resolve(rig)
        assertEquals("every mapped joint resolves onto the rig", BoneMap.byJoint.keys, resolved.keys)
        assertTrue(Joint.SCAPULA_A in resolved && Joint.SCAPULA_P in resolved)
    }
}
