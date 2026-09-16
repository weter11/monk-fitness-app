package com.monkfitness.app.ui.customprogram

import androidx.annotation.StringRes
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationErrorCode
import com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
import com.monkfitness.app.domain.adaptive.ProgramConfigurationValidation
import com.monkfitness.app.domain.adaptive.ProgramConfigurationWarningCode
import com.monkfitness.app.domain.adaptive.TrainingDomain

/**
 * A family's selection, as the user sees it: three semantic states, and nothing else.
 *
 * [PARTIAL] is a state in its own right, not a shade of the other two: a family with some of its
 * exercises enabled is neither fully in nor fully out of the program, and the UI says so in words, not
 * only in colour. The state is always derived from the draft at the moment it is asked for — nothing
 * stores it — so a family header can never disagree with the exercises under it.
 */
enum class FamilySelectionState {
    /** Every exercise of the family is in the draft. */
    ALL_ENABLED,

    /** Some, but not all, exercises of the family are in the draft. */
    PARTIAL,

    /** No exercise of the family is in the draft. */
    NONE_ENABLED
}

/** One exercise row: its identity, its name resource and whether the draft currently enables it. */
data class CustomProgramExerciseRow(
    val id: String,
    @StringRes val nameRes: Int,
    val enabled: Boolean
)

/**
 * One family section of the editor.
 *
 * The two exercise lists are deliberately separate:
 *
 *  * [exercises] is the family's complete draft state, in the library's own order. [selectionState] is
 *    derived from this list and only this list;
 *  * [visibleExercises] is the subset the current search shows. Filtering changes what is displayed,
 *    never what is selected, so it can never change a family's state.
 */
data class CustomProgramFamilyGroup(
    val familyId: String,
    @StringRes val nameRes: Int,
    val exercises: List<CustomProgramExerciseRow>,
    val visibleExercises: List<CustomProgramExerciseRow>,
    val selectionState: FamilySelectionState
) {
    /** Whether the family has anything to show under the current search. */
    val isVisible: Boolean
        get() = visibleExercises.isNotEmpty()

    val enabledCount: Int
        get() = exercises.count { it.enabled }

    val exerciseCount: Int
        get() = exercises.size
}

/**
 * One hard validation error, ready to be shown.
 *
 * The domain-level identity is carried through unchanged — [code], [trainingDomain], [exerciseId] are
 * exactly what the validator reported — while [messageRes] and [subjectRes] are the localized wording
 * for them. [requiredEquipmentRes] is what the offending exercise needs, resolved from the same library
 * the editor displays, so the user can see *why* the exercise cannot be used without the validator
 * having to carry the equipment vocabulary.
 */
data class CustomProgramErrorPresentation(
    val code: ProgramConfigurationErrorCode,
    val trainingDomain: TrainingDomain?,
    val exerciseId: String?,
    @StringRes val messageRes: Int,
    @StringRes val subjectRes: Int,
    @StringRes val requiredEquipmentRes: List<Int> = emptyList()
)

/** One soft balance warning, ready to be shown. Warnings never block Apply. */
data class CustomProgramWarningPresentation(
    val code: ProgramConfigurationWarningCode,
    val bodyRegion: BodyRegion,
    val usableExerciseCount: Int,
    @StringRes val messageRes: Int,
    @StringRes val subjectRes: Int
)

/**
 * Everything the Custom Program screen renders.
 *
 * The state is a snapshot of one moment of editing: the draft, the persisted selection it is being
 * compared against, the current search, the deterministic family grouping, and what the domain
 * validator said about the draft as it stands. It is produced by [CustomProgramEditor] and contains no
 * behaviour beyond the few derived questions the screen asks.
 */
data class CustomProgramEditorState(
    val isOpen: Boolean = false,
    val configurationSource: ProgramConfigurationSource = ProgramConfigurationSource.DEFAULT,
    val draftEnabledExerciseIds: Set<String> = emptySet(),
    val persistedEnabledExerciseIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val families: List<CustomProgramFamilyGroup> = emptyList(),
    val errors: List<CustomProgramErrorPresentation> = emptyList(),
    val warnings: List<CustomProgramWarningPresentation> = emptyList(),
    val isResetConfirmationVisible: Boolean = false,
    val appliedConfiguration: ProgramConfiguration? = null
) {

    /** Whether the draft differs from the persisted selection. */
    val hasUnsavedChanges: Boolean
        get() = draftEnabledExerciseIds != persistedEnabledExerciseIds

    /** Whether the persisted selection is the authoritative default set. */
    val isDefaultSelection: Boolean
        get() = configurationSource == ProgramConfigurationSource.DEFAULT

    /** Whether a hard error currently stands between the draft and the stored configuration. */
    val isApplyBlocked: Boolean
        get() = errors.isNotEmpty()

    /** The families the current search shows, in their deterministic order. */
    val visibleFamilies: List<CustomProgramFamilyGroup>
        get() = families.filter { it.isVisible }

    val enabledExerciseCount: Int
        get() = draftEnabledExerciseIds.size
}

/** The outcome of an Apply, so the caller can tell a stored configuration from a rejected one. */
sealed interface CustomProgramApplyResult {

    /** The draft was validated and stored; [configuration] is what the repository now holds. */
    data class Applied(val configuration: ProgramConfiguration) : CustomProgramApplyResult

    /** The draft failed validation. Nothing was stored and the editor stays open. */
    data class Rejected(val validation: ProgramConfigurationValidation) : CustomProgramApplyResult
}
