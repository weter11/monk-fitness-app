package com.monkfitness.app.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The loader's contract is that it either produces the definition the file describes or refuses with
 * the key path — a parse that drops a field would leave a muscle with no path and no error.
 */
class AnatomyDefinitionLoaderTest {

    private val shipped: String = File(ASSET).readText()

    @Test
    fun theShippedAssetRoundTripsToTheSameDefinition() {
        val once = AnatomyDefinitionLoader.load(shipped)
        val twice = AnatomyDefinitionLoader.load(shipped)
        assertEquals("loading the same bytes twice must give the same data", once, twice)
        assertEquals("anatomy_v1", once.id)
        assertEquals(1, once.version)
        assertTrue("the numeric margin is positive", once.epsNumMetres > 0f)
    }

    @Test
    fun everyMuscleKeepsItsHeadsWaypointsAndRadiusKnots() {
        val definition = AnatomyDefinitionLoader.load(shipped)
        val biceps = definition.muscle("biceps_femoris")
        assertNotNull("the two-head muscle must survive the parse", biceps)
        assertEquals(2, biceps!!.heads.size)
        assertEquals(listOf("long head", "short head"), biceps.heads.map { it.label })
        assertEquals("the long head keeps its intermediate point", 1, biceps.heads[0].waypoints.size)
        assertEquals(0, biceps.heads[1].waypoints.size)
        assertTrue("no muscle may lose its taper", definition.muscles.all { it.radiusKnots.size >= 2 })
    }

    @Test
    fun anAbsentFrameKeyLoadsAsUnrecordedRatherThanAsVerified() {
        val minimal = minimalDefinition()
        val definition = AnatomyDefinitionLoader.load(minimal)
        val attachment = definition.muscles.single().heads.single().origin
        assertEquals(AttachmentFrame.UNRECORDED, attachment.frame)
    }

    @Test
    fun anUnknownEnumTokenIsRefusedAndTheTokenIsNamed() {
        val broken = minimalDefinition().replace("\"SUPERFICIAL\"", "\"deepish\"")
        assertRefused(broken, "deepish")
    }

    @Test
    fun aMissingRequiredKeyIsRefusedAndTheKeyIsNamed() {
        val broken = minimalDefinition().replace("\"widthMetres\": 0.05,", "")
        assertRefused(broken, "widthMetres")
    }

    @Test
    fun aRadiusKnotThatIsNotAPairIsRefused() {
        val broken = minimalDefinition().replace("[[0.0, 0.004], [1.0, 0.004]]", "[[0.0, 0.004], [1.0]]")
        assertRefused(broken, "radiusKnots[1]")
    }

    @Test
    fun textThatIsNotAJsonObjectIsRefused() {
        assertRefused("not json at all", "JSON object")
    }

    private fun assertRefused(json: String, named: String) {
        try {
            AnatomyDefinitionLoader.load(json)
            fail("expected the loader to refuse a definition containing \"$named\"")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "the message must name what was wrong (\"$named\"), was: ${error.message}",
                error.message.orEmpty().contains(named, ignoreCase = true)
            )
        }
    }

    private fun minimalDefinition(): String = """
        {
          "id": "anatomy_test",
          "version": 1,
          "epsNumMetres": 0.00001,
          "sampling": { "spacingMetres": 0.01, "minSamples": 8, "maxSamples": 48, "maxPointsPerHead": 96 },
          "wrapSurfaces": [
            { "id": "ball", "segment": "PELVIS", "centreMetres": [0.0, 0.0, 0.0], "radiiMetres": [0.05, 0.05, 0.05] }
          ],
          "landmarks": [],
          "muscles": [
            {
              "id": "test_muscle",
              "name": "Test muscle",
              "layer": "SUPERFICIAL",
              "heads": [
                {
                  "origin": { "segment": "PELVIS", "x": 0.01, "y": 0.0, "z": 0.01 },
                  "insertion": { "segment": "THORAX", "x": 0.01, "y": -0.10, "z": 0.01 }
                }
              ],
              "widthMetres": 0.05,
              "thicknessMetres": 0.02,
              "tendon": { "originFraction": 0.1, "insertionFraction": 0.1 },
              "wraps": [],
              "radiusKnots": [[0.0, 0.004], [1.0, 0.004]],
              "corridorMetres": 0.1
            }
          ]
        }
    """.trimIndent()

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
