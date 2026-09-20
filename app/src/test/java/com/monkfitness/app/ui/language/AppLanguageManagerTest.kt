package com.monkfitness.app.ui.language

import com.monkfitness.app.language.AppLanguage
import com.monkfitness.app.language.AppLanguageManager
import com.monkfitness.app.language.AppLanguageSettings
import com.monkfitness.app.language.AppLocaleStore
import com.monkfitness.app.language.LegacyLanguageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language policy, exercised without Android.
 *
 * The manager is the app's one owner of language selection, so its behaviour is stated here rather than
 * inferred from a running screen: with no stored choice the app follows the system, an explicit choice is
 * exactly that locale, and the selection a pre-localization install stored is handed to the platform once
 * — and only while it is still there to hand over.
 *
 * [RecordingLocaleStore] and [FakeLegacyLanguageStore] stand in for the platform's storage. They are the
 * reason the manager takes its two stores as collaborators: the rules are about *when* a locale is written
 * and *what* is written, and both are observable without a device.
 */
class AppLanguageManagerTest {

    @Test
    fun withNoStoredChoiceTheAppFollowsTheSystem() {
        val store = RecordingLocaleStore()
        val manager = AppLanguageManager(store, FakeLegacyLanguageStore())

        assertEquals(AppLanguage.SYSTEM_DEFAULT, manager.selection())
        assertTrue("reading a selection must not write one", store.writes.isEmpty())
    }

    @Test
    fun anExplicitChoiceIsAppliedAsTheApplicationLocale() {
        val store = RecordingLocaleStore()
        val manager = AppLanguageManager(store, FakeLegacyLanguageStore())

        manager.select(AppLanguage.SPANISH)

        assertEquals(listOf(listOf("es")), store.writes)
        assertEquals(AppLanguage.SPANISH, manager.selection())
    }

    @Test
    fun selectingTheSystemDefaultClearsTheApplicationLocale() {
        val store = RecordingLocaleStore(applied = listOf("it"))
        val manager = AppLanguageManager(store, FakeLegacyLanguageStore())

        assertEquals(AppLanguage.ITALIAN, manager.selection())

        manager.select(AppLanguage.SYSTEM_DEFAULT)

        assertEquals(
            "the mode is the *absence* of an application locale, so it is written as an empty list",
            listOf(listOf<String>()),
            store.writes
        )
        assertEquals(AppLanguage.SYSTEM_DEFAULT, manager.selection())

        manager.resetToSystemDefault()
        assertEquals(
            "resetting means the same thing as choosing the mode",
            listOf(listOf<String>(), listOf<String>()),
            store.writes
        )
    }

    @Test
    fun aSelectionMadeElsewhereIsReadBack() {
        // Android's own per-app language screen writes the same state; the app must report it, not a copy.
        val store = RecordingLocaleStore(applied = listOf("bg"))
        val manager = AppLanguageManager(store, FakeLegacyLanguageStore())

        assertEquals(AppLanguage.BULGARIAN, manager.selection())
    }

    @Test
    fun aLocaleTheAppNoLongerShipsReadsAsTheSystemDefault() {
        // What the app is doing in that case really is "resolving through Android's own fallback".
        val store = RecordingLocaleStore(applied = listOf("fr"))
        val manager = AppLanguageManager(store, FakeLegacyLanguageStore())

        assertEquals(AppLanguage.SYSTEM_DEFAULT, manager.selection())
    }

    @Test
    fun everyTagTheAppShipsIsUnderstoodThroughItsRegionAndCase() {
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromTag("pt-BR"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es-419"))
        assertEquals(AppLanguage.BULGARIAN, AppLanguage.fromTag("bg_BG"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("RU"))
        assertEquals(AppLanguage.UKRAINIAN, AppLanguage.fromTag(" uk "))
        assertNull(AppLanguage.fromTag("fr"))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag(null))
    }

    // ---- the one-time hand-off of a pre-localization choice ----------------------------------------

    @Test
    fun anInstallWithNoStoredLanguageIsLeftOnTheSystemLanguage() {
        val store = RecordingLocaleStore()
        val legacy = FakeLegacyLanguageStore(tag = null)
        val manager = AppLanguageManager(store, legacy)

        val migrated = runBlocking { manager.migrateLegacySelection() }

        assertNull("there was nothing to hand over", migrated)
        assertTrue(
            "an install that never stored a language must not be moved into one — the old flow's " +
                "\"missing means Russian\" is exactly what this rule replaces",
            store.writes.isEmpty()
        )
        assertEquals(AppLanguage.SYSTEM_DEFAULT, manager.selection())
        assertFalse(legacy.cleared)
    }

    @Test
    fun aStoredRussianChoiceSurvivesTheUpgrade() {
        val store = RecordingLocaleStore()
        val legacy = FakeLegacyLanguageStore(tag = "ru")
        val manager = AppLanguageManager(store, legacy)

        val migrated = runBlocking { manager.migrateLegacySelection() }

        assertEquals(AppLanguage.RUSSIAN, migrated)
        assertEquals(listOf(listOf("ru")), store.writes)
        assertTrue("the legacy key is removed, so it cannot be migrated twice", legacy.cleared)
        assertEquals(AppLanguage.RUSSIAN, manager.selection())
    }

    @Test
    fun everyLegacyValueTheShippedBuildsCouldStoreIsHandedOver() {
        val cases = mapOf("ru" to AppLanguage.RUSSIAN, "en" to AppLanguage.ENGLISH, "uk" to AppLanguage.UKRAINIAN)

        cases.forEach { (stored, expected) ->
            val store = RecordingLocaleStore()
            val manager = AppLanguageManager(store, FakeLegacyLanguageStore(tag = stored))

            assertEquals("a stored \"$stored\" is an explicit choice", expected, runBlocking { manager.migrateLegacySelection() })
            assertEquals(listOf(listOf(expected.tag)), store.writes)
        }
    }

    @Test
    fun aStoredValueTheAppCannotPresentFallsBackToTheSystemLanguage() {
        val store = RecordingLocaleStore()
        val legacy = FakeLegacyLanguageStore(tag = "fr")
        val manager = AppLanguageManager(store, legacy)

        assertEquals(AppLanguage.SYSTEM_DEFAULT, runBlocking { manager.migrateLegacySelection() })
        assertEquals(listOf(listOf<String>()), store.writes)
        assertTrue("a value that cannot be used is still not left behind", legacy.cleared)
    }

    @Test
    fun theHandOffRunsOnceAndNeverOverwritesANewerChoice() {
        val store = RecordingLocaleStore()
        val legacy = FakeLegacyLanguageStore(tag = "ru")
        val manager = AppLanguageManager(store, legacy)

        runBlocking { manager.migrateLegacySelection() }
        // The user then picks Italian through the published picker…
        manager.select(AppLanguage.ITALIAN)
        // …and the next start runs the same hand-off again.
        val secondRun = runBlocking { manager.migrateLegacySelection() }

        assertNull("the legacy value was removed by the first run", secondRun)
        assertEquals(AppLanguage.ITALIAN, manager.selection())
        assertEquals(
            "the hand-off must never be a second source of truth",
            listOf(listOf("ru"), listOf("it")),
            store.writes
        )
    }

    // ---- the system's own screen ------------------------------------------------------------------

    @Test
    fun theSystemLanguageScreenIsOfferedOnlyWhereThePlatformHasOne() {
        assertFalse("Android 12 and below have no per-app language screen", AppLanguageSettings.isSystemPickerAvailable(32))
        assertFalse("API 33 is the first version that has it", AppLanguageSettings.isSystemPickerAvailable(33 - 1))
        assertTrue("API 33 introduced it", AppLanguageSettings.isSystemPickerAvailable(33))
        assertTrue("and it stays on later platforms", AppLanguageSettings.isSystemPickerAvailable(34))
    }

    @Test
    fun theEnumIsTheAppsOnlyListOfSupportedLanguages() {
        assertEquals(7, AppLanguage.supported.size)
        assertEquals(7, AppLanguage.supportedTags.toSet().size)
        assertTrue("no entry may repeat a tag", AppLanguage.supportedTags.none { it.isBlank() })
        assertEquals(
            "the mode is not one of the languages",
            AppLanguage.entries.size - 1,
            AppLanguage.supported.size
        )
    }

    @Test
    fun theHandOffRemovesTheStoredValueBeforeItAppliesTheLocale() {
        // Found on a device, not in this suite: applying a locale restarts the process on Android 13+, so a
        // hand-off that cleared the key *after* applying it never cleared it — the app locale was applied,
        // the key survived, and the next start handed the same stale value over again, over a language the
        // user had chosen since. The order is therefore part of the behaviour, not an implementation detail.
        val events = mutableListOf<String>()
        val store = RecordingLocaleStore(events = events)
        val legacy = FakeLegacyLanguageStore(tag = "ru", events = events)
        val manager = AppLanguageManager(store, legacy)

        runBlocking { manager.migrateLegacySelection() }

        assertEquals("the removal must precede the write that restarts the app", listOf("clear", "write:ru"), events)
    }

    private class RecordingLocaleStore(
        applied: List<String> = emptyList(),
        private val events: MutableList<String> = mutableListOf()
    ) : AppLocaleStore {
        var applied: List<String> = applied
            private set
        val writes = mutableListOf<List<String>>()

        override fun readTags(): List<String> = applied

        override fun writeTags(tags: List<String>) {
            writes += tags
            applied = tags
            events += "write:${tags.joinToString(",")}"
        }
    }

    private class FakeLegacyLanguageStore(
        private val tag: String? = null,
        private val events: MutableList<String> = mutableListOf()
    ) : LegacyLanguageStore {
        var cleared = false
            private set

        override suspend fun readTag(): String? = if (cleared) null else tag

        override suspend fun clear() {
            cleared = true
            events += "clear"
        }
    }
}
