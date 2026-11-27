package com.monkfitness.app.anatomyexport

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.MuscleDefinition
import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import com.monkfitness.app.character.GltfRig
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.HelperBindSet
import com.monkfitness.app.character.HelperBinds
import com.monkfitness.app.character.X1Bind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Emits one skinned GLB per muscle layer — `muscles_sup.glb` and `muscles_deep.glb` — each carrying
 * one section per muscle and side of that layer, bound to that layer's exported helper bones.
 *
 * A layer is its own asset because the whole atlas' helpers do not fit one skin: the superficial
 * palette is 34 + 208 joints and the deep one 34 + 80, where the sum would be 322. Every ring is
 * carried by the blended bind frames of the two helpers that own it — the same blend linear-blend
 * skinning applies once the layer is posed — so the authored surface sits where the skin will put it
 * and no ring is derived by a rule the skinning step does not repeat.
 *
 * This is a deterministic stand-in, not muscle art: it exists so the helper-bound pipeline can be
 * built, validated and measured, and it is the section contract a later authored asset is checked
 * against. Scale is 1 on every helper (§7.6): a muscle moves along its path and never stretches.
 */
object MuscleMeshExport {

    /** Ring vertices around the path; eight keeps a 4 mm tendon round enough at this size. */
    private const val RING_SEGMENTS = 8

    /** Rings per head, so a bend between two helper stations is interpolated rather than cut. */
    private const val RING_COUNT = 13

    /** A ring may use at most this fraction of the width the bend allows before the surface folds. */
    private const val FOLD_FRACTION = 0.9f

    private const val MODE_TRIANGLES = 4
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(option(args, "--root") ?: "../..")
        val anatomyFile = File(root, option(args, "--anatomy") ?: "app/src/main/assets/anatomy/anatomy_v1.json")
        val rigFile = File(root, option(args, "--rig") ?: "app/src/main/assets/characters/humanoid_v2.glb")
        val manifestFile = File(root, option(args, "--helpers") ?: "app/src/main/assets/anatomy/helpers_v1.json")

        val definition = AnatomyDefinitionLoader.load(anatomyFile.readText())
        val atlas = HelperBindSet.read(manifestFile.readText())
        val body = GltfRig.parse(rigFile.readBytes())
        require(body.jointNodes.size == atlas.skinJoints) {
            "the rig skins ${body.jointNodes.size} joints, the binds assume ${atlas.skinJoints}"
        }

        val unitsPerMetre = X1Bind.unitsPerMetre(SkeletonDefinition.DEFAULT_ADULT)
        val frame = restFrame(definition, unitsPerMetre)
        for (layer in MuscleLayer.entries) {
            val binds = atlas.partition(layer)
            val sections = sections(definition, frame, binds, unitsPerMetre, layer)
            val bytes = Glb(binds, body, sections).write()

            val outFile = File(root, output(args, layer))
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(bytes)
            println(
                "wrote ${outFile.path}: $layer ${sections.size} sections, " +
                    "${sections.sumOf { it.vertexCount }} vertices, " +
                    "${sections.sumOf { it.indices.size / 3 }} triangles, palette ${binds.palette}"
            )
        }
    }

    /** Where a layer's asset lands; the superficial path keeps the `--out` the Stage-3 exporter had. */
    private fun output(args: Array<String>, layer: MuscleLayer): String = when (layer) {
        MuscleLayer.SUPERFICIAL ->
            option(args, "--sup-out") ?: option(args, "--out") ?: "app/src/main/assets/characters/muscles_sup.glb"
        MuscleLayer.DEEP ->
            option(args, "--deep-out") ?: "app/src/main/assets/characters/muscles_deep.glb"
    }

    /** The bind stance the helper manifest was exported from; the mesh's rings sit on its paths. */
    private fun restFrame(definition: AnatomyDefinition, unitsPerMetre: Float): AnatomyFrame {
        val skeleton = SkeletonDefinition.DEFAULT_ADULT
        val bindJoints = Array(Joint.entries.size) { Vector3() }
        for ((joint, position) in X1Bind.bindJoints(skeleton)) {
            bindJoints[joint.index].set(position)
        }
        val bind = SkeletonPose(joints = bindJoints)
        val solver = AnatomySolver(definition, skeleton, unitsPerMetre.toDouble(), bind)
        val frame = AnatomyFrame.of(definition)
        try {
            solver.solve(bind, frame)
        } finally {
            solver.close()
        }
        for (slot in 0 until frame.headCount) {
            require(frame.pointCounts[slot] >= 2) {
                "head ${frame.heads[slot]} has no rest path, so it has no section to emit"
            }
        }
        return frame
    }

    /** One head's rest path in the rig's asset metres, with its cumulative arc length. */
    private fun pathOf(frame: AnatomyFrame, slot: Int, unitsPerMetre: Float): Path {
        val count = frame.pointCounts[slot]
        val points = FloatArray(count * 3)
        val scratch = FloatArray(3)
        for (index in 0 until count) {
            X1Bind.toAssetInto(
                Vector3(
                    frame.pointComponent(slot, index, 0),
                    frame.pointComponent(slot, index, 1),
                    frame.pointComponent(slot, index, 2)
                ),
                unitsPerMetre,
                scratch
            )
            scratch.copyInto(points, index * 3)
        }
        val cumulative = DoubleArray(count)
        for (index in 1 until count) {
            var sum = 0.0
            for (component in 0..2) {
                val delta = (points[index * 3 + component] - points[(index - 1) * 3 + component]).toDouble()
                sum += delta * delta
            }
            cumulative[index] = cumulative[index - 1] + sqrt(sum)
        }
        require(cumulative[count - 1] > 0.0) { "head ${frame.heads[slot]} has a degenerate rest path" }
        return Path(points, cumulative[count - 1], cumulative)
    }

    private fun sections(
        definition: AnatomyDefinition,
        frame: AnatomyFrame,
        binds: HelperBinds,
        unitsPerMetre: Float,
        layer: MuscleLayer
    ): List<Section> {
        val out = ArrayList<Section>()
        for (muscle in definition.muscles) {
            if (muscle.layer != layer) continue
            for (side in MuscleSide.entries) {
                val section = Section("${muscle.id}_${side.name}")
                for (headIndex in muscle.heads.indices) {
                    val head = MuscleHeadId(muscle.id, side, headIndex)
                    val group = binds.groupOf(head)
                    require(group >= 0) { "$head has no helper binds" }
                    val slot = frame.heads.indexOf(head)
                    require(slot >= 0) { "$head is not a slot of the definition" }
                    section.appendHead(muscle, group, binds, pathOf(frame, slot, unitsPerMetre))
                }
                require(section.vertexCount > 0) { "${section.name} has no geometry" }
                out += section
            }
        }
        return out
    }

    /** One muscle side's skinned surface: every head it owns, as rings around the helper frames. */
    private class Section(val name: String) {

        val positions = Floats()
        val normals = Floats()
        val tangents = Floats()
        val uvs = Floats()
        val joints = Ints()
        val weights = Floats()
        val indices = Ints()

        val vertexCount: Int get() = positions.size / 3

        fun appendHead(muscle: MuscleDefinition, group: Int, binds: HelperBinds, path: Path) {
            val stations = Array(binds.stationCount) { station ->
                val helper = binds.binds[binds.helperIndexOf(group, station)]
                Frame(helper.position, helper.basis)
            }
            val rings = Array(RING_COUNT) { ring -> ringFrame(ring, binds, stations, path) }
            val first = vertexCount

            for (ring in 0 until RING_COUNT) {
                val u = ring.toFloat() / (RING_COUNT - 1)
                val radius = minOf(radiusAt(muscle, u), bendCap(rings, ring))
                val owner = ownerAt(binds.stations, u)
                val frame = rings[ring]
                for (segment in 0 until RING_SEGMENTS) {
                    val angle = 2.0 * Math.PI * segment / RING_SEGMENTS
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    // The ring's radial basis is the frame's own X and Z, so a ring shrinks toward its
                    // centre instead of shearing across the path when the two helpers separate.
                    val nx = cosA * frame.axes[0] + sinA * frame.axes[6]
                    val ny = cosA * frame.axes[1] + sinA * frame.axes[7]
                    val nz = cosA * frame.axes[2] + sinA * frame.axes[8]
                    positions.add(frame.point[0] + radius * nx)
                    positions.add(frame.point[1] + radius * ny)
                    positions.add(frame.point[2] + radius * nz)
                    normals.add(nx)
                    normals.add(ny)
                    normals.add(nz)
                    tangents.add(frame.axes[3])
                    tangents.add(frame.axes[4])
                    tangents.add(frame.axes[5])
                    tangents.add(1f)
                    uvs.add(u)
                    uvs.add(segment.toFloat() / RING_SEGMENTS)
                    val helper = binds.paletteIndex(binds.helperIndexOf(group, owner.first))
                    val other = binds.paletteIndex(binds.helperIndexOf(group, owner.second))
                    joints.add(helper)
                    joints.add(other)
                    joints.add(helper)
                    joints.add(helper)
                    weights.add(owner.firstWeight)
                    weights.add(owner.secondWeight)
                    weights.add(0f)
                    weights.add(0f)
                }
            }

            for (ring in 0 until RING_COUNT - 1) {
                val lower = first + ring * RING_SEGMENTS
                val upper = lower + RING_SEGMENTS
                for (segment in 0 until RING_SEGMENTS) {
                    val next = (segment + 1) % RING_SEGMENTS
                    // Wound so (b - a) x (c - a) points along the outward radial, not into the muscle.
                    indices.add(lower + segment)
                    indices.add(upper + segment)
                    indices.add(upper + next)
                    indices.add(lower + segment)
                    indices.add(upper + next)
                    indices.add(lower + next)
                }
            }

            appendCap(rings[0], binds, group, ownerAt(binds.stations, 0f), first, inward = true)
            appendCap(
                rings[RING_COUNT - 1], binds, group, ownerAt(binds.stations, 1f),
                first + (RING_COUNT - 1) * RING_SEGMENTS, inward = false
            )
        }

        /**
         * Closes an open end with a fan to the frame's centre. An open tube reads as a hollow sleeve at
         * the tendon end, which a single-sided material would show as a hole.
         */
        private fun appendCap(
            frame: Frame,
            binds: HelperBinds,
            group: Int,
            owner: Owner,
            ringBase: Int,
            inward: Boolean
        ) {
            val centre = vertexCount
            val sign = if (inward) -1f else 1f
            positions.add(frame.point[0])
            positions.add(frame.point[1])
            positions.add(frame.point[2])
            normals.add(sign * frame.axes[3])
            normals.add(sign * frame.axes[4])
            normals.add(sign * frame.axes[5])
            tangents.add(frame.axes[3])
            tangents.add(frame.axes[4])
            tangents.add(frame.axes[5])
            tangents.add(1f)
            uvs.add(0.5f)
            uvs.add(0.5f)
            val helper = binds.paletteIndex(binds.helperIndexOf(group, owner.first))
            joints.add(helper)
            joints.add(helper)
            joints.add(helper)
            joints.add(helper)
            weights.add(1f)
            weights.add(0f)
            weights.add(0f)
            weights.add(0f)

            for (segment in 0 until RING_SEGMENTS) {
                val next = (segment + 1) % RING_SEGMENTS
                indices.add(centre)
                if (inward) {
                    indices.add(ringBase + segment)
                    indices.add(ringBase + next)
                } else {
                    indices.add(ringBase + next)
                    indices.add(ringBase + segment)
                }
            }
        }

        /**
         * How wide a ring at [ring] may be before the surface folds through its own centre line: a tube
         * of radius `r` swept around a turn of angle `θ` in steps of `s` turns inside out once
         * `r > (s / 2) / tan(θ / 2)`. A straight run turns by nothing and bounds nothing.
         */
        private fun bendCap(rings: Array<Frame>, ring: Int): Float {
            var cap = Float.MAX_VALUE
            for (index in maxOf(ring - 1, 1)..minOf(ring + 1, rings.size - 2)) {
                cap = minOf(cap, FOLD_FRACTION * foldRadius(rings, index))
            }
            return cap
        }

        private fun foldRadius(rings: Array<Frame>, index: Int): Float {
            val before = subtract(rings[index].point, rings[index - 1].point)
            val after = subtract(rings[index + 1].point, rings[index].point)
            val beforeLength = magnitude(before)
            val afterLength = magnitude(after)
            if (!(beforeLength > 0.0) || !(afterLength > 0.0)) return Float.MAX_VALUE
            val cos = (before[0] * after[0] + before[1] * after[1] + before[2] * after[2]) /
                (beforeLength * afterLength)
            val turn = Math.acos(cos.coerceIn(-1.0, 1.0))
            if (turn < 1e-6) return Float.MAX_VALUE
            return (0.5 * minOf(beforeLength, afterLength) / Math.tan(turn / 2.0)).toFloat()
        }
    }

    /**
     * A ring's frame: the blend of the two helper binds that own its arc fraction, centred on the
     * solved path itself. A centre interpolated between the two binds would run along the chord and
     * put a corner at every station, and a tube of this radius folds at a corner rather than bending.
     */
    private fun ringFrame(ring: Int, binds: HelperBinds, stations: Array<Frame>, path: Path): Frame {
        val u = ring.toFloat() / (RING_COUNT - 1)
        val owner = ownerAt(binds.stations, u)
        val first = stations[owner.first]
        val second = stations[owner.second]
        val axes = if (owner.secondWeight <= 0f) first.axes
        else if (owner.secondWeight >= 1f) second.axes
        else slerpAxes(first.axes, second.axes, owner.secondWeight)
        return Frame(path.at(u), axes)
    }

    /** One head's rest path, sampled by arc fraction into the rig's asset metres. */
    private class Path(private val points: FloatArray, private val total: Double, private val cumulative: DoubleArray) {

        val count: Int get() = cumulative.size

        fun at(u: Float): FloatArray {
            val target = total * u.toDouble()
            var segment = 0
            while (segment < count - 2 && cumulative[segment + 1] < target) segment++
            val span = cumulative[segment + 1] - cumulative[segment]
            val blend = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0
            return FloatArray(3) { component ->
                val from = points[(segment * 3 + component)]
                val to = points[((segment + 1) * 3 + component)]
                from + (to - from) * blend.toFloat()
            }
        }
    }

    /** A point in asset metres and its three axes, rows `x`, `y` (the path direction), `z`. */
    private class Frame(val point: FloatArray, val axes: FloatArray)

    /** The pair of helpers a station-to-station length belongs to, and the blend between them. */
    private class Owner(val first: Int, val firstWeight: Float, val second: Int, val secondWeight: Float)

    private fun ownerAt(stations: FloatArray, u: Float): Owner {
        val last = stations.size - 1
        if (u <= stations[0]) return Owner(0, 1f, 0, 0f)
        if (u >= stations[last]) return Owner(last, 1f, last, 0f)
        for (index in 0 until last) {
            if (u <= stations[index + 1]) {
                val span = stations[index + 1] - stations[index]
                val t = if (span > 0f) ((u - stations[index]) / span).coerceIn(0f, 1f) else 0f
                return Owner(index, 1f - t, index + 1, t)
            }
        }
        return Owner(last, 1f, last, 0f)
    }

    /** Linear between the muscle's authored radius knots: a circumference, not a deformation model. */
    private fun radiusAt(muscle: MuscleDefinition, u: Float): Float {
        val knots = muscle.radiusKnots
        require(knots.size >= 2) { "muscle ${muscle.id} has no radius profile" }
        if (u <= knots.first().first) return knots.first().second
        if (u >= knots.last().first) return knots.last().second
        for (index in 0 until knots.size - 1) {
            val start = knots[index]
            if (u <= knots[index + 1].first) {
                val span = knots[index + 1].first - start.first
                val t = if (span > 0f) (u - start.first) / span else 0f
                return start.second + (knots[index + 1].second - start.second) * t
            }
        }
        return knots.last().second
    }

    /** Two frames blended on the shortest arc; a plain lerp of the axes can swing them the wrong way. */
    private fun slerpAxes(from: FloatArray, to: FloatArray, t: Float): FloatArray {
        val a = quaternion(from)
        val b = quaternion(to)
        var bx = b[0]
        var by = b[1]
        var bz = b[2]
        var bw = b[3]
        var dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
        if (dot < 0f) {
            // The same rotation with the opposite sign is the shorter way round.
            bx = -bx; by = -by; bz = -bz; bw = -bw
            dot = -dot
        }
        val blended = if (dot > 0.9995f) {
            floatArrayOf(
                a[0] + (bx - a[0]) * t, a[1] + (by - a[1]) * t,
                a[2] + (bz - a[2]) * t, a[3] + (bw - a[3]) * t
            )
        } else {
            val angle = Math.acos(dot.toDouble())
            val sinAngle = sin(angle).toFloat()
            val wa = (sin((1.0 - t) * angle) / sinAngle).toFloat()
            val wb = (sin(t * angle) / sinAngle).toFloat()
            floatArrayOf(
                a[0] * wa + bx * wb, a[1] * wa + by * wb,
                a[2] * wa + bz * wb, a[3] * wa + bw * wb
            )
        }
        // The blend is not a unit quaternion, and a ring built from a stretched triad has normals that
        // are not unit length: normalize before reading the axes out.
        val length = sqrt(blended[0] * blended[0] + blended[1] * blended[1] + blended[2] * blended[2] + blended[3] * blended[3])
        if (!(length > 0f)) return from
        for (entry in 0 until 4) blended[entry] /= length
        return axesOf(blended)
    }

    /** The quaternion of a rotation whose basis rows are the three axes, as `x, y, z, w`. */
    private fun quaternion(basis: FloatArray): FloatArray {
        // A basis row is an axis, so the matrix' column `c` is basis row `c`: M[r][c] = basis[c * 3 + r].
        val m00 = basis[0]; val m10 = basis[1]; val m20 = basis[2]
        val m01 = basis[3]; val m11 = basis[4]; val m21 = basis[5]
        val m02 = basis[6]; val m12 = basis[7]; val m22 = basis[8]
        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }
    }

    /** Back to basis rows: one row per axis of the rotation. */
    private fun axesOf(q: FloatArray): FloatArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy),
            2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx),
            2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy)
        )
    }

    /** The GLB container: one buffer, one buffer view and one accessor per attribute, one skin. */
    private class Glb(
        private val binds: HelperBinds,
        private val body: GltfRig,
        private val sections: List<Section>
    ) {

        private val bin = Bin()
        private val views = ArrayList<View>()
        private val accessors = ArrayList<Accessor>()
        private val sectionAccessors = ArrayList<PrimitiveAccessors>()

        fun write(): ByteArray {
            for (section in sections) sectionAccessors += append(section)
            val inverseBinds = appendInverseBinds()
            return container(json(inverseBinds))
        }

        private fun append(section: Section): PrimitiveAccessors {
            val count = section.vertexCount
            val positions = section.positions.toFloatArray()
            val min = FloatArray(3) { Float.MAX_VALUE }
            val max = FloatArray(3) { -Float.MAX_VALUE }
            for (vertex in 0 until count) {
                for (component in 0..2) {
                    min[component] = minOf(min[component], positions[vertex * 3 + component])
                    max[component] = maxOf(max[component], positions[vertex * 3 + component])
                }
            }
            val position = addAccessor("VEC3", FLOAT, count, bin.floats(positions), ARRAY_BUFFER, min, max)
            val normal = addAccessor("VEC3", FLOAT, count, bin.floats(section.normals.toFloatArray()), ARRAY_BUFFER)
            val tangent = addAccessor("VEC4", FLOAT, count, bin.floats(section.tangents.toFloatArray()), ARRAY_BUFFER)
            val uv = addAccessor("VEC2", FLOAT, count, bin.floats(section.uvs.toFloatArray()), ARRAY_BUFFER)
            val joints = addAccessor("VEC4", UNSIGNED_SHORT, count, bin.ushorts(section.joints.toIntArray()), ARRAY_BUFFER)
            val weights = addAccessor("VEC4", FLOAT, count, bin.floats(section.weights.toFloatArray()), ARRAY_BUFFER)
            val indices = addAccessor(
                "SCALAR", UNSIGNED_INT, section.indices.size,
                bin.uints(section.indices.toIntArray()), ELEMENT_ARRAY_BUFFER
            )
            return PrimitiveAccessors(position, normal, tangent, uv, joints, weights, indices)
        }

        private fun appendInverseBinds(): Int {
            val matrices = FloatArray(binds.palette * 16)
            for (index in 0 until binds.skinJoints) {
                // The rig's own range keeps the body's inverse binds, so a vertex weighted to a body
                // joint deforms with the body it sits on rather than with an identity.
                body.inverseBinds[index].copyInto(matrices, index * 16)
            }
            for (helper in 0 until binds.helperCount) {
                val scratch = FloatArray(16)
                binds.inverseBindInto(helper, scratch)
                scratch.copyInto(matrices, (binds.skinJoints + helper) * 16)
            }
            return addAccessor("MAT4", FLOAT, binds.palette, bin.floats(matrices), 0, null, null)
        }

        private fun addAccessor(
            type: String,
            componentType: Int,
            count: Int,
            bytes: ByteArray,
            target: Int,
            min: FloatArray? = null,
            max: FloatArray? = null
        ): Int {
            bin.align()
            val offset = bin.size
            bin.write(bytes)
            views += View(offset, bytes.size, target)
            accessors += Accessor(type, componentType, count, views.size - 1, min, max)
            return accessors.size - 1
        }

        private fun json(inverseBindAccessor: Int): String {
            val text = StringBuilder(64 * 1024)
            text.append("{\n")
            text.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"monk-anatomy-export\"},\n")
            text.append("\"scene\":0,\n")
            text.append("\"scenes\":[{\"name\":\"Muscles\",\"nodes\":[0]}],\n")

            text.append("\"nodes\":[")
            text.append("{\"name\":\"Muscles\",\"children\":[")
            val children = ArrayList<String>(binds.palette + sections.size)
            for (index in 1..binds.palette) children += index.toString()
            for (index in 0 until sections.size) children += (1 + binds.palette + index).toString()
            text.append(children.joinToString(","))
            text.append("]}")
            // The body's own joint names, in the order its skin uploads them, so a matrix read back from
            // this asset can be named against the rig the rest of the character uses.
            for (index in 0 until binds.skinJoints) {
                text.append(",{\"name\":\"").append(escape(body.nodes[body.jointNodes[index]].name)).append("\"}")
            }
            for (helper in 0 until binds.helperCount) {
                text.append(",{\"name\":\"").append(escape(helperName(helper))).append("\"}")
            }
            for ((index, section) in sections.withIndex()) {
                text.append(",{\"name\":\"").append(escape(section.name))
                text.append("\",\"mesh\":").append(index).append(",\"skin\":0}")
            }
            text.append("],\n")

            text.append("\"skins\":[{\"name\":\"MuscleHelpers\",\"skeleton\":0,\"joints\":[")
            text.append((1..binds.palette).joinToString(","))
            text.append("],\"inverseBindMatrices\":").append(inverseBindAccessor).append("}],\n")

            text.append("\"materials\":[{\"name\":\"Muscle\",\"doubleSided\":false,")
            text.append("\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.62,0.2,0.24,1.0],")
            text.append("\"metallicFactor\":0.0,\"roughnessFactor\":0.55}}],\n")

            text.append("\"meshes\":[")
            for ((index, section) in sections.withIndex()) {
                val primitive = sectionAccessors[index]
                text.append("{\"name\":\"").append(escape(section.name)).append("\",\"primitives\":[{")
                text.append("\"attributes\":{")
                text.append("\"POSITION\":").append(primitive.positions).append(",")
                text.append("\"NORMAL\":").append(primitive.normals).append(",")
                text.append("\"TANGENT\":").append(primitive.tangents).append(",")
                text.append("\"TEXCOORD_0\":").append(primitive.uv).append(",")
                text.append("\"JOINTS_0\":").append(primitive.joints).append(",")
                text.append("\"WEIGHTS_0\":").append(primitive.weights)
                text.append("},\"indices\":").append(primitive.indices)
                text.append(",\"material\":0,\"mode\":").append(MODE_TRIANGLES).append("}]}")
                if (index != sections.lastIndex) text.append(',')
            }
            text.append("],\n")

            text.append("\"accessors\":[")
            for ((index, accessor) in accessors.withIndex()) {
                text.append("{\"bufferView\":").append(accessor.view)
                text.append(",\"componentType\":").append(accessor.componentType)
                text.append(",\"count\":").append(accessor.count)
                text.append(",\"type\":\"").append(accessor.type).append('"')
                accessor.min?.let { text.append(",\"min\":[").append(it.joinToString(",") { value -> number(value) }).append(']') }
                accessor.max?.let { text.append(",\"max\":[").append(it.joinToString(",") { value -> number(value) }).append(']') }
                text.append('}')
                if (index != accessors.lastIndex) text.append(',')
            }
            text.append("],\n")

            text.append("\"bufferViews\":[")
            for ((index, view) in views.withIndex()) {
                text.append("{\"buffer\":0,\"byteOffset\":").append(view.offset)
                text.append(",\"byteLength\":").append(view.length)
                if (view.target != 0) text.append(",\"target\":").append(view.target)
                text.append('}')
                if (index != views.lastIndex) text.append(',')
            }
            text.append("],\n")
            text.append("\"buffers\":[{\"byteLength\":").append(bin.size).append("}]\n")
            text.append("}\n")
            return text.toString()
        }

        private fun helperName(helper: Int): String {
            val head = binds.binds[helper].head
            return "${head.muscleId}_${head.side.name}_${head.headIndex}_${helper % binds.stationCount}"
        }

        private fun container(json: String): ByteArray {
            val jsonBytes = json.toByteArray(Charsets.UTF_8)
            val jsonPadding = (4 - jsonBytes.size % 4) % 4
            bin.align()
            val binBytes = bin.toByteArray()
            val total = 12 + 8 + jsonBytes.size + jsonPadding + 8 + binBytes.size
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(MAGIC)
            buffer.putInt(2)
            buffer.putInt(total)
            buffer.putInt(jsonBytes.size + jsonPadding)
            buffer.putInt(CHUNK_JSON)
            buffer.put(jsonBytes)
            repeat(jsonPadding) { buffer.put(' '.code.toByte()) }
            buffer.putInt(binBytes.size)
            buffer.putInt(CHUNK_BIN)
            buffer.put(binBytes)
            return buffer.array()
        }
    }

    private class PrimitiveAccessors(
        val positions: Int,
        val normals: Int,
        val tangents: Int,
        val uv: Int,
        val joints: Int,
        val weights: Int,
        val indices: Int
    )

    private class View(val offset: Int, val length: Int, val target: Int)

    private class Accessor(
        val type: String,
        val componentType: Int,
        val count: Int,
        val view: Int,
        val min: FloatArray?,
        val max: FloatArray?
    )

    private class Bin {
        private val out = ByteArrayOutputStream(256 * 1024)
        val size: Int get() = out.size()

        fun align() {
            while (out.size() % 4 != 0) out.write(0)
        }

        fun write(bytes: ByteArray) {
            out.write(bytes)
        }

        fun floats(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(values)
            return buffer.array()
        }

        fun uints(values: IntArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asIntBuffer().put(values)
            return buffer.array()
        }

        fun ushorts(values: IntArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (value in values) {
                require(value in 0..0xFFFF) { "$value does not fit a joint index" }
                buffer.putShort(value.toShort())
            }
            return buffer.array()
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Floats(capacity: Int = 512) {
        private var data = FloatArray(capacity)
        var size: Int = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }

    private class Ints(capacity: Int = 512) {
        private var data = IntArray(capacity)
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toIntArray(): IntArray = data.copyOf(size)
    }

    private fun subtract(a: FloatArray, b: FloatArray): DoubleArray = doubleArrayOf(
        (a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble(), (a[2] - b[2]).toDouble()
    )

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun magnitude(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private const val FLOAT = 5126
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private fun number(value: Float): String = if (value == 0f) "0.0" else value.toString()

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun option(args: Array<String>, name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
