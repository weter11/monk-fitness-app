package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionNameSource
import com.monkfitness.app.data.model.NutritionNameTrust
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * A name is the word for a food, not a measurement of it, so it is checked against the vocabulary
 * concept it cites rather than against a composition row. The failure modes here are invisible in the
 * app: naming a concept after the row that merely stands in for it (cottage cheese is not fromage
 * blanc, mixed nuts are not cashew), calling a packet of six eggs "egg", claiming a review nobody
 * performed, or leaving the source's own capital and singular where a dictionary writes otherwise.
 */
class NutritionIngredientNamesTest {

    companion object {
        /** The markets this pilot names: Belgium's two languages, Bulgaria's and Germany's. */
        private val PILOT_LANGUAGES = listOf("nl", "fr", "bg", "de")

        /** What each vocabulary publishes, so a licence cannot be copied from the wrong one. */
        private val LICENCES = mapOf("Wikidata" to "CC0 1.0", "AGROVOC" to "CC BY 4.0")

        /**
         * Foods sold by the piece, as the shopper meets them: the plural, and — where a cited label
         * was inflected — the singular that label spells. `null` means the app wrote the plural
         * itself, so there is no label to point back at.
         */
        private val COUNTABLE = mapOf(
            "eggs" to mapOf(
                "nl" to ("eieren" to "ei"),
                "fr" to ("œufs" to "oeuf"),
                "bg" to ("яйца" to "Яйце"),
                "de" to ("Eier" to "Ei")
            ),
            "potatoes" to mapOf(
                "nl" to ("aardappelen" to "aardappel"),
                "fr" to ("pommes de terre" to "pomme de terre"),
                "bg" to ("картофи" to null),
                "de" to ("Kartoffeln" to "Kartoffel")
            ),
            "banana" to mapOf(
                "nl" to ("bananen" to "banaan"),
                "fr" to ("bananes" to "banane"),
                "bg" to ("банани" to "банан"),
                "de" to ("Bananen" to "Banane")
            ),
            "apple" to mapOf(
                "nl" to ("appels" to "appel"),
                "fr" to ("pommes" to "pomme"),
                "bg" to ("ябълки" to "ябълка"),
                "de" to ("Äpfel" to "Apfel")
            ),
            "nuts" to mapOf(
                "nl" to ("noten" to null),
                "fr" to ("fruits à coque" to "fruit à coque"),
                "bg" to ("ядки" to null),
                "de" to ("Nüsse" to null)
            )
        )

        /** Foods bought by weight or by volume: one word covers any amount of them. */
        private val MASS = listOf(
            "chicken", "rice", "oats", "buckwheat", "cottage_cheese", "yogurt", "tuna",
            "carrots", "tomatoes", "courgette", "cauliflower", "lentils", "chickpeas",
            "salmon", "cod", "pasta", "bread", "soy_milk", "tofu", "ham", "milk"
        )

        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()

        /** Every (item, language, term type) the committed Wikidata extract holds. */
        private val wikidataTerms: Map<Triple<String, String, String>, String> =
            NutritionCatalogueFixture.rows("wikidata/labels.csv").drop(1)
                .associate { row -> Triple(row[0], row[1], row[2]) to row[3] }

        /** Every (concept, language) the committed AGROVOC extract holds. */
        private val agrovocTerms: Map<Pair<String, String>, String> =
            NutritionCatalogueFixture.rows("agrovoc/pref_labels.csv").drop(1)
                .associate { row -> (row[0] to row[1]) to row[2] }

        private val usdaRows: Set<String> =
            NutritionCatalogueFixture.rows("usda_fdc/foods.csv").drop(1).map { it[0] }.toSet()
        private val ciqualRows: Set<String> =
            NutritionCatalogueFixture.rows("ciqual_2020/alim.csv").drop(1).map { it[0] }.toSet()

        /** Case and the œ ligature are spelling, not a different food. */
        fun fold(text: String): String = text.replace("œ", "oe").replace("Œ", "OE").lowercase()
    }

    private val catalogue = parseCatalogue(NutritionCatalogueFixture.asset("catalogue.json").readText())

    @Test
    fun everyIngredientIsNamedInEveryPilotLanguage() {
        assertEquals(26, catalogue.ingredients.size)
        catalogue.ingredients.forEach { ingredient ->
            assertTrue(
                "${ingredient.key}: names ${ingredient.names.keys} are not the pilot languages",
                ingredient.names.keys.containsAll(PILOT_LANGUAGES)
            )
            PILOT_LANGUAGES.forEach { language ->
                val name = ingredient.names.getValue(language)
                assertTrue("${ingredient.key}/$language: blank name", name.name.isNotBlank())
                assertTrue(
                    "${ingredient.key}/$language: a name must not arrive padded",
                    name.name == name.name.trim()
                )
            }
        }
    }

    @Test
    fun everyNameIsWrittenTheWayADictionaryWritesIt() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                if (language == "de") {
                    assertTrue(
                        "${ingredient.key}/de: a German noun is capitalised, \"${name.name}\" is not",
                        name.name.first().isUpperCase()
                    )
                } else {
                    assertEquals(
                        "${ingredient.key}/$language: a dictionary entry is lowercase",
                        name.name.lowercase(),
                        name.name
                    )
                }
            }
        }
    }

    @Test
    fun aCountableFoodIsNamedInThePluralAndSaysWhatItWasInflectedFrom() {
        COUNTABLE.forEach { (key, byLanguage) ->
            val ingredient = catalogue.ingredientsByKey.getValue(key)
            byLanguage.forEach { (language, expected) ->
                val (plural, singular) = expected
                val name = ingredient.names.getValue(language)

                assertEquals("$key/$language: the shelf says $plural", plural, name.name)
                assertEquals(
                    "$key/$language: inflected from $singular",
                    singular,
                    name.inflectedFrom
                )
            }
        }
    }

    @Test
    fun aFoodBoughtByWeightKeepsOneForm() {
        MASS.forEach { key ->
            val ingredient = catalogue.ingredientsByKey.getValue(key)
            PILOT_LANGUAGES.forEach { language ->
                assertNull(
                    "$key/$language: ${ingredient.names.getValue(language).name} is not countable, " +
                        "so nothing was inflected",
                    ingredient.names.getValue(language).inflectedFrom
                )
            }
        }
    }

    @Test
    fun anInflectedNameCitesTheLabelItWasInflectedFrom() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                val singular = name.inflectedFrom ?: return@forEach
                val source = name.source as? NutritionNameSource.Vocabulary
                    ?: throw AssertionError("${ingredient.key}/$language: ${name.name} cites no label to inflect")
                val cited = term(source, language)
                    ?: throw AssertionError("${ingredient.key}/$language: ${source.itemId} holds no term")
                assertEquals(
                    "${ingredient.key}/$language: \"${name.name}\" claims to inflect \"$singular\", " +
                        "which is not the label ${source.database} ${source.itemId} spells",
                    fold(cited),
                    fold(singular)
                )
            }
        }
    }

    @Test
    fun everySourcedNameCitesATermTheVocabularyActuallyHolds() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                val source = name.source as? NutritionNameSource.Vocabulary ?: return@forEach
                val cited = term(source, language)
                    ?: throw AssertionError("${ingredient.key}/$language: ${source.itemId} holds no such term")
                // A plural is checked through its singular; a plain name is the label itself.
                assertEquals(
                    "${ingredient.key}/$language: ${source.database} ${source.itemId} holds no such term",
                    fold(cited),
                    fold(name.inflectedFrom ?: name.name)
                )
            }
        }
    }

    @Test
    fun aNoteIsPlainTextWhenThereIsOne() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                val note = name.note ?: return@forEach
                assertTrue("${ingredient.key}/$language: blank note", note.isNotBlank())
                assertEquals("${ingredient.key}/$language: padded note", note.trim(), note)
            }
        }
        assertTrue(
            "Bulgarian retail does not sell cottage cheese, so its name has to say so",
            catalogue.ingredientsByKey.getValue("cottage_cheese").names.getValue("bg").note?.isNotBlank() == true
        )
    }

    @Test
    fun everySourcedNameCarriesTheLicenceItsDatabasePublishes() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                val source = name.source as? NutritionNameSource.Vocabulary ?: return@forEach
                val expected = LICENCES[source.database]
                    ?: throw AssertionError("${ingredient.key}/$language: ${source.database} is not a vocabulary this pilot cites")
                assertEquals("${ingredient.key}/$language: licence", expected, source.licence)
                assertTrue("${ingredient.key}/$language: no concept id", source.itemId.matches(Regex("[Qc][_0-9a-z]+")))
                assertTrue("${ingredient.key}/$language: no term type", source.term.isNotBlank())
            }
        }
    }

    @Test
    fun noNameIsTakenFromACompositionRow() {
        catalogue.ingredients.forEach { ingredient ->
            val compositionRows = listOfNotNull(
                ingredient.source?.itemId,
                ingredient.secondSource?.itemId,
                ingredient.pieceSource?.itemId
            ).toSet()
            ingredient.names.forEach { (language, name) ->
                val source = name.source as? NutritionNameSource.Vocabulary ?: return@forEach
                assertFalse(
                    "${ingredient.key}/$language: ${source.itemId} is a row this ingredient's numbers come from",
                    source.itemId in compositionRows
                )
                assertFalse(
                    "${ingredient.key}/$language: ${source.itemId} is a food-composition row, not a vocabulary concept",
                    source.itemId in usdaRows || source.itemId in ciqualRows
                )
            }
        }
    }

    @Test
    fun aStandInFoodsOwnNameIsNeverTheConceptsName() {
        // The two concepts the catalogue's numbers borrow from another food: CIQUAL's fresh cream
        // cheese stands in for cottage cheese, and grilled cashew for mixed nuts.
        val forbidden = mapOf(
            "cottage_cheese" to listOf("fromage blanc", "quark", "kwark", "speisequark"),
            "nuts" to listOf("cashew", "cajou", "kajú", "cashewnoot", "кашу")
        )

        forbidden.forEach { (key, words) ->
            val ingredient = catalogue.ingredientsByKey.getValue(key)
            ingredient.names.forEach { (language, name) ->
                words.forEach { word ->
                    assertFalse(
                        "$key/$language: \"${name.name}\" names the stand-in food, not the concept",
                        name.name.lowercase().contains(word)
                    )
                }
            }
        }
    }

    @Test
    fun nothingClaimsToHaveBeenReviewedByAHuman() {
        val claimed = catalogue.ingredients.flatMap { ingredient ->
            ingredient.names.filterValues { it.trust == NutritionNameTrust.VERIFIED }
                .keys.map { "${ingredient.key}/$it" }
        }
        assertTrue("no name in this pilot has been reviewed by a human: $claimed", claimed.isEmpty())
    }

    @Test
    fun anUnverifiedNameIsTheAppsOwnAndASourcedOneIsNot() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                when (name.trust) {
                    NutritionNameTrust.UNVERIFIED -> assertEquals(
                        "${ingredient.key}/$language: an unverified name must say the app wrote it",
                        NutritionNameSource.Agent,
                        name.source
                    )
                    else -> assertTrue(
                        "${ingredient.key}/$language: a ${name.trust.key} name must cite a vocabulary",
                        name.source is NutritionNameSource.Vocabulary
                    )
                }
            }
        }
    }

    private fun term(source: NutritionNameSource.Vocabulary, language: String): String? = when (source.database) {
        "Wikidata" -> wikidataTerms[Triple(source.itemId, language, source.term)]
        "AGROVOC" -> if (source.term == "prefLabel") agrovocTerms[source.itemId to language] else null
        else -> null
    }
}
