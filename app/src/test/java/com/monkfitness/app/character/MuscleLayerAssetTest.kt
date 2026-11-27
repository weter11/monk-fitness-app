package com.monkfitness.app.character

import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.character.HelperBindSet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The runtime palette must address the authored asset's helpers in manifest order — for each
 * partition's own asset, since each GLB binds only its layer's helpers.
 */
class MuscleLayerAssetTest {

    @Test
    fun eachSkinUsesItsPartitionsAuthoredPaletteInManifestOrder() {
        val atlas = HelperBindSet.read(File(MANIFEST).readText())
        for (layer in MuscleLayer.entries) {
            val binds = atlas.partition(layer)
            val asset = MuscleAsset(File(MuscleExportFixture.assetPath(layer)))
            assertEquals("$layer joint count", binds.palette, asset.rig.jointNodes.size)
            assertEquals("$layer inverse binds", binds.palette, asset.rig.inverseBinds.size)
            for (group in 0 until binds.helperCount / binds.stationCount) {
                for (station in 0 until binds.stationCount) {
                    val helper = binds.binds[binds.helperIndexOf(group, station)]
                    val inverse = asset.rig.inverseBinds[binds.paletteIndex(binds.helperIndexOf(group, station))]
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
            for (primitive in asset.primitives) {
                assertTrue("${primitive.name} has vertices", primitive.positions.isNotEmpty())
                for (vertex in primitive.positions.indices) {
                    var weight = 0f
                    for (slot in 0 until 4) {
                        val joint = primitive.joints[vertex][slot]
                        assertTrue("${primitive.name} vertex $vertex names a helper", joint >= binds.skinJoints)
                        assertTrue("${primitive.name} vertex $vertex is inside its palette", joint < binds.palette)
                        if (primitive.weights[vertex][slot] > 0f) weight += primitive.weights[vertex][slot]
                    }
                    assertEquals("${primitive.name} vertex $vertex weights", 1f, weight, 1e-6f)
                }
            }
        }
    }

    private class MuscleAsset(file: File) {
        val rig: GltfRig = GltfRig.parse(file.readBytes())
        val primitives: List<Primitive>

        init {
            val bytes = file.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.int; buffer.int; buffer.int
            val jsonLength = buffer.int
            buffer.int
            val root = JSONObject(String(ByteArray(jsonLength).also { buffer.get(it) }, Charsets.UTF_8))
            var bin = java.nio.ByteBuffer.allocate(0)
            while (buffer.remaining() >= 8) {
                val length = buffer.int
                val type = buffer.int
                val chunk = ByteArray(length).also { buffer.get(it) }
                if (type == 0x004E4942) bin = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
            val accessors = root.getJSONArray("accessors")
            val views = root.getJSONArray("bufferViews")
            fun layout(index: Int): Triple<Int, Int, Int> {
                val accessor = accessors.getJSONObject(index)
                val view = views.getJSONObject(accessor.getInt("bufferView"))
                val components = when (accessor.getString("type")) {
                    "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
                    else -> error("unexpected accessor type")
                }
                val width = when (accessor.getInt("componentType")) {
                    5126 -> 4; 5123 -> 2; 5125 -> 4
                    else -> error("unexpected component type")
                }
                return Triple(view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0), accessor.getInt("count"), components * width)
            }
            fun vectors(index: Int, components: Int): List<FloatArray> {
                val (base, count, stride) = layout(index)
                return (0 until count).map { vertex ->
                    FloatArray(components) { component -> bin.getFloat(base + vertex * stride + component * 4) }
                }
            }
            fun shorts(index: Int): List<IntArray> {
                val (base, count, stride) = layout(index)
                return (0 until count).map { vertex ->
                    IntArray(4) { component -> bin.getShort(base + vertex * stride + component * 2).toInt() and 0xFFFF }
                }
            }
            val meshes = root.getJSONArray("meshes")
            primitives = (0 until meshes.length()).map { meshIndex ->
                val mesh = meshes.getJSONObject(meshIndex)
                val primitive = mesh.getJSONArray("primitives").getJSONObject(0)
                val attributes = primitive.getJSONObject("attributes")
                Primitive(
                    name = mesh.getString("name"),
                    positions = vectors(attributes.getInt("POSITION"), 3),
                    joints = shorts(attributes.getInt("JOINTS_0")),
                    weights = vectors(attributes.getInt("WEIGHTS_0"), 4)
                )
            }
        }

        class Primitive(
            val name: String,
            val positions: List<FloatArray>,
            val joints: List<IntArray>,
            val weights: List<FloatArray>
        )
    }

    private companion object {
        const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
    }
}
