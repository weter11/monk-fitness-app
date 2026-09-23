package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramCreation
import com.monkfitness.app.domain.program.ProgramDraftEditor
import com.monkfitness.app.domain.program.ProgramDraftReview
import com.monkfitness.app.domain.program.ProgramDraftValidation
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorRejection
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.ProgramStructure
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.validation
import com.monkfitness.app.domain.program.withRenumberedDays
import java.time.Instant
import java.time.LocalDate

/**
 * The Manual Program Editor — §30 step 6, over the persistence of steps 3 and 4 and beside the
 * lifecycle decisions of step 5.
 *
 * ### The flow this class implements
 *
 * ```text
 * load/create → Draft → edit → validate → Review → Save
 * ```
 *
 * Both halves of that are deliberate. **Draft-first**: the editor never touches a saved Program's
 * revision while the user works — every edit happens to a `ProgramEditorDraft` (a value the editor's
 * operations replace, never mutate), and a revision exists only after [save] has been asked for one
 * and has decided one is warranted. **Revision-immutable**: a save never writes into an existing
 * revision. A structural change is a *new* revision, with new identities for its days and its
 * elements, saved by [ProgramPlanRepository.saveNewRevision], while the revision it supersedes keeps
 * describing exactly what it described — which is what lets a session that ran under it stay
 * explained (§6, §19, §23).
 *
 * ### The entry points, and how the draft says which one it is
 *
 * §7's editor supports Create, Edit, Copy and the review of an import, and the draft names its own
 * entry point rather than carrying a flag that could disagree with it:
 *
 * | entry point | draft | what [save] does |
 * | --- | --- | --- |
 * | create (`Build it myself`, MANUAL) | no Program, no base revision | a new Program, its first revision and its plan, in one transaction (§27) |
 * | copy | no Program, a base revision | a new Program — owned by the user whatever the source's [ProgramSource] was (§4) — carrying the structure that was copied |
 * | edit | a Program and the revision it was opened from | a new revision of that Program, **only** if the structure changed (§6) |
 * | Standard Program | — | editing it in place is refused; [copyDraft] on it is the §4 copy-before-edit path |
 *
 * ### What this layer decides, and why none of it is below it
 *
 * The blueprint's layering is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI` (§25), and the
 * decisions here are the ones neither storage nor a DAO could make without becoming policy:
 *
 *  * **whether a save warrants a revision.** The comparison is a value comparison between the draft's
 *    plan and the plan the save is measured against ([ProgramStructure]); it is not "the user pressed
 *    Save", because §6 says a rename, a description, a planned start date, a lifecycle change, a
 *    selection and an archive create no revision — and a no-op save creates nothing at all.
 *  * **which save is atomic.** A save that creates a Program is one transaction (§27's
 *    `Create / Copy / Import → Program + Revision + initial Slots`), and a save that edits one is one
 *    transaction around the revision that is written and the Program facts that change with it. A
 *    failure anywhere leaves **no** new revision and **no** moved pointer.
 *  * **what a draft may not become.** Validation runs before anything is written, so an unfinished
 *    draft cannot reach a DAO at all — and the validation's rules are the domain's own (§7's plan a
 *    revision must be able to hold, plus the entry points above), not the database's.
 *  * **that the built-in Program is not edited in place.** §4's rule is enforced here for
 *    [editDraft] and for [save], through the same [StandardProgramProtected] failure the lifecycle
 *    layer raises, so "editing Standard means creating a user copy first" has one implementation.
 *
 * ### What it deliberately does not do
 *
 *  * **No slot writes.** Creating or re-saving a Program does not schedule it: no date is chosen, no
 *    slot is added, and no slot is reconciled when a revision changes, because §20's scheduling is
 *    §30 step 7's. The constructor takes no `ProgramScheduleRepository` at all — the guarantee is the
 *    absence of the collaborator, not a promise not to use one. §27's *creation unit* and §27's
 *    *Save → future-slot reconciliation* are therefore **not** this class's to perform: they are
 *    composed above it by [ProgramSaveService], which asks the Scheduler and this class's
 *    [prepareCreation] for their answers and performs the single transactional write. A caller that
 *    needs a created Program to have initial slots must go through that path rather than through
 *    [save]'s structure-level create entry.
 *  * **No generation.** `Generate`/`Regenerate` are the Generated editor's (§30 step 10); a draft's
 *    mode is content, and this class stores it without ever producing a plan.
 *  * **No adaptive work and no import/export.** Steps 11–13.
 *  * **No re-minting of the Exercise Library.** An exercise is an opaque id here (§10): the editor
 *    never resolves one, never checks it against a catalogue and never writes exercise metadata.
 *
 * @param programRepository the Program aggregate: the facts a draft edits (name, description) and the
 *   transaction that creates a Program with its first revision.
 * @param planRepository the revision mechanism: reading the revision a draft was opened from, and
 *   saving a new one. No revision is ever updated through it (§6) — it exposes no such operation.
 * @param clock the clock the factual timestamps come from. A revision's `createdAt`, a Program's
 *   `createdAt`/`updatedAt` and the `updatedAt` a save stamps are all read from it, once, at the
 *   moment the save decides them (§26).
 * @param idGenerator mints every identity a save persists: a new Program's, a new revision's, and —
 *   for every save — the identities of that revision's days and plan elements (§26).
 * @param inTransaction runs a block in one database transaction. Production takes the database's own
 *   `withTransaction` from the composition root; the editor composes two writes in the edit path and
 *   one in the create path (§27).
 */
class ProgramEditorService(
    private val programRepository: ProgramRepository,
    private val planRepository: ProgramPlanRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The identities a draft's days and plan elements are addressed by while it is being edited.
     *
     * They are the draft's own handles: minting one here never stores anything, and [mintRevision]
     * mints fresh identities for the revision it writes, so no draft handle can reach a revision's
     * rows.
     */
    private val draftIds: DraftIdSource = DraftIdSource { idGenerator.newId() }

    // ---------------------------------------------------------------- opening a draft (§7)

    /**
     * A new, empty **MANUAL** draft — the editor's `Build it myself` entry path (§7).
     *
     * It is empty on purpose: no days, no name. A draft may be unfinished; a revision may not, and
     * validation is what stands between the two.
     */
    fun newDraft(name: String = "", description: String = ""): ProgramEditorDraft =
        ProgramEditorDraft(name = name, description = description)

    /** The editor's `edit` step over [draft]: every operation answers with the next draft (§7). */
    fun editor(draft: ProgramEditorDraft): ProgramDraftEditor = ProgramDraftEditor(draft, draftIds)

    /** The draft's validation — §7's `validate` step, decided from the draft alone. */
    fun validate(draft: ProgramEditorDraft): ProgramDraftValidation = draft.validation()

    /**
     * The Program and its first revision a create or copy save would produce — validated, minted with
     * fresh identities, and **written nowhere** (§27's creation unit, before its other two halves).
     *
     * This is the seam the application-level Save orchestration composes against: the editor answers
     * *"what Program and first revision does this draft describe?"* and the orchestration answers the
     * two questions that are not the editor's — which initial opportunities the Scheduler decides for
     * that pair ([com.monkfitness.app.domain.usecase.ProgramScheduler.initialSlotsFor]), and the one
     * transactional `createProgram(program, revision, slots)` that stores them together. Nothing here
     * opens a transaction or touches a row, so a caller that is refused — or one whose scheduling leg
     * fails before the write — has still written nothing at all.
     *
     * The planned start date arrives as a **value** rather than being read here: §3 makes it a fact of
     * the Program and §6 says it is no part of the structure, and the layer that receives the user's
     * creation request is the layer that decides what date it names (an exact choice, or *today* from
     * the injected clock). The Scheduler never invents one — a Program created without an anchor could
     * not be planned at all ([com.monkfitness.app.domain.program.ProgramSchedulingRefusal
     * .NoSchedulingAnchor]).
     *
     * @param draft a draft that names no Program: a create (`Build it myself` / `Build for me`) or a
     *   copy. An edit is refused here — it has a Program, and its save is [save]'s own path.
     * @param plannedStartDate the date the Program is **planned** to start on. A plan, never a start:
     *   the lifecycle stays `NOT_STARTED` and `actualStartDate` stays `null`.
     * An unfinished draft is a [ProgramEditorRejection.InvalidDraft] result, decided before any
     * identity reaches storage — and nothing is stored either way, whichever way it is decided.
     */
    fun prepareCreation(
        draft: ProgramEditorDraft,
        plannedStartDate: LocalDate
    ): ProgramEditorResult<ProgramCreation> = editorResult {
        require(draft.isNewProgram) {
            "a prepared creation names no Program: this draft edits " +
                "'${draft.programId?.value}', and an edit saves through save(), not through a creation"
        }
        val validation = draft.validation()
        if (!validation.isValid) throw DraftRejected(validation)

        val at = clock.now()
        val programId = ProgramId(idGenerator.newId())
        val revision = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            at = at
        )
        ProgramCreation(
            program = Program(
                programId = programId,
                name = draft.name,
                description = draft.description,
                source = ProgramSource.USER,
                lifecycleStatus = LifecycleStatus.NOT_STARTED,
                currentRevisionId = revision.revisionId,
                createdAt = at,
                updatedAt = at,
                plannedStartDate = plannedStartDate,
                actualStartDate = null,
                archivedAt = null
            ),
            revision = revision
        )
    }.rejecting()

    /**
     * A draft that edits [programId]'s current plan.
     *
     * The draft is opened from the Program as it is stored: its name, its description, and the plan of
     * the revision `currentRevisionId` points at. Nothing is written — an editor session that is
     * abandoned leaves no trace — and the revision is only read, never modified.
     *
     * Refused for the built-in Standard Program: §4 lets it be selected, copied and shared, and
     * editing it means creating a user copy first, which is [copyDraft].
     */
    suspend fun editDraft(programId: ProgramId): ProgramEditorResult<ProgramEditorDraft> =
        editorResult {
            val program = existingProgram(programId)
            guardedForEdit(program)
            val revision = planRepository.currentRevision(programId)
                ?: throw RevisionMissing(program.currentRevisionId)
            draftOf(program, revision)
        }.rejecting()

    /**
     * A draft that **copies** [programId] into a Program the user will own — the §4 path for editing
     * the Standard Program, and the general `Copy` operation of §4.
     *
     * The draft names no Program: a copy is a new Program, created when it is saved. Its base revision
     * is the source's current plan, and its days are that plan as the working arrangement to edit —
     * the source is not modified in any way by opening this draft, and the copy's own identities are
     * minted at save ([mintRevision]), so nothing of the source's is shared (§6, §23).
     *
     * The name is the caller's, because how "Copy of X" is phrased is a UI decision; the copy's source
     * is [ProgramSource.USER] whatever the source's was, which is what "a copy is the user's own"
     * means (§4).
     */
    suspend fun copyDraft(
        programId: ProgramId,
        name: String,
        description: String = ""
    ): ProgramEditorResult<ProgramEditorDraft> = editorResult {
        require(name.isNotBlank()) { "a copy of a Program is given a name" }
        val source = existingProgram(programId)
        val revision = planRepository.currentRevision(programId)
            ?: throw RevisionMissing(source.currentRevisionId)
        ProgramEditorDraft(
            // No Program: the copy does not exist until it is saved, and it will be a new identity.
            programId = null,
            baseRevisionId = revision.revisionId,
            name = name,
            description = description,
            mode = revision.mode,
            duration = revision.duration,
            schedule = revision.schedule,
            days = revision.days,
            focus = revision.focus
        )
    }.rejecting()

    // ---------------------------------------------------------------- review (§7)

    /**
     * What saving [draft] would do — §7's `Review` step, decided the same way [save] decides it.
     *
     * An *invalid* draft is a successful review carrying its findings: the point of the step is to
     * show the user what is wrong with the draft, so a review that refused to look at it would have
     * nothing to show. A draft whose Program or base revision is not stored, on the other hand, is a
     * [ProgramEditorRejection] — the same one the save would produce — because a review of a plan
     * nobody can save is not a review.
     */
    suspend fun review(draft: ProgramEditorDraft): ProgramEditorResult<ProgramDraftReview> =
        editorResult {
            val base = basePlanFor(draft)
            ProgramDraftReview.of(
                draft = draft,
                base = base?.structure,
                nextRevisionNumber = base?.nextRevisionNumber
            )
        }.rejecting()

    // ---------------------------------------------------------------- save (§6, §7, §27)

    /**
     * Saves [draft]: at most one new revision, or none.
     *
     * The order is the contract:
     *
     *  1. **validate first.** An invalid draft is rejected and nothing is written, so the DAOs only
     *     ever see a plan a revision can hold (§7, §28).
     *  2. **then dispatch on the entry point** — create, copy, or a revision of an existing Program.
     *  3. **then compare, for an edit.** A structure that matches the Program's *current* revision
     *     creates no revision (§6), which also makes saving the same draft twice idempotent: the
     *     second save finds the plan it just wrote and changes nothing.
     *  4. **then write**, in one transaction per operation, so a failure leaves no partial revision and
     *     no moved pointer (§27).
     *
     * The Program's own facts travel with the save: a name or a description that differs from the
     * stored one is written — no revision, because §6 says so — in the same transaction as the
     * revision, so a save either applies both or neither. Facts this stage deliberately does not
     * carry (the planned start date, the lifecycle, the selection, the archive stamp) are untouched by
     * every path here.
     */
    suspend fun save(draft: ProgramEditorDraft): ProgramEditorResult<ProgramSaveOutcome> =
        editorResult {
            val validation = draft.validation()
            if (!validation.isValid) throw DraftRejected(validation)
            when {
                // An edit: the Program exists and the draft names the revision it was opened from.
                draft.editsExistingProgram -> saveRevisionOf(draft)
                // A copy: a new Program whose plan is the structure the draft holds.
                draft.isBasedOnASavedRevision -> saveCopyOf(draft)
                // A create: a new Program and its first revision.
                else -> createProgramFrom(draft)
            }
        }.rejecting()

    // ---------------------------------------------------------------- the mechanism

    /**
     * Saves [draft] as a new revision of the Program it edits, or as nothing at all.
     *
     * Four rules are visible in this method and none of them is negotiable:
     *
     *  * the Program is loaded, so the comparison is made against the plan that is actually stored
     *    rather than against the one the draft remembers;
     *  * the built-in Program is refused (§4) before anything is read from its plan;
     *  * a structure identical to the current revision's creates **no** revision, and a save whose
     *    name and description are also unchanged writes **nothing** (§6's no-op save);
     *  * the revision is minted with new identities ([mintRevision]) and written by
     *    [ProgramPlanRepository.saveNewRevision], which moves `currentRevisionId` in its own
     *    transaction — the facts write goes first, still pointing at the revision it was loaded from,
     *    because a Program row written afterwards would point back at the plan it replaced.
     */
    private suspend fun saveRevisionOf(draft: ProgramEditorDraft): ProgramSaveOutcome {
        val programId = requireNotNull(draft.programId) {
            "a draft that edits a Program names it; this one is validated before it gets here (§7)"
        }
        val program = existingProgram(programId)
        guardedForEdit(program)
        val current = planRepository.currentRevision(programId)
            ?: throw RevisionMissing(program.currentRevisionId)

        val at = clock.now()
        val factsChanged = draft.name != program.name || draft.description != program.description
        val facts = if (factsChanged) {
            program.copy(name = draft.name, description = draft.description, updatedAt = at)
        } else {
            program
        }

        if (draft.structure == current.structure) {
            if (!factsChanged) {
                return ProgramSaveOutcome.NothingToChange(program, current.revisionId)
            }
            inTransaction { programRepository.updateProgram(facts) }
            return ProgramSaveOutcome.FactsSaved(facts, current.revisionId)
        }

        val revision = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = current.revisionNumber + 1,
            at = at
        )
        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
        }
        return ProgramSaveOutcome.RevisionSaved(
            program = facts.copy(currentRevisionId = revision.revisionId, updatedAt = at),
            revision = revision,
            createdProgram = false
        )
    }

    /**
     * Saves a copy: a new Program whose plan is the structure [draft] holds.
     *
     * The revision the draft was opened from must still be stored — a copy's provenance is a fact, and
     * a draft whose source is gone is a stale draft rather than a copy of nothing.
     */
    private suspend fun saveCopyOf(draft: ProgramEditorDraft): ProgramSaveOutcome {
        val baseRevisionId = requireNotNull(draft.baseRevisionId) {
            "a copy's draft names the revision it was opened from (§7)"
        }
        planRepository.revisionById(baseRevisionId) ?: throw RevisionMissing(baseRevisionId)
        return createProgramFrom(draft)
    }

    /**
     * Creates a Program, its first revision and that revision's plan as one unit (§27).
     *
     * The Program is the user's — [ProgramSource.USER] — and is `NOT_STARTED` with no planned or
     * actual start: creating a Program is not starting it, and a planned start date is a Program fact
     * that §6 says creates no revision, which the lifecycle layer already owns
     * ([ProgramLifecycleService.setPlannedStartDate]).
     *
     * **No slots are written.** §27's creation unit mentions initial slots, and §30 step 7 is what
     * decides them: the scheduler owns how a duration and a schedule become dates, so a creation here
     * passes none and a Program is unscheduled until the scheduler says otherwise.
     */
    private suspend fun createProgramFrom(draft: ProgramEditorDraft): ProgramSaveOutcome {
        val at = clock.now()
        val programId = ProgramId(idGenerator.newId())
        val revision = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            at = at
        )
        val program = Program(
            programId = programId,
            name = draft.name,
            description = draft.description,
            source = ProgramSource.USER,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = revision.revisionId,
            createdAt = at,
            updatedAt = at,
            plannedStartDate = null,
            actualStartDate = null,
            archivedAt = null
        )
        programRepository.createProgram(program, revision)
        return ProgramSaveOutcome.RevisionSaved(program, revision, createdProgram = true)
    }

    /**
     * The revision a save would persist for [draft]: the draft's plan, **re-identified**.
     *
     * This is the seam the whole stage turns on, and the method states it in one sentence: a save
     * mints a new revision identity, a new identity for every day and a new identity for every plan
     * element, and changes nothing else. That is why a revision never reuses a row of the revision it
     * replaces (a plan day and a plan element are rows of their own — §6, §23), why a copy shares
     * nothing with its source, and why the draft's own handles are never persisted.
     *
     * The `require` is the second half of that sentence as a guard: if re-identifying a plan ever
     * changed its content — a reordered day, a dropped element, a rewritten prescription — the save
     * would fail loudly instead of persisting a plan the user never reviewed.
     */
    private fun mintRevision(
        draft: ProgramEditorDraft,
        programId: ProgramId,
        revisionNumber: Int,
        at: Instant
    ): ProgramRevision {
        val plan = draft.withRenumberedDays()
        val revision = ProgramRevision(
            revisionId = RevisionId(idGenerator.newId()),
            programId = programId,
            revisionNumber = revisionNumber,
            mode = plan.mode,
            duration = plan.duration,
            schedule = plan.schedule,
            days = plan.days.map { day ->
                day.copy(
                    programDayId = ProgramDayId(idGenerator.newId()),
                    exercises = day.exercises.map { element ->
                        element.copy(programExerciseId = ProgramExerciseId(idGenerator.newId()))
                    }
                )
            },
            createdAt = at,
            focus = plan.focus
        )
        require(revision.structure == plan.structure) {
            "saving a draft re-identifies its plan and changes nothing else: the minted revision and " +
                "the draft it came from disagree structurally"
        }
        return revision
    }

    /**
     * The structure a save compares [draft] against, and the ordinal a new revision would carry.
     *
     * An edit is compared against the Program's **current** revision — not against the revision the
     * draft was opened from — because "did the plan change?" is a question about what is stored now,
     * and answering it against a stale starting point would create a second revision for a change the
     * user already saved. A copy is compared against its source, which is informative rather than
     * decisive: a copy creates a Program even when nothing was changed.
     *
     * @return `null` for a create, which has nothing to be compared against.
     */
    private suspend fun basePlanFor(draft: ProgramEditorDraft): BasePlan? = when {
        draft.editsExistingProgram -> {
            val programId = requireNotNull(draft.programId) { "an edit names its Program (§7)" }
            val program = existingProgram(programId)
            guardedForEdit(program)
            val current = planRepository.currentRevision(programId)
                ?: throw RevisionMissing(program.currentRevisionId)
            BasePlan(current.structure, current.revisionNumber + 1)
        }

        draft.baseRevisionId != null -> {
            val revisionId = draft.baseRevisionId
            val revision = planRepository.revisionById(revisionId) ?: throw RevisionMissing(revisionId)
            // A copy's first revision is numbered from one however far its source had got.
            BasePlan(revision.structure, null)
        }

        else -> null
    }

    /** The draft an editor session starts from: the stored Program's facts and its current plan. */
    private fun draftOf(program: Program, revision: ProgramRevision): ProgramEditorDraft =
        ProgramEditorDraft(
            programId = program.programId,
            baseRevisionId = revision.revisionId,
            name = program.name,
            description = program.description,
            mode = revision.mode,
            duration = revision.duration,
            schedule = revision.schedule,
            days = revision.days,
            focus = revision.focus
        )

    /** The stored Program [programId] names, or a [EditorProgramMissing] refusal (§28). */
    private suspend fun existingProgram(programId: ProgramId): Program =
        programRepository.programById(programId) ?: throw EditorProgramMissing(programId)

    /**
     * §4's copy-before-edit rule, for the operations that change a Program's own content.
     *
     * Selection, copying and — through [copyDraft] and [saveCopyOf] — editing-by-copy are unaffected,
     * because those are the three things §4 lets the built-in Program receive. The failure is the
     * lifecycle layer's own [StandardProgramProtected], so the rule has one implementation and the
     * refusal one sentence.
     */
    private fun guardedForEdit(program: Program) {
        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)
    }
}

/** The structure a save is measured against, with the ordinal a new revision would carry. */
private data class BasePlan(val structure: ProgramStructure, val nextRevisionNumber: Int?)

// ---------------------------------------------------------------- the failures the mechanism throws

/**
 * A Program an editor operation names is not stored (§28 `INVALID_DATA`).
 *
 * Thrown inside the operation so the result boundary can map it to a
 * [ProgramEditorRejection.Refused] naming §28's `ProgramNotFound`; it never reaches a caller as an
 * exception.
 */
internal class EditorProgramMissing(val programId: ProgramId) :
    RuntimeException(ProgramOperationRefusal.ProgramNotFound(programId).message)

/** A revision a draft names is not stored, so the draft's provenance is not real (§23). */
internal class RevisionMissing(val revisionId: RevisionId) :
    RuntimeException("no revision '${revisionId.value}' is stored (§23)")

/** The draft is not finished, so nothing was written (§7's validate step). */
internal class DraftRejected(val validation: ProgramDraftValidation) :
    RuntimeException(
        "the draft cannot be saved: " + validation.issues.joinToString("; ") { it.message }
    )

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramEditorResult.Failed] rather than
 * absorbing it (§28's `SYSTEM_FAILURE`, §33's prohibition on turning a failure into an empty result).
 */
internal inline fun <T> editorResult(block: () -> T): ProgramEditorResult<T> = try {
    ProgramEditorResult.Success(block())
} catch (failure: Throwable) {
    ProgramEditorResult.Failed(failure)
}

/** Maps an operation's internal failures onto the typed rejections the caller receives. */
private fun <T> ProgramEditorResult<T>.rejecting(): ProgramEditorResult<T> = when (this) {
    is ProgramEditorResult.Success -> this
    is ProgramEditorResult.Rejected -> this
    is ProgramEditorResult.Failed -> when (val cause = cause) {
        is DraftRejected -> ProgramEditorResult.Rejected(
            ProgramEditorRejection.InvalidDraft(cause.validation)
        )
        is EditorProgramMissing -> ProgramEditorResult.Rejected(
            ProgramEditorRejection.Refused(ProgramOperationRefusal.ProgramNotFound(cause.programId))
        )
        is StandardProgramProtected -> ProgramEditorResult.Rejected(
            ProgramEditorRejection.Refused(ProgramOperationRefusal.StandardProgramCannotBeEdited)
        )
        is RevisionMissing -> ProgramEditorResult.Rejected(
            ProgramEditorRejection.RevisionNotFound(cause.revisionId)
        )
        else -> this
    }
}
