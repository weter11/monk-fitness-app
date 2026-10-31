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
 * The Manual Program Editor: `load/create → Draft → edit → validate → Review → Save`.
 *
 * Two properties hold the design. **Draft-first**: an edit happens to a `ProgramEditorDraft` value, replaced
 * never mutated, and a revision exists only after [save] decides one is warranted. **Revision-immutable**: a
 * save never writes into an existing revision — a structural change is a *new* revision with new identities
 * for its days and elements, while the revision it supersedes keeps describing exactly what it described,
 * which is what lets a session that ran under it stay explained.
 *
 * The draft names its own entry point rather than carrying a flag that could disagree with it: no Program and
 * no base revision is a create (new Program, first revision and plan in one transaction); a base revision
 * with no Program is a copy (a new user-owned Program carrying the copied structure); a Program plus the
 * revision it was opened from is an edit, which writes a new revision **only** if the structure changed.
 * Editing the built-in Program in place is refused — [copyDraft] is the copy-before-edit path.
 *
 * Validation runs before anything is written, so an unfinished draft cannot reach a DAO, and the rules are
 * the domain's own.
 *
 * It takes no `ProgramScheduleRepository`: creating or re-saving a Program does not schedule it, and the
 * guarantee is the absence of the collaborator rather than a promise not to use one. The creation unit and
 * its save-then-reconcile pairing are composed above this class by [ProgramSaveService], which asks the
 * Scheduler and [prepareCreation] for their answers and performs the single transactional write.
 *
 * Exercises stay opaque ids here: the editor never resolves one or writes exercise metadata.
 *
 * @param programRepository the Program aggregate: the facts a draft edits and the transaction that creates a
 *   Program with its first revision.
 * @param planRepository reading the revision a draft was opened from and saving a new one — it exposes no
 *   update, so no revision is ever modified.
 * @param clock the factual timestamps, read once at the moment the save decides them.
 * @param idGenerator every identity a save persists, including the days and elements of the revision.
 * @param inTransaction the editor composes two writes in the edit path and one in the create path.
 */
class ProgramEditorService(
    private val programRepository: ProgramRepository,
    private val planRepository: ProgramPlanRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The draft's own handles: minting one stores nothing, and [mintRevision] mints fresh identities for the
     * revision it writes, so no draft handle can reach a revision's rows.
     */
    private val draftIds: DraftIdSource = DraftIdSource { idGenerator.newId() }

    /**
     * A new, empty **MANUAL** draft — the `Build it myself` entry path.
     *
     * Empty on purpose: a draft may be unfinished, a revision may not, and validation stands between them.
     */
    fun newDraft(name: String = "", description: String = ""): ProgramEditorDraft =
        ProgramEditorDraft(name = name, description = description)

    /** The `edit` step over [draft]: every operation answers with the next draft. */
    fun editor(draft: ProgramEditorDraft): ProgramDraftEditor = ProgramDraftEditor(draft, draftIds)

    fun validate(draft: ProgramEditorDraft): ProgramDraftValidation = draft.validation()

    /**
     * The Program and its first revision a create or copy save would produce — validated, minted with fresh
     * identities, and **written nowhere**.
     *
     * This is the seam [ProgramSaveService] composes against: the editor answers *what Program and first
     * revision does this draft describe*, and the orchestration answers the two questions that are not the
     * editor's — the initial opportunities, and the one transactional `createProgram(program, revision, slots)`.
     * Nothing here opens a transaction or touches a row.
     *
     * The planned start date arrives as a **value** rather than being read: it is a Program fact and no part
     * of the structure, and the layer that receives the user's creation request decides what date it names.
     *
     * @param draft a draft that names no Program: a create or a copy. An edit is refused here.
     * @param plannedStartDate the date the Program is **planned** to start on. A plan, never a start.
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
        val minted = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            at = at
        )
        val revision = minted.revision
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
            revision = revision,
            mintedProgramDays = minted.mintedProgramDays
        )
    }.rejecting()

    /**
     * A draft that edits [programId]'s current plan, opened from the Program as stored: its facts and the
     * plan of the revision `currentRevisionId` points at. Nothing is written.
     *
     * Refused for the built-in Program: editing it means creating a user copy first, which is [copyDraft].
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
     * A draft that **copies** [programId] into a Program the user will own — the path for editing the
     * built-in Program, and the general `Copy` operation.
     *
     * The draft names no Program: a copy is a new Program, created when it is saved, and its identities are
     * minted at save, so nothing of the source's is shared. The name is the caller's because "Copy of X" is a
     * UI decision; the copy's source is [ProgramSource.USER] whatever the source's was.
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
            focus = revision.focus,
            preferredExercises = revision.preferredExercises
        )
    }.rejecting()

    /**
     * What saving [draft] would do — decided the same way [save] decides it.
     *
     * An *invalid* draft is a successful review carrying its findings: the point of the step is to show the
     * user what is wrong, so a review that refused to look would have nothing to show. A draft whose Program
     * or base revision is not stored is a rejection — a review of a plan nobody can save is not a review.
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

    /**
     * Saves [draft]: at most one new revision, or none.
     *
     * The order is the contract: validate first, so the DAOs only ever see a plan a revision can hold; then
     * dispatch on the entry point; then, for an edit, compare — a structure matching the Program's *current*
     * revision creates no revision, which also makes saving the same draft twice idempotent; then write, in
     * one transaction, so a failure leaves no partial revision and no moved pointer.
     *
     * The Program's own facts travel with the save: a changed name or description is written with no revision,
     * in the same transaction, so a save applies both or neither. The planned start date, lifecycle, selection
     * and archive stamp are untouched by every path here.
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

    /**
     * A new revision whose **structure is the current one, unchanged** — minted because something outside the
     * structure changed, and reported so the caller can say what it is attaching.
     *
     * It carries the same three facts a structural [save] reports, so a caller attaching a plan-day-named
     * statement re-points it through the editor's own correspondence instead of guessing which of the new
     * revision's days was meant. The correspondence is keyed by the **current** revision's plan-day identities
     * — the handles a caller can honestly hold — and days are re-identified rather than reused.
     *
     * This is the *minted, unwritten* half of a target-only change, separate from
     * [saveTargetScheduleRevision] so the caller can compare against what is stored and decline to write. It
     * writes no row and moves no pointer.
     */
    suspend fun prepareTargetScheduleRevision(
        programId: ProgramId
    ): ProgramEditorResult<MintedTargetScheduleRevision> = editorResult {
        val program = existingProgram(programId)
        guardedForEdit(program)
        val current = planRepository.currentRevision(programId)
            ?: throw RevisionMissing(program.currentRevisionId)
        val minted = mintRevision(
            draft = draftOf(program, current),
            programId = programId,
            revisionNumber = current.revisionNumber + 1,
            at = clock.now()
        )
        val revision = minted.revision
        MintedTargetScheduleRevision(
            program = program.copy(
                currentRevisionId = revision.revisionId,
                updatedAt = revision.createdAt
            ),
            revision = revision,
            mintedProgramDays = minted.mintedProgramDays
        )
    }.rejecting()

    /**
     * Writes the revision [prepareTargetScheduleRevision] minted, and nothing else.
     *
     * No facts write: a target-only change alters no name, no description and no structure.
     * `saveNewRevision` moves `currentRevisionId` in its own transaction, so the revision it supersedes stays
     * current until that write lands.
     */
    suspend fun saveTargetScheduleRevision(
        minted: MintedTargetScheduleRevision
    ): ProgramEditorResult<ProgramSaveOutcome> = editorResult {
        inTransaction { planRepository.saveNewRevision(minted.revision, minted.revision.createdAt) }
        ProgramSaveOutcome.RevisionSaved(
            program = minted.program,
            revision = minted.revision,
            createdProgram = false,
            mintedProgramDays = minted.mintedProgramDays
        )
    }.rejecting()

    /**
     * Saves [draft] as a new revision of the Program it edits, or as nothing at all.
     *
     * The comparison is against the plan actually stored, not the one the draft remembers; an identical
     * structure creates **no** revision, and a save whose facts are unchanged too writes nothing. The facts
     * write goes **first**, while the Program still points at the revision it was loaded from — a Program row
     * written afterwards would point back at the plan it replaced.
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

        val minted = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = current.revisionNumber + 1,
            at = at
        )
        val revision = minted.revision
        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
        }
        return ProgramSaveOutcome.RevisionSaved(
            program = facts.copy(currentRevisionId = revision.revisionId, updatedAt = at),
            revision = revision,
            createdProgram = false,
            mintedProgramDays = minted.mintedProgramDays
        )
    }

    /**
     * Saves a copy: a new Program whose plan is the structure [draft] holds.
     *
     * The revision the draft was opened from must still be stored — a copy's provenance is a fact, and a draft
     * whose source is gone is a stale draft rather than a copy of nothing.
     */
    private suspend fun saveCopyOf(draft: ProgramEditorDraft): ProgramSaveOutcome {
        val baseRevisionId = requireNotNull(draft.baseRevisionId) {
            "a copy's draft names the revision it was opened from (§7)"
        }
        planRepository.revisionById(baseRevisionId) ?: throw RevisionMissing(baseRevisionId)
        return createProgramFrom(draft)
    }

    /**
     * Creates a Program, its first revision and that revision's plan as one unit.
     *
     * The Program is the user's and `NOT_STARTED`: creating is not starting, and a planned start date is a
     * Program fact the lifecycle layer already owns.
     *
     * **No slots are written.** The creation unit mentions initial slots, but the Scheduler decides them —
     * composing that is [ProgramSaveService]'s job — so a Program created here is unscheduled.
     */
    private suspend fun createProgramFrom(draft: ProgramEditorDraft): ProgramSaveOutcome {
        val at = clock.now()
        val programId = ProgramId(idGenerator.newId())
        val minted = mintRevision(
            draft = draft,
            programId = programId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            at = at
        )
        val revision = minted.revision
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
        return ProgramSaveOutcome.RevisionSaved(
            program = program,
            revision = revision,
            createdProgram = true,
            mintedProgramDays = minted.mintedProgramDays
        )
    }

    /**
     * The revision a save would persist for [draft]: the draft's plan, **re-identified**, with the
     * correspondence from each drafted plan-day handle to the identity it became.
     *
     * A save mints a new revision identity, a new identity for every day and every element, and changes
     * nothing else — which is why a revision never reuses a row of the one it replaces, why a copy shares
     * nothing with its source, and why the draft's handles are never persisted.
     *
     * The correspondence is **reported** rather than re-derived: the editor is the only component that
     * re-identifies, so a caller that stated a `workoutId -> ProgramDayId` binding against a handle cannot
     * know the saved identity any other way. The `require` makes a re-identification that changed content fail
     * loudly instead of persisting a plan the user never reviewed.
     */
    private fun mintRevision(
        draft: ProgramEditorDraft,
        programId: ProgramId,
        revisionNumber: Int,
        at: Instant
    ): MintedRevision {
        val plan = draft.withRenumberedDays()
        // A list rather than an `associate`: two draft days sharing a handle would collapse into one map entry,
        // and two revision days would then be minted the same identity — which the rule table's composite key
        // would refuse much later, for the wrong reason.
        val mintedDays = plan.days.map { day -> day.programDayId to ProgramDayId(idGenerator.newId()) }
        require(mintedDays.map { it.second }.distinct().size == mintedDays.size) {
            "saving a draft mints a distinct identity for every plan day"
        }
        val mintedByHandle = mintedDays.toMap()
        val revision = ProgramRevision(
            revisionId = RevisionId(idGenerator.newId()),
            programId = programId,
            revisionNumber = revisionNumber,
            mode = plan.mode,
            duration = plan.duration,
            schedule = plan.schedule,
            days = plan.days.map { day ->
                day.copy(
                    programDayId = requireNotNull(mintedByHandle[day.programDayId]),
                    exercises = day.exercises.map { element ->
                        element.copy(programExerciseId = ProgramExerciseId(idGenerator.newId()))
                    }
                )
            },
            createdAt = at,
            focus = plan.focus,
            preferredExercises = plan.preferredExercises
        )
        require(revision.structure == plan.structure) {
            "saving a draft re-identifies its plan and changes nothing else: the minted revision and " +
                "the draft it came from disagree structurally"
        }
        return MintedRevision(revision, mintedByHandle)
    }

    /**
     * The structure a save compares [draft] against, and the ordinal a new revision would carry.
     *
     * An edit compares against the Program's **current** revision, not the one the draft was opened from:
     * "did the plan change?" is a question about what is stored now, and a stale starting point would create
     * a second revision for a change the user already saved. A copy compares against its source, which is
     * informative rather than decisive — a copy creates a Program even when nothing changed.
     *
     * @return `null` for a create, which has nothing to compare against.
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
            focus = revision.focus,
            preferredExercises = revision.preferredExercises
        )

    /** The stored Program [programId] names, or a [EditorProgramMissing] refusal. */
    private suspend fun existingProgram(programId: ProgramId): Program =
        programRepository.programById(programId) ?: throw EditorProgramMissing(programId)

    /**
     * The copy-before-edit rule, for operations that change a Program's own content.
     *
     * Selection, copying and editing-by-copy are unaffected — those are the three things the built-in Program
     * does receive. The failure is the lifecycle layer's own [StandardProgramProtected], so the rule has one
     * implementation and the refusal one sentence.
     */
    private fun guardedForEdit(program: Program) {
        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)
    }
}

/** The structure a save is measured against, with the ordinal a new revision would carry. */
private data class BasePlan(val structure: ProgramStructure, val nextRevisionNumber: Int?)

/**
 * A minted revision and the correspondence that produced its plan-day identities.
 *
 * Private because the editor is the only component that mints them; the two travel together so the
 * correspondence can never be reported for a different revision than the one it minted.
 */
private data class MintedRevision(
    val revision: ProgramRevision,
    val mintedProgramDays: Map<ProgramDayId, ProgramDayId>
)

/**
 * A new revision minted for a reason that is **not** a structural change, with everything a caller needs to
 * attach a plan-day-named statement to it.
 *
 * Public, unlike the structural [MintedRevision], because a target-only change is performed by the save
 * boundary rather than by a draft save, and that boundary has to be able to *decline* to write the revision
 * after comparing the statement it would carry against what is stored — a comparison that needs the minted
 * correspondence before the write.
 *
 * @property program the Program as it will stand once this revision is saved — the *same* Program, with
 *   `currentRevisionId` and `updatedAt` already describing it. A target-only change never creates a Program.
 * @property revision the minted revision, whose structure equals the current one exactly.
 * @property mintedProgramDays the correspondence from each **current** plan-day identity to the identity that
 *   day becomes in [revision].
 */
class MintedTargetScheduleRevision(
    val program: Program,
    val revision: ProgramRevision,
    val mintedProgramDays: Map<ProgramDayId, ProgramDayId>
)

/**
 * Thrown inside the operation so the result boundary maps it to a rejection naming `ProgramNotFound`; it never
 * reaches a caller as an exception.
 */
internal class EditorProgramMissing(val programId: ProgramId) :
    RuntimeException(ProgramOperationRefusal.ProgramNotFound(programId).message)

/** A revision a draft names is not stored, so the draft's provenance is not real. */
internal class RevisionMissing(val revisionId: RevisionId) :
    RuntimeException("no revision '${revisionId.value}' is stored (§23)")

/** The draft is not finished, so nothing was written: the validate step rejected it. */
internal class DraftRejected(val validation: ProgramDraftValidation) :
    RuntimeException(
        "the draft cannot be saved: " + validation.issues.joinToString("; ") { it.message }
    )

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramEditorResult.Failed] rather than
 * absorbing it.
 */
internal inline fun <T> editorResult(block: () -> T): ProgramEditorResult<T> = try {
    ProgramEditorResult.Success(block())
} catch (failure: Throwable) {
    ProgramEditorResult.Failed(failure)
}

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
