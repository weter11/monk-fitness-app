package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Derives the muscle layer's whole skin palette each frame: the rig's own joint matrices as the base
 * range, then one matrix per helper bone re-sampled from the solved path.
 *
 * A helper's matrix is `world · inverseBind` in the rig's asset space — the same convention
 * [PoseRetargeter] uploads for the body — so both ranges reach one renderable unmodified.
 */
class MuscleLayerSkinner(
    private val rig: GltfRig,
    private val binds: HelperBinds,
    heads: List<MuscleHeadId>,
    private val unitsPerMetre: Float,
    pointCapacity: Int
) {

    private val groupBySlot: IntArray

    init {
        require(unitsPerMetre > 0f) { "unitsPerMetre must be positive, was $unitsPerMetre" }
        require(pointCapacity >= 2) { "pointCapacity $pointCapacity cannot hold a path" }
        require(rig.jointNodes.size == binds.palette) {
            "the rig skins ${rig.jointNodes.size} joints, the binds need ${binds.palette}"
        }
        require(rig.inverseBinds.size >= binds.palette) {
            "the rig carries ${rig.inverseBinds.size} inverse binds for ${binds.palette} joints"
        }
        require(heads.size * binds.stationCount == binds.helperCount) {
            "${heads.size} heads carry ${heads.size * binds.stationCount} helpers, " +
                "the manifest ${binds.helperCount}: some helper would never be written"
        }
        groupBySlot = IntArray(heads.size) { slot ->
            val group = binds.groupOf(heads[slot])
            require(group >= 0) { "${heads[slot]} has no helper binds" }
            group
        }
    }

    /** Every joint the layer's renderable must accept: the rig's own plus one per helper. */
    val paletteCount: Int get() = binds.palette

    val helperCount: Int get() = binds.helperCount

    /** The rig's own joint count, the size of the base range [write] copies. */
    val skinJoints: Int get() = binds.skinJoints

    /**
     * Fills [out] with [paletteCount] column-major skinning matrices: [body]'s joints first, then the
     * helpers in manifest order. Allocates nothing, so the steady skinning path can run per frame.
     */
    fun write(frame: AnatomyFrame, body: FloatArray, out: FloatArray) {
        val base = binds.skinJoints * 16
        require(body.size >= base) { "body must hold $base floats, was ${body.size}" }
        require(out.size >= paletteCount * 16) { "out must hold ${paletteCount * 16} floats, was ${out.size}" }
        require(frame.headCount == groupBySlot.size) {
            "the frame carries ${frame.headCount} heads, the skinner ${groupBySlot.size}"
        }
        System.arraycopy(body, 0, out, 0, base)
        for (slot in 0 until frame.headCount) writeHead(frame, slot, groupBySlot[slot], out)
    }

    private fun writeHead(frame: AnatomyFrame, slot: Int, group: Int, out: FloatArray) {
        val count = frame.pointCounts[slot]
        val path = frame.statuses[slot] == PathStatus.VALID.ordinal && count >= 2
        val total = if (path) arcLength(frame, slot, count) else 0.0
        if (!path || !(total > 0.0) || !total.isFinite()) {
            // A head with no path has no frame to follow. Identity leaves its vertices at the bind
            // instead of at NaN or at last frame's pose; the instance is not drawn either way.
            for (station in 0 until binds.stationCount) {
                identityInto(out, helperOffset(group, station))
            }
            return
        }
        val stations = binds.stations
        for (station in 0 until binds.stationCount) {
            sampleInto(frame, slot, count, total, stations[station])
            writeMatrix(world, rig.inverseBinds[binds.paletteIndex(binds.helperIndexOf(group, station))],
                out, helperOffset(group, station))
        }
    }

    /** Arc length from the first point to every later one; the last entry is the head's total. */
    private fun arcLength(frame: AnatomyFrame, slot: Int, count: Int): Double {
        cumulative[0] = 0.0
        for (index in 1 until count) {
            var sum = 0.0
            for (component in 0..2) {
                val delta = (
                    frame.pointComponent(slot, index, component) -
                        frame.pointComponent(slot, index - 1, component)
                    ).toDouble()
                sum += delta * delta
            }
            cumulative[index] = cumulative[index - 1] + sqrt(sum)
        }
        return cumulative[count - 1]
    }

    /**
     * Rewrites [world] with the helper frame at [station]: the point and the path's own direction,
     * carried into the rig's asset space the way the exporter carries a bind.
     */
    private fun sampleInto(frame: AnatomyFrame, slot: Int, count: Int, total: Double, station: Float) {
        val target = total * station.toDouble()
        var segment = 0
        while (segment < count - 2 && cumulative[segment + 1] < target) segment++
        val span = cumulative[segment + 1] - cumulative[segment]
        val u = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0

        for (component in 0..2) {
            val from = frame.pointComponent(slot, segment, component).toDouble()
            val to = frame.pointComponent(slot, segment + 1, component).toDouble()
            enginePoint[component] = (from + (to - from) * u).toFloat()
        }
        pointEngine.set(enginePoint[0], enginePoint[1], enginePoint[2])
        X1Bind.toAssetInto(pointEngine, unitsPerMetre, assetPoint)

        var tx = direction(frame, slot, segment, 0)
        var ty = direction(frame, slot, segment, 1)
        var tz = direction(frame, slot, segment, 2)
        var length = sqrt(tx * tx + ty * ty + tz * tz)
        if (!(length > 0.0)) {
            // A zero-length last segment has no direction; the chord of the whole head does.
            tx = (frame.pointComponent(slot, count - 1, 0) - frame.pointComponent(slot, 0, 0)).toDouble()
            ty = (frame.pointComponent(slot, count - 1, 1) - frame.pointComponent(slot, 0, 1)).toDouble()
            tz = (frame.pointComponent(slot, count - 1, 2) - frame.pointComponent(slot, 0, 2)).toDouble()
            length = sqrt(tx * tx + ty * ty + tz * tz)
            if (!(length > 0.0)) {
                tx = 0.0
                ty = 1.0
                tz = 0.0
                length = 1.0
            }
        }
        tx /= length
        ty /= length
        tz /= length

        val upX = if (abs(ty) < 0.9) 0.0 else 1.0
        val upY = if (abs(ty) < 0.9) 1.0 else 0.0
        var zx = ty * 0.0 - tz * upY
        var zy = tz * upX - tx * 0.0
        var zz = tx * upY - ty * upX
        val zLength = sqrt(zx * zx + zy * zy + zz * zz)
        if (zLength > 0.0) {
            zx /= zLength
            zy /= zLength
            zz /= zLength
        }
        // The triad is right-handed by construction: x completes (tangent, z) the short way.
        val xx = ty * zz - tz * zy
        val xy = tz * zx - tx * zz
        val xz = tx * zy - ty * zx

        axisEngine.set(xx.toFloat(), xy.toFloat(), xz.toFloat())
        X1Bind.toAssetDirectionInto(axisEngine, assetAxis)
        basisAsset[0] = assetAxis[0]; basisAsset[1] = assetAxis[1]; basisAsset[2] = assetAxis[2]
        axisEngine.set(tx.toFloat(), ty.toFloat(), tz.toFloat())
        X1Bind.toAssetDirectionInto(axisEngine, assetAxis)
        basisAsset[3] = assetAxis[0]; basisAsset[4] = assetAxis[1]; basisAsset[5] = assetAxis[2]
        axisEngine.set(zx.toFloat(), zy.toFloat(), zz.toFloat())
        X1Bind.toAssetDirectionInto(axisEngine, assetAxis)
        basisAsset[6] = assetAxis[0]; basisAsset[7] = assetAxis[1]; basisAsset[8] = assetAxis[2]

        writeWorld(assetPoint)
    }

    private fun direction(frame: AnatomyFrame, slot: Int, segment: Int, component: Int): Double =
        (frame.pointComponent(slot, segment + 1, component) - frame.pointComponent(slot, segment, component)).toDouble()

    /** Column-major 4x4 from the asset-space basis rows and the asset-space position. */
    private fun writeWorld(point: FloatArray) {
        world[0] = basisAsset[0]; world[1] = basisAsset[1]; world[2] = basisAsset[2]; world[3] = 0f
        world[4] = basisAsset[3]; world[5] = basisAsset[4]; world[6] = basisAsset[5]; world[7] = 0f
        world[8] = basisAsset[6]; world[9] = basisAsset[7]; world[10] = basisAsset[8]; world[11] = 0f
        world[12] = point[0]; world[13] = point[1]; world[14] = point[2]; world[15] = 1f
    }

    private fun helperOffset(group: Int, station: Int): Int =
        binds.paletteIndex(binds.helperIndexOf(group, station)) * 16

    private fun writeMatrix(world: FloatArray, inverseBind: FloatArray, out: FloatArray, offset: Int) {
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += world[k * 4 + row] * inverseBind[column * 4 + k]
                out[offset + column * 4 + row] = sum
            }
        }
    }

    private fun identityInto(out: FloatArray, offset: Int) {
        for (entry in 0 until 16) out[offset + entry] = 0f
        out[offset] = 1f
        out[offset + 5] = 1f
        out[offset + 10] = 1f
        out[offset + 15] = 1f
    }

    // Reused every frame: a per-frame allocation here is a per-frame GC cost on the render thread.
    private val cumulative = DoubleArray(pointCapacity)
    private val pointEngine = Vector3()
    private val axisEngine = Vector3()
    private val assetPoint = FloatArray(3)
    private val assetAxis = FloatArray(3)
    private val enginePoint = FloatArray(3)
    private val basisAsset = FloatArray(9)
    private val world = FloatArray(16)
}
