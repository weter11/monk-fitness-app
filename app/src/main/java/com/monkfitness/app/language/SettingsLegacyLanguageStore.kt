package com.monkfitness.app.language

import com.monkfitness.app.data.local.SettingsManager

/**
 * [LegacyLanguageStore] over the DataStore key that every build before the localization stage wrote.
 *
 * The key is read through [SettingsManager] because that object owns the settings file, but it is not
 * a setting any more: nothing in the app writes it, and this store exists to empty it once.
 */
class SettingsLegacyLanguageStore(private val settingsManager: SettingsManager) : LegacyLanguageStore {

    override suspend fun readTag(): String? = settingsManager.readLegacyLanguage()

    override suspend fun clear() = settingsManager.clearLegacyLanguage()
}
