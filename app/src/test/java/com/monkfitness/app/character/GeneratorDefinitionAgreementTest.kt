package com.monkfitness.app.character

import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.SkeletonStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The generator's declared proportions and the engine's are one set of numbers. The asset is derived
 * from `DEFINITION`, so a drift between the two would move every joint A1 measures while the rig
 * still looked internally consistent.
 */
class GeneratorDefinitionAgreementTest {

    private val script = File("../scripts/character/generate_humanoid_v2.py")

    private fun declared(): JSONObject {
        assertTrue("generator missing: ${script.absolutePath}", script.isFile)
        val text = script.readText()
        val start = text.indexOf("DEFINITION = {")
        assertTrue("generator declares no DEFINITION block", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        assertTrue("DEFINITION block is not closed", end > open)
        return JSONObject(text.substring(open, end + 1))
    }

    private fun expected(): Map<String, Float> {
        val def = HumanSkeletonDefinition()
        return mapOf(
            "torsoLength" to def.torsoLength,
            "neckLength" to def.neckLength,
            "thighLength" to def.thighLength,
            "shinLength" to def.shinLength,
            "footLength" to def.footLength,
            "upperArmLength" to def.upperArmLength,
            "forearmLength" to def.forearmLength,
            "shoulderWidth" to def.shoulderWidth,
            "hipWidth" to def.hipWidth,
            "palmLength" to def.hand.palmLength,
            "fingerLength" to def.hand.fingerLength,
            "handWidth" to def.hand.handWidth,
            "heelRatio" to def.foot.heelRatio,
            "toeRatio" to def.foot.toeRatio,
            "pelvisLift" to MonkJointResolver.restPelvisHeight(def) - def.shinLength - def.thighLength,
            "pelvisHeightMetres" to X1Bind.pelvisHeightMetres,
            "headRadius" to SkeletonStyle.DEFAULT.headRadius
        )
    }

    @Test
    fun everyDeclaredProportionMatchesTheEngineDefinition() {
        val declared = declared()
        val expected = expected()
        assertEquals(
            "the generator declares and the definition carries the same fields",
            expected.keys,
            declared.keys().asSequence().toSet()
        )
        for ((key, value) in expected) {
            val got = declared.getDouble(key).toFloat()
            assertTrue("$key: generator $got, HumanSkeletonDefinition $value", abs(got - value) < 1e-4f)
        }
    }

    @Test
    fun theGeneratorDerivesTheSameScaleAsX1() {
        val declared = declared()
        val pelvisY = declared.getDouble("shinLength") + declared.getDouble("thighLength") +
            declared.getDouble("pelvisLift")
        val scale = pelvisY / declared.getDouble("pelvisHeightMetres")
        assertEquals(
            "generator scale must equal X1Bind.unitsPerMetre",
            X1Bind.unitsPerMetre(HumanSkeletonDefinition()).toDouble(),
            scale,
            1e-4
        )
    }

    @Test
    fun theGeneratorDoesNotTranscribeTheBindCoordinatesItClaimsToDerive() {
        val text = script.readText()
        // The X1 positions are arithmetic on DEFINITION; a literal engine coordinate in the file
        // means someone pasted the table back in.
        for (literal in listOf("0.185957", "355.0", "235.0,", "-10.15", "24.85")) {
            assertTrue("generator transcribes the X1 coordinate $literal", !text.contains(literal))
        }
    }
}
