package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.TargetScheduleSourceRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ProgramSaveOutcome.RevisionSaved
import com.monkfitness.app.domain.program.ProgramSchedulingRefusal
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import java.time.LocalDate
import java.time.ZoneId

/**
 * The application layer's **Save**: the two composition lines — creation and edit-plus-reconciliation —
 * joined where they belong.
 *
 * Neither line has a single owner. The editor decides a draft's **structure** but may not touch a date or a
 * slot; the Scheduler decides **opportunities** but plans a *stored* Program and never writes a Program row;
 * the repository persists what it is told in one transaction and decides nothing. Composing them is this
 * class's whole job: `prepareCreation` → `initialSlotsFor` → `createProgram` in one transaction, exactly as
 * [ProgramImportService] does for an import. A structural edit is `editor.save` → `schedule(programId)`,
 * both in one transaction, the pass running **after** the revision is stored because the pass reads what is
 * stored.
 *
 * A target source is revisioned Program behaviour, so the two absences a caller can express are different
 * claims on different legs: `targetSchedule: TargetScheduleAuthoring?` belongs to a **creation** and `null`
 * means "this Program has never stated target semantics"; `targetChange` belongs to an **edit** and `null`
 * means `Keep`. Each is refused on the leg it does not belong to — a single `null` meaning both "keep what
 * is there" and "there was never anything" is the ambiguity that let a structural edit silently drop a
 * Program's target scheduling.
 *
 * No target fact is invented here. Rules, cadences, anchor dates and bindings are forwarded verbatim; the
 * only thing re-pointed is a plan-day *handle* onto the identity the editor minted, through the editor's own
 * correspondence. The planned start date is **not** used as a target anchor: no ownership rule equates the
 * two, so the anchor stays an explicit authoring input.
 *
 * Selection is never touched — creating a Program selects nothing, and the lifecycle service is not even a
 * collaborator here.
 *
 * @param editor the structure owner and the **single** owner of revision minting.
 * @param programRepository the creation primitive: the Program-owned graph and the slots in one transaction,
 *   or nothing at all.
 * @param scheduler the timing owner: initial opportunities for a creation, the one reconciliation pass after
 *   a structural edit. This class decides neither a date nor a slot.
 * @param clock *today* is read from here when the creation request names no exact date.
 * @param zone the calendar that instant is read as a date in — the same one the Scheduler plans in, so the
 *   defaulted date and the date the slots anchor to are the same day.
 * @param inTransaction the creation's whole graph, an edit with its reconciliation, and a target-only change
 *   with the revision it mints.
 * @param targetSourceRepository the revision-owned explicit target source: where a stated source is written,
 *   and where nothing is written when a statement states none.
 */
class ProgramSaveService(
    private val editor: ProgramEditorService,
    private val programRepository: ProgramRepository,
    private val scheduler: ProgramScheduler,
    private val targetSourceRepository: TargetScheduleSourceRepository,
    private val clock: Clock,
    private val zone: ZoneId,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * Saves [draft]: the creation unit for a create or a copy, revision-plus-reconciliation for an edit.
     *
     * @param plannedStartDate the date the user chose for a **new** Program, or `null` for *today* from
     *   [clock] read in [zone] at the moment of this save. Ignored for an edit: an existing Program's
     *   planned start date is its own fact, moved only through
     *   [ProgramLifecycleService.setPlannedStartDate], never as a side effect of a structural save — and
     *   never used as a target rule's anchor date.
     * @param targetSchedule the target semantics stated for the revision a creation mints, or `null` for
     *   none (the revision then reads as `Missing`). Supplying it for an **edit** is refused.
     * @param targetChange what an **edit** says about the revision's target scheduling, or `null` for
     *   [TargetScheduleRevisionChange.Keep] — the honest default, so a structural edit cannot silently drop
     *   the source. Passing it for a creation is refused.
     * @return the outcome; [ProgramEditorResult.Rejected] and [ProgramEditorResult.Failed] mean nothing was
     *   written — for a creation, not one row of any of the five tables, and no target source row either.
     */
    suspend fun save(
        draft: ProgramEditorDraft,
        plannedStartDate: LocalDate? = null,
        targetSchedule: TargetScheduleAuthoring? = null,
        targetChange: TargetScheduleRevisionChange? = null
    ): ProgramEditorResult<ProgramSaveOutcome> = if (draft.editsExistingProgram) {
        saveRevisionWithReconciliation(draft, targetSchedule, targetChange ?: TargetScheduleRevisionChange.Keep)
    } else {
        // A creation has no previous revision, so there is nothing to keep and nothing to clear. The
        // statement is refused before any decision is taken, so the creation never starts.
        val editChange = targetChange
        if (editChange != null) {
            ProgramEditorResult.Failed(
                TargetScheduleRevisionChangeException.EditChangeOnACreation(editChange)
            )
        } else {
            createProgram(draft, plannedStartDate ?: today(), targetSchedule)
        }
    }

    /**
     * Changes only the target scheduling of an existing Program, creating a revision when — and only when —
     * the statement actually differs from what the current revision states.
     *
     * A target-only change alters no plan day, so the structural comparison cannot see it: the draft is
     * identical to the current revision, the editor would answer *nothing to change*, and the user's stated
     * target schedule would be discarded without a word. Hence a separate operation.
     *
     * The order is the contract:
     *  1. read what is stored, **before** anything is minted — the write moves the pointer, and there would
     *     be nothing to compare against after;
     *  2. mint, unwritten, so a statement that turns out identical costs nothing;
     *  3. compare **semantics, not identity**: a new revision re-identifies every plan day, so equal sources
     *     never compare equal by value; the comparison is field-by-field through the minted correspondence,
     *     and order-insensitive because each rule identity and workout identity is a total key;
     *  4. write once, or not at all — revision and source inside one transaction, so a failing source write
     *     rolls the revision back and the Program keeps the plan it had.
     *
     * `Keep`, and `Clear` over a revision that already states nothing, are both "no change" and mint nothing.
     * The previous revision is never written to: the repository exposes no update or delete, so a superseded
     * revision's source survives by construction.
     *
     * @return [ProgramSaveOutcome.RevisionSaved] when a differing statement was applied,
     *   [ProgramSaveOutcome.NothingToChange] when it states what is already stored.
     */
    suspend fun saveTargetScheduleChange(
        programId: ProgramId,
        change: TargetScheduleRevisionChange
    ): ProgramEditorResult<ProgramSaveOutcome> {
        val program = programRepository.programById(programId)
            ?: return ProgramEditorResult.Rejected(
                com.monkfitness.app.domain.program.ProgramEditorRejection.Refused(
                    com.monkfitness.app.domain.program.ProgramOperationRefusal.ProgramNotFound(programId)
                )
            )
        val currentRevisionId = program.currentRevisionId
        // The three read outcomes are kept apart: only `Missing` means "no target source", and a `Malformed`
        // read is a third fact each change answers differently — collapsing them would hide that.
        val stored = targetSourceRepository.sourceOf(currentRevisionId)

        // `Keep` states no change, so nothing is minted at all. It needs no read: a change that
        // changes nothing is answered the same way whatever the current revision states.
        if (change is TargetScheduleRevisionChange.Keep) {
            return ProgramEditorResult.Success(
                ProgramSaveOutcome.NothingToChange(program, currentRevisionId)
            )
        }
        // `Clear` over a source that **cannot be read** is refused, not answered as "nothing to change":
        // unreadable rows do not say the revision stated no source, and superseding them would destroy them
        // without anyone deciding to.
        if (change is TargetScheduleRevisionChange.Clear &&
            stored is TargetScheduleSourceRead.Malformed
        ) {
            return ProgramEditorResult.Failed(
                TargetScheduleRevisionChangeException.ClearOverUnreadableStoredSource(
                    revisionId = currentRevisionId,
                    reason = stored.reason
                )
            )
        }
        // `Clear` on a revision that states **no** source is that same claim, already made by the
        // storage, so there is nothing to change.
        if (change is TargetScheduleRevisionChange.Clear &&
            stored is TargetScheduleSourceRead.Missing
        ) {
            return ProgramEditorResult.Success(
                ProgramSaveOutcome.NothingToChange(program, currentRevisionId)
            )
        }
        // A `Replace` over an unreadable source is the one way forward the caller has, so it is not
        // refused: the new revision states what the caller meant, and the unreadable rows stay exactly
        // where they are, on the revision that stated them.
        val storedSource = (stored as? TargetScheduleSourceRead.Source)?.source

        val prepared = when (val minted = editor.prepareTargetScheduleRevision(programId)) {
            is ProgramEditorResult.Success -> minted.value
            is ProgramEditorResult.Rejected -> return minted
            is ProgramEditorResult.Failed -> return minted
        }
        val newRevisionId = prepared.revision.revisionId
        val requested = when (change) {
            is TargetScheduleRevisionChange.Replace -> change.authoring
            is TargetScheduleRevisionChange.Clear -> null
            is TargetScheduleRevisionChange.Keep -> null
        }
        // A `Replace` that states what is stored changes nothing, even though it would attach to a real new
        // revision: the semantics are the claim. The comparison happens in the space of plan-day handles,
        // before either statement is attached to an identity — the new revision re-identifies every day, so
        // comparing afterwards would compare different identities and never see the equality.
        if (requested != null && storedSource != null) {
            val storedAsAuthoring = storedSource.asAuthoringOver(prepared.mintedProgramDays.keys)
            if (storedAsAuthoring.statesTheSameTargetSemanticsAs(requested)) {
                return ProgramEditorResult.Success(
                    ProgramSaveOutcome.NothingToChange(program, currentRevisionId)
                )
            }
        }
        var saved: RevisionSaved? = null
        return try {
            inTransaction {
                val written = when (val outcome = editor.saveTargetScheduleRevision(prepared)) {
                    is ProgramEditorResult.Success -> outcome.value
                    // The editor minted this revision itself and refused to write it, which is a system
                    // failure rather than a rule: nothing below has run and the transaction rolls back empty.
                    is ProgramEditorResult.Rejected ->
                        throw EditorRefusedItsOwnMintedRevision(outcome.rejection.message)
                    is ProgramEditorResult.Failed -> throw outcome.cause
                }
                if (written !is RevisionSaved) {
                    throw EditorRefusedItsOwnMintedRevision(
                        "a target-only change saved ${written::class.simpleName} instead of a revision"
                    )
                }
                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf(
                            revisionId = newRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                }
                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))
        } catch (failure: Throwable) {
            ProgramEditorResult.Failed(failure)
        }
    }

    /**
     * The `Create / Copy → Program + Revision + ProgramDays + ProgramExercises + initial Slots` unit as one
     * unit: decide everything first, write once.
     *
     * The order is [ProgramImportService.save]'s own — the editor's answer, then the Scheduler's answer
     * computed against values and touching no row, then the single transaction — so a refusal on either
     * answer has nothing to roll back, and a failure on the write cannot leave a Program with a plan but no
     * opportunities, or opportunities but no Program. The stated target source is written **inside that same
     * unit**, so a source that fails takes the Program and its first revision with it.
     *
     * A **copy** states its target source only through [targetSchedule]: its plan days are minted fresh, so
     * there is no correspondence from the source Program's bindings for anything to be inferred from.
     */
    private suspend fun createProgram(
        draft: ProgramEditorDraft,
        plannedStartDate: LocalDate,
        targetSchedule: TargetScheduleAuthoring?
    ): ProgramEditorResult<ProgramSaveOutcome> {
        val creation = when (val prepared = editor.prepareCreation(draft, plannedStartDate)) {
            is ProgramEditorResult.Success -> prepared.value
            is ProgramEditorResult.Rejected -> return prepared
            is ProgramEditorResult.Failed -> return prepared
        }
        return try {
            val slots = when (
                val decided = scheduler.initialSlotsFor(creation.program, creation.revision)
            ) {
                is ProgramSchedulingResult.Success -> decided.value
                is ProgramSchedulingResult.Refused -> throw CreationSchedulingRefused(decided.reason)
                is ProgramSchedulingResult.Failure -> throw decided.cause
            }
            inTransaction {
                programRepository.createProgram(creation.program, creation.revision, slots)
                stateTargetSourceFor(
                    revisionId = creation.revision.revisionId,
                    mintedProgramDays = creation.mintedProgramDays,
                    targetSchedule = targetSchedule
                )
            }
            ProgramEditorResult.Success(
                ProgramSaveOutcome.RevisionSaved(
                    program = creation.program,
                    revision = creation.revision,
                    createdProgram = true,
                    mintedProgramDays = creation.mintedProgramDays
                )
            )
        } catch (failure: Throwable) {
            ProgramEditorResult.Failed(failure)
        }
    }

    /**
     * A structural save of an existing Program: the editor's revision rule, the caller's target statement,
     * then the Scheduler's reconciliation pass, inside **one** transaction.
     *
     * Running the pass here rather than leaving it to the screen is what makes the rule true in production
     * instead of a comment: the editor may not reach a slot, and the UI may not decide when a pass runs. Only
     * a save that actually created a revision reconciles — a no-op or facts-only save changed no plan, and a
     * pass would be a second, quieter decision point for the same rule.
     *
     * One transaction means the pairing is the database's own: a failing reconciliation rolls the revision
     * back, and a *refused* one (an archived Program being the reachable case) changes nothing about the
     * save, because the next pass is idempotent and picks it up.
     *
     * The target statement rides the same unit and rule: applied **against the new revision**, never the one
     * it replaced, so the previous revision's source survives. `Keep` reads the **current** revision's source
     * before the editor's save while the pointer still names it, converts it to an authoring over the draft's
     * plan days, and re-points it through the minted correspondence — so no old `ProgramDayId` reaches the new
     * rows. A stored binding whose plan day the draft no longer carries is refused rather than re-pointed:
     * which day that workout presents now is the caller's statement to make.
     */
    private suspend fun saveRevisionWithReconciliation(
        draft: ProgramEditorDraft,
        targetSchedule: TargetScheduleAuthoring?,
        targetChange: TargetScheduleRevisionChange
    ): ProgramEditorResult<ProgramSaveOutcome> {
        // An edit states its target scheduling exactly once, through `targetChange`. An authoring
        // handed to an edit instead would be a second implicit vocabulary for the same decision, and
        // the refusal happens before the transaction opens, so nothing was written either way.
        if (targetSchedule != null) {
            return ProgramEditorResult.Failed(
                TargetScheduleRevisionChangeException.CreationAuthoringOnAnEdit
            )
        }
        val programId = requireNotNull(draft.programId) {
            "a draft that edits a Program names it (§7)"
        }
        return try {
            // Read before the editor's save moves `currentRevisionId`: after it, this would name the revision
            // this save supersedes. Inside the try so its typed refusal writes nothing rather than escaping.
            val carriedForward = when (targetChange) {
                // `Keep` branches on the read's three outcomes rather than casting: a `Malformed` read is
                // refused outright, because a new revision stating no source would say "this Program has no
                // target schedule" on the strength of rows nobody can read.
                is TargetScheduleRevisionChange.Keep -> {
                    val stored = targetSourceRepository.sourceOf(
                        programRepository.programById(programId)?.currentRevisionId
                            ?: throw EditorProgramMissing(programId)
                    )
                    when (stored) {
                        is TargetScheduleSourceRead.Source ->
                            stored.source.asAuthoringOver(
                                draft.days.map { day -> day.programDayId }.toSet()
                            )
                        // The revision states no target semantics, so the new one states none either:
                        // there is nothing to carry forward and no claim to invent.
                        is TargetScheduleSourceRead.Missing -> null
                        is TargetScheduleSourceRead.Malformed ->
                            throw TargetScheduleRevisionChangeException.KeepOverUnreadableStoredSource(
                                revisionId = stored.revisionId,
                                reason = stored.reason
                            )
                    }
                }
                is TargetScheduleRevisionChange.Replace -> targetChange.authoring
                is TargetScheduleRevisionChange.Clear -> null
            }
            var result: ProgramEditorResult<ProgramSaveOutcome>? = null
            inTransaction {
                result = editor.save(draft)
                val saved = (result as? ProgramEditorResult.Success)?.value
                if (saved is RevisionSaved) {
                    if (carriedForward != null) {
                        targetSourceRepository.store(
                            carriedForward.asSourceOf(
                                revisionId = saved.revision.revisionId,
                                mintedProgramDays = saved.mintedProgramDays
                            )
                        )
                    }
                    when (val pass = scheduler.schedule(saved.program.programId)) {
                        // A failed pass is not absorbable: rethrow so the transaction rolls the revision back
                        // — save and reconciliation, or neither.
                        is ProgramSchedulingResult.Failure -> throw pass.cause
                        // A refusal decides nothing and writes nothing; the save stands.
                        is ProgramSchedulingResult.Refused,
                        is ProgramSchedulingResult.Success -> Unit
                    }
                }
            }
            result ?: ProgramEditorResult.Failed(
                IllegalStateException("the editor's save produced no result (§28)")
            )
        } catch (failure: Throwable) {
            ProgramEditorResult.Failed(failure)
        }
    }

    /**
     * The one place a stated creation authoring becomes a stored source — or, when none was stated, nothing
     * at all.
     *
     * `null` writes no row and the revision reads back as `Missing`. Deliberately *not* an empty
     * `TargetScheduleSource`: "this revision states no target semantics" and "this revision states that it
     * has none" are different claims, and only the first is representable.
     *
     * Every other value is forwarded verbatim — rules with their own identities, cadences and anchor dates,
     * bindings re-pointed from the drafted handle onto the minted identity. The anchor is never taken from
     * the planned start date, and no cadence is chosen or normalised: a value reaching the database that the
     * caller did not state would be a fact invented at a storage boundary.
     */
    private suspend fun stateTargetSourceFor(
        revisionId: RevisionId,
        mintedProgramDays: Map<ProgramDayId, ProgramDayId>,
        targetSchedule: TargetScheduleAuthoring?
    ) {
        if (targetSchedule == null) return
        targetSourceRepository.store(
            targetSchedule.asSourceOf(revisionId = revisionId, mintedProgramDays = mintedProgramDays)
        )
    }

    private fun today(): LocalDate = clock.now().atZone(zone).toLocalDate()
}

/**
 * The Scheduler refused to plan a Program being created — unreachable in production, since a creation always
 * carries a planned start date, and carried as a failure so the sentence says which rule fired rather than
 * leaving an anonymous exception.
 */
internal class CreationSchedulingRefused(val reason: ProgramSchedulingRefusal) :
    RuntimeException("the Program being created cannot be scheduled: ${reason.message}")

/**
 * The editor refused to write a revision it had just minted itself.
 *
 * A target-only change prepares the revision through the editor, so the only refusals still reachable at the
 * write are about the Program as it stands, and there is no second chance — reader and writer are the same
 * transaction. Reported as a system failure with this sentence rather than translated into a second
 * vocabulary: the transaction rolls back and the Program keeps its previous revision and its source.
 */
internal class EditorRefusedItsOwnMintedRevision(detail: String) :
    RuntimeException("the editor refused the revision it minted for a target-only change: $detail")
