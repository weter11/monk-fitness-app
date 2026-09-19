package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId

/**
 * What the editor's **validate** step answers, and the only thing `Save` is allowed to act on (§7).
 *
 * Validation is a step of the flow rather than a symptom of it —
 * `load/create → Draft → edit → validate → Review → Save` — because a draft is allowed to be
 * incomplete: `ProgramEditorDraft` holds blank names, no days and half-arranged plans on purpose.
 * The rules below are therefore *all* that stands between an unfinished draft and a saved revision,
 * and they are decided before anything is persisted, so an invalid draft cannot reach a DAO (§28's
 * `INVALID_DATA` class, reported as a result rather than raised).
 *
 * Two properties of this type are deliberate:
 *
 *  * **it carries findings, never repairs.** The only field is [issues]; there is no repaired plan, no
 *    suggested exercise, no re-enabled selection and no corrected prescription, so validation can
 *    never silently overwrite a user's choice (§33). The editor's answer to an invalid draft is the
 *    list of what is wrong with it.
 *  * **it is decided from the draft alone.** No repository, no library, no equipment list and no
 *    generator range takes part: a manual program's plan is free-form (§2), so the rules below are
 *    the domain's own invariants read one step earlier — the plan a revision must be able to hold
 *    (§6, §23), plus the entry points §7 lets a draft come from.
 */
data class ProgramDraftValidation(val issues: List<ProgramDraftIssue>) {

    /** Whether `Save` may persist this draft at all. */
    val isValid: Boolean
        get() = issues.isEmpty()

    companion object {

        /** A draft that may be saved. */
        val VALID: ProgramDraftValidation = ProgramDraftValidation(emptyList())

        /** The validation carrying [issues], each one once. */
        fun of(issues: List<ProgramDraftIssue>): ProgramDraftValidation =
            ProgramDraftValidation(issues.distinct())
    }
}

/**
 * One reason a draft cannot be saved.
 *
 * Typed rather than a sentence, for the same reason [ProgramOperationRefusal] is: the UI wants a
 * message, and a test (or a future analytics path) wants to know *which* rule fired without matching
 * text. Every case names what it found, so a failure message reports the value that broke the rule.
 */
sealed interface ProgramDraftIssue {

    /** The user-facing sentence. */
    val message: String

    /** A Program has a name (§1: `programId` is stable, `name` is not blank). */
    data object BlankName : ProgramDraftIssue {
        override val message: String = "a Program needs a name before it can be saved"
    }

    /**
     * A saved revision carries a plan (§6, §23).
     *
     * This is the rule that makes the draft's own emptiness significant: an unfinished plan is
     * representable in the editor and not in storage, and this is the seam between the two.
     */
    data object NoPlan : ProgramDraftIssue {
        override val message: String = "a Program needs at least one day before it can be saved"
    }

    /** Two days of one plan cannot share an identity: a day is a row of its own (§1, §23). */
    data class DuplicateDayIdentity(val programDayId: ProgramDayId) : ProgramDraftIssue {
        override val message: String =
            "two days of this plan share the identity '${programDayId.value}'; every day has its own"
    }

    /**
     * The days are numbered `1..n` in order (§6).
     *
     * A position that repeats, skips or runs backwards is not an ordering — and since a revision's
     * day numbering is what the scheduler and the session runtime index by, it is fixed before the
     * plan is saved rather than repaired afterwards.
     */
    data class DaysOutOfOrder(val positions: List<Int>) : ProgramDraftIssue {
        override val message: String =
            "the plan's days must be numbered 1..n in order, found $positions"
    }

    /**
     * A day that is not a rest day prescribes work.
     *
     * §20's rule is one-sided — *"a rest day is the one type that prescribes nothing"* — and the
     * converse is what makes the other types useful: a training, mobility or posture day with no
     * exercise would be a slot nobody can perform, so it is a draft that is not finished yet rather
     * than a plan worth saving.
     */
    data class DayWithoutWork(
        val programDayId: ProgramDayId,
        val position: Int,
        val type: ProgramDayType
    ) : ProgramDraftIssue {
        override val message: String =
            "day $position (${type.name}) plans nothing; a ${type.name} day prescribes at least one " +
                "exercise, or it is a rest day (§20)"
    }

    /**
     * A draft that edits a Program must name the revision it was opened from (§7).
     *
     * The three entry points are `create`, `copy` and `edit`, and only the third names a Program.
     * A draft naming a Program without naming the revision it started from is none of them, so it is
     * refused here rather than guessed at: guessing would mean choosing what the user's edit is
     * relative to, and a wrong guess silently overwrites a plan (§33).
     */
    data class ProgramWithoutBaseRevision(val programId: ProgramId) : ProgramDraftIssue {
        override val message: String =
            "this draft edits Program '${programId.value}' but does not name the revision it was " +
                "opened from; editing needs both, and creating a Program names neither (§7)"
    }
}

/**
 * The validation of this draft — the pure `validate` step of the editor flow.
 *
 * The issues are produced in a fixed order (the declaration order of the checks below), so the same
 * draft always reports the same list.
 */
fun ProgramEditorDraft.validation(): ProgramDraftValidation {
    val checks = listOfNotNull(
        if (name.isBlank()) ProgramDraftIssue.BlankName else null,
        if (days.isEmpty()) ProgramDraftIssue.NoPlan else null,
        duplicateDayIdentity(),
        daysOutOfOrder(),
        dayWithoutWork(),
        programWithoutBaseRevision()
    )
    return ProgramDraftValidation.of(checks)
}

private fun ProgramEditorDraft.duplicateDayIdentity(): ProgramDraftIssue? {
    val identities = days.map { it.programDayId }
    val duplicate = identities.firstOrNull { identity -> identities.count { it == identity } > 1 }
    return duplicate?.let { ProgramDraftIssue.DuplicateDayIdentity(it) }
}

private fun ProgramEditorDraft.daysOutOfOrder(): ProgramDraftIssue? {
    val positions = days.map { it.position }
    return if (positions == (1..days.size).toList()) null else ProgramDraftIssue.DaysOutOfOrder(positions)
}

private fun ProgramEditorDraft.dayWithoutWork(): ProgramDraftIssue? {
    val empty = days.firstOrNull { day -> day.type != ProgramDayType.REST && day.exercises.isEmpty() }
    return empty?.let {
        ProgramDraftIssue.DayWithoutWork(
            programDayId = it.programDayId,
            position = it.position,
            type = it.type
        )
    }
}

private fun ProgramEditorDraft.programWithoutBaseRevision(): ProgramDraftIssue? =
    if (programId != null && baseRevisionId == null) {
        ProgramDraftIssue.ProgramWithoutBaseRevision(programId)
    } else {
        null
    }
