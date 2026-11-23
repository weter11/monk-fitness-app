package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleDefinition
import com.monkfitness.app.anatomy.RadiusProfile
import com.monkfitness.app.anatomy.TendonSpec
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
 * Positions are metres in engine axes (`+Y` up, `+X` forward, `+Z` lateral). The anatomy solves in
 * engine units, so [unitsPerMetre] is the one scale conversion on this path and it happens here
 * rather than in the renderer, applied to the centre line and the radius alike; the axis change into
 * the asset's own frame is the renderer's, because only the renderer knows which asset it is drawing.
 *
 * A frame only moves the pose. The ring basis, the radius profile and the tendon tint are properties
 * of the definition, so they are derived once ([ringCos], [StationTable]) instead of per station per
 * head, and the index buffer is a function of the definition's head set alone: it is written once and
 * reused for every frame after it, whatever the solver refuses.
 */
class ProceduralMuscleMesh(
    private val definition: AnatomyDefinition,
    private val unitsPerMetre: Double,
    private val palette: SkinPalette = SkinPalette.DEFAULT,
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
    private val tangents = DoubleArray(samples * 3)
    /** Per-segment lengths for [resample], one entry per path point the frame can hold. */
    private val lengths = DoubleArray(definition.sampling.maxPointsPerHead)

    /**
     * The ring's angle pair per segment. Every vertex of a station reads the same two values, so
     * re-deriving them per station per head was the largest per-frame cost of the tessellation.
     */
    private val ringCos = DoubleArray(radialSegments) { cos(TWO_PI * it / radialSegments) }
    private val ringSin = DoubleArray(radialSegments) { sin(TWO_PI * it / radialSegments) }
    private val ringU = FloatArray(radialSegments) { it.toFloat() / radialSegments }
    private val stationV = FloatArray(samples) { (it.toDouble() / (samples - 1)).toFloat() }

    /** Radius and tendon tint per station, built once per muscle: neither moves with the pose. */
    private val stationTables = HashMap<String, StationTable>()

    /** The vertex each drawn head started at, so its indices can be emitted in a second pass. */
    private val headBases = IntArray(definition.headCount)

    private var topologySlot: DynamicGeometrySlot? = null
    private var topologyRevision = -1L
    private var topologyVertices = -1
    private var topologyIndices = -1

    /** The station's tint, written by [tintInto] so a station costs no allocation. */
    private var tintRed = 0f
    private var tintGreen = 0f
    private var tintBlue = 0f

    /**
     * Rebuilds [slot] from [frame] for the instances [selection] shows, and returns the triangle
     * count written. The slot is emptied first, so a hidden instance leaves no stale tube behind;
     * a selection the slot cannot hold is refused whole rather than written part-way.
     *
     * Every head of the definition owns its quota on every frame while the tubes are on: a head the
     * selection hides or the solver refuses is written collapsed onto one point instead of being
     * skipped. The counts are therefore a property of the definition alone, which is what keeps the
     * GPU primitive above this one alive across a rep — a refusal moves vertices, never topology.
     */
    fun write(frame: AnatomyFrame, slot: DynamicGeometrySlot, selection: MuscleSelection): Int {
        lastRefusal = null
        slot.begin()
        if (!selection.showsTubes) return 0

        val heads = frame.headCount
        if (heads == 0) return 0

        val vertexCount = heads * verticesPerHead
        val indexCount = heads * trianglesPerHead * 3
        if (!slot.claim(vertexCount, indexCount)) {
            lastRefusal =
                "$heads heads need $vertexCount vertices / ${heads * trianglesPerHead} triangles, " +
                    "the slot holds ${slot.vertexCapacity} / ${slot.triangleCount}"
            return 0
        }

        for (slotIndex in 0 until heads) {
            val state = frame.muscleStates[slotIndex]
            val instance = frame.instances[frame.instanceOf(slotIndex)]
            val base = if (selection.shows(instance, state.layer)) {
                pushVertices(frame, slotIndex, state.id, slot)
            } else {
                -1
            }
            headBases[slotIndex] = if (base >= 0) base else collapse(frame, slotIndex, slot)
        }

        // The head set no longer varies, so the index buffer is a function of the definition: the
        // same slot holding the same revision is the topology already in its array.
        if (slot === topologySlot && slot.topologyRevision == topologyRevision &&
            vertexCount == topologyVertices && indexCount == topologyIndices
        ) {
            slot.reuseIndices(indexCount)
        } else {
            writeIndices(slot.indices, slot.claimIndexRun(indexCount), headBases, heads)
            topologySlot = slot
            topologyRevision = slot.topologyRevision
            topologyVertices = vertexCount
            topologyIndices = indexCount
        }
        return indexCount / 3
    }

    /** Triangles one layer contributes when every instance of it is shown and solved. */
    fun trianglesPerLayer(layer: com.monkfitness.app.anatomy.MuscleLayer): Int =
        definition.muscles.filter { it.layer == layer }.sumOf { it.headSideCount * trianglesPerHead }

    /**
     * Writes one head's ring and cap vertices and returns the vertex it started at, or `-1` when the
     * head has nothing drawable. Every refusal happens before the first vertex is written, which is
     * what lets [write] treat the drawn head set as the whole topology.
     */
    private fun pushVertices(
        frame: AnatomyFrame,
        slotIndex: Int,
        muscleId: String,
        slot: DynamicGeometrySlot
    ): Int {
        val muscle = definition.muscle(muscleId) ?: return -1
        val count = frame.pointCounts[slotIndex]
        if (count < 2) return -1

        val total = resample(frame, slotIndex, count)
        if (total <= 0.0) return -1
        // The tube is tessellated at the version's station count, not at the path's own point count:
        // the budget is fixed by the definition, and a solver that returned a shorter path must not
        // change what the layer costs.
        val stations = samples
        if (!tangents(stations)) return -1

        val table = stationTable(muscle)
        val base = slot.claimVertexRun(verticesPerHead)
        // Array handles are hoisted out of both loops: this runs over every vertex of the frame, and a
        // slot accessor per component would be a field read per component as well.
        val positions = slot.positions
        val normals = slot.normals
        val uvs = slot.uvs
        val colors = slot.colors
        var vertex = base
        for (index in 0 until stations) {
            val cx = points[index * 3]
            val cy = points[index * 3 + 1]
            val cz = points[index * 3 + 2]
            val tx = tangents[index * 3]
            val ty = tangents[index * 3 + 1]
            val tz = tangents[index * 3 + 2]
            // The ring frame is built from the world axis least aligned with the tangent, so the ring
            // never collapses onto it; resolving that axis once keeps the three cross components from
            // each re-deciding it.
            val ax = axisX(tx, ty, tz)
            val ay = axisY(tx, ty, tz)
            val az = 1.0 - ax - ay
            var nx = ty * az - tz * ay
            var ny = tz * ax - tx * az
            var nz = tx * ay - ty * ax
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
            val radius = table.radii[index]
            val v = stationV[index]
            val red = table.reds[index]
            val green = table.greens[index]
            val blue = table.blues[index]
            for (ring in 0 until radialSegments) {
                val c = ringCos[ring]
                val s = ringSin[ring]
                val dx = c * nx + s * bx
                val dy = c * ny + s * by
                val dz = c * nz + s * bz
                val at = vertex * 3
                positions[at] = (cx + radius * dx).toFloat()
                positions[at + 1] = (cy + radius * dy).toFloat()
                positions[at + 2] = (cz + radius * dz).toFloat()
                normals[at] = dx.toFloat()
                normals[at + 1] = dy.toFloat()
                normals[at + 2] = dz.toFloat()
                uvs[vertex * 2] = ringU[ring]
                uvs[vertex * 2 + 1] = v
                colors[at] = red
                colors[at + 1] = green
                colors[at + 2] = blue
                vertex++
            }
        }

        if (caps) {
            for (end in 0 until 2) {
                val index = if (end == 0) 0 else stations - 1
                val sign = if (end == 0) -1.0 else 1.0
                val at = vertex * 3
                positions[at] = points[index * 3].toFloat()
                positions[at + 1] = points[index * 3 + 1].toFloat()
                positions[at + 2] = points[index * 3 + 2].toFloat()
                normals[at] = (sign * tangents[index * 3]).toFloat()
                normals[at + 1] = (sign * tangents[index * 3 + 1]).toFloat()
                normals[at + 2] = (sign * tangents[index * 3 + 2]).toFloat()
                uvs[vertex * 2] = 0.5f
                uvs[vertex * 2 + 1] = if (end == 0) 0f else 1f
                colors[at] = table.reds[index]
                colors[at + 1] = table.greens[index]
                colors[at + 2] = table.blues[index]
                vertex++
            }
        }

        require(vertex == base + verticesPerHead) { "a head wrote ${vertex - base} vertices, not $verticesPerHead" }
        return base
    }

    /**
     * Writes one head's quota collapsed onto a single point, for a head that is hidden or refused.
     *
     * Every vertex of the head lands on the same position, so each of its triangles has zero area and
     * rasterises nothing, while the head keeps the vertex and index ranges the primitive above was
     * built with. The point is the head's first solved sample when the solver left one — a hidden head
     * still sits inside the body — and the origin when the solver refused it and published no path.
     */
    private fun collapse(frame: AnatomyFrame, slotIndex: Int, slot: DynamicGeometrySlot): Int {
        val base = slot.claimVertexRun(verticesPerHead)
        val solved = frame.pointCounts[slotIndex] > 0
        // Metres, as in [resample]: the frame solves in engine units, and a head copied through raw
        // would sit hundreds of times too far out to be inside any body.
        val x = if (solved) (frame.pointComponent(slotIndex, 0, 0) / unitsPerMetre).toFloat() else 0f
        val y = if (solved) (frame.pointComponent(slotIndex, 0, 1) / unitsPerMetre).toFloat() else 0f
        val z = if (solved) (frame.pointComponent(slotIndex, 0, 2) / unitsPerMetre).toFloat() else 0f
        val positions = slot.positions
        val normals = slot.normals
        val uvs = slot.uvs
        val colors = slot.colors
        for (offset in 0 until verticesPerHead) {
            val vertex = base + offset
            val at = vertex * 3
            positions[at] = x
            positions[at + 1] = y
            positions[at + 2] = z
            normals[at] = 0f
            normals[at + 1] = 1f
            normals[at + 2] = 0f
            uvs[vertex * 2] = 0f
            uvs[vertex * 2 + 1] = 0f
            colors[at] = 0f
            colors[at + 1] = 0f
            colors[at + 2] = 0f
        }
        return base
    }

    /**
     * Emits the bands and caps of every drawn head into [out] from [start], in one pass.
     *
     * Front-facing under the `(v1 − v0) × (v2 − v0)` convention: the ring runs along `+b` and the
     * band along `+t`, so `(a, b, c)` is the only order that faces outward.
     */
    private fun writeIndices(out: IntArray, start: Int, bases: IntArray, written: Int) {
        var at = start
        for (head in 0 until written) {
            val base = bases[head]
            for (band in 0 until samples - 1) {
                for (ring in 0 until radialSegments) {
                    val next = if (ring + 1 == radialSegments) 0 else ring + 1
                    val a = base + band * radialSegments + ring
                    val b = base + band * radialSegments + next
                    val c = base + (band + 1) * radialSegments + ring
                    val d = base + (band + 1) * radialSegments + next
                    out[at] = a
                    out[at + 1] = b
                    out[at + 2] = c
                    out[at + 3] = b
                    out[at + 4] = d
                    out[at + 5] = c
                    at += 6
                }
            }
            if (caps) {
                for (end in 0 until 2) {
                    val index = if (end == 0) 0 else samples - 1
                    val centre = base + samples * radialSegments + end
                    val ringBase = base + index * radialSegments
                    for (ring in 0 until radialSegments) {
                        val next = if (ring + 1 == radialSegments) 0 else ring + 1
                        out[at] = centre
                        if (end == 0) {
                            out[at + 1] = ringBase + next
                            out[at + 2] = ringBase + ring
                        } else {
                            out[at + 1] = ringBase + ring
                            out[at + 2] = ringBase + next
                        }
                        at += 3
                    }
                }
            }
        }
    }

    /** Resamples the slot's path to [samples] equal-arc points in metres; returns the arc in units. */
    private fun resample(frame: AnatomyFrame, slotIndex: Int, count: Int): Double {
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
                // The frame is in engine units and the renderer's world is the asset's, in metres:
                // the radius is converted in [StationTable], and a centre line left in units would
                // put the tubes two hundred times too far out to be inside the frustum at all.
                points[index * 3 + component] = (from + (to - from) * u) / unitsPerMetre
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

    private fun stationTable(muscle: MuscleDefinition): StationTable {
        stationTables[muscle.id]?.let { return it }
        val table = StationTable(muscle)
        stationTables[muscle.id] = table
        return table
    }

    /**
     * Radius in metres and tendon tint of every station of one muscle: both are read from the
     * definition and the palette, and no pose input reaches either.
     */
    private inner class StationTable(muscle: MuscleDefinition) {
        val radii = DoubleArray(samples)
        val reds = FloatArray(samples)
        val greens = FloatArray(samples)
        val blues = FloatArray(samples)

        init {
            val profile = RadiusProfile(muscle.radiusKnots, unitsPerMetre)
            for (index in 0 until samples) {
                val v = index.toDouble() / (samples - 1)
                radii[index] = profile.at(v) / unitsPerMetre
                tintInto(v, muscle.tendon)
                reds[index] = tintRed
                greens[index] = tintGreen
                blues[index] = tintBlue
            }
        }
    }

    /**
     * Tints a station toward the tendon colour across the authored fractions. Linear, because a
     * hard step reads as a seam at this tube's radius while a tendon has no edge there.
     */
    private fun tintInto(v: Double, tendon: TendonSpec) {
        val fromOrigin = if (tendon.originFraction > 0f) 1.0 - v / tendon.originFraction else 0.0
        val fromInsertion = if (tendon.insertionFraction > 0f) {
            (v - (1.0 - tendon.insertionFraction)) / tendon.insertionFraction
        } else {
            0.0
        }
        val blend = (maxOf(fromOrigin, fromInsertion).coerceIn(0.0, 1.0) * palette.tendonBlend).toFloat()
        tintRed = palette.muscleRed + (palette.tendonRed - palette.muscleRed) * blend
        tintGreen = palette.muscleGreen + (palette.tendonGreen - palette.muscleGreen) * blend
        tintBlue = palette.muscleBlue + (palette.tendonBlue - palette.muscleBlue) * blend
    }

    /** The world axis least aligned with the tangent, so the ring never collapses onto it. */
    private fun axisX(tx: Double, ty: Double, tz: Double): Double =
        if (abs(tx) <= abs(ty) && abs(tx) <= abs(tz)) 1.0 else 0.0

    private fun axisY(tx: Double, ty: Double, tz: Double): Double =
        if (abs(ty) < abs(tx) && abs(ty) <= abs(tz)) 1.0 else 0.0

    companion object {
        /** Ring segments around the tube: eight reads as a tube at the character's scale. */
        const val RADIAL_SEGMENTS = 8

        private const val TWO_PI = 2.0 * Math.PI
    }
}
