package com.monkfitness.app.language

/**
 * The app's **one owner of language selection**.
 *
 * Settings, the composition root and the localization tests all ask this object what the language
 * state is; nothing else applies a locale, stores one, or keeps a language of its own. It exists
 * because the same decision used to live in four places at once — a DataStore key, a manual
 * `Locale.setDefault` in the activity, a `Configuration` rebuild in the notification receiver and a
 * row of buttons in Settings — and four copies of one decision is three too many.
 *
 * The object itself holds no state: the platform does ([AppLocaleStore]), which is what makes the
 * app's picker and Android's own per-app language screen the same setting rather than two.
 */
class AppLanguageManager(
    private val localeStore: AppLocaleStore,
    private val legacyStore: LegacyLanguageStore,
) {

    /**
     * What the user currently has selected.
     *
     * With no application locale applied this is [AppLanguage.SYSTEM_DEFAULT] — never a language
     * standing in for one that was never chosen. A locale the app does not ship (a system screen once
     * held a language that has since been withdrawn from the build) reads as [AppLanguage.SYSTEM_DEFAULT]
     * too, because that is what the app is actually doing in that case: resolving through Android's own
     * fallback.
     */
    fun selection(): AppLanguage =
        AppLanguage.fromTag(localeStore.readTags().firstOrNull()) ?: AppLanguage.SYSTEM_DEFAULT

    /**
     * Applies [language] as the app's own locale, or — for [AppLanguage.SYSTEM_DEFAULT] — clears the
     * override so the app follows the device again.
     *
     * This is the app's *only* way to change the language. It hands the decision to the platform, which
     * applies it, stores it, syncs it with the system's per-app language screen and recreates the
     * activity so every resource is re-read.
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
     * Existing users must not lose the language they picked when the app stopped keeping languages in
     * its own DataStore, and a user who never picked one must not be moved *into* a language they never
     * chose — which is what the old `language ?: "ru"` default did. So the rule is:
     *
     *  * the legacy value, **when it exists**, becomes the application locale and is then removed, so
     *    there is exactly one authoritative language state from that moment on;
     *  * when there is **no** legacy value, nothing is applied and nothing is written, so the app stays
     *    on the system language. This also makes the migration safe to run on every start: it can never
     *    overwrite a language the user has chosen through the published picker or through Android's own
     *    app-language screen, because after the first run there is no legacy value left to read.
     *
     * @return the language the hand-off applied, or `null` when there was nothing to migrate.
     */
    suspend fun migrateLegacySelection(): AppLanguage? {
        val legacyTag = legacyStore.readTag() ?: return null
        val migrated = AppLanguage.fromTag(legacyTag) ?: AppLanguage.SYSTEM_DEFAULT

        // The key is removed **first**, and the locale is applied second, because on Android 13+ applying a
        // locale restarts the process: clearing afterwards left the hand-off half-done on every start, which
        // meant the stored value stayed behind as a second source of truth — and re-applied itself over a
        // language the user had chosen since. Read, remove, then apply: if the restart happens mid-way, the
        // only thing that can be lost is a choice that has already been handed over.
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
