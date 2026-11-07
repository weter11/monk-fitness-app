package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/** The reader has to tolerate fields a later schema adds, or the first market name breaks every build. */
class NutritionCatalogueSchemaTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()
    }

    private val asset = NutritionCatalogueFixture.asset("catalogue.json").readText()

    @Test
    fun theShippedAssetCarriesNoPerMarketNamesYet() {
        assertEquals(false, asset.contains("\"names\""))
    }

    @Test
    fun anOptionalFieldTheReaderDoesNotKnowIsIgnored() {
        val withNames = asset
            .replaceFirst("\"id\": \"chicken\",", "\"id\": \"chicken\",\n      \"names\": {\"fr\": \"Poulet\", \"it\": \"Pollo\"},")
            .replaceFirst("\"ingredients\": [", "\"markets\": {\"fr\": \"FR\", \"it\": \"IT\"},\n  \"ingredients\": [")

        val parsed = parseCatalogue(withNames)

        assertEquals(12, parsed.ingredients.size)
        assertEquals(30, parsed.templates.size)
        assertEquals("171534", parsed.ingredientsByKey.getValue("chicken").source!!.itemId)
        assertEquals(
            NutritionCatalogue.templatesById.getValue("breakfast_high_carb_1").calories,
            parsed.templatesById.getValue("breakfast_high_carb_1").calories
        )
    }
}
