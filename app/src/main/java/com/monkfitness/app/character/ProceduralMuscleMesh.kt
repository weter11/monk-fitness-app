package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tessellates every solved head into a tube, into a caller-owned [DynamicGeometrySlot].
 *
 * The budget is a property of the definition, not of the frame: [samples] comes from the version's
 * `maxSamples` and [radialSegments] is the tube's ring, so the same definition always costs the same
 * geometry and a slot sized from [vertexCapacity]/[triangleCapacity] can never overflow.
 *
 * Positions are metres. The anatomy solves in engine units, so [unitsPerMetre] is the one conversion
 * on this path and it happens here rather than in the renderer.
 */
class ProceduralMuscleMesh(
    private val definition: AnatomyDefinition,
    private val unitsPerMetre: Double,
    val samples: Int = definition.sampling.maxSamples,
    val radialSegments: Int = RADIAL_SEGMENTS,
    private val caps: Boolean = true
) {

    init {
        require(unitsPerMetre > 0.0 && unitsPerMetre.isFinite()) {
            "unitsPerMetre $unitsPerMetre must be finite and positive"
        }
        require(samples >= 2) { "samples $samples cannot describe a tube" }
        require(radialSegments >= 3) { "radialSegments $radialSegments cannot close a ring" }
    }

    /** Band triangles per head: two per ring quad, `R` quads per band, `N - 1` bands. */
    val bandTrianglesPerHead: Int = 2 * radialSegments * (samples - 1)

    /** Fan-cap triangles per head: one `R`-triangle fan at each end. */
    val capTrianglesPerHead: Int = if (caps) 2 * radialSegments else 0

    val trianglesPerHead: Int = bandTrianglesPerHead + capTrianglesPerHead

    /** Ring vertices, plus one cap centre per end when [caps]. */
    val verticesPerHead: Int = samples * radialSegments + if (caps) 2 else 0

    val triangleCapacity: Int = definition.headCount * trianglesPerHead

    val vertexCapacity: Int = definition.headCount * verticesPerHead

    /** Why the last [write] refused, or null when it wrote. */
    var lastRefusal: String? = null
        private set

    /** A slot sized for this definition's whole head set, so a build can never be refused for room. */
    fun slot(): DynamicGeometrySlot = DynamicGeometrySlot(vertexCapacity, triangleCapacity * 3)

    private val points = DoubleArray(samples * 3)
    private val radii = DoubleArray(samples)
    private val tangents = DoubleArray(samples * 3)
    private val profileCache = HashMap<String, com.monkfitness.app.anatomy.RadiusProfile>()

    /**
     * Rebuilds [slot] from [frame] for the instances [selection] shows, and returns the triangle
     * count written. The slot is emptied first, so a hidden instance leaves no stale tube behind;
     * a selection the slot cannot hold is refused whole rather than written part-way.
     */
    fun write(frame: AnatomyFrame, slot: DynamicGeometrySlot, selection: MuscleSelection): Int {
        lastRefusal = null
        slot.begin()

        var heads = 0
        for (slotIndex in 0 until frame.headCount) {
            val state = frame.muscleStates[slotIndex]
            val instance = frame.instances[frame.instanceOf(slotIndex)]
            if (selection.shows(instance, state.layer)) heads++
        }
        if (heads == 0) return 0

        if (!slot.claim(heads * verticesPerHead, heads * trianglesPerHead * 3)) {
            lastRefusal =
                "$heads heads need ${heads * verticesPerHead} vertices / ${heads * trianglesPerHead} triangles, " +
                    "the slot holds ${slot.vertexCapacity} / ${slot.triangleCount}"
            return 0
        }

        var triangles = 0
        for (slotIndex in 0 until frame.headCount) {
            val state = frame.muscleStates[slotIndex]
            val instance = frame.instances[frame.instanceOf(slotIndex)]
            if (!selection.shows(instance, state.layer)) continue
            triangles += tube(frame, slotIndex, state.id, slot)
        }
        return triangles
    }

    /** Triangles one layer contributes when every instance of it is shown and solved. */
    fun trianglesPerLayer(layer: com.monkfitness.app.anatomy.MuscleLayer): Int =
        definition.muscles.filter { it.layer == layer }.sumOf { it.headSideCount * trianglesPerHead }

    private fun tube(frame: AnatomyFrame, slotIndex: Int, muscleId: String, slot: DynamicGeometrySlot): Int {
        val muscle = definition.muscle(muscleId) ?: return 0
        val count = frame.pointCounts[slotIndex]
        if (count < 2) return 0

        val profile = profileCache.getOrPut(muscleId) {
            com.monkfitness.app.anatomy.RadiusProfile(muscle.radiusKnots, unitsPerMetre)
        }
        val total = resample(frame, slotIndex, count)
        if (total <= 0.0) return 0
        // The tube is tessellated at the version's station count, not at the path's own point count:
        // the budget is fixed by the definition, and a solver that returned a shorter path must not
        // change what the layer costs.
        val stations = samples
        if (!tangents(stations)) return 0

        val base = slot.vertexCount
        val first = slot.indexCount
        for (index in 0 until stations) {
            radii[index] = profile.at(index.toDouble() / (stations - 1))
        }

        for (index in 0 until stations) {
            val cx = points[index * 3]
            val cy = points[index * 3 + 1]
            val cz = points[index * 3 + 2]
            val tx = tangents[index * 3]
            val ty = tangents[index * 3 + 1]
            val tz = tangents[index * 3 + 2]
            var nx = normalAxisX(tx, ty, tz)
            var ny = normalAxisY(tx, ty, tz)
            var nz = normalAxisZ(tx, ty, tz)
            // t × ref is only unit when the two are unit and perpendicular; the axis is chosen to be
            // least aligned, not orthogonal, so the ring basis is normalized here.
            val nLength = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= nLength
            ny /= nLength
            nz /= nLength
            // b = t × n closes a right-handed frame with the ring, so the ring runs the way every
            // outward normal is emitted and the band winding below stays one convention.
            val bx = ty * nz - tz * ny
            val by = tz * nx - tx * nz
            val bz = tx * ny - ty * nx
            val radius = radii[index] / unitsPerMetre
            val v = index.toDouble() / (stations - 1)
            for (ring in 0 until radialSegments) {
                val angle = TWO_PI * ring / radialSegments
                val c = cos(angle)
                val s = sin(angle)
                val dx = c * nx + s * bx
                val dy = c * ny + s * by
                val dz = c * nz + s * bz
                slot.pushVertex(
                    (cx + radius * dx).toFloat(), (cy + radius * dy).toFloat(), (cz + radius * dz).toFloat(),
                    dx.toFloat(), dy.toFloat(), dz.toFloat(),
                    ring.toFloat() / radialSegments, v.toFloat()
                )
            }
        }

        for (band in 0 until stations - 1) {
            for (ring in 0 until radialSegments) {
                val next = (ring + 1) % radialSegments
                val a = base + band * radialSegments + ring
                val b = base + band * radialSegments + next
                val c = base + (band + 1) * radialSegments + ring
                val d = base + (band + 1) * radialSegments + next
                slot.pushTriangle(a, c, b)
                slot.pushTriangle(b, c, d)
            }
        }

        if (caps) {
            for (end in 0 until 2) {
                val index = if (end == 0) 0 else stations - 1
                val sign = if (end == 0) -1.0 else 1.0
                val centre = slot.vertexCount
                slot.pushVertex(
                    points[index * 3].toFloat(), points[index * 3 + 1].toFloat(), points[index * 3 + 2].toFloat(),
                    (sign * tangents[index * 3]).toFloat(),
                    (sign * tangents[index * 3 + 1]).toFloat(),
                    (sign * tangents[index * 3 + 2]).toFloat(),
                    0.5f, if (end == 0) 0f else 1f
                )
                val ringBase = base + index * radialSegments
                for (ring in 0 until radialSegments) {
                    val next = (ring + 1) % radialSegments
                    if (end == 0) {
                        slot.pushTriangle(centre, ringBase + next, ringBase + ring)
                    } else {
                        slot.pushTriangle(centre, ringBase + ring, ringBase + next)
                    }
                }
            }
        }

        return (slot.indexCount - first) / 3
    }

    /** Resamples the slot's path to [samples] equal-arc points; returns the arc length in units. */
    private fun resample(frame: AnatomyFrame, slotIndex: Int, count: Int): Double {
        val lengths = DoubleArray(count)
        var total = 0.0
        for (index in 1 until count) {
            val dx = frame.pointComponent(slotIndex, index, 0) - frame.pointComponent(slotIndex, index - 1, 0)
            val dy = frame.pointComponent(slotIndex, index, 1) - frame.pointComponent(slotIndex, index - 1, 1)
            val dz = frame.pointComponent(slotIndex, index, 2) - frame.pointComponent(slotIndex, index - 1, 2)
            lengths[index] = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            total += lengths[index]
        }
        if (total <= 0.0) return 0.0

        var segment = 1
        var walk = 0.0
        for (index in 0 until samples) {
            val target = total * index / (samples - 1)
            while (segment < count - 1 && walk + lengths[segment] < target) {
                walk += lengths[segment]
                segment++
            }
            val span = lengths[segment]
            val u = if (span > 0.0) ((target - walk) / span).coerceIn(0.0, 1.0) else 0.0
            for (component in 0..2) {
                val from = frame.pointComponent(slotIndex, segment - 1, component)
                val to = frame.pointComponent(slotIndex, segment, component)
                points[index * 3 + component] = (from + (to - from) * u).toDouble()
            }
        }
        return total
    }

    /** Central-difference tangents with a forward/backward fallback at the ends; false when flat. */
    private fun tangents(count: Int): Boolean {
        var any = false
        for (index in 0 until count) {
            val low = if (index == 0) 0 else index - 1
            val high = if (index == count - 1) count - 1 else index + 1
            var dx = points[high * 3] - points[low * 3]
            var dy = points[high * 3 + 1] - points[low * 3 + 1]
            var dz = points[high * 3 + 2] - points[low * 3 + 2]
            var length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length <= 0.0) {
                // A repeated sample has no direction of its own; the previous ring's tangent is the
                // only one that keeps the tube continuous instead of twisting it.
                if (index > 0) {
                    dx = tangents[(index - 1) * 3]
                    dy = tangents[(index - 1) * 3 + 1]
                    dz = tangents[(index - 1) * 3 + 2]
                    length = sqrt(dx * dx + dy * dy + dz * dz)
                }
            }
            if (length <= 0.0) return false
            tangents[index * 3] = dx / length
            tangents[index * 3 + 1] = dy / length
            tangents[index * 3 + 2] = dz / length
            any = true
        }
        return any
    }

    private fun normalAxisX(tx: Double, ty: Double, tz: Double): Double = crossX(tx, ty, tz, axisX(tx, ty, tz), axisY(tx, ty, tz), axisZ(tx, ty, tz))

    private fun normalAxisY(tx: Double, ty: Double, tz: Double): Double = crossY(tx, ty, tz, axisX(tx, ty, tz), axisY(tx, ty, tz), axisZ(tx, ty, tz))

    private fun normalAxisZ(tx: Double, ty: Double, tz: Double): Double = crossZ(tx, ty, tz, axisX(tx, ty, tz), axisY(tx, ty, tz), axisZ(tx, ty, tz))

    /** The world axis least aligned with the tangent, so the ring never collapses onto it. */
    private fun axisX(tx: Double, ty: Double, tz: Double): Double = if (abs(tx) <= abs(ty) && abs(tx) <= abs(tz)) 1.0 else 0.0

    private fun axisY(tx: Double, ty: Double, tz: Double): Double = if (abs(ty) < abs(tx) && abs(ty) <= abs(tz)) 1.0 else 0.0

    private fun axisZ(tx: Double, ty: Double, tz: Double): Double = 1.0 - axisX(tx, ty, tz) - axisY(tx, ty, tz)

    private fun crossX(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double =
        ay * bz - az * by

    private fun crossY(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double =
        az * bx - ax * bz

    private fun crossZ(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double =
        ax * by - ay * bx

    companion object {
        /** Ring segments around the tube: eight reads as a tube at the character's scale. */
        const val RADIAL_SEGMENTS = 8

        private const val TWO_PI = 2.0 * Math.PI
    }
}
