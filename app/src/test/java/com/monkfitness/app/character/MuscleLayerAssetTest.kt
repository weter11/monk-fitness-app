package com.monkfitness.app.character

import com.monkfitness.app.anatomy.MuscleLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The runtime palette must address the authored asset's helpers in manifest order — for each
 * partition's own asset, since each GLB binds only its layer's helpers. The sections are read by the
 * same reader the stage builds its vertex buffers from, so a primitive the renderer could not draw
 * fails here first.
 */
class MuscleLayerAssetTest {

    @Test
    fun eachSkinUsesItsPartitionsAuthoredPaletteInManifestOrder() {
        val atlas = HelperBindSet.read(File(MANIFEST).readText())
        for (layer in MuscleLayer.entries) {
            val binds = atlas.partition(layer)
            val bytes = File(MuscleExportFixture.assetPath(layer)).readBytes()
            val rig = GltfRig.parse(bytes)
            assertEquals("$layer joint count", binds.palette, rig.jointNodes.size)
            assertEquals("$layer inverse binds", binds.palette, rig.inverseBinds.size)
            for (group in 0 until binds.helperCount / binds.stationCount) {
                for (station in 0 until binds.stationCount) {
                    val helper = binds.binds[binds.helperIndexOf(group, station)]
                    val inverse = rig.inverseBinds[binds.paletteIndex(binds.helperIndexOf(group, station))]
                    val bound = floatArrayOf(
                        -(inverse[0] * inverse[12] + inverse[1] * inverse[13] + inverse[2] * inverse[14]),
                        -(inverse[4] * inverse[12] + inverse[5] * inverse[13] + inverse[6] * inverse[14]),
                        -(inverse[8] * inverse[12] + inverse[9] * inverse[13] + inverse[10] * inverse[14])
                    )
                    assertEquals("$layer helper $group/$station bind x", helper.position[0], bound[0], 1e-6f)
                    assertEquals("$layer helper $group/$station bind y", helper.position[1], bound[1], 1e-6f)
                    assertEquals("$layer helper $group/$station bind z", helper.position[2], bound[2], 1e-6f)
                }
            }

            val asset = MuscleMeshAsset.read(bytes)
            assertEquals("$layer skin joints", binds.palette, asset.jointCount)
            assertEquals("$layer section names are unique", asset.sections.size, asset.sections.map { it.name }.toSet().size)
            for (section in asset.sections) {
                assertEquals("${section.name} normals match its vertices", section.positions.size, section.normals.size)
                assertEquals("${section.name} tangents match its vertices", section.vertexCount * 4, section.tangents.size)
                assertEquals("${section.name} uv0 matches its vertices", section.vertexCount * 2, section.uvs.size)
                assertEquals("${section.name} indexes whole triangles", 0, section.indexCount % 3)
                for (vertex in 0 until section.vertexCount) {
                    var weight = 0f
                    for (slot in 0 until 4) {
                        val joint = section.joints[vertex * 4 + slot]
                        assertTrue("${section.name} vertex $vertex names a helper", joint >= binds.skinJoints)
                        assertTrue("${section.name} vertex $vertex is inside its palette", joint < binds.palette)
                        if (section.weights[vertex * 4 + slot] > 0f) weight += section.weights[vertex * 4 + slot]
                    }
                    assertEquals("${section.name} vertex $vertex weights", 1f, weight, 1e-6f)
                    for (component in 0 until 3) {
                        val value = section.positions[vertex * 3 + component]
                        assertTrue(
                            "${section.name} vertex $vertex sits inside the section's bounds",
                            value >= section.bounds[component] && value <= section.bounds[component + 3]
                        )
                    }
                }
                for (index in section.indices) {
                    assertTrue("${section.name} indexes one of its own vertices", index in 0 until section.vertexCount)
                }
            }
        }
    }

    private companion object {
        const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
    }
}
