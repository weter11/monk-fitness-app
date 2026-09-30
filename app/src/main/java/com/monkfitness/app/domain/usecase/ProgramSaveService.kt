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
 * The application layer's **Save** — §27's two composition lines, joined where they belong.
 *
 * ### Why this layer exists
 *
 * Two rules of §27 name work that no single existing owner could perform:
 *
 * ```text
 * Create / Copy   → Program + Revision + ProgramDays + ProgramExercises + initial Slots
 * Save Editor     → new Revision + future-slot reconciliation
 * ```
 *
 * Neither the editor nor the Scheduler nor the repository can hold either line alone, and the
 * blueprint's ownership says why: the editor decides a draft's **structure** but may not touch a
 * date or a slot (§20 is the Scheduler's, pinned by `ProgramEditorArchitectureTest` on the editor's
 * constructor); the Scheduler decides **opportunities** but plans a Program's *stored* state and
 * never writes a Program row; the repository persists what it is told as **one transaction** and
 * decides nothing. Composing the three was therefore left open — and until this class, production
 * called only [ProgramEditorService.save], so a created Program landed without a planned start date
 * and without a single initial opportunity: a Program graph nobody could start, plan or show on Home.
 *
 * This class is the missing composition, and it mirrors [ProgramImportService] exactly — which has
 * been performing the same creation unit for imports since §30 step 13:
 *
 * ```text
 * create / copy   prepareCreation(draft, date)      editor: validate + mint Program + first Revision
 *               → initialSlotsFor(program, revision) Scheduler: the opportunities, decided, not written
 *               → createProgram(program, revision, slots)   repository: one transaction, or nothing
 *               → store(targetSource)               the revision's explicit target source, stated
 *
 * edit            editor.save(draft)                 structure: at most one new Revision (§6)
 *               → schedule(programId)               Scheduler: reconcile the future opportunities
 *               — inside ONE transaction, so a failed reconciliation leaves no half-applied save
 * ```
 *
 * ### Target scheduling is revisioned Program behaviour
 *
 * Stage 19 made a target source **authorable** and applied it to the revision a save creates. Stage
 * 22 makes it a *revisioned* behaviour with three explicit cases, and this boundary is where they are
 * stated:
 *
 * ```text
 * Target schedule is revisioned Program behaviour.
 *
 * Target change:
 *     Replace / Clear
 *         → new Revision
 *
 * No target change:
 *     Keep
 *
 * Structural Program edit:
 *     target source is carried forward unless explicitly Replace/Clear
 *
 * Revision source:
 *     immutable
 *     revision-owned
 * ```
 *
 * The two absences a caller can express are therefore **different claims on different legs**, and
 * each is refused if it is used on the leg it does not belong to:
 *
 * | parameter | legal on | `null` means |
 * | --- | --- | --- |
 * | `targetSchedule: TargetScheduleAuthoring?` | a **creation** (create / copy) | this Program has never stated target semantics — the revision reads [TargetScheduleSourceRead.Missing] |
 * | `targetChange: TargetScheduleRevisionChange?` | an **edit** of an existing Program | [TargetScheduleRevisionChange.Keep] — carry the current revision's source forward |
 *
 * A creation handed an edit statement is refused with
 * [TargetScheduleRevisionChangeException.EditChangeOnACreation] and an edit handed a creation
 * authoring with [TargetScheduleRevisionChangeException.CreationAuthoringOnAnEdit], because a single
 * `null` that means both *"keep what is there"* and *"there was never anything"* is exactly the
 * ambiguity that let a structural edit silently drop a Program's target scheduling.
 *
 * ### What this layer decides, and what it does not
 *
 * Exactly one thing is decided here: **which leg runs for which request, and what "atomic" means for
 * it.** Everything else arrives as an answer from the owner of the rule:
 *
 *  * **the planned start date.** [save] takes the date the creation request names, or reads *today*
 *    from the injected clock and calendar when the user chose no exact date. That is the whole date
 *    policy of a creation: the Scheduler is never asked to pick one (it refuses a Program without an
 *    anchor rather than inventing one — §3), and the value lands on the Program row, not in the
 *    revision's structure (§6: choosing a date creates no revision of its own).
 *  * **initial slots.** [ProgramScheduler.initialSlotsFor] is the only source; this class never
 *    builds a slot, never assembles a schedule request and never computes a date itself.
 *  * **reconciliation.** [ProgramScheduler.schedule] is the only pass, and it is run *after* the new
 *    revision is stored — the pass reads what is stored, so "after" is not a preference but what
 *    makes the decision see the revision the user just saved. Its rules stay entirely inside the
 *    Scheduler: this class only decides *when* a pass runs, and it runs exactly once per structural
 *    save. A **target-only** change runs no pass at all — a target revision change is not a legacy
 *    schedule change (§13's isolation, in both directions).
 *  * **whether a target-only change warrants a revision at all.** The two questions are separate
 *    because a target-only change alters no structure: does the statement differ from what is stored,
 *    compared field-by-field through the editor's own minted correspondence? If it does not, nothing
 *    is written. If it does, one new revision of the *same* Program is minted and the statement is
 *    attached to it.
 *  * **selection is never touched.** Creating a Program selects nothing (§9's opt-in belongs to
 *    import); the lifecycle service — the selection owner — is not even a collaborator here.
 *
 * ### No target fact is ever invented here
 *
 * The rules, their cadences, their anchor dates, the derived rule's source and the workout-to-plan-day
 * bindings are all forwarded verbatim; the only thing this layer re-points is a plan-day *handle* onto
 * the identity the editor minted for it, through the correspondence the editor itself reports. The
 * planned start date is **not** used as an anchor date: no documented ownership rule in this
 * repository equates the two, so the anchor stays an explicit authoring input. No plan-day
 * `position`, `name`, date, weekday or index is ever consulted to decide a binding, no `workoutId` is
 * derived from a `ProgramDayId`, and no legacy `ProgramSchedule` is read to manufacture a source.
 *
 * ### The refusals of a creation, and why one of them cannot fire
 *
 * A refusal or a failure of the scheduling leg happens **before** the write, so nothing is stored —
 * the same shape [ProgramImportService.save] has. For a Program created through this class the
 * anchor refusal ([ProgramSchedulingRefusal.NoSchedulingAnchor]) is unreachable by construction:
 * every creation carries a non-null planned start date (an exact choice or today), and the Program
 * is neither archived nor terminal a picosecond after it was minted. What *can* happen is a storage
 * or data failure out of the Scheduler's decision, and that is surfaced as
 * [ProgramEditorResult.Failed] with the whole table set untouched — measured by
 * `ProgramSaveServiceTest`, not promised here.
 *
 * @param editor the structure owner and the **single** owner of revision minting: it validates the
 *   draft, mints the Program and the first revision ([ProgramEditorService.prepareCreation]), performs
 *   an edit's own save rule (§6), and mints the revision a target-only change warrants.
 * @param programRepository §27's creation primitive — the Program-owned graph and the slots in one
 *   transaction, or nothing at all.
 * @param scheduler §20's timing owner: initial opportunities for a creation, the one reconciliation
 *   pass after a structural edit. This class decides neither a date nor a slot.
 * @param clock the clock *today* is read from when the creation request names no exact date (§26).
 * @param zone the calendar that clock's instant is read as a date in — the same one the Scheduler
 *   plans in, so the date this layer defaults to and the date the slots anchor to are the same day.
 * @param inTransaction runs a block in one database transaction: the creation's whole graph, an
 *   edit with its reconciliation, and a target-only change with the revision it mints.
 * @param targetSourceRepository the revision-owned explicit target source: where a stated source is
 *   written, and where nothing is written when a statement states none. It is a repository rather
 *   than a DAO so this layer never learns the storage vocabulary, and it is the only target-stage
 *   collaborator here — the contour is still separately callable and no pass is run.
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
     * Saves [draft]: §27's creation unit for a create or a copy, §27's revision-plus-reconciliation
     * for an edit, and the editor's own three answers (revision / facts / nothing) in every case.
     *
     * @param plannedStartDate the date the user explicitly chose for a **new** Program, or `null`
     *   when they chose none — in which case the creation takes *today* from [clock] read in [zone],
     *   at the moment of this save. It is ignored for an edit: an existing Program's planned start
     *   date is its own fact, moved only through
     *   [ProgramLifecycleService.setPlannedStartDate], never as a side effect of a structural save.
     *   It is also never used as a target rule's anchor date — see the class KDoc.
     * @param targetSchedule the explicit target scheduling semantics the caller states for the
     *   revision this save **creates** a Program for, or `null` when the caller states none.
     *   Supplied for a create or a copy it is written against the new revision inside the same
     *   transaction; omitted, no target row is written and the new revision reads as
     *   [TargetScheduleSourceRead.Missing]. Supplying it for an **edit** is refused with
     *   [TargetScheduleRevisionChangeException.CreationAuthoringOnAnEdit] — an edit states its target
     *   scheduling with [targetChange].
     * @param targetChange what an **edit** says about the revision's target scheduling, or `null`
     *   for [TargetScheduleRevisionChange.Keep] — the honest default: the current revision's source is
     *   carried forward onto the new revision, so a structural edit cannot silently drop it. Passing
     *   it for a creation is refused with
     *   [TargetScheduleRevisionChangeException.EditChangeOnACreation].
     * @return the save's outcome — [ProgramEditorResult.Rejected] and [ProgramEditorResult.Failed]
     *   mean nothing was written (for a creation: not one row of any of the five tables, and no target
     *   source row either).
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

    // ---------------------------------------------------------------- a target-only change (§6, §22)

    /**
     * Changes only the target scheduling of an existing Program, creating a revision when — and only
     * when — the statement actually differs from what the current revision states.
     *
     * This is the defect Stage 22 exists to fix. A target-only change alters no plan day, so §6's
     * structural comparison cannot see it: the draft is byte-identical to the current revision, the
     * editor would answer *nothing to change*, and the user's stated target schedule would be
     * discarded without a word. So the change is given its own operation here, which asks the editor
     * to mint a revision whose structure is the current one and then decides whether to keep it.
     *
     * The order is the contract, and it is why this is not "save the draft again":
     *
     *  1. **read what is stored.** The current revision's source is read *before* anything is minted,
     *     because the write moves the pointer and there would be nothing to compare against after.
     *  2. **mint, unwritten.** [ProgramEditorService.prepareTargetScheduleRevision] produces the new
     *     revision and the correspondence from each current plan-day identity to the identity that day
     *     becomes. No row is written, so a statement that turns out to be identical costs nothing.
     *  3. **compare semantics, not identity.** A new revision re-identifies every plan day (§6), so a
     *     source that states exactly the same rules and the same `workoutId -> plan day` bindings
     *     never compares equal by value. The comparison is field-by-field through the minted
     *     correspondence ([statesTheSameTargetSemanticsAs]) and is order-insensitive, because a rule
     *     identity and a workout identity are each a total key.
     *  4. **write once, or not at all.** Only a differing statement reaches
     *     [ProgramEditorService.saveTargetScheduleRevision] and the source write, and both happen
     *     inside **one** transaction — so a refused or failing source write rolls the new revision
     *     back with it and the Program keeps the plan it had, with its target source still readable.
     *
     * [TargetScheduleRevisionChange.Keep] on this operation means *no target change*, so no revision
     * is minted: a change that changes nothing is not a change. [TargetScheduleRevisionChange.Clear]
     * on a Program whose current revision already states no source is the same claim, and is
     * likewise not a change.
     *
     * The previous revision is never written to. There is no update and no delete anywhere in this
     * path — the repository exposes neither — so a superseded revision's source survives byte-for-byte
     * by construction rather than by promise.
     *
     * @param change the statement about the new revision's target scheduling.
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
        // The three read outcomes are kept apart here, deliberately. Only `Missing` means "no target
        // source"; a `Malformed` read is a third fact, and each of the three changes answers it
        // differently — which is exactly what collapsing them would have hidden.
        val stored = targetSourceRepository.sourceOf(currentRevisionId)

        // `Keep` states no change, so nothing is minted at all. It needs no read: a change that
        // changes nothing is answered the same way whatever the current revision states.
        if (change is TargetScheduleRevisionChange.Keep) {
            return ProgramEditorResult.Success(
                ProgramSaveOutcome.NothingToChange(program, currentRevisionId)
            )
        }
        // `Clear` over a source that **cannot be read** is refused, not answered as "nothing to
        // change". `NothingToChange` would be the honest answer only if the revision really stated no
        // target source, and unreadable rows do not say that — they say something this boundary cannot
        // parse, and superseding them would destroy it without anyone deciding to.
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
        // A `Replace` that states what is stored already changes nothing, even though the revision it
        // would attach to is a real new revision: the *semantics* are the claim, and they are equal.
        // The comparison happens in the space of plan-day handles, before either statement is attached
        // to an identity — the new revision re-identifies every day, so comparing afterwards would
        // compare two different identities and never see the equality.
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
                    // The editor minted this revision itself and refused to write it, which is a
                    // §28 SYSTEM_FAILURE rather than a rule: nothing below has run and the
                    // transaction rolls back empty.
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

    // ---------------------------------------------------------------- a creation (§27)

    /**
     * §27's `Create / Copy → Program + Revision + ProgramDays + ProgramExercises + initial Slots`
     * as one unit: decide everything first, write once.
     *
     * The order is the contract and it is [ProgramImportService.save]'s own: the editor's answer
     * (validation and minted identities), then the Scheduler's answer (the initial opportunities —
     * computed against values, touching no row), and only then the single transaction. A refusal or
     * a failure on either answer therefore has *nothing* to roll back, and a failure on the write
     * rolls back the whole graph including the slots, so a partially created Program — a Program
     * with a plan but no opportunities, or opportunities but no Program — cannot exist. The stated
     * target source is written **inside that same unit**, so a source that is refused or that fails to
     * write takes the Program and its first revision with it and leaves no orphaned source behind.
     *
     * A **copy** states its target source only through the [targetSchedule] parameter, like any other
     * creation: the copy's plan days are minted fresh at save and the copy shares nothing with its
     * source (§6, §23), so there is no correspondence from the source Program's bindings to the
     * copy's days for anything to be inferred from. A copy without a stated authoring therefore has
     * no target source, and a caller that wants one states it against the copy's own drafted days.
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

    // ---------------------------------------------------------------- an edit (§6, §27)

    /**
     * A structural save of an existing Program: the editor's revision rule, the caller's target
     * statement, then the Scheduler's reconciliation pass, inside **one** transaction.
     *
     * The pass is §27's own second half — *"Save Editor → new Revision + future-slot
     * reconciliation"* — and running it here rather than leaving it to the screen is what makes the
     * line true in production instead of a comment: the editor may not reach a slot, and the UI may
     * not decide when a scheduling pass runs. Only a save that actually created a revision
     * reconciles: a no-op save or a facts-only save has changed no plan, and there is nothing for a
     * pass to find that it did not find before (running one anyway would be a second, quieter
     * decision point for the same rule).
     *
     * One transaction around all of it means the pairing §27 states is the database's own: a
     * reconciliation that fails rolls the revision back with it (the user sees §28's `SYSTEM_FAILURE`
     * and still has the plan they had), and a reconciliation that is *refused* — an archived Program
     * being the reachable case — changes nothing about the save: the refusal is an ordinary absence
     * and the next pass, idempotent by construction, picks the reconciliation up.
     *
     * The target statement rides the same unit and the same rule. It is applied **against the new
     * revision** the structural save just created, never against the revision that save replaced — so
     * the previous revision's stored source survives byte-for-byte, and a save that creates no
     * revision (a no-op or a facts-only save) applies no statement at all, because there is no new
     * revision to own one. A source that is refused or that fails to write rolls the revision back
     * with it, which is the same all-or-nothing the reconciliation already obeys.
     *
     * [TargetScheduleRevisionChange.Keep] reads the **current** revision's stored source — read
     * before the editor's save, while the pointer still names it — converts it into an authoring over
     * the draft's own plan days, and lets that authoring be re-pointed onto the new revision through
     * the minted correspondence. The rules are forwarded verbatim; the bindings are re-identified,
     * never copied, so no old `ProgramDayId` reaches the new revision's rows. A stored binding whose
     * plan day the draft no longer carries is refused
     * ([TargetScheduleAuthoringException.StoredProgramDayNotInTheDraft]) rather than re-pointed at
     * some other day: which plan day that workout presents now is the caller's statement to make.
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
            // Read before the editor's save moves `currentRevisionId`: this is the source `Keep`
            // carries forward, and after the save it would name the revision this save supersedes.
            // The conversion is inside the try so its typed refusal is a §28 SYSTEM_FAILURE that
            // writes nothing, rather than an exception escaping the boundary.
            val carriedForward = when (targetChange) {
                // `Keep` branches on the read's own three outcomes rather than casting to `Source` and
                // treating everything else as nothing: a `Malformed` read is refused outright, because
                // a new revision stating no source would say "this Program has no target schedule" on
                // the strength of rows nobody can read.
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
                        // A failed pass is not absorbable (§33): rethrow so the outer transaction
                        // rolls the revision back with it — save and reconciliation, or neither.
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
     * The one place a stated creation authoring becomes a stored source — or, when none was stated,
     * becomes nothing at all.
     *
     * `null` is the whole of the absent case: no row is written, no source is constructed, and the
     * revision reads back as [TargetScheduleSourceRead.Missing]. This is deliberately *not* an empty
     * [TargetScheduleSource], because a revision with no rules cannot produce an occurrence at all —
     * "this revision states no target semantics" and "this revision states that it has none" are
     * different claims and only the first is representable.
     *
     * Every other value is forwarded verbatim: the rules with their own identities, cadences and
     * anchor dates, and the bindings re-pointed from the drafted plan-day handle onto the identity the
     * editor minted for it. The anchor date is never taken from the Program's planned start date, no
     * cadence is chosen or normalized, and no legacy `ProgramSchedule` is read — a value that reaches
     * the database that the caller did not state would be a fact invented at a storage boundary.
     *
     * The repository's own typed refusals — a blank or duplicate rule identity, a duplicate workout
     * binding, a plan day that is not one of this revision's — are raised from inside this call, inside
     * the enclosing transaction, so each one rolls the revision back rather than leaving it saved
     * without the source the caller asked for.
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

    /** Today, in the composition root's calendar — the default of a creation request with no exact date. */
    private fun today(): LocalDate = clock.now().atZone(zone).toLocalDate()
}

/**
 * The Scheduler refused to plan a Program that is being created — unreachable in production, since a
 * creation always carries a planned start date, and carried as a [ProgramEditorResult.Failed] so the
 * boundary the caller already reads holds: nothing was written, and the sentence says which rule
 * fired rather than leaving an anonymous exception.
 */
internal class CreationSchedulingRefused(val reason: ProgramSchedulingRefusal) :
    RuntimeException("the Program being created cannot be scheduled: ${reason.message}")

/**
 * The editor refused to write a revision it had just minted itself.
 *
 * A target-only change prepares the revision through the editor, so the only refusals still reachable
 * at the write are ones about the Program as it stands — and there is no second chance to get a
 * different answer, because the reader and the writer are the same transaction. Rather than
 * translating the refusal into a second vocabulary, the boundary reports it as §28's `SYSTEM_FAILURE`
 * with this sentence: the transaction rolls back, the Program keeps its previous current revision, and
 * that revision's target source stays readable.
 */
internal class EditorRefusedItsOwnMintedRevision(detail: String) :
    RuntimeException("the editor refused the revision it minted for a target-only change: $detail")
