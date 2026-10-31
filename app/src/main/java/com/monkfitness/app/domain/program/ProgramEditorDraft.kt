package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId

/**
 * One editor session's work in progress — deliberately **not** a [ProgramRevision].
 *
 * `Generate` and `Regenerate` only ever alter a draft, and `Save` is what turns a draft into a new revision
 * (or, for a no-op save, into nothing at all). The two types differ in exactly the ways that matter:
 *
 *  * a draft has **no revision identity** — there is no `revisionId` here, because nothing has been
 *    committed yet. What it carries instead is what it was opened from;
 *  * a draft may be **incomplete and untidy**: no days yet, a name the user has not typed, work still
 *    being arranged. A revision may not be any of those;
 *  * a draft is edited by producing the next draft; a revision is never edited at all.
 *
 * [programId] and [baseRevisionId] between them say which of the editor's four entry points opened this
 * draft, without a separate flag that could disagree with them: neither means create (or review an
 * import), only a base revision means copy, and both mean editing an existing Program.
 *
 * A draft holds the same *shapes* as a revision's content, because reconciliation runs on them: a
 * generated element, an element the user authored and a pinned element are distinguishable on the draft,
 * so a regenerate pass can keep the first two and replace only what neither claims. [mode] is the draft's
 * own property, and saving a draft whose mode differs from the revision it was opened from is exactly the
 * explicit mode switch a new revision records.
 *
 * [focus] and [preferredExercises] are working configuration of the same kind: stated inputs to
 * generation rather than plan content, and structural, so a save that changes either and nothing else still
 * creates a revision. `ExercisePreference.NONE` is the absence — the user named nothing, which is a fact
 * and never an implicit ranking.
 */
data class ProgramEditorDraft(
    val programId: ProgramId? = null,
    val baseRevisionId: RevisionId? = null,
    val name: String = "",
    val description: String = "",
    val mode: ProgramMode = ProgramMode.MANUAL,
    val duration: ProgramDuration = ProgramDuration.Indefinite,
    val schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3),
    val days: List<ProgramDay> = emptyList(),
    val focus: FocusPlan = FocusPlan.DEFAULT,
    val preferredExercises: ExercisePreference = ExercisePreference.NONE
) {

    /** Whether saving this draft would create a Program rather than update one. */
    val isNewProgram: Boolean
        get() = programId == null

    /** Whether this draft was opened from a saved revision (a copy, or an edit). */
    val isBasedOnASavedRevision: Boolean
        get() = baseRevisionId != null

    /** Whether this draft edits an existing Program's structure — the case that mints a revision. */
    val editsExistingProgram: Boolean
        get() = programId != null && baseRevisionId != null

    val isGenerated: Boolean
        get() = mode == ProgramMode.GENERATED
}
