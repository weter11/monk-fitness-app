package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDraftSaveKind
import com.monkfitness.app.domain.program.transfer.ProgramImportDraft
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.workout.SessionStatus
import java.time.LocalDate

/**
 * The values the Program screens render.
 *
 * Every type here is built by [ProgramsController] from the *domain* values the application services
 * return and from nothing else: no entity, no DAO, no repository and no mapper appears in this file, and
 * the `String` identities (`programId`, `exerciseId`) are the domain's own opaque ids, kept as strings
 * because a navigation argument is an identifier and not an object.
 *
 * What is deliberately *not* here matters as much: no analytics measure beyond the counts the Progress
 * layer already computes, no adaptive verdict — there is no persisted family classification or progression
 * ladder to summarise, and nothing is invented to fill a card — and no second progress model.
 */

/**
 * Whether one exercise option answers the picker's search [query] — the rule itself, as a pure function
 * the JVM tests can decide without a Compose harness.
 *
 * The match is on the **user's own display name** first (searching `Flexion` in Spanish must find the
 * exercise a Spanish reader calls that) and on the **stable exercise id** second, because an id is a
 * legitimate thing to paste. Matching is case-insensitive **and diacritic-insensitive** — the reader who
 * types `Flexion` is not expected to reproduce `Flexión`'s accent — and the query is trimmed; a blank query
 * matches everything, which is the unfiltered list rather than a match.
 *
 * The fold is `NFD` decomposition minus the combining marks, applied identically to the needle and both
 * haystacks, so the comparison stays consistent under any device locale.
 */
fun matchesExerciseQuery(query: String, exerciseId: String, displayName: String): Boolean {
    val needle = query.trim().searchable()
    if (needle.isEmpty()) return true
    return displayName.searchable().contains(needle) ||
        exerciseId.searchable().contains(needle)
}

/** Lower-cased and stripped of diacritics — one fold, applied to every side of a search comparison. */
private fun String.searchable(): String = java.text.Normalizer
    .normalize(this, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase()

/** One exercise the plan editor may add: the id the plan stores and the label the UI shows. */
data class ExerciseOptionUi(
    val exerciseId: String,
    /** The localized display name, or `0` when the id is not in the catalogue. */
    val nameRes: Int,
    /** The catalogue's own family id, carried for grouping rather than for a plan decision. */
    val familyId: String,
    /** Whether the catalogue records this exercise as timed, which decides its prescription dimension. */
    val isTimerBased: Boolean
)

/** One row of My Programs: a saved Program with the state the list shows. */
data class ProgramRowUi(
    val programId: String,
    val name: String,
    val source: ProgramSource,
    val lifecycleStatus: LifecycleStatus,
    /** Whether the user filed it away. Not a lifecycle state, and shown separately. */
    val isArchived: Boolean,
    val hasOpenPause: Boolean,
    /** Whether this is the one global selection. Mutually exclusive across the rows. */
    val isSelected: Boolean,
    /** Whether the copy-before-edit and no-delete rules apply to it. */
    val isBuiltIn: Boolean,
    val plannedStartDate: LocalDate?
)

/** One attempt as the detail screen's recent-workouts list shows it. */
data class ProgramHistoryRowUi(
    val plannedFor: LocalDate,
    val status: SessionStatus,
    val performedSets: Int,
    val exposedExercises: Int
)

/** Program Detail: the current-state management view, and nothing more. */
data class ProgramDetailUi(
    val row: ProgramRowUi,
    val mode: ProgramMode,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    /** Which revision the Program's pointer names, human-readable only. */
    val revisionNumber: Int,
    val dayCount: Int,
    val restDayCount: Int,
    val exerciseCount: Int,
    /** The next date the Scheduler would plan for, or `null` when it has none to give. */
    val nextOpportunity: LocalDate?,
    /** Whether the revision is over: no date at or after today. */
    val hasNoFutureDate: Boolean,
    /**
     * Whether the Scheduler's answer could not be read at all — a storage failure or invalid persisted data,
     * which is **not** the same fact as "there is no next date".
     */
    val nextWorkoutUnreadable: Boolean,
    val completed: Int,
    val missed: Int,
    val upcoming: Int,
    val recentWorkouts: List<ProgramHistoryRowUi>,
    /** The next-Program fact, as far as the application layer exposes it on a list read. */
    val hasNextProgram: Boolean
) {

    /** Whether the select action has anything to do: selecting the selection changes nothing. */
    val isSelectable: Boolean
        get() = !row.isSelected

    /** Whether the delete action is offered at all. The rule itself stays in the lifecycle layer. */
    val isDeletable: Boolean
        get() = !row.isBuiltIn

    /**
     * Whether the screen shows the ordinary *"nothing is planned yet"* line.
     *
     * It is false whenever the Scheduler's answer could not be read, because a failure is not an absence:
     * the detail says the schedule could not be read instead, so the two are never rendered as one state.
     */
    val showsNoPlannedDate: Boolean
        get() = !nextWorkoutUnreadable && nextOpportunity == null && !hasNoFutureDate
}

data class ProgramDraftDayUi(
    val programDayId: String,
    val position: Int,
    val type: ProgramDayType,
    val name: String?,
    val elements: List<ProgramDraftElementUi>
)

data class ProgramDraftElementUi(
    val programExerciseId: String,
    val exerciseId: String,
    /** The catalogue's label, or `0` when the catalogue does not know the id. */
    val nameRes: Int,
    val dimension: PrescriptionDimension,
    val sets: Int,
    val targetPerSet: Int,
    val isPinned: Boolean
)

/**
 * The Review step, presented: what saving the draft would do, decided by
 * [com.monkfitness.app.domain.usecase.ProgramEditorService.review] rather than by this layer.
 *
 * It is `null` until the user asks for it, and any further edit clears it — a review describes the draft it
 * was computed from, and showing a stale one would be a sentence about a plan that no longer exists.
 */
data class ProgramDraftReviewUi(
    val saveKind: ProgramDraftSaveKind,
    /** Whether saving would create a revision — the domain's answer, never the UI's. */
    val willCreateARevision: Boolean,
    val isSavable: Boolean,
    val revisionNumber: Int?,
    /** Which aspects of the structure differ from the plan the save is measured against. */
    val changeRes: List<Int>,
    val dayCount: Int,
    val restDayCount: Int,
    val exerciseCount: Int,
    val setCount: Int
) {

    val createsProgram: Boolean
        get() = saveKind == ProgramDraftSaveKind.CREATES_PROGRAM ||
            saveKind == ProgramDraftSaveKind.CREATES_COPY
}

/**
 * Which editor entry point opened the draft, as the draft itself decides it: a draft naming neither a
 * Program nor a base revision creates one, one naming a base revision but no Program copies one, and one
 * naming both edits one.
 */
enum class ProgramDraftEntry {

    /** `Build it myself` / `Build for me` on a Program that does not exist yet. */
    CREATE,

    /** `Copy` of an existing Program: the copy is created when it is saved. */
    COPY,

    /** A structural edit of an existing Program: a save creates a revision, or nothing. */
    EDIT
}

/**
 * The draft the editor screen renders — a *presentation* of
 * [com.monkfitness.app.domain.program.ProgramEditorDraft], built by the controller from the domain value
 * and never assembled by a Composable.
 *
 * The findings are string resources rather than the domain's own sentences, because the domain's messages
 * are developer-facing English.
 */
data class ProgramDraftUi(
    val entry: ProgramDraftEntry,
    val mode: ProgramMode,
    val name: String,
    val description: String,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    val days: List<ProgramDraftDayUi>,
    /**
     * The draft's Goals & Focus configuration, handed over unchanged as the domain's
     * [com.monkfitness.app.domain.program.FocusPlan]: the screen displays it and edits it by handing back a
     * new value, so an editable copy here would be a second source of truth that could disagree with the
     * draft it claims to show.
     */
    val focus: com.monkfitness.app.domain.program.FocusPlan,
    /**
     * The draft's **exercise preference**, most preferred first.
     *
     * The domain's [com.monkfitness.app.domain.program.ExercisePreference], for the same reason [focus] is:
     * a copy of the list here would be a second editable source of truth, and a reordered entry would be
     * the one thing a user could change without the next generation ever seeing it.
     */
    val preferredExercises: com.monkfitness.app.domain.program.ExercisePreference =
        com.monkfitness.app.domain.program.ExercisePreference.NONE,
    /** Whether the draft could be saved right now, as `validate` answered it. */
    val isValid: Boolean,
    /** The findings, as message resources, in the domain's own order. */
    val issueRes: List<Int>,
    /** The review, once the user has asked for one; `null` before that and after any further edit. */
    val review: ProgramDraftReviewUi? = null
) {

    val setCount: Int
        get() = days.sumOf { day -> day.elements.sumOf { element -> element.sets } }

    val elementCount: Int
        get() = days.sumOf { day -> day.elements.size }
}

/**
 * The Import Review step: the Import Draft presented to the user, plus the date choice and the
 * make-active checkbox, which default to today and OFF.
 *
 * The plan summary is the *draft's* own content — the file as it would be saved — and the two choices are
 * the user's. Neither is serialized and neither has a second home: the document the file describes is
 * unchanged, and the two values travel with the confirmation call into the import transaction.
 */
data class ProgramImportReviewUi(
    val name: String,
    val description: String,
    val dayCount: Int,
    val exerciseCount: Int,
    val setCount: Int,
    val plannedStartDate: LocalDate,
    val makeActive: Boolean
) {

    /** Whether the reviewed plan holds at least one day, which a savable plan must. */
    val hasDays: Boolean
        get() = dayCount > 0
}

/**
 * The whole Program UI's state, as one value.
 *
 * One state object rather than several flows, because the screens are a *flow*: the list feeds the
 * detail, the detail opens the editor, and the import review is entered from the list and left for it.
 * Keeping them together makes "the list was refreshed after the delete" a property of a single
 * assignment rather than of two flows that can interleave.
 */
data class ProgramsUiState(
    val loading: Boolean = false,
    val rows: List<ProgramRowUi> = emptyList(),
    val selectedProgramId: String? = null,
    val hasNextProgram: Boolean = false,
    /** The open Program Detail, or `null` when the detail screen is not open. */
    val detail: ProgramDetailUi? = null,
    /** The open editor draft, or `null` when the editor screen is not open. */
    val draft: ProgramDraftUi? = null,
    /**
     * The planned start date the user explicitly chose for a **new** Program in the open editor session, or
     * `null` when they chose none — held beside [draft] rather than inside it, because the draft is
     * structure and choosing a date must never create a Revision. `null` is not *no date* but *no exact
     * choice*, which Save resolves to today from the injected clock.
     */
    val draftPlannedStartDate: LocalDate? = null,
    /**
     * The reviewed import, held from the review step to the confirmation.
     *
     * It is the pipeline's own `ProgramImportDraft` — the value the import service produced and the value
     * its save consumes — so the confirmation hands back exactly what was reviewed, and the screen cannot
     * substitute a draft of its own.
     */
    val importDraft: ProgramImportDraft? = null,
    val importReview: ProgramImportReviewUi? = null,
    /** The catalogue the plan editor offers, read once per editor session. */
    val exerciseOptions: List<ExerciseOptionUi> = emptyList(),
    /**
     * The **Preview** of a generation pass, presented — `null` when there is none.
     *
     * A *temporary operation result*, not a second source of truth and not a second draft: the working draft
     * is [draft], and nothing here can be edited, saved or persisted. It is `null` before the user asks for
     * one, `null` the moment they use it, and `null` again after any change to the draft it was built from,
     * because a preview of a draft that no longer exists is exactly the answer the screens must not present.
     */
    val generationPreview: ProgramGenerationPreviewUi? = null,
    /** The last operation's outcome. The screens show it and clear it. */
    val notice: ProgramNotice? = null
) {

    /** The rows the list shows: the archived ones are filed away, not deleted. */
    val activeRows: List<ProgramRowUi>
        get() = rows.filterNot { it.isArchived }

    /** The archived rows, shown separately so "archived" is never confused with "gone". */
    val archivedRows: List<ProgramRowUi>
        get() = rows.filter { it.isArchived }

    val isEmpty: Boolean
        get() = rows.isEmpty()
}
