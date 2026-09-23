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
 * §16's chain is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI state ⇄ Composable`, and these are the
 * last two links: every type here is built by [ProgramsController] from the *domain* values the
 * application services return and from nothing else. No entity, no DAO, no repository and no mapper
 * appears in this file, and the two `String` fields that exist (`programId`, `exerciseId`) are the
 * domain's own opaque identities, kept as strings because a navigation argument is an identifier and
 * not an object.
 *
 * What is deliberately *not* here is as much a part of the design as what is. There is no analytics
 * measure beyond the counts §21 already computes, no adaptive verdict — the target tree has no
 * persisted family classification or progression ladder, so there is nothing to summarise and nothing
 * is invented to fill a card (§30 step 12's recorded gap) — and no second progress model: Progress and
 * History keep their own screens (§22).
 */

/**
 * Whether one exercise option answers the picker's search [query] — the rule itself, as a pure
 * function the JVM tests can decide without a Compose harness.
 *
 * The match is on the **user's own display name** first (the localized label resolved for this
 * locale — searching `Flexion` in Spanish must find the exercise a Spanish reader calls that) and on
 * the **stable exercise id** second, because an id is a legitimate thing to paste and §10 keeps ids
 * opaque but real. Matching is case-insensitive **and diacritic-insensitive** — the reader who types
 * `Flexion` is not expected to reproduce `Flexión`'s accent — the query is trimmed; a blank query
 * matches everything, which is the unfiltered list rather than a match.
 *
 * The fold is `NFD` decomposition minus the combining marks, applied identically to the needle and
 * both haystacks, so whatever locale's text is being compared is transformed the same way on both
 * sides and the comparison stays consistent under any device locale.
 *
 * @param query what the user typed, unmodified.
 * @param exerciseId the option's stable id (§10: opaque in both directions).
 * @param displayName the option's localized display name, already resolved by the screen — a pure
 *   function may not resolve a string resource, and the screen already has it for rendering.
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
    /** The library id a plan element stores (§10: opaque in both directions). */
    val exerciseId: String,
    /** The localized display name, or `0` when the id is not in the catalogue. */
    val nameRes: Int,
    /** The catalogue's own family id, carried for grouping rather than for a plan decision. */
    val familyId: String,
    /** Whether the catalogue records this exercise as timed, which decides its prescription dimension. */
    val isTimerBased: Boolean
)

/** One row of My Programs (§21): a saved Program with the state the list shows. */
data class ProgramRowUi(
    val programId: String,
    val name: String,
    val source: ProgramSource,
    val lifecycleStatus: LifecycleStatus,
    /** Whether the user filed it away. Not a lifecycle state, and shown separately (§3, §29). */
    val isArchived: Boolean,
    /** Whether a pause interval is in effect right now (§3). */
    val hasOpenPause: Boolean,
    /** Whether this is the one global selection. Mutually exclusive across the rows. */
    val isSelected: Boolean,
    /** Whether the §4 copy-before-edit and no-delete rules apply to it. */
    val isBuiltIn: Boolean,
    val plannedStartDate: LocalDate?
)

/** One attempt as the detail screen's recent-workouts list shows it (§22). */
data class ProgramHistoryRowUi(
    val plannedFor: LocalDate,
    val status: SessionStatus,
    val performedSets: Int,
    val exposedExercises: Int
)

/** Program Detail: §22's current-state management view, and nothing more. */
data class ProgramDetailUi(
    val row: ProgramRowUi,
    val mode: ProgramMode,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    /** Which revision the Program's pointer names, human-readable only (§23). */
    val revisionNumber: Int,
    val dayCount: Int,
    val restDayCount: Int,
    val exerciseCount: Int,
    /** The next date the Scheduler would plan for, or `null` when it has none to give. */
    val nextOpportunity: LocalDate?,
    /** Whether the revision is over: no date at or after today (§20). */
    val hasNoFutureDate: Boolean,
    /**
     * Whether the Scheduler's answer could not be read at all — a storage failure or invalid persisted data
     * (§28's `SYSTEM_FAILURE`), which is **not** the same fact as "there is no next date" (§15, §33).
     */
    val nextWorkoutUnreadable: Boolean,
    val completed: Int,
    val missed: Int,
    val upcoming: Int,
    val recentWorkouts: List<ProgramHistoryRowUi>,
    /** §3's next-Program fact, as far as the application layer exposes it on a list read. */
    val hasNextProgram: Boolean
) {

    /** Whether the §3 select action has anything to do: selecting the selection changes nothing. */
    val isSelectable: Boolean
        get() = !row.isSelected

    /** Whether the §4 delete action is offered at all. The rule itself stays in the lifecycle layer. */
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

/** One plan day while a draft is being edited. */
data class ProgramDraftDayUi(
    val programDayId: String,
    val position: Int,
    val type: ProgramDayType,
    val name: String?,
    val elements: List<ProgramDraftElementUi>
)

/** One plan element while a draft is being edited. */
data class ProgramDraftElementUi(
    val programExerciseId: String,
    val exerciseId: String,
    /** The catalogue's label, or `0` when the catalogue does not know the id. */
    val nameRes: Int,
    val dimension: PrescriptionDimension,
    /** The unit this element is prescribed in: repetitions, or seconds when it is timed. */
    val sets: Int,
    val targetPerSet: Int,
    val isPinned: Boolean
)

/**
 * The §7 **Review** step, presented: what saving the draft would do, decided by
 * [com.monkfitness.app.domain.usecase.ProgramEditorService.review] rather than by this layer.
 *
 * It is `null` until the user asks for it, and any further edit clears it — a review describes the
 * draft it was computed from, and showing a stale one would be a sentence about a plan that no longer
 * exists.
 */
data class ProgramDraftReviewUi(
    val saveKind: ProgramDraftSaveKind,
    /** Whether saving would create a revision (§6's answer, never the UI's). */
    val willCreateARevision: Boolean,
    val isSavable: Boolean,
    /** The ordinal a new revision would carry, when one is created. */
    val revisionNumber: Int?,
    /** Which aspects of the structure differ from the plan the save is measured against. */
    val changeRes: List<Int>,
    val dayCount: Int,
    val restDayCount: Int,
    val exerciseCount: Int,
    val setCount: Int
) {

    /** Whether saving this draft would create a Program rather than change one. */
    val createsProgram: Boolean
        get() = saveKind == ProgramDraftSaveKind.CREATES_PROGRAM ||
            saveKind == ProgramDraftSaveKind.CREATES_COPY
}

/**
 * Which of §7's editor entry points opened the draft, as the draft itself decides it: a draft naming
 * neither a Program nor a base revision creates one, a draft naming a base revision but no Program
 * copies one, and a draft naming both edits one.
 */
enum class ProgramDraftEntry {

    /** `Build it myself` / `Build for me` on a Program that does not exist yet. */
    CREATE,

    /** `Copy` of an existing Program: the copy is created when it is saved (§4). */
    COPY,

    /** A structural edit of an existing Program: a save creates a revision, or nothing (§6). */
    EDIT
}

/**
 * The draft the editor screen renders — a *presentation* of
 * [com.monkfitness.app.domain.program.ProgramEditorDraft], built by the controller from the domain
 * value and never assembled by a Composable (§16: the UI does not build drafts, it displays them).
 *
 * The findings are string resources rather than the domain's own sentences: the domain's messages are
 * developer-facing English, and §14 requires every word the user reads to come from a resource.
 */
data class ProgramDraftUi(
    val entry: ProgramDraftEntry,
    val mode: ProgramMode,
    val name: String,
    val description: String,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    val days: List<ProgramDraftDayUi>,
    /** Whether the draft could be saved right now, as `validate` answered it. */
    val isValid: Boolean,
    /** The findings, as message resources, in the domain's own order. */
    val issueRes: List<Int>,
    /** The review, once the user has asked for one; `null` before that and after any further edit. */
    val review: ProgramDraftReviewUi? = null
) {

    /** How many sets the draft plans in total, for the review line. */
    val setCount: Int
        get() = days.sumOf { day -> day.elements.sumOf { element -> element.sets } }

    /** How many plan elements the draft holds, over every day. */
    val elementCount: Int
        get() = days.sumOf { day -> day.elements.size }
}

/**
 * The Import Review step (§5's *Import Draft* presented to the user, and §30 step 14's date choice).
 *
 * The plan summary is the *draft's* own content — the file as it would be saved — and the two choices
 * below it are the user's, held here until the confirmation that writes them (§2 of this stage):
 *
 * ```text
 * plannedStartDate   the date the imported Program is planned to start on. Defaults to today.
 * makeActive         §5's checkbox, default OFF.
 * ```
 *
 * Neither is serialized and neither has a second home: the document the file describes is unchanged,
 * and the two values travel with the confirmation call into the import transaction.
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

    /** Whether the reviewed plan holds at least one day, which a savable plan must (§7). */
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
    /** Whether a read is in flight. */
    val loading: Boolean = false,
    val rows: List<ProgramRowUi> = emptyList(),
    val selectedProgramId: String? = null,
    val hasNextProgram: Boolean = false,
    /** The open Program Detail, or `null` when the detail screen is not open. */
    val detail: ProgramDetailUi? = null,
    /** The open editor draft, or `null` when the editor screen is not open. */
    val draft: ProgramDraftUi? = null,
    /**
     * The planned start date the user explicitly chose for a **new** Program in the open editor
     * session, or `null` when they chose none — the creation request's own fact (§3, §6), held beside
     * [draft] rather than inside it: the draft is structure, and choosing a date must never create a
     * Revision. `null` is not *no date* — it is *no exact choice*, which the Save resolves to today
     * from the injected clock at Save time.
     */
    val draftPlannedStartDate: LocalDate? = null,
    /**
     * The reviewed import, held from the review step to the confirmation.
     *
     * It is the pipeline's own `ProgramImportDraft` — the value the import service produced and the
     * value its save consumes — so the confirmation hands back exactly what was reviewed, and the
     * screen cannot substitute a draft of its own (§7 of the transfer document: the draft's
     * constructor is internal).
     */
    val importDraft: ProgramImportDraft? = null,
    val importReview: ProgramImportReviewUi? = null,
    /** The catalogue the plan editor offers, read once per editor session. */
    val exerciseOptions: List<ExerciseOptionUi> = emptyList(),
    /** The last operation's outcome. The screens show it and clear it. */
    val notice: ProgramNotice? = null
) {

    /** The rows the list shows: the archived ones are filed away, not deleted (§29). */
    val activeRows: List<ProgramRowUi>
        get() = rows.filterNot { it.isArchived }

    /** The archived rows, shown separately so "archived" is never confused with "gone" (§3, §29). */
    val archivedRows: List<ProgramRowUi>
        get() = rows.filter { it.isArchived }

    /** Whether anything is saved at all — the empty state's condition. */
    val isEmpty: Boolean
        get() = rows.isEmpty()
}
