package com.monkfitness.app.ui.nutrition

import com.monkfitness.app.data.model.NutritionIngredientName
import com.monkfitness.app.data.model.NutritionNameTrust

/**
 * The one label a shopping-list row carries: the market's word, then the word the app uses.
 *
 * An unverified word is nobody-reviewed, so a reader whose interface is not English is also given
 * the English one in brackets. Everything else — quantity, selection, calories — is not this.
 */
object NutritionIngredientLabel {

    fun of(
        localName: NutritionIngredientName?,
        interfaceName: String,
        englishName: String,
        interfaceLanguage: String?
    ): String {
        val entry = localName ?: return interfaceName
        val local = entry.name.trim()
        if (local.isEmpty() || sameWord(local, interfaceName)) return interfaceName

        val shown = local.replaceFirstChar { it.titlecase() }
        val english = englishName.trim().replaceFirstChar { it.lowercase() }
        val bracket = entry.trust == NutritionNameTrust.UNVERIFIED &&
            !isEnglish(interfaceLanguage) &&
            english.isNotEmpty() &&
            !sameWord(local, english)
        return if (bracket) "$shown ($english) · $interfaceName" else "$shown · $interfaceName"
    }

    /** `Öl` and `öl` are one word: a difference of case is never a reason to print it twice. */
    private fun sameWord(left: String, right: String): Boolean =
        left.trim().equals(right.trim(), ignoreCase = true)

    private fun isEnglish(tag: String?): Boolean =
        tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() == "en"
}
