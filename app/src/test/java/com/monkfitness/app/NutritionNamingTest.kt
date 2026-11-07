package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.NutritionLanguage
import com.monkfitness.app.data.model.NutritionMarket
import com.monkfitness.app.data.model.NutritionNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

/**
 * Where the market comes from, and which of its languages is used.
 *
 * The region only ever preselects: it is the phone's setting, not a decision, so a country outside
 * the list must leave the app with no market rather than with a guessed neighbour.
 */
class NutritionNamingTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()
    }

    private fun resolve(
        storedMarket: String? = null,
        storedLanguage: String? = null,
        region: String? = null,
        interfaceLanguage: String? = null
    ) = NutritionNaming.resolve(storedMarket, storedLanguage, region, interfaceLanguage)

    @Test
    fun aBelgianPhoneStartsOnDutchBecauseTheInterfaceIsNotOneOfItsLanguages() {
        val naming = resolve(region = "BE", interfaceLanguage = "en")

        assertEquals(NutritionMarket.BELGIUM, naming.market)
        assertEquals(NutritionLanguage.DUTCH, naming.language)
    }

    @Test
    fun aBelgianPhoneReadingFrenchStartsOnFrench() {
        assertEquals(NutritionLanguage.FRENCH, resolve(region = "BE", interfaceLanguage = "fr").language)
    }

    @Test
    fun aOneLanguageMarketNeedsNoChoice() {
        assertEquals(NutritionMarket.BULGARIA, resolve(region = "BG", interfaceLanguage = "ru").market)
        assertEquals(NutritionLanguage.BULGARIAN, resolve(region = "BG", interfaceLanguage = "ru").language)
        assertEquals(NutritionLanguage.GERMAN, resolve(region = "DE", interfaceLanguage = "en").language)
    }

    @Test
    fun aRegionOutsideTheListLeavesNoMarket() {
        val naming = resolve(region = "FR", interfaceLanguage = "fr")

        assertNull(naming.market)
        assertNull(naming.language)
    }

    @Test
    fun anUnknownRegionLeavesNoMarketRatherThanGuessingOne() {
        assertNull(resolve(region = null).market)
        assertNull(resolve(region = "").market)
        assertNull(resolve(region = "ZZ").market)
        assertNull(resolve(region = "BES").market)
    }

    @Test
    fun theRegionIsReadWhateverItsCase() {
        assertEquals(NutritionMarket.BELGIUM, resolve(region = "be").market)
        assertEquals(NutritionMarket.GERMANY, resolve(region = " De ").market)
    }

    @Test
    fun aChosenMarketBeatsTheRegion() {
        val naming = resolve(storedMarket = "DE", region = "BE", interfaceLanguage = "nl")

        assertEquals(NutritionMarket.GERMANY, naming.market)
        assertEquals(NutritionLanguage.GERMAN, naming.language)
    }

    @Test
    fun aChosenLanguageIsKeptWhenTheMarketSpeaksIt() {
        assertEquals(
            NutritionLanguage.FRENCH,
            resolve(storedMarket = "BE", storedLanguage = "fr", region = "BE", interfaceLanguage = "nl").language
        )
    }

    @Test
    fun aChosenLanguageTheMarketDoesNotSpeakIsReplaced() {
        // A Dutch choice carried over to Germany is not a language Germany speaks.
        assertEquals(
            NutritionLanguage.GERMAN,
            resolve(storedMarket = "DE", storedLanguage = "nl", region = "BE", interfaceLanguage = "en").language
        )
    }

    @Test
    fun aStoredMarketTheListNoLongerOffersFallsBackToTheRegion() {
        val naming = resolve(storedMarket = "XX", region = "BG")

        assertEquals(NutritionMarket.BULGARIA, naming.market)
    }

    @Test
    fun theSelectionReadsTheCatalogueNameForItsLanguage() {
        val naming = resolve(storedMarket = "BE", storedLanguage = "nl")
        val rice = NutritionCatalogue.ingredientsByKey.getValue("rice")

        assertEquals("rijst", naming.nameOf(rice)?.name)
        assertNull(NutritionNaming(null, null).nameOf(rice))
    }
}
