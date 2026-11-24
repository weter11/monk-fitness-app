package com.monkfitness.app.anatomyexport

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleSide
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.GltfRig
import com.monkfitness.app.character.HelperBindManifest
import com.monkfitness.app.character.X1Bind
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Emits each muscle head's four helper binds from the solver's own rest path at the X1 A-pose, so an
 * exported layer cannot bind to rest geometry the runtime does not report. Positions and bases leave in
 * the rig's asset space — `B⁻¹ · p / unitsPerMetre`, the retargeter's own conversion — because a
 * helper consumed next to the 34-joint GLB skeleton has to sit in that skeleton's metres and axes.
 */
object AnatomyExport {

    /** Helper stations along the head's rest path, by arc fraction: origin, proximal, distal, insertion. */
    private val STATIONS = doubleArrayOf(0.0, 0.3, 0.7, 1.0)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(option(args, "--root") ?: "../..")
        val anatomyFile = File(root, option(args, "--anatomy") ?: "app/src/main/assets/anatomy/anatomy_v1.json")
        val rigFile = File(root, option(args, "--rig") ?: "app/src/main/assets/characters/humanoid_v2.glb")
        val outFile = File(root, option(args, "--out") ?: "app/src/main/assets/anatomy/helpers_v1.json")

        val definition = AnatomyDefinitionLoader.load(anatomyFile.readText())
        val skeleton = SkeletonDefinition.DEFAULT_ADULT
        val unitsPerMetre = X1Bind.unitsPerMetre(skeleton)

        val bindJoints = Array(Joint.entries.size) { Vector3() }
        for ((joint, position) in X1Bind.bindJoints(skeleton)) {
            bindJoints[joint.index].set(position)
        }
        val bind = SkeletonPose(joints = bindJoints)

        val solver = AnatomySolver(definition, skeleton, unitsPerMetre.toDouble(), bind)
        val frame = AnatomyFrame.of(definition)
        try {
            // The solver measured this same frame for its rest lengths in init; solving it again is
            // what makes the rest path readable without adding an accessor to the runtime.
            solver.solve(bind, frame)
        } finally {
            solver.close()
        }

        val helpers = place(definition, frame, unitsPerMetre)
        val rig = GltfRig.parse(rigFile.readBytes())
        val palette = HelperBindManifest.requireWithinPalette(rig.jointNodes.size, helpers.size)

        outFile.parentFile?.mkdirs()
        outFile.writeText(manifest(definition, rig.jointNodes.size, palette, helpers))
        println("wrote ${outFile.path}: ${helpers.size} helpers, palette $palette/${HelperBindManifest.PALETTE_LIMIT}")
    }

    private fun place(definition: AnatomyDefinition, frame: AnatomyFrame, unitsPerMetre: Float): List<Helper> {
        val helpers = ArrayList<Helper>(definition.headCount * STATIONS.size)
        for (slot in 0 until frame.headCount) {
            val id = frame.heads[slot]
            val count = frame.pointCounts[slot]
            require(count >= 2) {
                "head $id has no rest path ($count points): the rest stance refused it, so it has no bind"
            }
            val cumulative = DoubleArray(count)
            for (index in 1 until count) cumulative[index] = cumulative[index - 1] + distance(frame, slot, index - 1, index)
            val total = cumulative[count - 1]
            HelperBindManifest.requireRestLength(id.toString(), total)

            for ((stationIndex, station) in STATIONS.withIndex()) {
                val (point, tangent) = sample(frame, slot, station, cumulative, total)
                helpers += Helper(
                    name = "${id.muscleId}_${id.side.name}_${id.headIndex}_$stationIndex",
                    id = id,
                    station = station,
                    point = toAsset(point, unitsPerMetre),
                    basis = toAssetBasis(basis(tangent))
                )
            }
        }
        return helpers
    }

    /** The solver's engine units → the rig's asset metres, `B⁻¹ · p / unitsPerMetre`. */
    private fun toAsset(engine: DoubleArray, unitsPerMetre: Float): DoubleArray {
        val out = FloatArray(3)
        X1Bind.toAssetInto(Vector3(engine[0].toFloat(), engine[1].toFloat(), engine[2].toFloat()), unitsPerMetre, out)
        return doubleArrayOf(out[0].toDouble(), out[1].toDouble(), out[2].toDouble())
    }

    /** An axis is a direction, so it takes `B⁻¹` with no scale: a rotation stays orthonormal. */
    private fun toAssetBasis(engineAxes: DoubleArray): DoubleArray {
        val out = DoubleArray(engineAxes.size)
        for (row in 0 until 3) {
            val axis = X1Bind.toAssetDirection(
                Vector3(engineAxes[row * 3].toFloat(), engineAxes[row * 3 + 1].toFloat(), engineAxes[row * 3 + 2].toFloat())
            )
            out[row * 3] = axis.x.toDouble()
            out[row * 3 + 1] = axis.y.toDouble()
            out[row * 3 + 2] = axis.z.toDouble()
        }
        return out
    }

    /** Interpolates the polyline at [station] by arc length and returns the point and its segment direction. */
    private fun sample(
        frame: AnatomyFrame,
        slot: Int,
        station: Double,
        cumulative: DoubleArray,
        total: Double
    ): Pair<DoubleArray, DoubleArray> {
        val count = cumulative.size
        val target = total * station
        var segment = 0
        while (segment < count - 2 && cumulative[segment + 1] < target) segment++
        val span = cumulative[segment + 1] - cumulative[segment]
        val u = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0
        val point = DoubleArray(3) { component ->
            val from = frame.pointComponent(slot, segment, component).toDouble()
            val to = frame.pointComponent(slot, segment + 1, component).toDouble()
            from + (to - from) * u
        }
        var tangent = DoubleArray(3) { component ->
            (frame.pointComponent(slot, segment + 1, component) - frame.pointComponent(slot, segment, component)).toDouble()
        }
        var length = magnitude(tangent)
        if (!(length > 0.0)) {
            // A zero-length last segment has no direction; the chord of the whole head does.
            tangent = DoubleArray(3) { component ->
                (frame.pointComponent(slot, count - 1, component) - frame.pointComponent(slot, 0, component)).toDouble()
            }
            length = magnitude(tangent)
        }
        if (length > 0.0) {
            for (component in 0..2) tangent[component] /= length
        } else {
            tangent = doubleArrayOf(0.0, 1.0, 0.0)
        }
        return point to tangent
    }

    /**
     * `+Y` along the tangent, `+Z` perpendicular to it and the world up, `+X` completing an
     * orthonormal triad: the helper carries rotation only, so its scale is exactly 1. The axes are
     * engine-space here and are re-expressed in the rig's axes by [toAssetBasis].
     */
    private fun basis(tangent: DoubleArray): DoubleArray {
        val up = if (abs(tangent[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val z = normalize(cross(tangent, up))
        val x = cross(tangent, z)
        return doubleArrayOf(x[0], x[1], x[2], tangent[0], tangent[1], tangent[2], z[0], z[1], z[2])
    }

    private fun manifest(
        definition: AnatomyDefinition,
        skinJoints: Int,
        palette: Int,
        helpers: List<Helper>
    ): String {
        val text = StringBuilder(helpers.size * 220)
        text.append("{\n")
        text.append("  \"schema\": 1,\n")
        text.append("  \"anatomyId\": \"${definition.id}\",\n")
        text.append("  \"anatomyVersion\": ${definition.version},\n")
        text.append("  \"units\": \"${HelperBindManifest.UNITS}\",\n")
        text.append("  \"stations\": [${STATIONS.joinToString(", ")}],\n")
        text.append("  \"skinJoints\": $skinJoints,\n")
        text.append("  \"helpersPerHead\": ${STATIONS.size},\n")
        text.append("  \"helperCount\": ${helpers.size},\n")
        text.append("  \"palette\": $palette,\n")
        text.append("  \"paletteLimit\": ${HelperBindManifest.PALETTE_LIMIT},\n")
        text.append("  \"helpers\": [\n")
        for ((index, helper) in helpers.withIndex()) {
            text.append("    {")
            text.append("\"name\": \"${helper.name}\", ")
            text.append("\"muscleId\": \"${helper.id.muscleId}\", ")
            text.append("\"side\": \"${helper.id.side.name}\", ")
            text.append("\"headIndex\": ${helper.id.headIndex}, ")
            text.append("\"station\": ${helper.station}, ")
            text.append("\"position\": [")
            for (component in 0..2) {
                if (component > 0) text.append(", ")
                text.append(number(helper.point[component]))
            }
            text.append("], ")
            text.append("\"basis\": [")
            for (row in 0 until 3) {
                if (row > 0) text.append(", ")
                text.append("[${number(helper.basis[row * 3])}, ${number(helper.basis[row * 3 + 1])}, ${number(helper.basis[row * 3 + 2])}]")
            }
            text.append("]}")
            if (index != helpers.lastIndex) text.append(',')
            text.append('\n')
        }
        text.append("  ]\n}\n")
        return text.toString()
    }

    private fun number(value: Double): String = HelperBindManifest.number(value)

    private fun distance(frame: AnatomyFrame, slot: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = (frame.pointComponent(slot, to, component) - frame.pointComponent(slot, from, component)).toDouble()
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun magnitude(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: DoubleArray): DoubleArray {
        val length = magnitude(v)
        return if (length > 0.0) doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
        else doubleArrayOf(0.0, 0.0, 1.0)
    }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun option(args: Array<String>, name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    /** One helper's bind frame in the rig's asset space: a position in metres and a rotation-only basis. */
    private class Helper(
        val name: String,
        val id: MuscleHeadId,
        val station: Double,
        val point: DoubleArray,
        val basis: DoubleArray
    )
}
