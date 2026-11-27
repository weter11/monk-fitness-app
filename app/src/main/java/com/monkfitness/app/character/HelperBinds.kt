package com.monkfitness.app.character

import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One helper's bind frame in the rig's asset space: metres, and a basis whose rows are its axes.
 * Rotation only — a helper carrying scale would stretch the muscle it skins, which §7.6 forbids.
 */
class HelperBind(
    val head: MuscleHeadId,
    val station: Float,
    val position: FloatArray,
    /** Row-major 3x3: row `i` is axis `i` (x, y, z) in asset axes. */
    val basis: FloatArray
) {

    /** Column-major 4x4 of the bind pose itself, the transform the inverse bind undoes. */
    fun bindWorldInto(out: FloatArray) {
        out[0] = basis[0]; out[1] = basis[1]; out[2] = basis[2]; out[3] = 0f
        out[4] = basis[3]; out[5] = basis[4]; out[6] = basis[5]; out[7] = 0f
        out[8] = basis[6]; out[9] = basis[7]; out[10] = basis[8]; out[11] = 0f
        out[12] = position[0]; out[13] = position[1]; out[14] = position[2]; out[15] = 1f
    }

    /** Column-major 4x4 that takes a bind-space vertex back into the helper's local space. */
    fun inverseBindInto(out: FloatArray) {
        // The basis is a pure rotation, so its inverse is its transpose: the columns of the
        // inverse are the rows of the basis.
        out[0] = basis[0]; out[1] = basis[3]; out[2] = basis[6]; out[3] = 0f
        out[4] = basis[1]; out[5] = basis[4]; out[6] = basis[7]; out[7] = 0f
        out[8] = basis[2]; out[9] = basis[5]; out[10] = basis[8]; out[11] = 0f
        val x = position[0]
        val y = position[1]
        val z = position[2]
        // The translation is the inverse rotation's rows dotted with the bind position, negated; the
        // inverse's rows are the basis' own rows, which is why the same three terms appear in order.
        out[12] = -(basis[0] * x + basis[1] * y + basis[2] * z)
        out[13] = -(basis[3] * x + basis[4] * y + basis[5] * z)
        out[14] = -(basis[6] * x + basis[7] * y + basis[8] * z)
        out[15] = 1f
    }
}

/**
 * One layer's exported helper binds: the palette it binds into, the stations every head carries, and each
 * helper's frame. Helpers are head-major and station-minor, so helper `i` owns palette slot `skinJoints + i`.
 */
class HelperBinds(
    val skinJoints: Int,
    val stations: FloatArray,
    val binds: List<HelperBind>,
    /** The layer this palette skins; null for a schema-1 manifest, which carried a single one. */
    val layer: MuscleLayer? = null
) {

    init {
        require(skinJoints > 0) { "the layer needs the rig's joints, got $skinJoints" }
        require(stations.size >= 2) { "a head needs at least two stations, got ${stations.size}" }
        require(binds.isNotEmpty()) { "the manifest carries no helpers" }
        require(binds.size % stations.size == 0) {
            "${binds.size} helpers do not divide into groups of ${stations.size}"
        }
        HelperBindManifest.requireWithinPalette(skinJoints, binds.size, layer)
        for (index in binds.indices) {
            val bind = binds[index]
            require(bind.station == stations[index % stations.size]) {
                "helper $index binds at ${bind.station}, not station ${stations[index % stations.size]}"
            }
            require(index == 0 || index % stations.size != 0 || bind.head != binds[index - 1].head) {
                "helper $index repeats head ${bind.head} inside its station group"
            }
        }
    }

    val helperCount: Int get() = binds.size

    val stationCount: Int get() = stations.size

    /** Every joint the layer's skin addressing must cover: the rig's own plus one per helper. */
    val palette: Int get() = skinJoints + helperCount

    private val groupOf: Map<MuscleHeadId, Int> = buildMap {
        binds.forEachIndexed { index, bind -> putIfAbsent(bind.head, index / stationCount) }
    }

    /** The head's helper group, or -1 when this manifest has no binds for it. */
    fun groupOf(head: MuscleHeadId): Int = groupOf[head] ?: -1

    /** The palette slot of a helper, by its index in manifest order. */
    fun paletteIndex(helperIndex: Int): Int = skinJoints + helperIndex

    fun bindWorldInto(helperIndex: Int, out: FloatArray) = binds[helperIndex].bindWorldInto(out)

    fun inverseBindInto(helperIndex: Int, out: FloatArray) = binds[helperIndex].inverseBindInto(out)

    /** Registers a group's helper indices are contiguous, so a group is `group * stationCount + s`. */
    fun helperIndexOf(group: Int, stationIndex: Int): Int = group * stationCount + stationIndex
}

/**
 * Every layer's helper binds, read once: one [HelperBinds] per layer, each with its own palette.
 * The atlas is one asset per skin, so no palette has to hold more than one layer's helpers.
 */
class HelperBindSet(
    val skinJoints: Int,
    val stations: FloatArray,
    val partitions: List<HelperBinds>
) {

    init {
        require(partitions.isNotEmpty()) { "the manifest carries no helpers" }
        require(partitions.map { it.layer }.distinct().size == partitions.size) {
            "two partitions claim the same layer: ${partitions.map { it.layer }}"
        }
        require(partitions.all { it.skinJoints == skinJoints && it.stations.contentEquals(stations) }) {
            "every partition must bind the same rig, at the same stations"
        }
    }

    /** Every helper in the atlas, across every layer. */
    val helperCount: Int get() = partitions.sumOf { it.helperCount }

    /**
     * The layer's own palette and helpers. A layer the manifest does not carry is a refusal rather
     * than an empty skin, because a layer with no helpers draws nothing an author asked for.
     */
    fun partition(layer: MuscleLayer): HelperBinds =
        partitions.firstOrNull { it.layer == layer } ?: throw IllegalArgumentException(
            "the manifest carries no $layer helpers; it has ${partitions.map { it.layer }}"
        )

    /** The single unlabelled partition of a schema-1 manifest, or null when the atlas is split. */
    val unpartitioned: HelperBinds?
        get() = partitions.singleOrNull()?.takeIf { it.layer == null }

    companion object {

        const val ASSET_PATH = "anatomy/helpers_v1.json"

        private const val SCHEMA_SINGLE = 1
        private const val SCHEMA_PARTITIONED = 2

        /**
         * Reads the manifest, refusing anything a skin cannot use: a palette over the bindable
         * limit, a non-finite number, or a basis that is not a rotation would all deform a muscle
         * silently rather than fail on screen.
         */
        fun read(text: String): HelperBindSet {
            val root = JSONObject(text)
            val schema = root.getInt("schema")
            require(schema == SCHEMA_SINGLE || schema == SCHEMA_PARTITIONED) {
                "helper manifest schema $schema is not $SCHEMA_SINGLE or $SCHEMA_PARTITIONED"
            }
            require(root.getString("units") == HelperBindManifest.UNITS) {
                "helper binds must be in ${HelperBindManifest.UNITS}, not ${root.getString("units")}"
            }
            val stations = root.getJSONArray("stations")
            val stationValues = FloatArray(stations.length()) { stations.getDouble(it).toFloat() }
            require(stationValues[0] == 0f && stationValues[stationValues.size - 1] == 1f) {
                "stations ${stationValues.toList()} do not span the whole path"
            }
            for (index in 1 until stationValues.size) {
                require(stationValues[index] > stationValues[index - 1]) {
                    "stations ${stationValues.toList()} are not increasing"
                }
            }

            val skinJoints = root.getInt("skinJoints")
            require(root.getInt("helpersPerHead") == stationValues.size) {
                "the manifest declares ${root.getInt("helpersPerHead")} helpers per head, " +
                    "not the ${stationValues.size} stations it samples"
            }
            val array = root.getJSONArray("helpers")
            require(root.getInt("helperCount") == array.length()) {
                "the manifest says ${root.getInt("helperCount")} helpers and carries ${array.length()}"
            }

            val entries = entries(array, schema)
            val partitions = when (schema) {
                SCHEMA_SINGLE -> listOf(HelperBinds(skinJoints, stationValues, entries.map { it.bind }, null))
                else -> partitioned(root, skinJoints, stationValues, entries)
            }
            if (schema == SCHEMA_SINGLE) {
                require(root.getInt("palette") == partitions.single().palette) {
                    "the manifest claims palette ${root.getInt("palette")}, " +
                        "the binds need ${partitions.single().palette}"
                }
            }
            return HelperBindSet(skinJoints, stationValues, partitions)
        }

        /** Each helper in manifest order, tagged with the layer its partition declares. */
        private fun entries(array: JSONArray, schema: Int): List<Entry> {
            val out = ArrayList<Entry>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val head = MuscleHeadId(
                    entry.getString("muscleId"),
                    MuscleSide.valueOf(entry.getString("side")),
                    entry.getInt("headIndex")
                )
                val position = components(entry.getJSONArray("position"), "position")
                val rows = entry.getJSONArray("basis")
                val basis = FloatArray(9)
                for (row in 0 until 3) {
                    val axis = components(rows.getJSONArray(row), "basis[$row]")
                    for (column in 0 until 3) basis[row * 3 + column] = axis[column]
                }
                val name = entry.optString("name", head.toString())
                requireRotation(name, basis)
                val layer = if (schema == SCHEMA_SINGLE) null else MuscleLayer.valueOf(entry.getString("layer"))
                out += Entry(
                    HelperBind(
                        head = head,
                        station = entry.getDouble("station").toFloat(),
                        position = position,
                        basis = basis
                    ),
                    layer
                )
            }
            return out
        }

        /**
         * The declared partitions, cut out of the manifest's own helper order: a helper tagged one
         * layer inside another layer's range would skin a muscle with a palette that does not hold it.
         */
        private fun partitioned(
            root: JSONObject,
            skinJoints: Int,
            stations: FloatArray,
            entries: List<Entry>
        ): List<HelperBinds> {
            val declared = root.getJSONArray("partitions")
            require(declared.length() > 0) { "a partitioned manifest needs at least one partition" }
            val out = ArrayList<HelperBinds>(declared.length())
            var first = 0
            for (slot in 0 until declared.length()) {
                val partition = declared.getJSONObject(slot)
                val layer = MuscleLayer.valueOf(partition.getString("layer"))
                require(partition.optInt("firstHelper", first) == first) {
                    "the $layer partition starts at ${partition.optInt("firstHelper", first)}, " +
                        "not where the previous one ends ($first)"
                }
                val count = partition.getInt("helperCount")
                require(count > 0 && first + count <= entries.size) {
                    "the $layer partition claims $count helpers at $first of ${entries.size}"
                }
                val slice = entries.subList(first, first + count)
                val foreign = slice.indexOfFirst { it.layer != layer }
                require(foreign < 0) {
                    "the $layer partition carries a helper tagged ${slice.getOrNull(foreign)?.layer}"
                }
                val binds = HelperBinds(skinJoints, stations, slice.map { it.bind }, layer)
                require(partition.getInt("palette") == binds.palette) {
                    "the $layer partition claims palette ${partition.getInt("palette")}, " +
                        "the binds need ${binds.palette}"
                }
                out += binds
                first += count
            }
            require(first == entries.size) {
                "the partitions cover $first of ${entries.size} helpers"
            }
            return out
        }

        private fun components(array: org.json.JSONArray, label: String): FloatArray {
            require(array.length() == 3) { "$label has ${array.length()} components, not 3" }
            return FloatArray(3) { index -> floatOf(array.getDouble(index), "$label[$index]") }
        }

        private fun floatOf(value: Double, label: String): Float {
            require(value.isFinite()) { "$label is $value: a skin cannot bind it" }
            return value.toFloat()
        }

        private fun requireRotation(name: String, basis: FloatArray) {
            for (row in 0 until 3) {
                val length = sqrt(
                    basis[row * 3] * basis[row * 3] +
                        basis[row * 3 + 1] * basis[row * 3 + 1] +
                        basis[row * 3 + 2] * basis[row * 3 + 2]
                )
                require(abs(length - 1f) <= ROTATION_TOLERANCE) {
                    "$name axis $row is $length long, not unit"
                }
            }
            for (first in 0 until 3) for (second in first + 1 until 3) {
                val dot = basis[first * 3] * basis[second * 3] +
                    basis[first * 3 + 1] * basis[second * 3 + 1] +
                    basis[first * 3 + 2] * basis[second * 3 + 2]
                require(abs(dot) <= ROTATION_TOLERANCE) { "$name axes $first/$second are not orthogonal" }
            }
            // A mirrored basis has det -1: a rotation the inverse-bind transpose would invert wrongly.
            val determinant = basis[0] * (basis[4] * basis[8] - basis[5] * basis[7]) -
                basis[1] * (basis[3] * basis[8] - basis[5] * basis[6]) +
                basis[2] * (basis[3] * basis[7] - basis[4] * basis[6])
            require(determinant > 0f) { "$name basis is mirrored, not a rotation" }
        }

        private const val ROTATION_TOLERANCE = 1e-5f
    }

    private class Entry(val bind: HelperBind, val layer: MuscleLayer?)
}
