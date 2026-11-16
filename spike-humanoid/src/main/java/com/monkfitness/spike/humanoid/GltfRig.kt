package com.monkfitness.spike.humanoid

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only GLB reader: node hierarchy, rest TRS, the first skin's joint order, its inverse
 * bind matrices and the geometry's triangle count.
 *
 * The spike needs exactly this and nothing more: gltfio owns rendering and skinning, but it does not
 * expose the joint order or the inverse bind matrices, which a pose-driven (non-animation-driven)
 * character has to supply.
 */
class GltfRig(
    val nodes: List<GltfNode>,
    val sceneRoots: IntArray,
    /** glTF node indices of the skin's joints, in the order the skinning buffer expects. */
    val jointNodes: IntArray,
    /** Inverse bind matrix per joint (16 floats, column-major); null entries mean identity. */
    val inverseBinds: List<FloatArray>,
    val triangleCount: Int,
    val morphTargetCount: Int,
    val animationNames: List<String>
) {
    private val indexByName: Map<String, Int> = buildMap {
        nodes.forEach { n -> if (n.name.isNotEmpty()) putIfAbsent(n.name, n.index) }
    }

    private val indexByNormalizedName: Map<String, Int> = buildMap {
        nodes.forEach { n -> if (n.name.isNotEmpty()) putIfAbsent(normalize(n.name), n.index) }
    }

    fun nodeByName(name: String): Int? = indexByName[name]

    fun nodeByNormalizedName(name: String): Int? = indexByNormalizedName[normalize(name)]

    /**
     * Rest world rotation of every node, accumulated from the glTF rest TRS.
     *
     * Rest *positions* are not accumulated here: the renderer derives them from the same rest
     * translations at bind time and lets the retargeter drive rotations only.
     */
    fun restWorldRotations(): Array<Quat> {
        val out = Array(nodes.size) { Quat.IDENTITY }
        fun walk(index: Int, parentRotation: Quat) {
            val node = nodes[index]
            val world = parentRotation * node.rotation
            out[index] = world
            node.children.forEach { walk(it, world) }
        }
        sceneRoots.forEach { walk(it, Quat.IDENTITY) }
        return out
    }

    fun restWorldTranslations(scale: Float = 1f): Array<FloatArray> {
        val out = Array(nodes.size) { FloatArray(3) }
        fun walk(index: Int, parent: FloatArray, parentRotation: Quat) {
            val node = nodes[index]
            val local = floatArrayOf(
                node.translation[0] * scale,
                node.translation[1] * scale,
                node.translation[2] * scale
            )
            val rotated = rotate(parentRotation, local)
            out[index] = floatArrayOf(
                parent[0] + rotated[0],
                parent[1] + rotated[1],
                parent[2] + rotated[2]
            )
            node.children.forEach { walk(it, out[index], parentRotation * node.rotation) }
        }
        sceneRoots.forEach { walk(it, FloatArray(3), Quat.IDENTITY) }
        return out
    }

    private fun rotate(q: Quat, v: FloatArray): FloatArray {
        val m = q.toMatrix3()
        return floatArrayOf(
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
            m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
            m[2] * v[0] + m[5] * v[1] + m[8] * v[2]
        )
    }

    companion object {
        private const val MAGIC = 0x46546C67
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942

        private fun normalize(name: String): String =
            name.lowercase()
                .removePrefix("mixamorig:")
                .removePrefix("mixamorig_")
                .replace("_", "")
                .replace(" ", "")
                .replace(".", "")

        fun normalizeName(name: String): String = normalize(name)

        fun parse(bytes: ByteArray): GltfRig {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == MAGIC) { "not a GLB (bad magic)" }
            buffer.int // version
            buffer.int // total length

            var json: JSONObject? = null
            var bin: ByteBuffer? = null
            while (buffer.remaining() >= 8) {
                val chunkLength = buffer.int
                val chunkType = buffer.int
                if (chunkLength < 0 || chunkLength > buffer.remaining()) break
                val chunk = ByteArray(chunkLength)
                buffer.get(chunk)
                when (chunkType) {
                    CHUNK_JSON -> json = JSONObject(String(chunk, Charsets.UTF_8))
                    CHUNK_BIN -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            val g = json ?: error("GLB has no JSON chunk")

            val nodesJson = g.optJSONArray("nodes") ?: error("GLB has no nodes")
            val childrenOf = HashMap<Int, IntArray>()
            val nodes = ArrayList<GltfNode>(nodesJson.length())
            for (i in 0 until nodesJson.length()) {
                val n = nodesJson.getJSONObject(i)
                val children = n.optJSONArray("children")
                val childIndices = if (children == null) IntArray(0) else IntArray(children.length()) { children.getInt(it) }
                childrenOf[i] = childIndices
                nodes += GltfNode(
                    index = i,
                    name = n.optString("name", ""),
                    translation = readVec3(n.optJSONArray("translation")),
                    rotation = readQuat(n.optJSONArray("rotation")),
                    scale = readVec3(n.optJSONArray("scale"), 1f),
                    children = childIndices
                )
            }
            val parentOf = IntArray(nodes.size) { -1 }
            childrenOf.forEach { (parent, kids) -> kids.forEach { parentOf[it] = parent } }
            for (i in nodes.indices) nodes[i] = nodes[i].copy(parent = parentOf[i])

            val scenes = g.optJSONArray("scenes")
            val scene = g.optInt("scene", 0)
            val roots = scenes?.optJSONObject(scene)?.optJSONArray("nodes")?.let { arr ->
                IntArray(arr.length()) { arr.getInt(it) }
            } ?: parentOf.indices.filter { parentOf[it] < 0 }.toIntArray()

            val skins = g.optJSONArray("skins")
            val joints = skins?.optJSONObject(0)?.optJSONArray("joints")?.let { arr ->
                IntArray(arr.length()) { arr.getInt(it) }
            } ?: IntArray(0)
            val inverseBindAccessor = skins?.optJSONObject(0)?.optInt("inverseBindMatrices", -1) ?: -1
            val inverseBinds = if (inverseBindAccessor >= 0 && bin != null) {
                readMat4Array(g, bin, inverseBindAccessor, joints.size)
            } else {
                List(joints.size) { Mat4.identity() }
            }

            var triangles = 0
            var morphs = 0
            val meshes = g.optJSONArray("meshes")
            if (meshes != null) {
                for (m in 0 until meshes.length()) {
                    val prims = meshes.getJSONObject(m).optJSONArray("primitives") ?: continue
                    for (p in 0 until prims.length()) {
                        val prim = prims.getJSONObject(p)
                        val accessors = g.getJSONArray("accessors")
                        val indices = prim.optInt("indices", -1)
                        triangles += if (indices >= 0) {
                            accessors.getJSONObject(indices).getInt("count") / 3
                        } else {
                            val pos = prim.getJSONObject("attributes").getInt("POSITION")
                            accessors.getJSONObject(pos).getInt("count") / 3
                        }
                        morphs += prim.optJSONArray("targets")?.length() ?: 0
                    }
                }
            }

            val animations = g.optJSONArray("animations")
            val animationNames = if (animations == null) emptyList() else
                (0 until animations.length()).map { animations.getJSONObject(it).optString("name", "clip$it") }

            return GltfRig(nodes, roots, joints, inverseBinds, triangles, morphs, animationNames)
        }

        private fun readVec3(array: org.json.JSONArray?, default: Float = 0f): FloatArray =
            if (array == null) floatArrayOf(default, default, default)
            else floatArrayOf(array.getDouble(0).toFloat(), array.getDouble(1).toFloat(), array.getDouble(2).toFloat())

        private fun readQuat(array: org.json.JSONArray?): Quat =
            if (array == null) Quat.IDENTITY
            else Quat.fromArray(
                floatArrayOf(
                    array.getDouble(0).toFloat(),
                    array.getDouble(1).toFloat(),
                    array.getDouble(2).toFloat(),
                    array.getDouble(3).toFloat()
                )
            )

        private fun readMat4Array(g: JSONObject, bin: ByteBuffer, accessorIndex: Int, count: Int): List<FloatArray> {
            val accessors = g.getJSONArray("accessors")
            val accessor = accessors.getJSONObject(accessorIndex)
            require(accessor.getInt("componentType") == 5126) { "inverse bind matrices must be float" }
            require(accessor.getString("type") == "MAT4") { "inverse bind accessor must be MAT4" }
            val bufferViews = g.getJSONArray("bufferViews")
            val view = bufferViews.getJSONObject(accessor.getInt("bufferView"))
            val base = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val stride = view.optInt("byteStride", 64)
            val out = ArrayList<FloatArray>(count)
            for (i in 0 until count) {
                val floats = FloatArray(16)
                bin.position(base + i * stride)
                bin.asFloatBuffer().get(floats, 0, 16)
                out += floats
            }
            return out
        }
    }
}

data class GltfNode(
    val index: Int,
    val name: String,
    val parent: Int = -1,
    val translation: FloatArray,
    val rotation: Quat,
    val scale: FloatArray,
    val children: IntArray
) {
    // Arrays make data-class equality reference-based; the rig is a read-once structure.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = index
}
