package com.monkfitness.app.character

/**
 * The two readings the tube tests need of a slot whose topology no longer moves: whether one head's
 * quota is collapsed onto a point, and how much area its triangles cover.
 *
 * Head quotas are claimed in slot order, so a head's vertices are `head * verticesPerHead` onward —
 * the same arithmetic `writeIndices` uses to emit them.
 */
internal object CollapsedHeads {

    fun firstVertex(mesh: ProceduralMuscleMesh, head: Int): Int = head * mesh.verticesPerHead

    /** True when every vertex of [head]'s quota shares one position, so its triangles have no area. */
    fun isCollapsed(slot: DynamicGeometrySlot, mesh: ProceduralMuscleMesh, head: Int): Boolean {
        val first = firstVertex(mesh, head)
        val x = slot.positions[first * 3]
        val y = slot.positions[first * 3 + 1]
        val z = slot.positions[first * 3 + 2]
        for (offset in 1 until mesh.verticesPerHead) {
            val at = (first + offset) * 3
            if (slot.positions[at] != x || slot.positions[at + 1] != y || slot.positions[at + 2] != z) {
                return false
            }
        }
        return true
    }

    /** The largest triangle area [head] covers; zero is the collapsed reading. */
    fun maxTriangleArea(slot: DynamicGeometrySlot, mesh: ProceduralMuscleMesh, head: Int): Float {
        val first = firstVertex(mesh, head)
        val end = first + mesh.verticesPerHead
        var worst = 0f
        var index = 0
        while (index + 2 < slot.indexCount) {
            val a = slot.indices[index]
            val b = slot.indices[index + 1]
            val c = slot.indices[index + 2]
            index += 3
            if (a < first || a >= end || b < first || b >= end || c < first || c >= end) continue
            worst = maxOf(worst, area(slot, a, b, c))
        }
        return worst
    }

    private fun area(slot: DynamicGeometrySlot, a: Int, b: Int, c: Int): Float {
        val ux = slot.positions[b * 3] - slot.positions[a * 3]
        val uy = slot.positions[b * 3 + 1] - slot.positions[a * 3 + 1]
        val uz = slot.positions[b * 3 + 2] - slot.positions[a * 3 + 2]
        val vx = slot.positions[c * 3] - slot.positions[a * 3]
        val vy = slot.positions[c * 3 + 1] - slot.positions[a * 3 + 1]
        val vz = slot.positions[c * 3 + 2] - slot.positions[a * 3 + 2]
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        return 0.5f * kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
    }
}
