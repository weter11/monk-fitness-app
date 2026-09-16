package com.monkfitness.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The persistence contract of `ProgramConfigurationRepository` — the boundary Task 11/13 will consume.
 *
 * What is pinned here:
 *
 *  * a missing record loads as the authoritative `DEFAULT`, and a read persists nothing;
 *  * a real change persists `CUSTOM` with the next version, an identical selection is a true no-op
 *    (same source, same version, stored bytes untouched), and a selection equal to the authoritative
 *    default set reads back as `DEFAULT` rather than a fabricated `CUSTOM`;
 *  * reset-to-default returns to `DEFAULT` and advances the version only when the effective selection
 *    actually changed, so repeating it is a no-op and the counter never rewinds;
 *  * version monotonicity holds against a stored counter that is already ahead of the caller, and a
 *    candidate that predates the stored one is discarded;
 *  * an exercise id outside the library is rejected instead of persisted, and a stored selection is
 *    never silently filtered, merged or extended;
 *  * the stored representation is a stable source *name*, a set of ids and an integer version — no
 *    ordinals — and an unreadable record degrades to `DEFAULT` without rewinding the counter;
 *  * the configuration lives in its own DataStore, so unrelated persistent state survives every
 *    operation, the C3 preferences path cannot clear it, and it references no adaptive type.
 *
 * ## The storage is real
 *
 * The repository takes a `DataStore<Preferences>` rather than a `Context`, so this suite drives a real
 * preferences DataStore over a real file (`PreferenceDataStoreFactory`, the JVM variant the Android
 * artifact resolves to) and proves persistence the way a device does: by closing the store and
 * reopening the same file, which is what an app restart is. A no-op is proven by comparing the file's
 * digest and modification time before and after, not by trusting a return value.
 */
class ProgramConfigurationRepositoryTest {

    // ---- the authoritative library, read from the one type that owns it -------------------------

    private val library: Set<String> = WorkoutGenerator().getExerciseLibrary().map { it.id }.toSet()
    private val subset = setOf("pushups", "plank", "squats")
    private val otherSubset = setOf("cat_cow", "dead_bug", "bird_dog")
    private val equipmentGated = setOf("pullups", "rows", "hang", "face_pull")

    // The stored representation, declared here as literals on purpose: renaming a key orphans every
    // record a device has already written, so a rename must fail this suite rather than pass silently.
    private val sourceKey = stringPreferencesKey("program_configuration_source")
    private val idsKey = stringSetPreferencesKey("program_configuration_enabled_exercise_ids")
    private val versionKey = intPreferencesKey("program_configuration_version")
    private val unrelatedKey = stringPreferencesKey("unrelated_state")

    // ---- a real DataStore over a real file ------------------------------------------------------

    private data class Fingerprint(val exists: Boolean, val size: Long, val sha256: String, val modified: Long)

    private inner class Sessions {
        private val file = File(
            File(System.getProperty("java.io.tmpdir"), "task9-program-configuration-${System.nanoTime()}")
                .apply { mkdirs() },
            "program_configuration.preferences_pb"
        )
        private var scope: CoroutineScope? = null
        private var dataStore: DataStore<Preferences>? = null

        /** Opens the store over [file] and returns the repository under test. */
        suspend fun open(): ProgramConfigurationRepository {
            close()
            val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val store = PreferenceDataStoreFactory.create(scope = newScope) { file }
            scope = newScope
            dataStore = store
            return ProgramConfigurationRepository(store, library)
        }

        /** What an app restart looks like: the store closed and the same file opened again by [open]. */
        suspend fun close() {
            val current = scope ?: return
            val job = current.coroutineContext[Job]
            current.cancel()
            job?.join()
            scope = null
            dataStore = null
        }

        suspend fun persisted(): Preferences = requireNotNull(dataStore).data.first()

        suspend fun writeRaw(block: suspend (MutablePreferences) -> Unit) {
            requireNotNull(dataStore).edit(block)
        }

        fun fingerprint(): Fingerprint = Fingerprint(
            exists = file.exists(),
            size = file.length(),
            sha256 = if (file.exists()) {
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            } else "",
            modified = file.lastModified()
        )
    }

    private fun configTest(block: suspend Sessions.() -> Unit) {
        val sessions = Sessions()
        try {
            runBlocking { sessions.block() }
        } finally {
            runBlocking { sessions.close() }
        }
    }

    private fun mainSource(relativePath: String): File {
        val candidate = File("src/main/java/com/monkfitness/app/$relativePath")
        return if (candidate.isFile) candidate else File("app/src/main/java/com/monkfitness/app/$relativePath")
    }

    // ---- the authoritative default set ---------------------------------------------------------

    @Test
    fun theAuthoritativeDefaultSetIsTheWholeExerciseLibrary() {
        val authoritative = ProgramConfigurationRepository.authoritativeDefaultExerciseIds()

        assertEquals(library, authoritative)
        assertTrue("fixtures must be real library ids", library.containsAll(subset + otherSubset + equipmentGated))
        // Equipment-gated exercises belong to the default set: equipment filtering happens when a
        // workout is generated, it is not a property of the user's configuration.
        assertTrue(authoritative.containsAll(equipmentGated))
        assertTrue("expected a non-trivial library, was ${authoritative.size}", authoritative.size > 30)
    }

    // ---- load ----------------------------------------------------------------------------------

    @Test
    fun missingPersistedConfigurationLoadsAsTheAuthoritativeDefault() = configTest {
        val repository = open()

        val loaded = repository.load()

        assertEquals(ProgramConfigurationSource.DEFAULT, loaded.source)
        assertEquals(library, loaded.enabledExerciseIds)
        assertEquals(ProgramConfiguration.INITIAL_VERSION, loaded.configurationVersion)
    }

    @Test
    fun readingTheConfigurationDoesNotPersistAnything() = configTest {
        val repository = open()

        repository.load()
        repository.load()

        assertFalse("a read must not create the store file", fingerprint().exists)
    }

    @Test
    fun persistedCustomConfigurationRoundTripsAcrossSessions() = configTest {
        open().apply(subset)
        close()

        val reloaded = open().load()

        assertEquals(ProgramConfigurationSource.CUSTOM, reloaded.source)
        assertEquals(subset, reloaded.enabledExerciseIds)
        assertEquals(1, reloaded.configurationVersion)
    }

    @Test
    fun persistedDefaultConfigurationRoundTripsAcrossSessions() = configTest {
        val repository = open()
        repository.apply(subset)
        repository.resetToDefault()
        close()

        val reloaded = open().load()

        assertEquals(ProgramConfigurationSource.DEFAULT, reloaded.source)
        assertEquals(library, reloaded.enabledExerciseIds)
        assertEquals(2, reloaded.configurationVersion)
    }

    @Test
    fun enabledExerciseIdsRoundTripExactly() = configTest {
        val selection = subset + equipmentGated
        open().apply(selection)
        close()

        val reloaded = open().load()

        assertEquals(selection, reloaded.enabledExerciseIds)
        assertEquals("no id may be added or dropped by storage", selection.size, reloaded.enabledExerciseIds.size)
    }

    @Test
    fun configurationVersionRoundTrips() = configTest {
        open().apply(subset)
        open().apply(otherSubset)
        close()

        val reloaded = open().load()

        assertEquals(2, reloaded.configurationVersion)
        assertEquals(otherSubset, reloaded.enabledExerciseIds)
    }

    // ---- apply ---------------------------------------------------------------------------------

    @Test
    fun firstRealCustomChangeBecomesCustom() = configTest {
        val repository = open()

        val applied = repository.apply(subset)

        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(subset, applied.enabledExerciseIds)
        assertEquals(ProgramConfiguration.INITIAL_VERSION + 1, applied.configurationVersion)
    }

    @Test
    fun applyingASelectionEqualToTheAuthoritativeDefaultIsANoOpRatherThanAFakeCustom() = configTest {
        val repository = open()

        val applied = repository.apply(library)

        assertEquals(ProgramConfigurationSource.DEFAULT, applied.source)
        assertEquals(ProgramConfiguration.INITIAL_VERSION, applied.configurationVersion)
        assertFalse("nothing changed, so nothing may be stored", fingerprint().exists)
    }

    @Test
    fun applyingTheAuthoritativeDefaultAfterACustomSelectionBecomesDefault() = configTest {
        val repository = open()
        repository.apply(subset)

        val applied = repository.apply(library)

        assertEquals(ProgramConfigurationSource.DEFAULT, applied.source)
        assertEquals(library, applied.enabledExerciseIds)
        assertEquals(2, applied.configurationVersion)
        assertEquals(applied, open().load())
    }

    @Test
    fun applyingTheSameEffectiveConfigurationIsATrueNoOp() = configTest {
        val repository = open()
        repository.apply(subset)
        val before = fingerprint()

        val applied = repository.apply(setOf("squats", "plank", "pushups"))

        assertEquals(1, applied.configurationVersion)
        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(before, fingerprint())
    }

    @Test
    fun aNoOpDoesNotAdvanceTheVersion() = configTest {
        val repository = open()
        repository.apply(subset)

        repository.apply(subset)
        repository.apply(subset)
        close()

        assertEquals(1, open().load().configurationVersion)
    }

    // ---- reset to default ----------------------------------------------------------------------

    @Test
    fun resetToDefaultChangesCustomToDefault() = configTest {
        val repository = open()
        repository.apply(subset)

        val reset = repository.resetToDefault()

        assertEquals(ProgramConfigurationSource.DEFAULT, reset.source)
        assertEquals(library, reset.enabledExerciseIds)
        assertEquals(2, reset.configurationVersion)
    }

    @Test
    fun resetToDefaultAdvancesTheVersionOnlyWhenTheEffectiveConfigurationChanges() = configTest {
        val repository = open()
        repository.apply(subset)

        val first = repository.resetToDefault()
        val second = repository.resetToDefault()

        assertEquals(2, first.configurationVersion)
        assertEquals(2, second.configurationVersion)
        assertEquals(first, second)
    }

    @Test
    fun repeatedResetToDefaultIsANoOp() = configTest {
        val repository = open()
        repository.apply(subset)
        repository.resetToDefault()
        val before = fingerprint()

        repository.resetToDefault()

        assertEquals(before, fingerprint())
    }

    @Test
    fun versionsAreStrictlyMonotonicAcrossMultipleRealChanges() = configTest {
        val repository = open()

        val versions = mutableListOf<Int>()
        listOf(subset, otherSubset, library, subset, otherSubset).forEach { proposed ->
            versions += repository.apply(proposed).configurationVersion
        }
        versions += repository.resetToDefault().configurationVersion

        assertEquals(listOf(1, 2, 3, 4, 5, 6), versions)
        assertTrue(versions.zipWithNext().all { (previous, next) -> next > previous })
        assertEquals(6, open().load().configurationVersion)
    }

    // ---- monotonicity at the storage boundary --------------------------------------------------

    @Test
    fun theNextVersionIsDerivedFromTheStoredCounterNotFromTheCallersCopy() = configTest {
        val repository = open()
        writeRaw { preferences ->
            preferences[sourceKey] = ProgramConfigurationSource.CUSTOM.name
            preferences[idsKey] = subset
            preferences[versionKey] = 7
        }

        val applied = repository.apply(otherSubset)

        assertEquals(8, applied.configurationVersion)
        assertEquals(8, persisted()[versionKey])
    }

    @Test
    fun aCandidateWhoseVersionPrecedesTheStoredOneIsDiscarded() = configTest {
        val repository = open()
        repository.apply(subset)
        repository.apply(otherSubset)
        val before = fingerprint()

        val returned = repository.writeIfNotStale(
            ProgramConfiguration.custom(setOf("plank"), configurationVersion = 1)
        )

        val stored = repository.load()
        assertEquals(2, stored.configurationVersion)
        assertEquals(otherSubset, stored.enabledExerciseIds)
        assertEquals(stored, returned)
        assertEquals(before, fingerprint())
    }

    // ---- never invent, never re-enable, never merge ---------------------------------------------

    @Test
    fun anUnknownExerciseIdIsRejectedAndNothingIsPersisted() = configTest {
        val repository = open()
        val before = fingerprint()

        val failure = runCatching { repository.apply(setOf("pushups", "quantum_pushup")) }.exceptionOrNull()

        assertTrue("expected an IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("quantum_pushup"))
        assertEquals(before, fingerprint())
        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)
    }

    @Test
    fun aStoredSelectionIsNeverSilentlyFilteredMergedOrExtended() = configTest {
        val storedSelection = setOf("pushups", "legacy_exercise_v0")
        val repository = open()
        writeRaw { preferences ->
            preferences[sourceKey] = ProgramConfigurationSource.CUSTOM.name
            preferences[idsKey] = storedSelection
            preferences[versionKey] = 3
        }

        val loaded = repository.load()

        assertEquals(storedSelection, loaded.enabledExerciseIds)
        assertNotEquals(library, loaded.enabledExerciseIds)
    }

    // ---- the stored representation -------------------------------------------------------------

    @Test
    fun theStoredSourceIsTheStableNameNotAnOrdinal() = configTest {
        val repository = open()

        repository.apply(subset)

        assertEquals(ProgramConfigurationSource.CUSTOM.name, persisted()[sourceKey])
        assertEquals(1, persisted()[versionKey])
        assertEquals(subset, persisted()[idsKey])
    }

    @Test
    fun aRecordWrittenWithTheStableTokensStaysReadable() = configTest {
        val repository = open()
        writeRaw { preferences ->
            preferences[sourceKey] = "DEFAULT"
            preferences[idsKey] = library
            preferences[versionKey] = 4
        }

        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)

        writeRaw { preferences -> preferences[sourceKey] = "CUSTOM" }

        assertEquals(ProgramConfigurationSource.CUSTOM, repository.load().source)
    }

    @Test
    fun anUnreadableStoredSourceFallsBackToTheAuthoritativeDefaultWithoutRewindingTheVersion() = configTest {
        val repository = open()
        writeRaw { preferences ->
            preferences[sourceKey] = "1" // an ordinal: never written by this layer, never honoured on read
            preferences[idsKey] = subset
            preferences[versionKey] = 5
        }

        val unreadable = repository.load()

        assertEquals(ProgramConfigurationSource.DEFAULT, unreadable.source)
        assertEquals(library, unreadable.enabledExerciseIds)
        assertEquals(5, unreadable.configurationVersion)

        writeRaw { preferences ->
            preferences[sourceKey] = "FUTURE_SOURCE"
            preferences[versionKey] = 9
        }

        val futureToken = repository.load()

        assertEquals(ProgramConfigurationSource.DEFAULT, futureToken.source)
        assertEquals(9, futureToken.configurationVersion)

        // ...and the counter still moves forward from what is stored.
        assertEquals(10, repository.apply(subset).configurationVersion)

        writeRaw { preferences -> preferences[versionKey] = -3 }

        assertEquals(ProgramConfiguration.INITIAL_VERSION, repository.load().configurationVersion)
    }

    // ---- independence from everything else -----------------------------------------------------

    @Test
    fun unrelatedPersistentStateInTheStoreSurvivesEveryConfigurationOperation() = configTest {
        val repository = open()
        writeRaw { preferences -> preferences[unrelatedKey] = "keep-me" }

        repository.apply(subset)
        repository.resetToDefault()
        repository.apply(subset)
        close()

        val reopened = open()
        assertEquals("keep-me", persisted()[unrelatedKey])
        assertEquals(3, reopened.load().configurationVersion)
    }

    @Test
    fun theConfigurationLayerIsIndependentOfAdaptivePersistenceAndOfResetOrchestration() {
        val configurationSource = mainSource("domain/adaptive/ProgramConfiguration.kt")
        val repositorySource = mainSource("data/repository/ProgramConfigurationRepository.kt")
        assertTrue(configurationSource.isFile)
        assertTrue(repositorySource.isFile)

        val forbidden = listOf(
            "AdaptiveRepository",
            "FamilyProgressionState",
            "AdaptiveDecisionRecord",
            "AdaptiveDecisionHistoryDao",
            "FamilyProgressionStateDao",
            "AdaptiveProgramEngine",
            "ProgressionResolver",
            "AdaptivePolicy",
            "androidx.room",
            "AppDatabase",
            "ProgressDao",
            "ProgramMaintenance",
            "SettingsManager",
            "MainViewModel"
        )
        listOf(configurationSource, repositorySource).forEach { source ->
            val found = forbidden.filter { source.readText().contains(it) }
            assertTrue("${source.name} must not reference $found", found.isEmpty())
        }

        // The exercise catalogue is the generator's, and this layer derives the default set from it
        // in exactly one place — never from a second library of its own.
        val repositoryText = repositorySource.readText()
        assertEquals(1, Regex("WorkoutGenerator\\(").findAll(repositoryText).count())

        // A store of its own: the C3 Full Reset path clears the "settings" preferences, and it must
        // not be able to clear the configuration with them.
        assertTrue(
            "the configuration needs its own DataStore file",
            repositoryText.contains("\"program_configuration\"")
        )
        assertFalse(repositoryText.contains("\"settings\""))
        assertTrue(mainSource("data/local/SettingsManager.kt").readText().contains("name = \"settings\""))
    }
}
