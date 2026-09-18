package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId

/**
 * One editor session's work in progress — deliberately **not** a [ProgramRevision].
 *
 * The editor is always draft-first (§6, §7): `Generate` and `Regenerate` only ever alter a draft,
 * and `Save` is what turns a draft into a new revision (or, for a no-op save, into nothing at all).
 * The two types therefore differ in exactly the ways that matter:
 *
 *  * a draft has **no revision identity** — there is no `revisionId` here, because nothing has been
 *    committed yet. What it carries instead is what it was opened from;
 *  * a draft may be **incomplete and untidy**: no days yet, a name the user has not typed, work that
 *    is still being arranged. A revision may not be any of those;
 *  * a draft is edited by producing the next draft; a revision is never edited at all.
 *
 * [programId] and [baseRevisionId] between them say which of the editor's four entry points (§7)
 * opened this draft, without needing a separate flag that could disagree with them:
 *
 * ```text
 * programId = null, baseRevisionId = null   → create (or review an import)
 * programId = null, baseRevisionId != null  → copy
 * programId != null, baseRevisionId != null → edit an existing Program
 * ```
 *
 * A draft holds the same *shapes* as a revision's content ([ProgramDay] with [ProgramExercise] plan
 * elements), because reconciliation runs on them: a generated element, an element the user authored
 * and a pinned element are distinguishable on the draft, so a regenerate pass can keep the first two
 * and replace only what neither of them claims (§7). Which mode the draft is in ([mode]) is the
 * draft's own property, and saving a draft whose mode differs from the revision it was opened from
 * is exactly the explicit mode switch that a new revision records (§2).
 *
 * @property programId the Program being edited, or `null` when this draft will create a new one.
 * @property baseRevisionId the revision the draft started from, or `null`.
 * @property name the working name; may still be blank while the user is typing.
 * @property description the working description; may be empty.
 * @property mode the mode the draft is being edited in.
 * @property duration the working duration.
 * @property schedule the working schedule.
 * @property days the working plan, in the user's arrangement.
 */
data class ProgramEditorDraft(
    val programId: ProgramId? = null,
    val baseRevisionId: RevisionId? = null,
    val name: String = "",
    val description: String = "",
    val mode: ProgramMode = ProgramMode.MANUAL,
    val duration: ProgramDuration = ProgramDuration.Indefinite,
    val schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3),
    val days: List<ProgramDay> = emptyList()
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

    /** Whether the draft is a generated plan being edited. */
    val isGenerated: Boolean
        get() = mode == ProgramMode.GENERATED
}
