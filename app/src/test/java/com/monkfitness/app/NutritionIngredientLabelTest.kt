package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionIngredientName
import com.monkfitness.app.data.model.NutritionNameSource
import com.monkfitness.app.data.model.NutritionNameTrust
import com.monkfitness.app.ui.nutrition.NutritionIngredientLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One row, one rule: the market's word stands in front of the app's word, and a word nobody checked
 * carries the English one next to it unless English is what the reader is already reading.
 */
class NutritionIngredientLabelTest {

    private fun sourced(name: String) = NutritionIngredientName(
        name = name,
        trust = NutritionNameTrust.SOURCED,
        source = NutritionNameSource.Vocabulary("Wikidata", "Q1", "prefLabel", "CC0 1.0")
    )

    private fun unverified(name: String) = NutritionIngredientName(
        name = name,
        trust = NutritionNameTrust.UNVERIFIED,
        source = NutritionNameSource.Agent
    )

    @Test
    fun aSourcedLocalNameIsShownBesideTheInterfaceName() {
        assertEquals(
            "Rijst · Рис",
            NutritionIngredientLabel.of(sourced("rijst"), "Рис", "Rice", "ru")
        )
    }

    @Test
    fun anUnverifiedLocalNameGainsTheEnglishWordInBrackets() {
        assertEquals(
            "Boekweit (buckwheat) · Гречка",
            NutritionIngredientLabel.of(unverified("boekweit"), "Гречка", "Buckwheat", "ru")
        )
    }

    @Test
    fun anEnglishInterfaceGetsNoBracketsBecauseItIsAlreadyReadingEnglish() {
        assertEquals(
            "Hüttenkäse · Cottage cheese",
            NutritionIngredientLabel.of(unverified("hüttenkäse"), "Cottage cheese", "Cottage cheese", "en")
        )
        assertEquals(
            "Hüttenkäse · Cottage cheese",
            NutritionIngredientLabel.of(unverified("hüttenkäse"), "Cottage cheese", "Cottage cheese", "en-GB")
        )
    }

    @Test
    fun aNameThatIsTheInterfaceNameIsShownOnce() {
        assertEquals(
            "Картофи",
            NutritionIngredientLabel.of(unverified("картофи"), "Картофи", "Potatoes", "bg")
        )
        // A difference of case only — Wikidata's `ориз` against the interface's `Ориз` — is one word.
        assertEquals(
            "Ориз",
            NutritionIngredientLabel.of(sourced("ориз"), "Ориз", "Rice", "bg")
        )
    }

    @Test
    fun anIngredientWithoutANameForTheMarketShowsTheInterfaceNameOnly() {
        assertEquals(
            "Buckwheat",
            NutritionIngredientLabel.of(null, "Buckwheat", "Buckwheat", "ru")
        )
    }

    @Test
    fun anEmptyNameIsTreatedAsNoName() {
        assertEquals(
            "Buckwheat",
            NutritionIngredientLabel.of(unverified("   "), "Buckwheat", "Buckwheat", "ru")
        )
    }

    @Test
    fun theBracketIsDroppedWhenItWouldRepeatTheLocalName() {
        // French retail prints the English loan for cottage cheese, so the bracket has nothing to add.
        assertEquals(
            "Cottage cheese · Творог",
            NutritionIngredientLabel.of(unverified("cottage cheese"), "Творог", "Cottage cheese", "ru")
        )
    }

    @Test
    fun aLowercaseSourceNameIsCapitalisedForTheRow() {
        assertEquals(
            "Œuf · Яйца",
            NutritionIngredientLabel.of(sourced("œuf"), "Яйца", "Eggs", "ru")
        )
        assertEquals(
            "Риба тон (tuna) · Тунец",
            NutritionIngredientLabel.of(unverified("риба тон"), "Тунец", "Tuna", "ru")
        )
    }

    @Test
    fun theEnglishWordIsLowercasedBecauseItIsAnAside() {
        assertEquals(
            "Noten (nuts) · Орехи",
            NutritionIngredientLabel.of(unverified("noten"), "Орехи", "Nuts", "ru")
        )
    }

    @Test
    fun aSourcedNameIsNotBracketedEvenWhenItDiffersFromEnglish() {
        assertEquals(
            "Hähnchenbrust · Куриная грудка",
            NutritionIngredientLabel.of(sourced("Hähnchenbrust"), "Куриная грудка", "Chicken breast", "ru")
        )
    }
}
