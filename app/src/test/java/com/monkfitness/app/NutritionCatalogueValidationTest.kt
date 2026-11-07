package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.SUPPORTED_SCHEMA_VERSION
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * The catalogue is a data file, so its truth has to be asserted against the exports it cites rather
 * than against itself: every number is looked up by the row id the asset names, and every template is
 * recomputed from those rows.
 */
class NutritionCatalogueValidationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()

        private val usdaFoods: Map<String, String> = NutritionCatalogueFixture.rows("usda_fdc/foods.csv")
            .drop(1).map { fields -> fields[0] to fields[1] }.toMap()
        private val usdaNutrients: Map<String, Map<String, Double>> =
            NutritionCatalogueFixture.rows("usda_fdc/food_nutrients.csv").drop(1)
                .groupBy { it[0] }
                .mapValues { entry -> entry.value.associateBy({ it[1] }, { it[2].toDouble() }) }
        private val usdaPortions: Map<String, Pair<String, Int>> =
            NutritionCatalogueFixture.rows("usda_fdc/food_portions.csv").drop(1)
                .associate { it[0] to (it[1] to it[5].toInt()) }
        private val ciqualNames: Map<String, String> = NutritionCatalogueFixture.rows("ciqual_2020/alim.csv")
            .drop(1).map { fields -> fields[0] to fields[1] }.toMap()
        private val ciqualValues: Map<String, Map<String, Double>> =
            NutritionCatalogueFixture.rows("ciqual_2020/compo.csv").drop(1).filter { it[1] == "328" }
                .groupBy { it[0] }
                .mapValues { entry -> entry.value.associateBy({ it[1] }, { it[2].replace(',', '.').toDouble() }) }
        private val legacyTotals: Map<String, Pair<Int, Int>> =
            NutritionCatalogueFixture.rows("legacy_template_totals.csv").drop(1)
                .associate { it[0] to (it[1].toInt() to it[2].toInt()) }
    }

    private val catalogue = parseCatalogue(NutritionCatalogueFixture.asset("catalogue.json").readText())

    @Test
    fun theAssetDeclaresASupportedSchema() {
        assertEquals(SUPPORTED_SCHEMA_VERSION, catalogue.schemaVersion)
        assertEquals(catalogue.ingredients.size, catalogue.ingredients.map { it.key }.toSet().size)
        assertEquals(catalogue.templates.size, catalogue.templates.map { it.id }.toSet().size)
    }

    @Test
    fun everyIngredientIsCitableToTheExportItNames() {
        catalogue.ingredients.forEach { ingredient ->
            assertNotNull("${ingredient.key}: no source", ingredient.source)
            val source = ingredient.source!!
            assertEquals("USDA FDC", source.source)
            assertTrue("${ingredient.key}: ${source.itemId} is not in the committed USDA export", source.itemId in usdaFoods)
            assertTrue("${ingredient.key}: no retrieval date", source.retrievedOn.isNotBlank())
            assertTrue("${ingredient.key}: basis ${ingredient.basis}", ingredient.basis in setOf("raw", "cooked"))

            val expected = usdaNutrients.getValue(source.itemId)
            assertNotNull("${ingredient.key}: no nutrients", ingredient.nutrientsPer100g)
            val nutrients = ingredient.nutrientsPer100g!!
            assertEquals("${ingredient.key}: kcal", expected.getValue("1008"), nutrients.kcal, 1e-9)
            assertEquals("${ingredient.key}: protein", expected.getValue("1003"), nutrients.proteinGrams, 1e-9)
            assertEquals("${ingredient.key}: fat", expected.getValue("1004"), nutrients.fatGrams, 1e-9)
            assertEquals("${ingredient.key}: carbohydrate", expected.getValue("1005"), nutrients.carbohydrateGrams, 1e-9)
        }
    }

    @Test
    fun everyPieceIsWeighedByACommittedUsdaPortionRow() {
        catalogue.ingredients.filter { it.unit == NutritionQuantityUnit.PIECES }.forEach { ingredient ->
            assertNotNull("${ingredient.key}: no piece weight", ingredient.gramsPerPiece)
            assertNotNull("${ingredient.key}: no piece source", ingredient.pieceSource)
            val grams = ingredient.gramsPerPiece!!
            val portion = ingredient.pieceSource!!
            assertTrue(
                "${ingredient.key}: portion ${portion.itemId} is not in the committed export",
                portion.itemId in usdaPortions
            )
            val row = usdaPortions.getValue(portion.itemId)
            assertEquals("${ingredient.key}: portion belongs to another food", ingredient.source!!.itemId, row.first)
            assertEquals("${ingredient.key}: piece weight", grams, row.second)
        }
    }

    @Test
    fun everySecondSourceResolvesAndTheGapIsStated() {
        val report = StringBuilder("ingredient,usda_kcal,ciqual_kcal,gap_percent\n")
        catalogue.ingredients.forEach { ingredient ->
            assertNotNull("${ingredient.key}: no second source", ingredient.secondSource)
            val second = ingredient.secondSource!!
            assertEquals("CIQUAL 2020", second.source)
            assertTrue("${ingredient.key}: ${second.itemId} is not in the committed CIQUAL export", second.itemId in ciqualNames)
            assertEquals(
                "${ingredient.key}: second-source kcal does not match the export row",
                ciqualValues.getValue(second.itemId).getValue("328"),
                second.kcalPer100g,
                1e-9
            )

            val primary = ingredient.nutrientsPer100g!!.kcal
            val gap = abs(primary - second.kcalPer100g) / primary * 100.0
            report.append("${ingredient.key},$primary,${second.kcalPer100g},${"%.1f".format(gap)}\n")
            if (gap > 10.0) {
                assertTrue(
                    "${ingredient.key}: the two sources differ by ${"%.1f".format(gap)}% and the record states no reason",
                    !second.note.isNullOrBlank()
                )
            }
        }
        println(report)
    }

    @Test
    fun everyTemplateIngredientResolvesAndItsTotalIsDerivedFromNutrients() {
        val report = StringBuilder("template,legacy_kcal,computed_kcal,difference_percent,legacy_protein_g,computed_protein_g\n")
        catalogue.templates.forEach { template ->
            assertTrue("${template.id}: no ingredients", template.ingredients.isNotEmpty())

            val kcal = template.ingredients.sumOf { amount ->
                val ingredient: NutritionIngredient = amount.ingredient
                val grams = if (ingredient.unit == NutritionQuantityUnit.PIECES) {
                    amount.amount * ingredient.gramsPerPiece!!
                } else {
                    amount.amount
                }
                grams * ingredient.nutrientsPer100g!!.kcal / 100.0
            }
            val protein = template.ingredients.sumOf { amount ->
                val ingredient = amount.ingredient
                val grams = if (ingredient.unit == NutritionQuantityUnit.PIECES) {
                    amount.amount * ingredient.gramsPerPiece!!
                } else {
                    amount.amount
                }
                grams * ingredient.nutrientsPer100g!!.proteinGrams / 100.0
            }

            assertTrue("${template.id}: a total must come from positive nutrients", kcal > 0.0)
            val runtime = NutritionCatalogue.templatesById.getValue(template.id)
            assertEquals("${template.id}: runtime kcal is not the ingredient total", kcal, runtime.calories.toDouble(), 0.5)
            assertEquals("${template.id}: runtime protein is not the ingredient total", protein, runtime.proteinGrams.toDouble(), 0.5)
            assertTrue(
                "${template.id}: total disagrees with its ingredients by more than 2%",
                abs(kcal - runtime.calories) <= kcal * 0.02
            )

            val legacy = legacyTotals.getValue(template.id)
            val difference = (kcal - legacy.first) / legacy.first * 100.0
            report.append(
                "${template.id},${legacy.first},${"%.1f".format(kcal)},${"%+.1f".format(difference)}," +
                    "${legacy.second},${"%.1f".format(protein)}\n"
            )
        }
        println(report)
    }
}
