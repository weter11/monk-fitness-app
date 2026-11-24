package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest is read by the exporter's consumer and by the skinner, so the reader is the one place a
 * palette, a station or a mirrored basis is refused — before it can reach a skin and deform a muscle
 * silently.
 */
class HelperBindsTest {

    private val exported = File(MANIFEST).readText()

    @Test
    fun readsEveryHelperInStationOrderWithPaletteSlotsAfterTheRig() {
        val binds = HelperBinds.read(exported)
        assertEquals(34, binds.skinJoints)
        assertEquals(4, binds.stationCount)
        assertEquals(72, binds.helperCount)
        assertEquals(106, binds.palette)
        assertEquals(0.3f, binds.stations[1], 0f)
        assertEquals(34, binds.paletteIndex(0))
        assertEquals(105, binds.paletteIndex(binds.helperCount - 1))

        // A group is `index / stationCount`, and every helper of a group names the same head.
        val group = binds.groupOf(binds.binds[0].head)
        assertEquals(0, group)
        for (station in 0 until binds.stationCount) {
            assertEquals(
                "helper ${binds.helperIndexOf(group, station)} is not station $station of its group",
                binds.binds[0].head,
                binds.binds[binds.helperIndexOf(group, station)].head
            )
        }
        assertEquals(-1, binds.groupOf(com.monkfitness.app.anatomy.MuscleHeadId("absent", com.monkfitness.app.anatomy.MuscleSide.A, 0)))
    }

    @Test
    fun theBindWorldAndItsInverseUndoEachOther() {
        val binds = HelperBinds.read(exported)
        val world = FloatArray(16)
        val inverse = FloatArray(16)
        for (helper in 0 until binds.helperCount) {
            binds.bindWorldInto(helper, world)
            binds.inverseBindInto(helper, inverse)
            val product = product(world, inverse)
            for (entry in 0 until 16) {
                assertEquals("helper $helper entry $entry", IDENTITY[entry], product[entry], 1e-6f)
            }
        }
    }

    @Test
    fun aPaletteOverTheBindableLimitIsRefusedBeforeASkinSeesIt() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(skinJoints = 254))
        }
        assertTrue("${failure.message}", failure.message?.contains("255") == true)
    }

    @Test
    fun aMirroredBasisIsRefusedRatherThanInverted() {
        // A negated axis is a reflection: its inverse is not its transpose, so a skin would mirror the
        // muscle it drives instead of turning it.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(basis = "[[-1,0,0],[0,1,0],[0,0,1]]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("mirrored") == true)
    }

    @Test
    fun aStretchedBasisIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(basis = "[[1.5,0,0],[0,1,0],[0,0,1]]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("unit") == true)
    }

    @Test
    fun aNonFiniteNumberIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(position = "[1e400,0,0]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("Infinity") == true)
    }

    @Test
    fun aStationOutsideItsGroupIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(station = 0.4))
        }
        assertTrue("${failure.message}", failure.message?.contains("station") == true)
    }

    @Test
    fun aManifestThatClaimsMoreHelpersThanItCarriesIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBinds.read(synthetic(declaredHelpers = 4))
        }
        assertTrue("${failure.message}", failure.message?.contains("carries") == true)
    }

    private fun synthetic(
        skinJoints: Int = 34,
        basis: String = "[[1,0,0],[0,1,0],[0,0,1]]",
        position: String = "[0,0,0]",
        station: Double = 0.0,
        declaredHelpers: Int = 2
    ): String = """
        {
          "schema": 1,
          "anatomyId": "synthetic",
          "anatomyVersion": 1,
          "units": "metres",
          "stations": [0.0, 1.0],
          "skinJoints": $skinJoints,
          "helpersPerHead": 2,
          "helperCount": $declaredHelpers,
          "palette": ${skinJoints + 2},
          "paletteLimit": 255,
          "helpers": [
            {"name": "m_A_0_0", "muscleId": "m", "side": "A", "headIndex": 0, "station": $station,
             "position": $position, "basis": $basis},
            {"name": "m_A_0_1", "muscleId": "m", "side": "A", "headIndex": 0, "station": 1.0,
             "position": [0, 1, 0], "basis": [[1,0,0],[0,1,0],[0,0,1]]}
          ]
        }
    """.trimIndent()

    private fun product(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                out[column * 4 + row] = sum
            }
        }
        return out
    }

    private companion object {
        const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"

        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
