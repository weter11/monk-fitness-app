package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionNameSource
import com.monkfitness.app.data.model.NutritionNameTrust
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * A name is the word for a food, not a measurement of it, so it is checked against the vocabulary
 * concept it cites rather than against a composition row. Two failure modes matter and neither is
 * visible in the app: naming a concept after the row that merely stands in for it (cottage cheese is
 * not fromage blanc, mixed nuts are not cashew), and claiming a review nobody performed.
 */
class NutritionIngredientNamesTest {

    companion object {
        /** The markets this pilot names: Belgium's two languages, Bulgaria's and Germany's. */
        private val PILOT_LANGUAGES = listOf("nl", "fr", "bg", "de")

        /** What each vocabulary publishes, so a licence cannot be copied from the wrong one. */
        private val LICENCES = mapOf("Wikidata" to "CC0 1.0", "AGROVOC" to "CC BY 4.0")

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
    }

    private val catalogue = parseCatalogue(NutritionCatalogueFixture.asset("catalogue.json").readText())

    @Test
    fun everyIngredientIsNamedInEveryPilotLanguage() {
        assertEquals(12, catalogue.ingredients.size)
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
    fun everySourcedNameCitesATermTheVocabularyActuallyHolds() {
        catalogue.ingredients.forEach { ingredient ->
            ingredient.names.forEach { (language, name) ->
                val source = name.source as? NutritionNameSource.Vocabulary ?: return@forEach
                val cited = when (source.database) {
                    "Wikidata" -> wikidataTerms[Triple(source.itemId, language, source.term)]
                    "AGROVOC" -> if (source.term == "prefLabel") agrovocTerms[source.itemId to language] else null
                    else -> null
                }
                assertEquals(
                    "${ingredient.key}/$language: ${source.database} ${source.itemId} holds no such term",
                    name.name,
                    cited
                )
            }
        }
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
}
