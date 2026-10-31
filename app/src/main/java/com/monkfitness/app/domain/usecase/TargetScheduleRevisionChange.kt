package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId

/**
 * What a caller states about the **target scheduling semantics** of a Program's new revision, on an
 * *existing* Program: [Keep] the current source, [Replace] it, or [Clear] it.
 *
 * An omitted authoring writes no row, so a Program whose current revision stated a source silently lost
 * it on the next edit: one `null` stood for three claims (*keep what is there*, *state nothing*, *there
 * was never anything*), so no honest default could be stated.
 *
 * [Clear] is not an empty [TargetScheduleAuthoring] — an authoring with no rule is refused, because a
 * revision with no rules cannot produce an occurrence — so "state nothing" and "state that it has none"
 * stay apart. The create side is a different parameter with a different absence: `null` there still
 * means *no source was ever stated*.
 */
sealed interface TargetScheduleRevisionChange {

    /**
     * The caller is not changing target scheduling: the current source is carried forward onto the new
     * revision, its bindings re-identified by the editor; on a target-only operation no revision is minted.
     */
    data object Keep : TargetScheduleRevisionChange

    /**
     * A whole new source, attached to the revision this operation creates — never to the one it replaces.
     * Its authoring names plan days by the **drafted** handle: every plan day is re-identified on a save.
     */
    data class Replace(val authoring: TargetScheduleAuthoring) : TargetScheduleRevisionChange

    /**
     * The new revision states no source. Nothing is deleted: the replaced revision keeps its own source.
     */
    data object Clear : TargetScheduleRevisionChange
}

/**
 * Typed refusals for a statement that does not apply to the operation it was given to. Both are raised
 * inside the save boundary's transaction, so each rolls the operation back rather than leaving a revision
 * saved with a statement it never accepted.
 */
sealed class TargetScheduleRevisionChangeException(message: String) :
    IllegalArgumentException(message) {

    /**
     * An edit statement was given to a creation, which has no previous revision to keep or clear: it
     * states its first source with an authoring, or states nothing.
     */
    data class EditChangeOnACreation(val change: TargetScheduleRevisionChange) :
        TargetScheduleRevisionChangeException(
            "a Program that does not exist yet cannot keep or clear a target source: $change is an " +
                "edit statement, and a creation states its first target source with an authoring"
        )

    /**
     * A creation authoring was given to an edit, which states its target scheduling exactly once — through
     * Replace / Clear / Keep — rather than through the `null` that meant three different things.
     */
    data object CreationAuthoringOnAnEdit : TargetScheduleRevisionChangeException(
        "an edit states its target scheduling with Keep / Replace / Clear, not with a creation " +
            "authoring; the two absences are different claims and are not interchangeable"
    )

    /**
     * A structural edit asked to carry the source forward, and that source is stored but not readable.
     *
     * Minting a revision that states no source would turn "states something I cannot parse" into "states
     * nothing" — a claim storage never made. The save is refused: the Program keeps its revision and the
     * unreadable rows stay. `Replace` or `Clear` is the caller's way forward, which is what makes `Keep`
     * distinguishable from them.
     */
    data class KeepOverUnreadableStoredSource(
        val revisionId: RevisionId,
        val reason: String
    ) : TargetScheduleRevisionChangeException(
        "the target schedule source stored for revision '${revisionId.value}' is not readable, so it " +
            "cannot be carried forward: $reason. State Replace with the target source you mean, or " +
            "Clear to state that this Program's next revision has none — the stored source itself is " +
            "left exactly as it is"
    )

    /**
     * An explicit **Clear** over a stored-but-unreadable source. Over a revision that states no source it
     * is the same claim already made; over unreadable rows, answering `NothingToChange` would read
     * unreadable data as absence, and superseding the revision would destroy whatever the rows say without
     * anyone deciding to. So it is refused and the current revision stays current.
     */
    data class ClearOverUnreadableStoredSource(
        val revisionId: RevisionId,
        val reason: String
    ) : TargetScheduleRevisionChangeException(
        "the target schedule source stored for revision '${revisionId.value}' is not readable, so it " +
            "cannot be cleared: $reason. Unreadable persisted data is not the same fact as no target " +
            "source, and Clear is never answered by treating it as one"
    )
}

/**
 * The authoring a stored [TargetScheduleSource] states, over the plan days a **draft** carries: the read
 * side of the authoring boundary, and the only honest way to carry a previous revision's source forward.
 *
 * A draft opened from a revision carries that revision's plan-day identities as its own handles (it copies
 * the plan, it does not re-identify it), so the stored identity *is* the drafted handle and the
 * correspondence is a membership question. A binding whose day the draft does not carry is **refused**
 * rather than pointed at another day: only the caller can say which day the workout presents now.
 */
fun TargetScheduleSource.asAuthoringOver(
    draftedProgramDays: Set<ProgramDayId>
): TargetScheduleAuthoring = TargetScheduleAuthoring(
    rules = rules,
    programDayBindings = programDayBindings.map { binding ->
        if (binding.programDayId !in draftedProgramDays) {
            throw TargetScheduleAuthoringException.StoredProgramDayNotInTheDraft(
                workoutId = binding.workoutId,
                programDayId = binding.programDayId
            )
        }
        TargetScheduleAuthoringBinding(
            workoutId = binding.workoutId,
            draftedProgramDayId = binding.programDayId
        )
    }
)

/**
 * Do two authorings state the **same target scheduling semantics** over the same drafted handles?
 *
 * [TargetScheduleAuthoring.equals] would be wrong: every save mints a fresh `ProgramDayId` for every day,
 * so a semantically identical source always differs by identity once attached, and an identity comparison
 * would mint a revision on every save of an unchanged schedule. The comparison is made **before** the
 * statement is attached, over the handles the caller named.
 *
 * Order-insensitivity is a decision: a whole-value guard inherits its definition of "identical" from
 * whatever `ORDER BY` the read used, so re-presenting the same rules in another order would be refused as
 * a *conflicting* source. Here the domain value decides that rule order is not part of the claim.
 */
fun TargetScheduleAuthoring.statesTheSameTargetSemanticsAs(
    other: TargetScheduleAuthoring
): Boolean {
    if (rules.size != other.rules.size || programDayBindings.size != other.programDayBindings.size) {
        return false
    }
    if (rules.associateBy { it.ruleId } != other.rules.associateBy { it.ruleId }) return false
    return programDayBindings.associateBy { it.workoutId } ==
        other.programDayBindings.associateBy { it.workoutId }
}
