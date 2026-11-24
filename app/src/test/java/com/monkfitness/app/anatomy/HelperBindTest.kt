package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * The exported helper binds are a claim about the solver's rest path: four per head at 0/30/70/100 %
 * of arc, exactly on the polyline. The manifest is checked against a fresh rest solve, so a solver
 * change that moves a rest path fails here instead of shipping a layer bound to the old one.
 */
class HelperBindTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)

    @Test
    fun everyHelperBindsOnTheSolverRestPathAtItsStation() {
        val frame = restFrame()
        val helpers = manifest().getJSONArray("helpers")
        assertEquals("four helpers per head-side instance", definition.headCount * 4, helpers.length())

        var worst = 0.0
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val slot = slotOf(frame, helper)
            val station = helper.getDouble("station")
            val expected = pointAt(frame, slot, station)
            val position = helper.getJSONArray("position")
            val dx = expected[0] - position.getDouble(0)
            val dy = expected[1] - position.getDouble(1)
            val dz = expected[2] - position.getDouble(2)
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            worst = maxOf(worst, distance)
            assertTrue(
                "${helper.getString("name")} binds $distance engine units off the rest path at $station",
                distance <= TOLERANCE
            )
        }
        println("worst helper bind residual = $worst engine units")
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
    fun thePaletteStaysInsideTheRenderableLimit() {
        val manifest = manifest()
        val skin = manifest.getInt("skinJoints")
        val helpers = manifest.getInt("helperCount")
        assertEquals("one helper per head and station", definition.headCount * 4, helpers)
        assertEquals("the palette is the rig's joints plus the helpers", skin + helpers, manifest.getInt("palette"))
        assertTrue("palette ${manifest.getInt("palette")} over 255", manifest.getInt("palette") <= 255)
    }

    private fun restFrame(): AnatomyFrame {
        val solver = AnatomySolver(definition, skeleton, scale, restPose)
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

    /** The point at [station] of [slot]'s polyline, by arc length. */
    private fun pointAt(frame: AnatomyFrame, slot: Int, station: Double): DoubleArray {
        val count = frame.pointCounts[slot]
        val cumulative = DoubleArray(count)
        for (index in 1 until count) cumulative[index] = cumulative[index - 1] + distance(frame, slot, index - 1, index)
        val target = cumulative[count - 1] * station
        var segment = 0
        while (segment < count - 2 && cumulative[segment + 1] < target) segment++
        val span = cumulative[segment + 1] - cumulative[segment]
        val u = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0
        return DoubleArray(3) { component ->
            val from = frame.pointComponent(slot, segment, component).toDouble()
            val to = frame.pointComponent(slot, segment + 1, component).toDouble()
            from + (to - from) * u
        }
    }

    private fun distance(frame: AnatomyFrame, slot: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = (frame.pointComponent(slot, to, component) - frame.pointComponent(slot, from, component)).toDouble()
            sum += delta * delta
        }
        return sqrt(sum)
    }

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
        const val TOLERANCE = 1e-3
    }
}
