package com.monkfitness.app.character

import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val atlas = HelperBindSet.read(exported)

    @Test
    fun readsEveryHelperInStationOrderWithPaletteSlotsAfterTheRig() {
        val binds = atlas.partition(MuscleLayer.SUPERFICIAL)
        assertEquals(34, binds.skinJoints)
        assertEquals(4, binds.stationCount)
        assertEquals(208, binds.helperCount)
        assertEquals(242, binds.palette)
        assertEquals(0.3f, binds.stations[1], 0f)
        assertEquals(34, binds.paletteIndex(0))
        assertEquals(241, binds.paletteIndex(binds.helperCount - 1))

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
        assertEquals(-1, binds.groupOf(MuscleHeadId("absent", MuscleSide.A, 0)))
    }

    @Test
    fun theAtlasIsOnePartitionPerLayerAndEachFitsThePalette() {
        assertEquals(288, atlas.helperCount)
        assertEquals(listOf(MuscleLayer.SUPERFICIAL, MuscleLayer.DEEP), atlas.partitions.map { it.layer })
        val deep = atlas.partition(MuscleLayer.DEEP)
        assertEquals(80, deep.helperCount)
        assertEquals(114, deep.palette)
        for (partition in atlas.partitions) {
            assertTrue(
                "the ${partition.layer} palette ${partition.palette} must fit the renderable",
                partition.palette <= HelperBindManifest.PALETTE_LIMIT
            )
            assertTrue(
                "the atlas' helpers do not fit one skin, so it cannot be exported unpartitioned",
                atlas.helperCount + atlas.skinJoints > HelperBindManifest.PALETTE_LIMIT
            )
        }
        assertNull("a partitioned manifest has no unlabelled partition", atlas.unpartitioned)
    }

    @Test
    fun aSchemaOneManifestStillReadsAsOneUnlabelledPartition() {
        val legacy = HelperBindSet.read(synthetic(schema = 1))
        assertEquals(1, legacy.partitions.size)
        val binds = legacy.unpartitioned
        assertTrue("a schema-1 manifest reads as one partition", binds != null)
        assertEquals(2, binds?.helperCount)
        assertEquals(null, binds?.layer)
    }

    @Test
    fun theBindWorldAndItsInverseUndoEachOther() {
        for (binds in atlas.partitions) {
            val world = FloatArray(16)
            val inverse = FloatArray(16)
            for (helper in 0 until binds.helperCount) {
                binds.bindWorldInto(helper, world)
                binds.inverseBindInto(helper, inverse)
                val product = product(world, inverse)
                for (entry in 0 until 16) {
                    assertEquals("${binds.layer} helper $helper entry $entry", IDENTITY[entry], product[entry], 1e-6f)
                }
            }
        }
    }

    @Test
    fun aLayerTheManifestDoesNotCarryIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(schema = 1)).partition(MuscleLayer.DEEP)
        }
        assertTrue("${failure.message}", failure.message?.contains("DEEP") == true)
    }

    @Test
    fun aPaletteOverTheBindableLimitIsRefusedBeforeASkinSeesIt() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(skinJoints = 254))
        }
        assertTrue("${failure.message}", failure.message?.contains("255") == true)
    }

    @Test
    fun aPartitionOverTheLimitIsRefusedEvenWhenTheAtlasFitsAcrossLayers() {
        // Each layer is its own skin, so the budget is per partition: 111 superficial heads at two
        // stations each need 256 joints even though the deep partition beside them is tiny.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(partitioned(superficialHeads = 111, deepHeads = 1))
        }
        assertTrue("${failure.message}", failure.message?.contains("SUPERFICIAL") == true)
        assertTrue("${failure.message}", failure.message?.contains("255") == true)
    }

    @Test
    fun aHelperTaggedWithAnotherLayersPaletteIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(partitioned(superficialHeads = 1, deepHeads = 1, deepTag = "SUPERFICIAL"))
        }
        assertTrue("${failure.message}", failure.message?.contains("carries a helper tagged") == true)
    }

    @Test
    fun aMirroredBasisIsRefusedRatherThanInverted() {
        // A negated axis is a reflection: its inverse is not its transpose, so a skin would mirror the
        // muscle it drives instead of turning it.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(basis = "[[-1,0,0],[0,1,0],[0,0,1]]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("mirrored") == true)
    }

    @Test
    fun aStretchedBasisIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(basis = "[[1.5,0,0],[0,1,0],[0,0,1]]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("unit") == true)
    }

    @Test
    fun aNonFiniteNumberIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(position = "[1e400,0,0]"))
        }
        assertTrue("${failure.message}", failure.message?.contains("Infinity") == true)
    }

    @Test
    fun aStationOutsideItsGroupIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(station = 0.4))
        }
        assertTrue("${failure.message}", failure.message?.contains("station") == true)
    }

    @Test
    fun aManifestThatClaimsMoreHelpersThanItCarriesIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HelperBindSet.read(synthetic(declaredHelpers = 4))
        }
        assertTrue("${failure.message}", failure.message?.contains("carries") == true)
    }

    /** A schema-1 document: one unlabelled list of two helpers, one head, two stations. */
    private fun synthetic(
        schema: Int = 1,
        skinJoints: Int = 34,
        basis: String = "[[1,0,0],[0,1,0],[0,0,1]]",
        position: String = "[0,0,0]",
        station: Double = 0.0,
        declaredHelpers: Int = 2
    ): String = """
        {
          "schema": $schema,
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
            {"name": "m_A_0_0", "muscleId": "m", "side": "A", "headIndex": 0, "layer": "SUPERFICIAL",
             "station": $station, "position": $position, "basis": $basis},
            {"name": "m_A_0_1", "muscleId": "m", "side": "A", "headIndex": 0, "layer": "SUPERFICIAL",
             "station": 1.0, "position": [0, 1, 0], "basis": [[1,0,0],[0,1,0],[0,0,1]]}
          ]
        }
    """.trimIndent()

    /** A two-layer document: [superficialHeads] and [deepHeads] heads, two stations each. */
    private fun partitioned(
        superficialHeads: Int,
        deepHeads: Int,
        deepTag: String = "DEEP"
    ): String {
        val helpers = ArrayList<String>()
        fun addHeads(prefix: String, heads: Int, tag: String) {
            for (head in 0 until heads) {
                for (station in 0..1) {
                    helpers += """            {"name": "${prefix}_${head}_$station", "muscleId": "${prefix}_m", """ +
                        """"side": "A", "headIndex": $head, "layer": "$tag", "station": ${if (station == 0) "0.0" else "1.0"}, """ +
                        """"position": [0, ${station + head * 2}, 0], "basis": [[1,0,0],[0,1,0],[0,0,1]]}"""
                }
            }
        }
        addHeads("s", superficialHeads, "SUPERFICIAL")
        addHeads("d", deepHeads, deepTag)
        return """
            {
              "schema": 2,
              "anatomyId": "synthetic",
              "anatomyVersion": 1,
              "units": "metres",
              "stations": [0.0, 1.0],
              "skinJoints": 34,
              "helpersPerHead": 2,
              "helperCount": ${superficialHeads * 2 + deepHeads * 2},
              "paletteLimit": 255,
              "partitions": [
                {"layer": "SUPERFICIAL", "firstHelper": 0, "helperCount": ${superficialHeads * 2}, "palette": ${34 + superficialHeads * 2}},
                {"layer": "DEEP", "firstHelper": ${superficialHeads * 2}, "helperCount": ${deepHeads * 2}, "palette": ${34 + deepHeads * 2}}
              ],
              "helpers": [
${helpers.joinToString(",\n")}
              ]
            }
        """.trimIndent()
    }

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
