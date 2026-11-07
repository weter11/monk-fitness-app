package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R

/**
 * A language a market names its ingredients in.
 *
 * These are markets' languages, not the app's interface languages: the app ships no Dutch, French
 * or German UI, and never will just to label a shopping list.
 */
enum class NutritionLanguage(val code: String, @StringRes val labelRes: Int) {
    DUTCH("nl", R.string.nutrition_language_dutch),
    FRENCH("fr", R.string.nutrition_language_french),
    BULGARIAN("bg", R.string.nutrition_language_bulgarian),
    GERMAN("de", R.string.nutrition_language_german);

    companion object {
        /** The language [code] means, or `null` when it is not one a market here speaks. */
        fun ofCode(code: String?): NutritionLanguage? =
            entries.firstOrNull { it.code == primaryTag(code) }
    }
}

/** A country whose shops the shopping list is written for. */
enum class NutritionMarket(
    val countryCode: String,
    @StringRes val labelRes: Int,
    val languages: List<NutritionLanguage>
) {
    BELGIUM("BE", R.string.nutrition_market_belgium, listOf(NutritionLanguage.DUTCH, NutritionLanguage.FRENCH)),
    BULGARIA("BG", R.string.nutrition_market_bulgaria, listOf(NutritionLanguage.BULGARIAN)),
    GERMANY("DE", R.string.nutrition_market_germany, listOf(NutritionLanguage.GERMAN));

    /** The language of this market [code] names, or `null` when the market does not speak it. */
    fun language(code: String?): NutritionLanguage? =
        languages.firstOrNull { it.code == primaryTag(code) }

    companion object {
        /** The market [country] is, or `null` when the list does not cover it. */
        fun ofCountry(country: String?): NutritionMarket? {
            val wanted = country?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.countryCode == wanted }
        }
    }
}

/**
 * Which market's words the shopping list uses, and in which of that market's languages.
 *
 * Resolved rather than stored: the market falls back to the phone's region and the language to the
 * interface's own, so a fresh install is already right and a chosen market survives a region change.
 */
data class NutritionNaming(val market: NutritionMarket?, val language: NutritionLanguage?) {

    /** This selection's name for [ingredient], or `null` when it has none for the language. */
    fun nameOf(ingredient: NutritionIngredient): NutritionIngredientName? =
        language?.let { ingredient.names[it.code] }

    companion object {
        /**
         * [storedMarket] and [storedLanguage] are the user's choices, if any; [region] is the phone's
         * region and [interfaceLanguage] the language the app is showing.
         */
        fun resolve(
            storedMarket: String?,
            storedLanguage: String?,
            region: String?,
            interfaceLanguage: String?
        ): NutritionNaming {
            val market = NutritionMarket.ofCountry(storedMarket)
                ?: NutritionMarket.ofCountry(region)
                ?: return NutritionNaming(null, null)
            val language = market.language(storedLanguage)
                ?: market.language(interfaceLanguage)
                ?: market.languages.first()
            return NutritionNaming(market, language)
        }
    }
}

/** Only the language subtag matters: `nl-BE` and `nl` are the same language, and `fr` is not. */
private fun primaryTag(tag: String?): String =
    tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
