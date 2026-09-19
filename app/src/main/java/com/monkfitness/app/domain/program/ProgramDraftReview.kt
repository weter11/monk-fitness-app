package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * What `Save` is about to do, decided *before* it does it — the editor's **Review** step (§7).
 *
 * The flow is `load/create → Draft → edit → validate → Review → Save`, and Review is the step that
 * makes the other four honest: it is the same computation `Save` performs (validation, and the
 * comparison of the draft's structure against the plan it will be saved against), surfaced as a value
 * instead of as a side effect. A screen renders it; a test asserts it; `Save` re-derives it. Nothing
 * in this type is a promise about the future that `Save` could contradict.
 *
 * The three questions it answers are the three a user actually has at that moment:
 *
 *  * *can I save this?* — [validation] (§7's validate step);
 *  * *what will saving produce?* — [saveKind], [targetProgramId] and [targetRevisionNumber];
 *  * *what did I change?* — [changes], in [ProgramStructureAspect] terms, computed against the
 *    structure the save will compare against. This is the shape §7's "conflicts with user choices are
 *    shown explicitly" needs: the editor reports which dimensions of the plan differ, rather than
 *    quietly being sure the user meant it.
 *
 * [dayCount], [restDayCount], [exerciseCount] and [setCount] are the plan's own totals, because a
 * Review screen states the plan in the user's terms (days, exercises, sets) rather than in rows.
 */
data class ProgramDraftReview(
    val validation: ProgramDraftValidation,
    val saveKind: ProgramDraftSaveKind,
    val targetProgramId: ProgramId?,
    val targetRevisionNumber: Int?,
    val changes: List<ProgramStructureAspect>,
    val dayCount: Int,
    val restDayCount: Int,
    val exerciseCount: Int,
    val setCount: Int
) {

    /** Whether this Save may proceed: an invalid draft never reaches a DAO (§33). */
    val isSavable: Boolean
        get() = validation.isValid

    /**
     * Whether saving creates a revision.
     *
     * Read the negative carefully, because it is the §6 rule that is easiest to lose: a draft whose
     * structure matches the plan it was opened from produces **no** revision, however much the user
     * typed in the Basics step.
     */
    val willCreateARevision: Boolean
        get() = saveKind != ProgramDraftSaveKind.NO_STRUCTURAL_CHANGE

    companion object {

        /**
         * The review of [draft], against the structure the save will compare it with.
         *
         * @param draft the draft being reviewed.
         * @param base the structure the plan will be compared against — the revision the draft was
         *   opened from for a copy, the Program's current revision for an edit, and `null` when the
         *   draft creates a Program (there is nothing to compare against, so [changes] is empty).
         * @param nextRevisionNumber the ordinal a new revision would carry, or `null` when the caller
         *   does not know it (a create always starts at 1, which needs no lookup).
         */
        fun of(
            draft: ProgramEditorDraft,
            base: ProgramStructure?,
            nextRevisionNumber: Int? = null
        ): ProgramDraftReview {
            val structure = draft.structure
            val saveKind = saveKindOf(draft, structure, base)
            return ProgramDraftReview(
                validation = draft.validation(),
                saveKind = saveKind,
                targetProgramId = draft.programId,
                targetRevisionNumber = when (saveKind) {
                    ProgramDraftSaveKind.NO_STRUCTURAL_CHANGE -> null
                    ProgramDraftSaveKind.CREATES_REVISION -> nextRevisionNumber
                    ProgramDraftSaveKind.CREATES_PROGRAM,
                    ProgramDraftSaveKind.CREATES_COPY -> ProgramRevision.FIRST_REVISION_NUMBER
                },
                changes = if (base == null) emptyList() else structure.differencesFrom(base),
                dayCount = structure.dayCount,
                restDayCount = structure.days.count { !it.isWorkDay },
                exerciseCount = structure.exerciseCount,
                setCount = structure.setCount
            )
        }

        private fun saveKindOf(
            draft: ProgramEditorDraft,
            structure: ProgramStructure,
            base: ProgramStructure?
        ): ProgramDraftSaveKind = when {
            // Editing an existing Program: a revision only if the plan itself changed. A rename, a
            // description or a start date are facts about the Program, not about its structure (§6).
            draft.editsExistingProgram ->
                if (base != null && structure == base) {
                    ProgramDraftSaveKind.NO_STRUCTURAL_CHANGE
                } else {
                    ProgramDraftSaveKind.CREATES_REVISION
                }
            // A copy is a *new Program* even when its plan is byte-identical to its source: copying
            // is not a no-op, it is the creation of a Program the user owns (§4).
            draft.isBasedOnASavedRevision -> ProgramDraftSaveKind.CREATES_COPY
            else -> ProgramDraftSaveKind.CREATES_PROGRAM
        }
    }
}

/**
 * What saving a draft produces (§27's `Save Editor`).
 *
 * The distinction between the first case and the other two is the whole revision mechanism in one
 * type: a structural difference is a **new immutable revision** with a new identity, and anything
 * else leaves the revisions alone.
 */
enum class ProgramDraftSaveKind {

    /** A new Program with its first revision. */
    CREATES_PROGRAM,

    /** A new Program, with its first revision, copied from the revision the draft was opened from. */
    CREATES_COPY,

    /** A new revision of an existing Program. */
    CREATES_REVISION,

    /** The plan did not change, so no revision is created — only the Program's own facts may be. */
    NO_STRUCTURAL_CHANGE
}
