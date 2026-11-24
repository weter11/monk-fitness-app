package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.HelperBindManifest
import com.monkfitness.app.character.X1Bind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The exported helper binds are a claim about the solver's rest path: four per head at 0/30/70/100 % of
 * arc, exactly on the polyline, in the same asset metres and axes as `humanoid_v2.glb`'s skeleton. Both
 * are re-derived here, so a solver change that moves a rest path, or an export that leaves in engine
 * units, fails instead of shipping a layer bound off the body.
 */
class HelperBindTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale: Float = X1Bind.unitsPerMetre(skeleton)
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)

    @Test
    fun everyHelperBindsOnTheSolverRestPathAtItsStation() {
        val frame = restFrame()
        val helpers = manifest().getJSONArray("helpers")
        assertEquals("four helpers per head-side instance", definition.headCount * STATIONS, helpers.length())

        var worstPosition = 0.0
        var worstAxis = 0.0
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val station = helper.getDouble("station")
            val (point, tangent) = stationFrame(frame, slotOf(frame, helper), station)

            val expectedPosition = toAsset(point)
            val positionResidual = distance(expectedPosition, helper.getJSONArray("position"))
            worstPosition = maxOf(worstPosition, positionResidual)
            assertTrue("$name binds $positionResidual m off the rest path at station $station", positionResidual <= TOLERANCE_METRES)

            // The basis' +Y is the path's own direction, carried onto the rig's axes.
            val expectedAxis = toAssetDirection(tangent)
            val axis = helper.getJSONArray("basis").getJSONArray(1)
            val axisResidual = maxOf(
                maxOf(abs(axis.getDouble(0) - expectedAxis[0]), abs(axis.getDouble(1) - expectedAxis[1])),
                abs(axis.getDouble(2) - expectedAxis[2])
            )
            worstAxis = maxOf(worstAxis, axisResidual)
            assertTrue("$name basis points $axisResidual off the rest path tangent at station $station", axisResidual <= TOLERANCE_METRES)
        }
        println("worst helper bind residual = $worstPosition m, worst basis-axis residual = $worstAxis")
    }

    @Test
    fun everyHelperCarriesRotationOnly() {
        val helpers = manifest().getJSONArray("helpers")
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val basis = helper.getJSONArray("basis")
            val rows = (0 until 3).map { row ->
                (0 until 3).map { column -> basis.getJSONArray(row).getDouble(column) }
            }
            for (row in 0 until 3) {
                assertEquals("$name row $row is not unit length", 1.0, magnitude(rows[row]), 1e-6)
            }
            for (first in 0 until 3) for (second in first + 1 until 3) {
                assertEquals("$name rows $first/$second are not orthogonal", 0.0, dot(rows[first], rows[second]), 1e-6)
            }
            assertEquals("$name basis is not a right-handed rotation", 1.0, determinant(rows), 1e-6)
        }
    }

    @Test
    fun everyHelperLandsInsideTheRigInMetres() {
        val manifest = manifest()
        assertEquals("helpers are written in the rig's own unit", HelperBindManifest.UNITS, manifest.getString("units"))

        val envelope = bodyEnvelope()
        // The rig is a metre-scale body: a manifest left in engine units is ~247x this tall.
        assertTrue("the rig's body is ${envelope[4]} m tall, not a metre-scale human", envelope[4] in 1.4..2.0)

        val helpers = manifest.getJSONArray("helpers")
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val position = helper.getJSONArray("position")
            val point = doubleArrayOf(position.getDouble(0), position.getDouble(1), position.getDouble(2))
            lowest = minOf(lowest, point[1])
            highest = maxOf(highest, point[1])
            assertTrue("$name sits at y=${point[1]} m, outside the body's 0.0..1.75 m", point[1] >= 0.0 && point[1] <= 1.75)
            assertTrue(
                "$name at (${point[0]}, ${point[1]}, ${point[2]}) m leaves the rig's body envelope " +
                    "x ${envelope[0]}..${envelope[3]}, y ${envelope[1]}..${envelope[4]}, z ${envelope[2]}..${envelope[5]}",
                point[0] >= envelope[0] && point[0] <= envelope[3] &&
                    point[1] >= envelope[1] && point[1] <= envelope[4] &&
                    point[2] >= envelope[2] && point[2] <= envelope[5]
            )
        }
        println("helper y bounds = $lowest..$highest m, inside the rig's envelope")
    }

    @Test
    fun thePaletteIsTheWritersOwnCountAndTheGateRefusesOneOver() {
        val manifest = manifest()
        val skin = manifest.getInt("skinJoints")
        val helpers = manifest.getInt("helperCount")
        assertEquals("one helper per head and station", definition.headCount * STATIONS, helpers)
        assertEquals(
            "the manifest's palette is the writer's own count",
            manifest.getInt("palette"),
            HelperBindManifest.requireWithinPalette(skin, helpers)
        )

        // The budget exactly is bindable and one joint over is not, so an off-by-one gate cannot ship.
        val budget = HelperBindManifest.PALETTE_LIMIT
        assertEquals(budget, HelperBindManifest.requireWithinPalette(skin, budget - skin))
        assertThrows(IllegalArgumentException::class.java) {
            HelperBindManifest.requireWithinPalette(skin, budget - skin + 1)
        }
    }

    @Test
    fun aNonFiniteRestLengthIsRefusedBeforeItReachesTheJson() {
        for (bad in doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            assertThrows("$bad passes a bare `> 0` rest length", IllegalArgumentException::class.java) {
                HelperBindManifest.requireRestLength("rectus_abdominis_A_0", bad)
            }
            assertThrows("$bad would be written as JSON no reader accepts", IllegalArgumentException::class.java) {
                HelperBindManifest.number(bad)
            }
        }
        val zero = assertThrows(IllegalArgumentException::class.java) {
            HelperBindManifest.requireRestLength("rectus_abdominis_A_0", 0.0)
        }
        assertTrue("a zero-length head must be refused, not sampled: ${zero.message}", zero.message?.contains("zero-length") == true)
    }

    private fun restFrame(): AnatomyFrame {
        val solver = AnatomySolver(definition, skeleton, scale.toDouble(), restPose)
        val frame = AnatomyFrame.of(definition)
        return solver.solve(restPose, frame)
    }

    private fun slotOf(frame: AnatomyFrame, helper: JSONObject): Int {
        val id = MuscleHeadId(
            helper.getString("muscleId"),
            MuscleSide.valueOf(helper.getString("side")),
            helper.getInt("headIndex")
        )
        val slot = frame.heads.indexOf(id)
        assertTrue("$id is not a slot of this definition", slot >= 0)
        return slot
    }

    /**
     * The engine-space point and unit tangent at [station] of [slot]'s polyline, by arc length: the
     * exporter's own sample, before it converts the pair into the rig's space.
     */
    private fun stationFrame(frame: AnatomyFrame, slot: Int, station: Double): Pair<DoubleArray, DoubleArray> {
        val count = frame.pointCounts[slot]
        val cumulative = DoubleArray(count)
        for (index in 1 until count) cumulative[index] = cumulative[index - 1] + distance(frame, slot, index - 1, index)
        val target = cumulative[count - 1] * station
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
        if (!(magnitude(tangent) > 0.0)) {
            tangent = DoubleArray(3) { component ->
                (frame.pointComponent(slot, count - 1, component) - frame.pointComponent(slot, 0, component)).toDouble()
            }
        }
        val length = magnitude(tangent)
        if (length > 0.0) for (component in 0..2) tangent[component] /= length
        return point to tangent
    }

    /** Engine units → the rig's asset metres, `B⁻¹ · p / unitsPerMetre`. */
    private fun toAsset(engine: DoubleArray): DoubleArray {
        val out = FloatArray(3)
        X1Bind.toAssetInto(Vector3(engine[0].toFloat(), engine[1].toFloat(), engine[2].toFloat()), scale, out)
        return doubleArrayOf(out[0].toDouble(), out[1].toDouble(), out[2].toDouble())
    }

    /** A direction on the rig's axes, unscaled: how the exporter rotates a basis axis. */
    private fun toAssetDirection(engine: DoubleArray): DoubleArray {
        val direction = X1Bind.toAssetDirection(Vector3(engine[0].toFloat(), engine[1].toFloat(), engine[2].toFloat()))
        return doubleArrayOf(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
    }

    /** `[minX, minY, minZ, maxX, maxY, maxZ]` of the rig's skinned body, read from the GLB's accessors. */
    private fun bodyEnvelope(): DoubleArray {
        val buffer = ByteBuffer.wrap(File(RIG).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // magic
        buffer.int // version
        buffer.int // total length
        val jsonLength = buffer.int
        buffer.int // chunk type: JSON
        val json = ByteArray(jsonLength).also { buffer.get(it) }
        val root = JSONObject(String(json, Charsets.UTF_8))
        val accessors = root.getJSONArray("accessors")

        val min = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val max = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var skinnedPrimitives = 0
        val meshes = root.getJSONArray("meshes")
        for (mesh in 0 until meshes.length()) {
            val primitives = meshes.getJSONObject(mesh).getJSONArray("primitives")
            for (index in 0 until primitives.length()) {
                val attributes = primitives.getJSONObject(index).getJSONObject("attributes")
                if (!attributes.has("JOINTS_0")) continue
                val bounds = accessors.getJSONObject(attributes.getInt("POSITION"))
                for (component in 0..2) {
                    min[component] = minOf(min[component], bounds.getJSONArray("min").getDouble(component))
                    max[component] = maxOf(max[component], bounds.getJSONArray("max").getDouble(component))
                }
                skinnedPrimitives++
            }
        }
        assertTrue("the rig has no skinned primitive to bound", skinnedPrimitives > 0)
        return doubleArrayOf(min[0], min[1], min[2], max[0], max[1], max[2])
    }

    private fun distance(expected: DoubleArray, actual: JSONArray): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = expected[component] - actual.getDouble(component)
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun distance(frame: AnatomyFrame, slot: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = (frame.pointComponent(slot, to, component) - frame.pointComponent(slot, from, component)).toDouble()
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun magnitude(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun magnitude(v: List<Double>): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dot(a: List<Double>, b: List<Double>): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun determinant(m: List<List<Double>>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    private fun manifest(): JSONObject {
        val stream = javaClass.getResourceAsStream("/anatomy/helpers_v1.json")
            ?: error("the exported helper manifest is missing from test resources")
        return stream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
        const val RIG = "src/main/assets/characters/humanoid_v2.glb"
        const val TOLERANCE_METRES = 1e-3
        const val STATIONS = 4
    }
}
