package com.monkfitness.app.character

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One section's skinned surface exactly as the exported GLB carries it, in the packed arrays a
 * Filament vertex buffer is filled from without a second conversion.
 */
internal class MuscleSectionMesh(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val tangents: FloatArray,
    val uvs: FloatArray,
    /** Four palette slots per vertex, flattened; the renderable's skinning buffer indexes them. */
    val joints: IntArray,
    val weights: FloatArray,
    val indices: IntArray,
    /** `min xyz, max xyz`, the renderable's explicit AABB. */
    val bounds: FloatArray
) {
    val vertexCount: Int get() = positions.size / 3
    val indexCount: Int get() = indices.size
}

/**
 * The muscle GLB read without gltfio: every section's primitive and the joint count its skin binds,
 * so the layer can own its vertex buffers, index buffers and skinning buffer.
 *
 * Reading is strict on purpose: a joint outside the skin, an index outside the section or a weight
 * that does not sum to one is refused by section name here rather than drawn as a corrupt mesh.
 */
internal class MuscleMeshAsset private constructor(
    val jointCount: Int,
    val sections: List<MuscleSectionMesh>
) {

    companion object {
        private const val MAGIC = 0x46546C67
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val FLOAT = 5126
        private const val UNSIGNED_SHORT = 5123
        private const val UNSIGNED_INT = 5125
        private const val MODE_TRIANGLES = 4

        fun read(bytes: ByteArray): MuscleMeshAsset {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == MAGIC) { "not a GLB (bad magic)" }
            buffer.int
            buffer.int
            var root: JSONObject? = null
            var bin = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 8) {
                val length = buffer.int
                val type = buffer.int
                val chunk = ByteArray(length)
                buffer.get(chunk)
                when (type) {
                    CHUNK_JSON -> root = JSONObject(String(chunk, Charsets.UTF_8))
                    CHUNK_BIN -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            val json = root ?: throw IllegalArgumentException("the GLB carries no JSON chunk")

            val accessors = json.getJSONArray("accessors")
            val views = json.getJSONArray("bufferViews")
            fun accessor(index: Int): Accessor {
                val entry = accessors.getJSONObject(index)
                val view = views.getJSONObject(entry.getInt("bufferView"))
                val components = when (entry.getString("type")) {
                    "SCALAR" -> 1
                    "VEC2" -> 2
                    "VEC3" -> 3
                    "VEC4" -> 4
                    else -> throw IllegalArgumentException("accessor $index has type ${entry.getString("type")}")
                }
                val width = when (entry.getInt("componentType")) {
                    FLOAT -> 4
                    UNSIGNED_SHORT -> 2
                    UNSIGNED_INT -> 4
                    else -> throw IllegalArgumentException(
                        "accessor $index has component type ${entry.getInt("componentType")}"
                    )
                }
                val stride = view.optInt("byteStride", components * width)
                return Accessor(
                    base = view.optInt("byteOffset", 0) + entry.optInt("byteOffset", 0),
                    count = entry.getInt("count"),
                    stride = stride,
                    componentType = entry.getInt("componentType"),
                    components = components
                )
            }
            fun floats(index: Int): FloatArray {
                val a = accessor(index)
                require(a.componentType == FLOAT) { "accessor $index is not float" }
                return FloatArray(a.count * a.components) { entry ->
                    bin.getFloat(a.base + (entry / a.components) * a.stride + (entry % a.components) * 4)
                }
            }
            fun unsigned(index: Int): IntArray {
                val a = accessor(index)
                require(a.componentType == UNSIGNED_SHORT || a.componentType == UNSIGNED_INT) {
                    "accessor $index is not an unsigned integer"
                }
                val width = if (a.componentType == UNSIGNED_SHORT) 2 else 4
                return IntArray(a.count * a.components) { entry ->
                    val at = a.base + (entry / a.components) * a.stride + (entry % a.components) * width
                    if (width == 2) bin.getShort(at).toInt() and 0xFFFF else bin.getInt(at)
                }
            }

            val skinJointCount = json.getJSONArray("skins").getJSONObject(0)
                .getJSONArray("joints").length()
            require(skinJointCount > 0) { "the muscle GLB binds no joints" }

            val meshes = json.getJSONArray("meshes")
            val sections = ArrayList<MuscleSectionMesh>(meshes.length())
            for (index in 0 until meshes.length()) {
                val mesh = meshes.getJSONObject(index)
                val name = mesh.optString("name").ifEmpty { "section$index" }
                val primitive = mesh.getJSONArray("primitives").getJSONObject(0)
                require(primitive.optInt("mode", MODE_TRIANGLES) == MODE_TRIANGLES) {
                    "$name is not a triangle primitive"
                }
                val attributes = primitive.getJSONObject("attributes")
                val positions = floats(attributes.getInt("POSITION"))
                val normals = floats(attributes.getInt("NORMAL"))
                val tangents = floats(attributes.getInt("TANGENT"))
                val uvs = floats(attributes.getInt("TEXCOORD_0"))
                val joints = unsigned(attributes.getInt("JOINTS_0"))
                val weights = floats(attributes.getInt("WEIGHTS_0"))
                val indices = unsigned(primitive.getInt("indices"))
                val vertexCount = positions.size / 3
                require(vertexCount > 0) { "$name has no vertex" }
                require(normals.size == positions.size) { "$name normals do not match its vertices" }
                require(tangents.size == vertexCount * 4) { "$name tangents do not match its vertices" }
                require(uvs.size == vertexCount * 2) { "$name uv0 does not match its vertices" }
                require(joints.size == vertexCount * 4) { "$name joints do not match its vertices" }
                require(weights.size == vertexCount * 4) { "$name weights do not match its vertices" }
                require(indices.size % 3 == 0) { "$name indices are not whole triangles" }
                var lowest = Int.MAX_VALUE
                var highest = -1
                for (joint in joints) {
                    require(joint in 0 until skinJointCount) { "$name names joint $joint of $skinJointCount" }
                    if (joint < lowest) lowest = joint
                    if (joint > highest) highest = joint
                }
                for (triangle in indices) {
                    require(triangle in 0 until vertexCount) { "$name indexes vertex $triangle of $vertexCount" }
                }
                for (vertex in 0 until vertexCount) {
                    var sum = 0f
                    for (slot in 0 until 4) sum += weights[vertex * 4 + slot]
                    require(kotlin.math.abs(sum - 1f) <= 1e-4f) {
                        "$name vertex $vertex weights sum to $sum"
                    }
                }
                val bounds = FloatArray(6)
                for (component in 0 until 3) {
                    var min = Float.MAX_VALUE
                    var max = -Float.MAX_VALUE
                    for (vertex in 0 until vertexCount) {
                        val value = positions[vertex * 3 + component]
                        if (value < min) min = value
                        if (value > max) max = value
                    }
                    bounds[component] = min
                    bounds[component + 3] = max
                }
                sections += MuscleSectionMesh(
                    name = name,
                    positions = positions,
                    normals = normals,
                    tangents = tangents,
                    uvs = uvs,
                    joints = joints,
                    weights = weights,
                    indices = indices,
                    bounds = bounds
                )
            }
            require(sections.isNotEmpty()) { "the muscle GLB carries no section" }
            return MuscleMeshAsset(skinJointCount, sections)
        }
    }

    private class Accessor(
        val base: Int,
        val count: Int,
        val stride: Int,
        val componentType: Int,
        val components: Int
    )
}
