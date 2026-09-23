package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.di.Clock
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
 *
 * edit            editor.save(draft)                 structure: at most one new Revision (§6)
 *               → schedule(programId)               Scheduler: reconcile the future opportunities
 *               — inside ONE transaction, so a failed reconciliation leaves no half-applied save
 * ```
 *
 * ### What this layer decides, and what it does not
 *
 * Exactly one thing is decided here: **which leg runs for which draft, and what "atomic" means for
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
 *    makes the decision see the revision the user just saved. Its rules (which future slots a
 *    revision no longer presents, that a completed attempt is history, that a second pass decides
 *    nothing) stay entirely inside the Scheduler: this class only decides *when* a pass runs, and it
 *    runs exactly once per structural save. A pass that is **refused** (an archived Program, a
 *    Program with no anchor) is an ordinary absence, not a failure — the revision the user saved
 *    stands, and the next pass reconciles; a pass that **fails** throws inside the transaction, so
 *    the save and its reconciliation are applied together or not at all.
 *  * **selection is never touched.** Creating a Program selects nothing (§9's opt-in belongs to
 *    import); the lifecycle service — the selection owner — is not even a collaborator here.
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
 * @param editor the structure owner: it validates the draft, mints the Program and the first
 *   revision ([ProgramEditorService.prepareCreation]) and performs an edit's own save rule (§6).
 * @param programRepository §27's creation primitive — the Program-owned graph and the slots in one
 *   transaction, or nothing at all.
 * @param scheduler §20's timing owner: initial opportunities for a creation, the one reconciliation
 *   pass after an edit. This class decides neither a date nor a slot.
 * @param clock the clock *today* is read from when the creation request names no exact date (§26).
 * @param zone the calendar that clock's instant is read as a date in — the same one the Scheduler
 *   plans in, so the date this layer defaults to and the date the slots anchor to are the same day.
 * @param inTransaction runs a block in one database transaction: the creation's whole graph, and an
 *   edit with its reconciliation.
 */
class ProgramSaveService(
    private val editor: ProgramEditorService,
    private val programRepository: ProgramRepository,
    private val scheduler: ProgramScheduler,
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
     * @return the save's outcome — [ProgramEditorResult.Rejected] and [ProgramEditorResult.Failed]
     *   mean nothing was written (for a creation: not one row of any of the five tables).
     */
    suspend fun save(
        draft: ProgramEditorDraft,
        plannedStartDate: LocalDate? = null
    ): ProgramEditorResult<ProgramSaveOutcome> =
        if (draft.editsExistingProgram) {
            saveRevisionWithReconciliation(draft)
        } else {
            createProgram(draft, plannedStartDate ?: today())
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
     * with a plan but no opportunities, or opportunities but no Program — cannot exist.
     */
    private suspend fun createProgram(
        draft: ProgramEditorDraft,
        plannedStartDate: LocalDate
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
            }
            ProgramEditorResult.Success(
                ProgramSaveOutcome.RevisionSaved(
                    program = creation.program,
                    revision = creation.revision,
                    createdProgram = true
                )
            )
        } catch (failure: Throwable) {
            ProgramEditorResult.Failed(failure)
        }
    }

    // ---------------------------------------------------------------- an edit (§6, §27)

    /**
     * A structural save of an existing Program: the editor's revision rule, then the Scheduler's
     * reconciliation pass, inside **one** transaction.
     *
     * The pass is §27's own second half — *"Save Editor → new Revision + future-slot
     * reconciliation"* — and running it here rather than leaving it to the screen is what makes the
     * line true in production instead of a comment: the editor may not reach a slot, and the UI may
     * not decide when a scheduling pass runs. Only a save that actually created a revision
     * reconciles: a no-op save or a facts-only save has changed no plan, and there is nothing for a
     * pass to find that it did not find before (running one anyway would be a second, quieter
     * decision point for the same rule).
     *
     * One transaction around both means the pairing §27 states is the database's own: a
     * reconciliation that fails rolls the revision back with it (the user sees §28's `SYSTEM_FAILURE`
     * and still has the plan they had), and a reconciliation that is *refused* — an archived Program
     * being the reachable case — changes nothing about the save: the refusal is an ordinary absence
     * and the next pass, idempotent by construction, picks the reconciliation up.
     */
    private suspend fun saveRevisionWithReconciliation(
        draft: ProgramEditorDraft
    ): ProgramEditorResult<ProgramSaveOutcome> {
        var result: ProgramEditorResult<ProgramSaveOutcome>? = null
        return try {
            inTransaction {
                result = editor.save(draft)
                val saved = (result as? ProgramEditorResult.Success)?.value
                if (saved is RevisionSaved) {
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
