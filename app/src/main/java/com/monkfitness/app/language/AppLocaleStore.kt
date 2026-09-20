package com.monkfitness.app.language

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The **application locale** as the platform stores it — the one place in this app that touches the
 * app-locale API, and the seam every language test is written against.
 *
 * It is deliberately this thin: the app does not own the storage, the platform (Android 13+) or
 * AndroidX (API 24–32) does, and nothing here may grow a second copy of it. Reading returns what the
 * platform currently has applied — which is also how a change made in the system's own per-app
 * language screen becomes visible to the app.
 */
interface AppLocaleStore {

    /**
     * The applied application locales, most preferred first.
     *
     * An **empty** list means no application-level override: the app follows the device's locale
     * list, exactly as it did before the user ever opened Settings → Language.
     */
    fun readTags(): List<String>

    /**
     * Applies [tags] as the application locales; an empty list clears the override and returns the
     * app to the system language.
     *
     * Called on the main thread: applying a locale recreates the running `Activity`, which is the
     * platform's own way of re-reading every resource — and the reason the language change needs no
     * recomposition trick of the app's own.
     */
    fun writeTags(tags: List<String>)
}

/**
 * The production [AppLocaleStore]: AndroidX `AppCompatDelegate`, which on Android 13+ *is* the
 * framework `LocaleManager` (so the app's picker and the system's per-app language screen write the
 * same state) and on API 24–32 is the platform's own backward-compatible implementation.
 *
 * The pre-13 storage is the one the `AppLocalesMetadataHolderService` entry in `AndroidManifest.xml`
 * opts into, so an app locale survives a restart on those versions too. It must never be replaced by
 * a store of this app's own.
 */
object AppCompatAppLocaleStore : AppLocaleStore {

    override fun readTags(): List<String> {
        val locales = AppCompatDelegate.getApplicationLocales()
        return (0 until locales.size()).mapNotNull { index -> locales.get(index)?.toLanguageTag() }
    }

    override fun writeTags(tags: List<String>) {
        AppCompatDelegate.setApplicationLocales(
            if (tags.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tags.joinToString(","))
            }
        )
    }
}
