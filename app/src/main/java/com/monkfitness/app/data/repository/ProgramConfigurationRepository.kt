package com.monkfitness.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The user's exercise configuration, in a preferences store of its own.
 *
 * It is deliberately NOT the `settings` store: the C3 "Full Reset" path clears that one wholesale, and
 * a configuration that shared it could be wiped — and its version counter rewound to zero — by a reset
 * this layer does not own. Returning the configuration to its default is
 * [ProgramConfigurationRepository.resetToDefault], which the lifecycle task calls explicitly.
 */
private val Context.programConfigurationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "program_configuration")

/**
 * The production entry point: the app's configuration store, over the authoritative default exercise
 * set. Kept to one line so the composition root decides nothing about storage.
 */
fun Context.programConfigurationRepository(
    defaultEnabledExerciseIds: Set<String> =
        ProgramConfigurationRepository.authoritativeDefaultExerciseIds()
): ProgramConfigurationRepository =
    ProgramConfigurationRepository(programConfigurationDataStore, defaultEnabledExerciseIds)

/**
 * The persistence adapter for [ProgramConfiguration]: the enabled exercise ids, whether that set is
 * the authoritative default or the user's own, and the version that identifies the configuration which
 * was effective.
 *
 * It is an adapter and nothing more. It decides no training-domain, equipment or balance rule — the
 * configuration validator owns those, and this layer only refuses to store an id that does not exist
 * in the exercise library, because a configuration must stay referable to real exercises. It holds no
 * adaptive state: progression, decision history, the calendar, the program revision and workout
 * history live elsewhere and no operation here reads or writes any of them. And it applies no
 * lifecycle semantics of its own: a reset here resets the selection, never the user's progress.
 *
 * ## Storage
 *
 * `source` is stored as the [ProgramConfigurationSource.name] token, the ids as a preferences string
 * set (the set semantics the app already uses for its own id collections, order-independent and free
 * of locale-dependent formatting), and the version as an integer. A record whose source token cannot
 * be read is not guessed at: the adapter falls back to the authoritative default *at the stored
 * counter*, so an unreadable record can neither fabricate a custom selection nor rewind the version.
 *
 * ## Versioning under concurrency
 *
 * Every mutation — [apply], [resetToDefault] and the storage-boundary guard — reads the stored
 * configuration, decides, and writes inside a single `DataStore.edit`, which serialises updates over
 * a snapshot. The next version is therefore always derived from the value actually on disk, never
 * from a copy the caller read earlier, so a stale caller cannot push the counter backwards; a
 * candidate whose version precedes the stored one is discarded by the same write path. A decision
 * that changes nothing writes nothing at all, so a no-op does not even touch the stored bytes.
 */
class ProgramConfigurationRepository(
    private val dataStore: DataStore<Preferences>,
    /** The authoritative default enabled set: every exercise of the library this app ships. */
    val defaultEnabledExerciseIds: Set<String>
) {

    companion object {

        private val SOURCE_KEY = stringPreferencesKey("program_configuration_source")
        private val ENABLED_EXERCISE_IDS_KEY =
            stringSetPreferencesKey("program_configuration_enabled_exercise_ids")
        private val VERSION_KEY = intPreferencesKey("program_configuration_version")

        /**
         * The complete default enabled set: every exercise id of the library this app ships, in the
         * one type that owns the library. Equipment-gated exercises are part of it on purpose —
         * equipment is checked when a workout is generated, it is not a property of the user's
         * configuration.
         */
        fun authoritativeDefaultExerciseIds(): Set<String> =
            WorkoutGenerator().getExerciseLibrary().map { it.id }.toSet()
    }

    /** The current configuration, re-emitted whenever it changes. */
    val configurationFlow: Flow<ProgramConfiguration> = dataStore.data.map { it.toConfiguration() }

    /**
     * The configuration in effect. On first use — no record at all — this is the authoritative
     * `DEFAULT`, and reading it stores nothing: a configuration is persisted when the user changes it,
     * not because it was looked at.
     */
    suspend fun load(): ProgramConfiguration = configurationFlow.first()

    /**
     * Applies a proposed enabled set.
     *
     * A selection equal to the current one is a no-op: the stored `source` and version are preserved
     * exactly and nothing is written. A different selection replaces the stored one — ids are never
     * merged and a disabled exercise is never silently re-enabled — becomes `DEFAULT` when it equals
     * the authoritative default set and `CUSTOM` otherwise, and advances the version by one.
     *
     * @throws IllegalArgumentException when the selection references an id that is not in the library.
     */
    suspend fun apply(enabledExerciseIds: Set<String>): ProgramConfiguration {
        requireKnownExerciseIds(enabledExerciseIds)
        return update { current -> current.applying(enabledExerciseIds, defaultEnabledExerciseIds) }
    }

    /**
     * Resets the selection to the authoritative default set, as `DEFAULT`.
     *
     * Nothing else is touched: not adaptive progression or its history, not the program revision, not
     * the calendar, not workout history and not nutrition. The version advances only when the effective
     * selection actually changes, so an already-default configuration is left exactly as it is.
     */
    suspend fun resetToDefault(): ProgramConfiguration =
        update { current -> current.resetToDefault(defaultEnabledExerciseIds) }

    /**
     * Stores [candidate] unless its version precedes the version already stored, in which case the
     * stored configuration wins and nothing is written.
     *
     * This is the write path's monotonicity rule, reachable from the persistence contract test so the
     * guard is provable on the JVM. Production callers use [apply] and [resetToDefault], which derive
     * their candidate from the stored configuration inside the same update and therefore never present
     * a stale one.
     */
    internal suspend fun writeIfNotStale(candidate: ProgramConfiguration): ProgramConfiguration =
        update { candidate }

    /**
     * The one read-decide-write path: `DataStore.edit` hands this block the stored snapshot and
     * serialises concurrent updates over it, so the decision is taken against the bytes that are
     * actually there. A candidate that predates the stored version is discarded rather than stored,
     * and a decision equal to the stored configuration is not written at all.
     */
    private suspend fun update(
        transform: (ProgramConfiguration) -> ProgramConfiguration
    ): ProgramConfiguration {
        var resolved: ProgramConfiguration? = null

        dataStore.edit { preferences ->
            val current = preferences.toConfiguration()
            val candidate = transform(current)

            val next = if (candidate.configurationVersion < current.configurationVersion) current else candidate

            resolved = next
            if (next != current) preferences.store(next)
        }

        return requireNotNull(resolved) { "the configuration update produced no configuration" }
    }

    private fun Preferences.toConfiguration(): ProgramConfiguration {
        val source = this[SOURCE_KEY]?.let { token ->
            ProgramConfigurationSource.entries.firstOrNull { it.name == token }
        }
        val enabledExerciseIds = this[ENABLED_EXERCISE_IDS_KEY]
        val version = (this[VERSION_KEY] ?: ProgramConfiguration.INITIAL_VERSION)
            .coerceAtLeast(ProgramConfiguration.INITIAL_VERSION)

        if (source == null || enabledExerciseIds == null) {
            // Nothing readable was ever stored: the authoritative default, at the stored counter so an
            // unreadable record cannot rewind a version that has already moved forward.
            return ProgramConfiguration.default(defaultEnabledExerciseIds, version)
        }

        return ProgramConfiguration(source, enabledExerciseIds, version)
    }

    private fun MutablePreferences.store(configuration: ProgramConfiguration) {
        this[SOURCE_KEY] = configuration.source.name
        this[ENABLED_EXERCISE_IDS_KEY] = configuration.enabledExerciseIds
        this[VERSION_KEY] = configuration.configurationVersion
    }

    private fun requireKnownExerciseIds(enabledExerciseIds: Set<String>) {
        val unknown = enabledExerciseIds - defaultEnabledExerciseIds
        require(unknown.isEmpty()) {
            "a program configuration may reference only existing exercise ids; unknown: " +
                unknown.sorted()
        }
    }
}
