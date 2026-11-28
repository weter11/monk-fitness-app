package com.monkfitness.app.character

/**
 * Where one section's vertices and indices live inside a partition's merged buffer.
 */
internal class MuscleSectionSlice(
    val vertexOffset: Int,
    val vertexCount: Int,
    val indexOffset: Int,
    val indexCount: Int
)

/**
 * The partition's sections laid out back to back in one vertex buffer and one index buffer, with
 * each section's slice kept so a hide or a selection can rewrite just that section.
 */
internal class MuscleSectionLayout private constructor(
    val slices: List<MuscleSectionSlice>,
    val vertexCount: Int,
    val indexCount: Int
) {

    fun slice(section: Int): MuscleSectionSlice = slices[section]

    companion object {

        fun of(sections: List<MuscleSectionMesh>): MuscleSectionLayout =
            ofCounts(sections.map { it.vertexCount to it.indexCount })

        /** Tests and fixtures can lay out sizes without a GLB; the arithmetic is the same one. */
        fun ofCounts(counts: List<Pair<Int, Int>>): MuscleSectionLayout {
            require(counts.isNotEmpty()) { "a merged partition needs at least one section" }
            val slices = ArrayList<MuscleSectionSlice>(counts.size)
            var vertex = 0
            var index = 0
            for ((order, count) in counts.withIndex()) {
                val (vertices, indices) = count
                require(vertices > 0) { "section $order has no vertex" }
                require(indices > 0 && indices % 3 == 0) { "section $order is not whole triangles" }
                slices += MuscleSectionSlice(vertex, vertices, index, indices)
                vertex += vertices
                index += indices
            }
            return MuscleSectionLayout(slices, vertex, index)
        }
    }
}
