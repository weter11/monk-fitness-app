package com.monkfitness.app.language

import androidx.annotation.StringRes
import com.monkfitness.app.R

/**
 * What the user chose in Settings → Language.
 *
 * [SYSTEM_DEFAULT] is not a language — it is the *absence* of an application-level locale, so its tag
 * is `null` and Android resolves resources against the device's own locale list (falling back to
 * `values/` for a device language this app does not ship). Every other entry carries the real Android
 * language tag that identifies it.
 *
 * The tag is the identifier; [labelRes] is copy, translated per language and never used to look a
 * language up. The tags of this enum are the app's one list of supported languages —
 * `res/xml/locale_config.xml`, the picker and the localization tests all derive from it.
 *
 * Declaration order **is** picker order: System default first, then the languages.
 */
enum class AppLanguage(val tag: String?, @StringRes val labelRes: Int) {
    SYSTEM_DEFAULT(null, R.string.language_system_default),
    ENGLISH("en", R.string.language_english),
    RUSSIAN("ru", R.string.language_russian),
    UKRAINIAN("uk", R.string.language_ukrainian),
    BULGARIAN("bg", R.string.language_bulgarian),
    PORTUGUESE("pt", R.string.language_portuguese),
    SPANISH("es", R.string.language_spanish),
    ITALIAN("it", R.string.language_italian);

    /**
     * Whether this entry means "the device decides", rather than naming a language. Such a selection
     * must never be written to the platform as a locale, and must stay out of `locale_config.xml`.
     */
    val isSystemDefault: Boolean get() = tag == null

    companion object {
        /** Every language the app ships resources for, in picker order. [SYSTEM_DEFAULT] is not one. */
        val supported: List<AppLanguage> = entries.filterNot { it.isSystemDefault }

        /** The Android language tags of [supported], in picker order. */
        val supportedTags: List<String> = supported.map { it.tag.orEmpty() }

        /**
         * The entry a language tag means, or `null` when the app does not ship that language.
         *
         * Only the primary subtag is read, so region and script are irrelevant: `pt-BR`, `es-419` and
         * `bg_BG` are languages this app has, while `fr` is not one of them.
         */
        fun fromTag(tag: String?): AppLanguage? {
            val primary = tag
                ?.trim()
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase()
                .orEmpty()
            if (primary.isEmpty()) return null
            return supported.firstOrNull { it.tag == primary }
        }
    }
}
