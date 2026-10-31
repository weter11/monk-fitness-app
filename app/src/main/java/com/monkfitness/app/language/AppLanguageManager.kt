package com.monkfitness.app.language

/**
 * The app's **one owner of language selection**: nothing else applies a locale, stores one, or keeps a
 * language of its own.
 *
 * The object itself holds no state — the platform does ([AppLocaleStore]) — which is what makes the
 * app's picker and Android's own per-app language screen the same setting rather than two.
 */
class AppLanguageManager(
    private val localeStore: AppLocaleStore,
    private val legacyStore: LegacyLanguageStore,
) {

    /**
     * What the user currently has selected.
     *
     * With no application locale applied this is [AppLanguage.SYSTEM_DEFAULT] — never a language standing
     * in for one that was never chosen. A locale the app no longer ships reads the same way, because that
     * is what the app is actually doing then: resolving through Android's own fallback.
     */
    fun selection(): AppLanguage =
        AppLanguage.fromTag(localeStore.readTags().firstOrNull()) ?: AppLanguage.SYSTEM_DEFAULT

    /**
     * Applies [language] as the app's own locale, or — for [AppLanguage.SYSTEM_DEFAULT] — clears the
     * override so the app follows the device again.
     *
     * The app's *only* way to change the language: the platform applies and stores it, syncs it with the
     * system's per-app language screen and recreates the activity so every resource is re-read.
     */
    fun select(language: AppLanguage) {
        localeStore.writeTags(
            if (language.isSystemDefault) emptyList() else listOfNotNull(language.tag)
        )
    }

    /** Settings → Language → *System language*: the same operation, named for what it means. */
    fun resetToSystemDefault() = select(AppLanguage.SYSTEM_DEFAULT)

    /**
     * The one-time hand-off of the language a pre-localization install stored for itself.
     *
     * A legacy value, **when it exists**, becomes the application locale and is then removed, so exactly
     * one authoritative language state remains from that moment on. When there is **no** legacy value,
     * nothing is applied and nothing is written, so the app stays on the system language. That also makes
     * the migration safe to run on every start: after the first run no legacy value is left to read, so it
     * can never overwrite a language chosen through the picker or Android's own app-language screen.
     *
     * @return the language the hand-off applied, or `null` when there was nothing to migrate.
     */
    suspend fun migrateLegacySelection(): AppLanguage? {
        val legacyTag = legacyStore.readTag() ?: return null
        val migrated = AppLanguage.fromTag(legacyTag) ?: AppLanguage.SYSTEM_DEFAULT

        // Clear **first**, apply second: on Android 13+ applying a locale restarts the process, and
        // clearing afterwards left the hand-off half-done on every start — the stored value survived as a
        // second source of truth and re-applied itself over a language chosen since. This way a mid-way
        // restart can only lose a choice that has already been handed over.
        legacyStore.clear()
        select(migrated)
        return migrated
    }

    companion object {

        /** The manager the app runs on, built over Android's own app-locale storage. */
        fun create(legacyStore: LegacyLanguageStore): AppLanguageManager =
            AppLanguageManager(AppCompatAppLocaleStore, legacyStore)
    }
}
