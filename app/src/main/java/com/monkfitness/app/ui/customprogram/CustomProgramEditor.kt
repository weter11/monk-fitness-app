package com.monkfitness.app.ui.customprogram

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseFamily
import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.data.repository.ProgramConfigurationRepository
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationValidator
import com.monkfitness.app.domain.adaptive.ProgramConfigurationValidation
import com.monkfitness.app.util.matchesQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Custom Program editor: a local draft, a deterministic grouping of the library, and the two
 * operations that change anything — Apply and Reset to default.
 *
 * ## Draft-first, always
 *
 * Opening the editor reads the persisted configuration and copies its selection into a draft. Every
 * checkbox, family toggle and search keystroke edits that draft and nothing else: no toggle writes to
 * storage, not even the store's bytes. Cancel throws the draft away and the next open reads the stored
 * configuration again. Only Apply stores anything, and only after the domain validator has accepted
 * the selection.
 *
 * ## Where the rules live
 *
 * This class owns no rule of its own:
 *
 *  * the exercise library, its families and the user's equipment are supplied by the caller;
 *  * validity is the domain validator's answer, produced from the draft and presented unchanged;
 *  * the persisted configuration, its source and its version are the repository's business — this class
 *    calls the repository's own apply and reset operations and never assembles or numbers a
 *    configuration itself, so a no-op stays a no-op and a reset on an already-default selection stays
 *    free;
 *  * nothing about adaptive progression, decision history or session snapshots is read or written here.
 *
 * ## Determinism
 *
 * The family order is the library's declared family order and the exercise order inside a family is the
 * library's own order; no map or set iteration decides what the user sees. Search is the app's own
 * matcher over the app's own exercise names and descriptions, and it only ever hides rows — never
 * changes the draft and never changes what a family's tri-state means.
 *
 * @param repository the persistence authority for the user's configuration.
 * @param exerciseLibrary the authoritative library, in its canonical order. Supplied as a provider so
 *  that opening the editor always reads the app's current library rather than a copy captured at
 *  construction time.
 * @param families the authoritative families, in their declared order.
 * @param availableEquipment the equipment the user has, read when the selection is validated so a
 *  change made in the settings screen is honoured the next time the editor is opened.
 */
class CustomProgramEditor(
    private val repository: ProgramConfigurationRepository,
    private val exerciseLibrary: () -> List<Exercise>,
    private val families: List<ExerciseFamily>,
    private val availableEquipment: () -> Set<Equipment>
) {

    private val _state = MutableStateFlow(CustomProgramEditorState())

    /** Everything the screen renders, re-emitted on every draft, search, apply and reset. */
    val state: StateFlow<CustomProgramEditorState> = _state.asStateFlow()

    private var library: List<Exercise> = emptyList()
    private var libraryById: Map<String, Exercise> = emptyMap()

    /** The stored configuration the draft is compared against; null until the editor has been opened. */
    private var persisted: ProgramConfiguration? = null

    // ---- opening and closing -------------------------------------------------------------------

    /**
     * Opens the editor: reads the persisted configuration, fills the library the screen works on and
     * resets the draft, the search and the confirmation to a clean editing session.
     *
     * Reading stores nothing, so opening the editor cannot change the user's configuration.
     */
    suspend fun open() {
        library = exerciseLibrary()
        libraryById = library.associateBy { exercise -> exercise.id }
        val stored = repository.load()
        persisted = stored

        _state.value = _state.value.copy(
            isOpen = true,
            configurationSource = stored.source,
            draftEnabledExerciseIds = stored.enabledExerciseIds,
            persistedEnabledExerciseIds = stored.enabledExerciseIds,
            searchQuery = EMPTY_QUERY,
            isResetConfirmationVisible = false,
            appliedConfiguration = null
        )

        rebuildFamilies()
        rebuildValidation(validationOf(stored.enabledExerciseIds))
    }

    /**
     * Throws the draft away without touching anything that was persisted: the draft becomes the stored
     * selection again, which is what Cancel means. Nothing is written, so the stored configuration, its
     * source and its version are exactly as they were before the editor was opened.
     */
    fun discardDraft() {
        val storedSelection = _state.value.persistedEnabledExerciseIds
        _state.value = _state.value.copy(
            draftEnabledExerciseIds = storedSelection,
            isResetConfirmationVisible = false
        )
        rebuildFamilies()
        rebuildValidation(validationOf(storedSelection))
    }

    // ---- editing the draft ---------------------------------------------------------------------

    /**
     * Toggles one exercise in the draft.
     *
     * An id the library does not define is ignored rather than added: the editor never extends the
     * selection beyond the exercises the app actually ships.
     */
    fun toggleExercise(exerciseId: String) {
        if (exerciseId !in libraryById) return
        val draft = _state.value.draftEnabledExerciseIds
        val next = if (exerciseId in draft) draft - exerciseId else draft + exerciseId
        setDraft(next)
    }

    /**
     * Toggles a whole family: fully enabled becomes fully disabled, anything else becomes fully enabled.
     *
     * Only that family's own exercises are touched: the operation adds or removes exactly the ids of
     * that family and leaves every other family's selection as it was. An unknown family is ignored.
     */
    fun toggleFamily(familyId: String) {
        val memberIds = library.filter { exercise -> exercise.familyId == familyId }.map { it.id }.toSet()
        if (memberIds.isEmpty()) return

        val draft = _state.value.draftEnabledExerciseIds
        val next = when (selectionStateOf(memberIds.count { id -> id in draft }, memberIds.size)) {
            FamilySelectionState.ALL_ENABLED -> draft - memberIds
            FamilySelectionState.PARTIAL,
            FamilySelectionState.NONE_ENABLED -> draft + memberIds
        }
        setDraft(next)
    }

    /** Filters the view. The draft is untouched: this only decides which rows are shown. */
    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        rebuildFamilies()
    }

    private fun setDraft(draft: Set<String>) {
        _state.value = _state.value.copy(draftEnabledExerciseIds = draft)
        rebuildFamilies()
        rebuildValidation(validationOf(draft))
    }

    // ---- reset to default ----------------------------------------------------------------------

    /** Shows the reset confirmation. Nothing is reset by asking. */
    fun requestResetToDefault() {
        _state.value = _state.value.copy(isResetConfirmationVisible = true)
    }

    /** Dismisses the reset confirmation. Nothing is reset by dismissing. */
    fun dismissResetConfirmation() {
        _state.value = _state.value.copy(isResetConfirmationVisible = false)
    }

    /**
     * Confirms the reset: the repository restores the authoritative default selection and the editor
     * reloads its draft from what was stored.
     *
     * The reset restores the selection only. Workout history, progress, the program calendar and the
     * adaptive state are none of this operation's business, and the version follows the repository's own
     * rule — a real change moves it once, an already-default selection leaves it alone.
     */
    suspend fun confirmResetToDefault(): ProgramConfiguration {
        val stored = repository.resetToDefault()
        persisted = stored

        _state.value = _state.value.copy(
            configurationSource = stored.source,
            draftEnabledExerciseIds = stored.enabledExerciseIds,
            persistedEnabledExerciseIds = stored.enabledExerciseIds,
            isResetConfirmationVisible = false
        )

        rebuildFamilies()
        rebuildValidation(validationOf(stored.enabledExerciseIds))
        return stored
    }

    // ---- apply ---------------------------------------------------------------------------------

    /**
     * Validates the draft and, only if the validator accepts it, stores it.
     *
     * A selection with hard errors is not persisted, the editor stays open and the findings are
     * published so the screen can show them: the configuration the user proposed is left exactly as the
     * user proposed it. A selection with soft warnings only is stored, warnings and all — warnings are
     * shown, they never block. Nothing is repaired, re-enabled or substituted on the user's behalf.
     */
    suspend fun apply(): CustomProgramApplyResult {
        val draft = _state.value.draftEnabledExerciseIds
        val validation = requireNotNull(validationOf(draft))

        rebuildValidation(validation)
        if (!validation.isValid) return CustomProgramApplyResult.Rejected(validation)

        val stored = repository.apply(draft)
        persisted = stored

        _state.value = _state.value.copy(
            configurationSource = stored.source,
            draftEnabledExerciseIds = stored.enabledExerciseIds,
            persistedEnabledExerciseIds = stored.enabledExerciseIds,
            appliedConfiguration = stored
        )

        rebuildFamilies()
        return CustomProgramApplyResult.Applied(stored)
    }

    // ---- the draft as the validator sees it ----------------------------------------------------

    /**
     * The validator's verdict on [draft], against the library and the equipment the editor was given.
     *
     * The proposed configuration is built by the domain type's own operation over the stored one, so the
     * source and the no-op rule are the domain's, not the editor's. Null before the editor has been
     * opened, when there is nothing to validate yet.
     */
    private fun validationOf(draft: Set<String>): ProgramConfigurationValidation? {
        val current = persisted ?: return null
        return ProgramConfigurationValidator.validate(
            configuration = current.applying(draft, repository.defaultEnabledExerciseIds),
            exerciseLibrary = library.map { exercise -> exercise.toConfigurationMetadata() },
            availableEquipment = availableEquipment()
        )
    }

    private fun rebuildValidation(validation: ProgramConfigurationValidation?) {
        _state.value = _state.value.copy(
            errors = if (validation == null) {
                emptyList()
            } else {
                CustomProgramValidationPresentation.errors(validation, libraryById)
            },
            warnings = if (validation == null) {
                emptyList()
            } else {
                CustomProgramValidationPresentation.warnings(validation)
            }
        )
    }

    // ---- the deterministic view ----------------------------------------------------------------

    private fun rebuildFamilies() {
        _state.value = _state.value.copy(
            families = buildFamilies(
                enabledExerciseIds = _state.value.draftEnabledExerciseIds,
                query = _state.value.searchQuery
            )
        )
    }

    /**
     * The families in their declared order, each holding its complete draft selection plus the rows the
     * search shows.
     *
     * A family the library declares no entry for cannot happen — the property is pinned by a test — but
     * if it ever did, its exercises are still listed instead of disappearing from the editor.
     */
    private fun buildFamilies(
        enabledExerciseIds: Set<String>,
        query: String
    ): List<CustomProgramFamilyGroup> {
        val exercisesByFamily = library.groupBy { exercise -> exercise.familyId }
        val declaredFamilyIds = families.map { family -> family.id }.toSet()

        val declared = families.mapNotNull { family ->
            val familyExercises = exercisesByFamily[family.id] ?: return@mapNotNull null
            familyGroup(family.id, family.nameRes, familyExercises, enabledExerciseIds, query)
        }
        val undeclared = exercisesByFamily.keys
            .filterNot { familyId -> familyId in declaredFamilyIds }
            .sorted()
            .map { familyId ->
                familyGroup(
                    familyId = familyId,
                    nameRes = R.string.custom_program_unknown_family,
                    familyExercises = exercisesByFamily.getValue(familyId),
                    enabledExerciseIds = enabledExerciseIds,
                    query = query
                )
            }

        return declared + undeclared
    }

    private fun familyGroup(
        familyId: String,
        nameRes: Int,
        familyExercises: List<Exercise>,
        enabledExerciseIds: Set<String>,
        query: String
    ): CustomProgramFamilyGroup {
        val rows = familyExercises.map { exercise -> row(exercise, enabledExerciseIds) }
        val visibleRows = familyExercises
            .filter { exercise -> matchesQuery(exercise, query) }
            .map { exercise -> row(exercise, enabledExerciseIds) }

        return CustomProgramFamilyGroup(
            familyId = familyId,
            nameRes = nameRes,
            exercises = rows,
            visibleExercises = visibleRows,
            selectionState = selectionStateOf(rows.count { row -> row.enabled }, rows.size)
        )
    }

    private fun row(exercise: Exercise, enabledExerciseIds: Set<String>) = CustomProgramExerciseRow(
        id = exercise.id,
        nameRes = exercise.nameRes,
        enabled = exercise.id in enabledExerciseIds
    )

    private fun selectionStateOf(enabledCount: Int, total: Int): FamilySelectionState = when {
        total == 0 || enabledCount == 0 -> FamilySelectionState.NONE_ENABLED
        enabledCount == total -> FamilySelectionState.ALL_ENABLED
        else -> FamilySelectionState.PARTIAL
    }

    private companion object {
        const val EMPTY_QUERY = ""
    }
}
