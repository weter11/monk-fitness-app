package com.monkfitness.app.character

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Bounds of the vertices a build wrote, in the space the renderer draws in.
 *
 * Filament aborts a renderable whose bounding box is empty while culling or a shadow flag is on, so
 * this carries an explicit [fallback] for the frames where nothing was written.
 */
class MeshBounds {

    var minX: Float = 0f
        private set
    var minY: Float = 0f
        private set
    var minZ: Float = 0f
        private set
    var maxX: Float = 0f
        private set
    var maxY: Float = 0f
        private set
    var maxZ: Float = 0f
        private set

    var isEmpty: Boolean = true
        private set

    fun reset() {
        isEmpty = true
        minX = 0f
        minY = 0f
        minZ = 0f
        maxX = 0f
        maxY = 0f
        maxZ = 0f
    }

    fun include(x: Float, y: Float, z: Float) {
        // One non-finite component would otherwise pin min/max at NaN for every later vertex, and a
        // NaN box is not empty, so it would pass the renderable's precondition and break culling.
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (isEmpty) {
            minX = x
            minY = y
            minZ = z
            maxX = x
            maxY = y
            maxZ = z
            isEmpty = false
            return
        }
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
    }

    /** A non-empty box at the origin for a build that wrote no vertex. */
    fun fallback() {
        reset()
        include(-FALLBACK_HALF_METRES, -FALLBACK_HALF_METRES, -FALLBACK_HALF_METRES)
        include(FALLBACK_HALF_METRES, FALLBACK_HALF_METRES, FALLBACK_HALF_METRES)
    }

    fun contains(x: Float, y: Float, z: Float): Boolean =
        !isEmpty && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ

    val centreX: Float get() = (minX + maxX) * 0.5f
    val centreY: Float get() = (minY + maxY) * 0.5f
    val centreZ: Float get() = (minZ + maxZ) * 0.5f
    val halfX: Float get() = (maxX - minX) * 0.5f
    val halfY: Float get() = (maxY - minY) * 0.5f
    val halfZ: Float get() = (maxZ - minZ) * 0.5f

    companion object {
        const val FALLBACK_HALF_METRES = 0.1f
    }
}

/**
 * Turns a [DynamicGeometrySlot] into the bytes a vertex buffer takes, and is the only place that
 * knows two things the GPU cannot be told twice: the asset's axes and the attribute byte widths.
 *
 * The anatomy solves in engine axes (`+X` forward, `+Z` lateral) while the drawn asset uses its own
 * (`+Z` forward, `+X` left), so every position is rotated by `B⁻¹ = (x, y, z) → (−z, y, x)` here,
 * at the one pass that already touches every vertex. No scale is applied: the mesh is metres.
 *
 * Writers are absolute-index `java.nio` puts, so they work on a heap buffer in a test and on a
 * direct buffer in the renderer without a copy or an allocation of their own.
 */
class TubeVertexLayout(
    private val plan: TubeAttributePlan.Supported,
    val vertexCapacity: Int,
    val indexCapacity: Int
) {

    init {
        require(vertexCapacity >= 0) { "vertexCapacity $vertexCapacity cannot be negative" }
        require(indexCapacity >= 0 && indexCapacity % 3 == 0) {
            "indexCapacity $indexCapacity must be whole triangles"
        }
    }

    val bufferCount: Int get() = plan.bufferCount

    /** The vertex-buffer slot [attribute] occupies in the plan this layout was built from. */
    fun bufferIndexOf(attribute: TubeAttribute): Int = plan.bufferIndexOf(attribute)

    /** The slot [attribute] occupies, or null when the material does not ask for it. */
    fun bufferIndexOfOrNull(attribute: TubeAttribute): Int? = plan.bufferIndexOfOrNull(attribute)

    fun indexBytes(indexCount: Int): Int = indexCount * UINT_BYTES

    /**
     * Writes [geometry]'s positions in the renderer's axes and grows [bounds] with every written
     * vertex, so the bounding box handed to Filament is the mesh's own rather than a guess.
     */
    fun writePositions(geometry: DynamicGeometrySlot, out: FloatBuffer, bounds: MeshBounds): Int {
        bounds.reset()
        val count = geometry.vertexCount
        val source = geometry.positions
        for (vertex in 0 until count) {
            val assetX = -source[vertex * 3 + 2]
            val assetY = source[vertex * 3 + 1]
            val assetZ = source[vertex * 3]
            out.put(vertex * 3, assetX)
            out.put(vertex * 3 + 1, assetY)
            out.put(vertex * 3 + 2, assetZ)
            bounds.include(assetX, assetY, assetZ)
        }
        if (count == 0) bounds.fallback()
        return count
    }

    fun writeUvs(geometry: DynamicGeometrySlot, out: FloatBuffer): Int {
        val count = geometry.vertexCount
        val source = geometry.uvs
        for (vertex in 0 until count) {
            out.put(vertex * 2, source[vertex * 2])
            out.put(vertex * 2 + 1, source[vertex * 2 + 1])
        }
        return count
    }

    /** Writes the per-vertex tint as normalized `UBYTE4`, the type glTF uses for `COLOR_0`. */
    fun writeColors(geometry: DynamicGeometrySlot, out: ByteBuffer): Int {
        val count = geometry.vertexCount
        val source = geometry.colors
        for (vertex in 0 until count) {
            out.put(vertex * 4, quantize(source[vertex * 3]))
            out.put(vertex * 4 + 1, quantize(source[vertex * 3 + 1]))
            out.put(vertex * 4 + 2, quantize(source[vertex * 3 + 2]))
            out.put(vertex * 4 + 3, OPAQUE)
        }
        return count
    }

    /** Fills the second UV set the ubershader requires with the same `0xFF` gltfio uses as a stand-in. */
    fun writeUv1Dummy(out: ByteBuffer, vertexCount: Int): Int {
        for (vertex in 0 until vertexCount) {
            out.put(vertex * 4, DUMMY_UV)
            out.put(vertex * 4 + 1, DUMMY_UV)
            out.put(vertex * 4 + 2, DUMMY_UV)
            out.put(vertex * 4 + 3, DUMMY_UV)
        }
        return vertexCount
    }

    fun writeIndices(geometry: DynamicGeometrySlot, out: IntBuffer): Int {
        val count = geometry.indexCount
        require(count <= indexCapacity) { "index count $count exceeds $indexCapacity" }
        val source = geometry.indices
        for (index in 0 until count) out.put(index, source[index])
        return count
    }

    private fun quantize(channel: Float): Byte = ((channel.coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()

    private companion object {
        const val UINT_BYTES = 4
        const val OPAQUE: Byte = -1
        const val DUMMY_UV: Byte = -1
    }
}
