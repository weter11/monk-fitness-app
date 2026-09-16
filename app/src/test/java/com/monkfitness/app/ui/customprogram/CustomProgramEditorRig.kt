package com.monkfitness.app.ui.customprogram

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.bodyRegion
import com.monkfitness.app.data.model.trainingDomain
import com.monkfitness.app.data.repository.ProgramConfigurationRepository
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.TrainingDomain
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * The real thing, not a mock: a `PreferenceDataStoreFactory` store over a temp file (the JVM variant
 * the Android artifact resolves to), the app's own `ProgramConfigurationRepository`, the app's own
 * exercise library and family list, and the app's own search matcher.
 *
 * Two substitutions are made, and only two:
 *
 *  * the exercise library's localized display text is supplied as a deterministic token per exercise
 *    (`"pushups wide"` for `pushups_wide`). On a device `MainViewModel.enrichExercise` fills those
 *    fields from `R.string` resources through `withLocalizedSearchText`; a JVM test has no resource
 *    table, so the tokens stand in for the app's own display text while every id, family, category,
 *    subcategory and equipment requirement stays exactly the library's;
 *  * the equipment set is a mutable field so a test can change the user's equipment while the editor
 *    is open, which is what the settings screen does.
 *
 * What is deliberately NOT substituted: the validator, the configuration type, the repository and its
 * storage. Those are the boundaries this task consumes, and a fake of any of them would test the fake.
 */
internal class CustomProgramEditorRig(
    initialEquipment: Set<Equipment> = emptySet(),
    val library: List<Exercise> = searchableLibrary()
) {

    /** The user's equipment, as `SettingsManager.availableEquipmentFlow` would report it. */
    var equipment: Set<Equipment> = initialEquipment

    val libraryById: Map<String, Exercise> = library.associateBy { it.id }
    val libraryIds: Set<String> = libraryById.keys

    /** The authoritative default set: every exercise of the library, exactly as the repository sees it. */
    val defaultEnabledExerciseIds: Set<String> = libraryIds

    val families: List<com.monkfitness.app.data.model.ExerciseFamily> = WorkoutGenerator().families

    private val directory = File(
        File(System.getProperty("java.io.tmpdir"), "task11-custom-program-${System.nanoTime()}")
            .apply { mkdirs() },
        "program_configuration"
    )
    private val file = File(directory, "program_configuration.preferences_pb")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    val repository = ProgramConfigurationRepository(dataStore, defaultEnabledExerciseIds)

    val editor = CustomProgramEditor(
        repository = repository,
        exerciseLibrary = { this.library },
        families = families,
        availableEquipment = { this.equipment }
    )

    // ---- helpers ------------------------------------------------------------------------------

    /** The stored bytes, hashed: the proof that an operation did or did not write anything at all. */
    fun storedDigest(): String = if (!file.isFile) {
        ABSENT
    } else {
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    fun idsInFamily(familyId: String): Set<String> =
        library.filter { it.familyId == familyId }.map { it.id }.toSet()

    fun idsInRegion(region: com.monkfitness.app.domain.adaptive.BodyRegion): Set<String> =
        library.filter { it.bodyRegion == region }.map { it.id }.toSet()

    fun idsInDomain(domain: com.monkfitness.app.domain.adaptive.TrainingDomain): Set<String> =
        library.filter { it.trainingDomain == domain }.map { it.id }.toSet()

    /** Persists [ids] through the repository, the way a previous editing session would have. */
    fun seed(ids: Set<String>): ProgramConfigurationSnapshot = runBlocking {
        val configuration = repository.apply(ids)
        ProgramConfigurationSnapshot(configuration.enabledExerciseIds, configuration.configurationVersion, configuration.source)
    }

    fun state(): CustomProgramEditorState = editor.state.value

    fun group(familyId: String): CustomProgramFamilyGroup =
        requireNotNull(state().families.firstOrNull { it.familyId == familyId }) {
            "no family group for $familyId in ${state().families.map { it.familyId }}"
        }

    fun inRig(block: suspend CustomProgramEditorRig.() -> Unit) {
        runBlocking { block() }
    }

    /** Closes the store's coroutine scope, the way a process ending would. */
    fun close() {
        scope.cancel()
    }

    companion object {

        const val ABSENT = "absent"

        /**
         * The app's library with deterministic stand-ins for its localized display text, so the app's
         * own `matchesQuery` can run on the JVM.
         */
        fun searchableLibrary(): List<Exercise> = WorkoutGenerator().getExerciseLibrary().map { exercise ->
            val token = exercise.id.replace('_', ' ')
            exercise.copy(
                nameRu = token,
                nameEn = token,
                nameUk = token,
                descriptionRu = token,
                descriptionEn = token,
                descriptionUk = token
            )
        }
    }
}

/** What the repository reports after a seed, without holding a `ProgramConfiguration` in the test. */
internal data class ProgramConfigurationSnapshot(
    val enabledExerciseIds: Set<String>,
    val configurationVersion: Int,
    val source: com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
)
