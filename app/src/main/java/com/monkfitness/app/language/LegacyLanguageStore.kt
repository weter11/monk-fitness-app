package com.monkfitness.app.language

/**
 * The language every build before the localization stage stored for itself, read once so an existing user
 * keeps the language they picked.
 *
 * It is a **hand-off source, not a setting**: the migration in [AppLanguageManager.migrateLegacySelection]
 * reads it, applies it as the application locale and removes it; nothing writes here again.
 */
interface LegacyLanguageStore {

    /** The stored language tag, or `null` when this install never stored one. */
    suspend fun readTag(): String?

    /** Removes the stored value, so it can never be migrated — or consulted — twice. */
    suspend fun clear()
}
