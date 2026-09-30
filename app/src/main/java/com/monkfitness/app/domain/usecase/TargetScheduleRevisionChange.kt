package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId

/**
 * What a caller states about the **target scheduling semantics** of a Program's revision, when the
 * statement is about an *existing* Program.
 *
 * ```text
 * structural change + Keep      → new Revision, previous source carried forward
 * structural change + Replace   → new Revision, the new source attached to it
 * structural change + Clear     → new Revision that states no target source
 * target-only Replace           → new Revision, same Program, same structure, new source
 * target-only Clear             → new Revision that states no target source
 * target-only Keep              → nothing: no Revision
 * ```
 *
 * ### Why this value exists, and why it is not `null`
 *
 * Stage 19 established that target scheduling is a caller-owned value applied at the save boundary,
 * and that an omitted authoring writes no row — so an existing Program whose current revision
 * *already states* a source silently lost it the next time the user edited a day. That is the defect
 * this value removes: the absent case was one `null` standing for three different claims (*keep what
 * is there*, *state nothing*, and *there was never anything*), so the honest default could not be
 * stated at all.
 *
 * ### The three cases, kept apart
 *
 *  * [Keep] — the caller is not talking about target scheduling. The current revision's source is
 *    carried forward onto whatever revision the save mints, re-identified through the editor's own
 *    correspondence. On a target-only operation it means *nothing changed*, so no revision is minted.
 *  * [Replace] — the caller states a whole new source. It is attached to the new revision only.
 *  * [Clear] — the caller states that this Program's next revision has **no** target source. This is
 *    not an empty [TargetScheduleAuthoring]: an authoring that states no rule is refused
 *    ([TargetScheduleAuthoringException.NoRulesStated]) because a revision with no rules cannot
 *    produce an occurrence, so it is not a storable source. "State nothing" and "state that it has
 *    none" are different claims, and [Clear] is the one that is representable.
 *
 * ### What it is not
 *
 * It is not a lifecycle state, it does not name a Program, and it decides nothing about *when* a
 * workout recurs. It is a statement about one revision, applied by
 * [ProgramSaveService] and refused by [TargetScheduleSourceRepository] under the typed refusals
 * Stage 18 already owns.
 *
 * The **create** side is deliberately a different parameter with a different absence:
 * [ProgramSaveService.save]'s `targetSchedule` is an authoring or `null`, and `null` on a creation
 * still means *no source was ever stated* ([TargetScheduleSourceRead.Missing]). Edit semantics are
 * not applied to a creation, and a creation's authoring is not an edit's [Keep].
 */
sealed interface TargetScheduleRevisionChange {

    /**
     * The caller is not changing target scheduling.
     *
     * On a structural edit the current revision's source is carried forward onto the new revision
     * with its bindings re-identified through the editor's minted day correspondence; on a
     * target-only operation this means the caller asked for a revision that is not warranted, so
     * none is minted and the Program keeps its current plan.
     */
    data object Keep : TargetScheduleRevisionChange

    /**
     * The caller states a whole new target source, which is attached to the revision this operation
     * creates — never to the revision it replaces.
     *
     * @property authoring the caller's own rules and bindings, naming plan days by the **drafted**
     *   handle of the revision the operation is about. §6 re-identifies every plan day on every
     *   save, so a handle cannot be a stored `ProgramDayId` of the revision being replaced; the
     *   editor is the single component that performs that re-identification and the one that
     *   reports the correspondence this value is re-pointed through.
     */
    data class Replace(val authoring: TargetScheduleAuthoring) : TargetScheduleRevisionChange

    /**
     * The caller states that the revision this operation creates has no target source at all.
     *
     * Nothing is deleted and nothing is mutated: the revision this operation replaces keeps its own
     * source byte-for-byte, and the new revision reads as [TargetScheduleSourceRead.Missing].
     */
    data object Clear : TargetScheduleRevisionChange
}

/**
 * Typed refusals for a target scheduling statement that does not apply to the operation it was given
 * to.
 *
 * Both are raised from inside the save boundary's own transaction, so each rolls the operation back
 * rather than leaving a revision saved with a statement it never accepted.
 */
sealed class TargetScheduleRevisionChangeException(message: String) :
    IllegalArgumentException(message) {

    /**
     * An edit statement was given to an operation that **creates** a Program.
     *
     * A creation has no previous revision, so there is nothing to [TargetScheduleRevisionChange.Keep]
     * and nothing to [TargetScheduleRevisionChange.Clear]: both of those claims are about a source
     * that would have to exist first. A creation states its first source with an authoring, or states
     * nothing.
     */
    data class EditChangeOnACreation(val change: TargetScheduleRevisionChange) :
        TargetScheduleRevisionChangeException(
            "a Program that does not exist yet cannot keep or clear a target source: $change is an " +
                "edit statement, and a creation states its first target source with an authoring"
        )

    /**
     * A creation statement was given to an operation that **edits** an existing Program.
     *
     * An edit states its target scheduling exactly once, through
     * [TargetScheduleRevisionChange.Replace] / [Clear] / [Keep]. An authoring handed to an edit
     * instead would be a second, implicit vocabulary for the same decision — the one whose `null`
     * meant three different things.
     */
    data object CreationAuthoringOnAnEdit : TargetScheduleRevisionChangeException(
        "an edit states its target scheduling with Keep / Replace / Clear, not with a creation " +
            "authoring; the two absences are different claims and are not interchangeable"
    )

    /**
     * A structural edit asked to **carry the current revision's source forward**, and that source is
     * stored but not readable.
     *
     * [com.monkfitness.app.domain.usecase.TargetScheduleSourceRead.Malformed] is a third fact, and
     * the only honest continuation of "keep what this revision states" when what it states cannot be
     * read is *no continuation at all*. Minting a new revision that states no source would turn
     * "this revision states something I cannot parse" into "this revision states nothing", which is a
     * claim the storage never made and the caller never asked for. The save is refused instead: the
     * Program keeps the revision it had, and the unreadable rows stay exactly where they are.
     *
     * The caller has a way forward — state `Replace` with a source they mean, or `Clear` — and both
     * are explicit. This is the refusal that makes the difference between them and `Keep` visible.
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
     * An explicit **Clear** over a source that is stored but not readable.
     *
     * `Clear` on a revision that states **no** source is that same claim, already made by the storage,
     * so it changes nothing. `Clear` over a source that exists but cannot be parsed is a different
     * question entirely: the caller is asking this Program to stop having a target schedule, and the
     * boundary cannot tell what it currently has. Answering `NothingToChange` would silently read
     * unreadable persisted data as absence; superseding the revision with one that states no source
     * would destroy whatever the malformed rows say without anyone deciding to. So the operation is
     * refused and the current revision stays current.
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
 * The authoring a stored [TargetScheduleSource] states, expressed over the plan days a **draft**
 * carries — the read side of the authoring boundary, and the only honest way to carry a previous
 * revision's source onto a new one.
 *
 * A binding's stored `programDayId` is a plan day of the revision that stated it, and an editor
 * draft opened from that revision carries the same identities as its own handles (§7's
 * `editDraft` copies the plan, it does not re-identify it). So the stored identity *is* the drafted
 * handle, and the correspondence this conversion needs is a membership question: does this draft
 * still carry that day? Nothing is derived from a day's `position`, its `name`, its date, its weekday
 * or its index, no `workoutId` is derived from a `ProgramDayId`, and a binding whose day the draft
 * does not carry is **refused** rather than pointed at some other day — the caller either removed
 * that plan day, in which case only they can say which day the workout should present now, or the
 * draft is not the draft they think it is.
 *
 * @param draftedProgramDays the plan-day identities the draft carries, in no particular order.
 * @throws TargetScheduleAuthoringException.StoredProgramDayNotInTheDraft for a binding naming a
 *   plan day this draft does not carry.
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
 * Do two authorings state the **same target scheduling semantics** over the same drafted plan-day
 * handles?
 *
 * This is the question *"would a target-only `Replace` change anything?"* has to answer, and
 * answering it by [TargetScheduleAuthoring.equals] would be wrong in the strictest possible way: a new
 * revision mints a fresh `ProgramDayId` for **every** day (§6), so a source that is semantically
 * identical always differs by identity once it has been attached, and an identity comparison would
 * mint a revision on every save of an unchanged target schedule.
 *
 * The comparison is therefore made **before** the statement is attached — in the space of the plan-day
 * handles the caller actually named, which is the same space the editor keys its minted
 * correspondence by. Two statements are equal when they state the same rules, keyed by rule identity,
 * and the same `workoutId -> plan day` bindings, keyed by workout identity. Both keys are total: a
 * source refuses a duplicate rule identity and a duplicate workout binding, so no two entries can
 * collapse into one and no ordering can hide a difference.
 *
 * Being order-insensitive is a decision, not an accident. The reference document's warning is exact:
 * an immutable-write guard that compares whole values inherits its definition of *"identical"* from
 * whatever `ORDER BY` the read happens to use, so a caller re-presenting the same rules in a
 * different order is refused as a *conflicting* source — technically consistent, and a surprising
 * refusal for a statement that changes nothing. Here the domain value decides, and it decides that
 * rule order is not part of the claim.
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
