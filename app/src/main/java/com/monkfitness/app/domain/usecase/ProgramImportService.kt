package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.transfer.ExerciseLibrary
import com.monkfitness.app.domain.program.transfer.ImportSchedulingRefused
import com.monkfitness.app.domain.program.transfer.ProgramImportDraft
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.program.transfer.ProgramTransferIssue
import com.monkfitness.app.domain.program.transfer.ProgramTransferMapper
import com.monkfitness.app.domain.program.transfer.ProgramTransferReader
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.program.transfer.ProgramTransferValidation
import com.monkfitness.app.domain.program.transfer.SemanticViolation
import com.monkfitness.app.domain.program.transfer.UnknownDocumentExercises
import com.monkfitness.app.domain.program.transfer.rejecting
import com.monkfitness.app.domain.program.transfer.transferResult
import com.monkfitness.app.domain.program.validation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Importing a Program — §30 step 13, the second half of §5 and §27's creation unit.
 *
 * ### The pipeline, and where each step is decided
 *
 * ```text
 * bytes / text              ProgramTransferFormat.decode      UTF-8, reported rather than repaired
 *   ↓ parse                 ProgramTransferReader (Json)      a syntax failure produces no value at all
 *   ↓ formatVersion         ProgramTransferReader             present, whole, supported
 *   ↓ transfer schema       ProgramTransferReader             fields, kinds, tokens, per-variant allowlists
 *   ↓ exerciseId            this class + ExerciseLibrary      a yes/no per referenced id, from the library
 *   ↓ semantic validation   ProgramTransferValidation         the domain's own value rules, decided first
 *   ↓ Import Draft          ProgramTransferMapper.draftOf     domain values + the domain's own draft validation
 *   ↓ save new Program      this class, in one transaction    §27: Program + Revision + plan + initial slots
 * ```
 *
 * Every step is a separate concern with its own findings, and the order is §5's own: a version this reader
 * does not know is *that* answer and not a pile of schema complaints, an unknown exercise is *that* answer
 * and not a semantic one, and a document that cannot be turned into a draft never reaches a repository.
 *
 * ### Identity: what is minted, and why nothing is reused
 *
 * A save mints a new `ProgramId`, a new `RevisionId`, a new `ProgramDayId` per day and a new
 * `ProgramExerciseId` per occurrence (§3), and there is no code path that could do otherwise: the format
 * carries no identity at all ([com.monkfitness.app.domain.program.transfer.ProgramTransferDocument]), the
 * draft's handles are replaced by [ProgramTransferMapper.revisionOf], and the reviewer is handed a draft
 * that names no Program. An imported Program therefore has **no foreign identity linkage** to whatever it
 * came from — and importing the same file twice produces two fully independent Programs (§5).
 *
 * ### Ownership: the source, the start date, the selection
 *
 * ```text
 * source            IMPORTED, always (§9, §10): the file cannot say, and an imported copy of the STANDARD
 *                   Program is a user-owned import that may be edited and deleted like any other
 * start             plannedStartDate = the day of the import, from the injected clock and the injected
 *                   calendar — a *plan*, and nothing about it starts anything (§3). The lifecycle stays
 *                   NOT_STARTED and actualStartDate is null, so §3's "a planned start date does not
 *                   automatically start a Program" holds exactly as it does everywhere else
 * selection         off unless the caller says otherwise (§9): one explicit boolean, default false
 * ```
 *
 * The planned start date is written here because §27 requires the creation unit to include the revision's
 * initial slots, and the Scheduler refuses to plan a Program that has no date to plan from — deliberately,
 * because inventing one would be the Scheduler deciding when the user's program begins
 * ([com.monkfitness.app.domain.program.ProgramSchedulingRefusal.NoSchedulingAnchor]). The date is
 * therefore supplied by the layer that decides what Program is being created, from the injected clock, as
 * `docs/PROGRAM_IMPORT_EXPORT.md` records. Nothing about it is exported or imported.
 *
 * ### Atomicity
 *
 * The save is **one transaction**: the Program, its first revision, its days, its elements and the
 * revision's initial slots are written together, and the explicit selection — when it is on — moves inside
 * the same unit. A failure at any leg rolls all of it back through the database's own transaction, which is
 * why a failed import leaves no partial Program, no partial revision and no partial slot (§13, §27).
 * [ProgramRepository.createProgram] is the primitive that carries it, and the slots written are the
 * Scheduler's answer rather than the importer's own construction (§8).
 *
 * @param programRepository the Program aggregate's creation unit: the four Program-owned tables and the
 *   slots, written as one. It exposes no lifecycle, no selection and no deletion, so an import cannot move
 *   a Program it did not create.
 * @param scheduler the timing owner: it decides which opportunities the imported revision receives (§20).
 *   This class never builds a slot, never chooses a date and never assembles a scheduling request.
 * @param lifecycleService the selection owner: the only path by which the imported Program can become the
 *   selected one (§3, §21). The importer never writes `AppState`.
 * @param exerciseLibrary §5's exerciseId boundary: the app's own catalogue, asked whether an id exists.
 * @param clock the clock every timestamp of the imported graph comes from (§18): the Program's `createdAt`
 *   and `updatedAt`, the revision's `createdAt`, and the day the imported Program is planned to start on.
 *   Read once per save, so one import is one moment.
 * @param idGenerator the identity source: the Program, the revision, every day and every occurrence (§26).
 * @param zone the calendar the save's instant is read as a date in — the date an imported Program is
 *   planned to start on, and the date the Scheduler plans from.
 * @param inTransaction runs a block in one database transaction. Production takes the database's own
 *   `withTransaction` from the composition root, exactly as the repositories do.
 */
class ProgramImportService(
    private val programRepository: ProgramRepository,
    private val scheduler: ProgramScheduler,
    private val lifecycleService: ProgramLifecycleService,
    private val exerciseLibrary: ExerciseLibrary,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val zone: ZoneId,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The identities a draft's days and elements are addressed by while it is being reviewed.
     *
     * They are the draft's own handles, minted the way the editor mints its own: minting one stores
     * nothing, and the save replaces every one of them, so no handle that exists because a document was
     * parsed can reach a row (§6, §23).
     */
    private val draftIds: DraftIdSource = DraftIdSource { idGenerator.newId() }

    // ---------------------------------------------------------------- the pipeline (§5)

    /**
     * §5's pipeline, up to and including the Import Draft: everything that can be known about a document
     * **before** anything is written.
     *
     * A caller reviews the result and decides; nothing here writes, so reviewing an import that is never
     * accepted leaves no trace anywhere. The document is refused as a whole for every failure — an unknown
     * exercise, a malformed prescription, an invalid focus — and never partially accepted (§5).
     */
    suspend fun review(bytes: ByteArray): ProgramTransferResult<ProgramImportDraft> = transferResult {
        val text = ProgramTransferFormat.decode(bytes)
        val document = ProgramTransferReader.read(text)

        // §5's exerciseId validation: the ids this document references, asked of the library that owns the
        // answer. Nothing is invented, substituted or dropped, and the refusal names every id it could not
        // resolve.
        val unknown = document.referencedExerciseIds.filter { exerciseId ->
            !exerciseLibrary.knows(exerciseId)
        }
        if (unknown.isNotEmpty()) throw UnknownDocumentExercises(unknown)

        val issues = ProgramTransferValidation.issuesIn(document)
        if (issues.isNotEmpty()) throw SemanticViolation(issues)

        // The mapping is total for a validated document: every rule the domain's constructors enforce has
        // already been decided above. The catch is therefore a finding about a *document* rather than a
        // crash, which is the honest reading of "the file the user picked produced a value the domain
        // refuses" — a system failure would attribute a defect to the file's owner.
        val plan = try {
            ProgramTransferMapper.draftOf(document, draftIds)
        } catch (failure: IllegalArgumentException) {
            throw SemanticViolation(
                listOf(
                    ProgramTransferIssue.InvalidDefinition(
                        "revision",
                        failure.message ?: "the document does not describe a plan the domain can hold"
                    )
                )
            )
        }

        // §6's last step, and the reuse §6 asks for: the draft is checked by the *domain's* own validation,
        // the same one `Save` consults in the editor, so an import can never produce a draft the editor
        // would then refuse. Its findings travel as the domain's own issues.
        val findings = plan.validation().issues
        if (findings.isNotEmpty()) {
            throw SemanticViolation(listOf(ProgramTransferIssue.PlanNotSavable(findings)))
        }

        ProgramImportDraft(document, plan)
    }.rejecting()

    // ---------------------------------------------------------------- the save (§27)

    /**
     * Saves an accepted draft as a **new, independent, user-owned Program**.
     *
     * @param draft a draft produced by [review]. It is the only source of the imported plan, and its
     *   document is what makes the imported graph's definition provably the file's.
     * @param makeActive §5's one optional choice, and §9's explicit opt-in: **off by default**. When it is
     *   off, the selection is not touched at all — not cleared, not kept, not compared. When it is on, the
     *   imported Program becomes the selected Program through [ProgramLifecycleService], which is the layer
     *   that owns selection, inside the same transaction as the creation.
     * @param plannedStartDate the date the imported Program is **planned** to start on, or `null` for
     *   the day it arrived. It is the user's choice when the import UI offers one, and it is part of
     *   the import request rather than a second transaction: §27's creation unit is *"Program +
     *   Revision + ProgramDays + ProgramExercises + initial Slots"*, and the initial slots are planned
     *   **from this date**, so importing and then moving the date would leave the first slots anchored
     *   to a date the user never chose. The value is a plan (§3) and starts nothing: the lifecycle is
     *   still `NOT_STARTED` and `actualStartDate` is still `null`, and the Scheduler refuses to plan
     *   without an anchor rather than inventing one, which is why the date travels here rather than
     *   being read from the row the save just wrote.
     */
    suspend fun save(
        draft: ProgramImportDraft,
        makeActive: Boolean = false,
        plannedStartDate: LocalDate? = null
    ): ProgramTransferResult<Program> = transferResult {
        val at = clock.now()
        val programId = ProgramId(idGenerator.newId())
        val revision = ProgramTransferMapper.revisionOf(draft.plan, programId, at, draftIds)

        val program = Program(
            programId = programId,
            name = draft.plan.name,
            description = draft.plan.description,
            // §9: an imported Program is never the Program it came from and never the built-in one. The
            // format does not carry a source, so there is nothing here that could disagree with this line.
            source = ProgramSource.IMPORTED,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = revision.revisionId,
            createdAt = at,
            updatedAt = at,
            plannedStartDate = plannedStartDate ?: importedOn(at),
            actualStartDate = null,
            archivedAt = null
        )

        // §8: the Scheduler decides what opportunities the imported revision receives. A refusal here (which
        // the planned start date above makes unreachable) is answered rather than skipped, and a failure is
        // propagated rather than absorbed — and because neither is written, the whole import rolls back.
        val slots = when (val decided = scheduler.initialSlotsFor(program, revision)) {
            is ProgramSchedulingResult.Success -> decided.value
            is ProgramSchedulingResult.Refused -> throw ImportSchedulingRefused(decided.reason)
            is ProgramSchedulingResult.Failure -> throw decided.cause
        }

        inTransaction {
            programRepository.createProgram(program, revision, slots)
            if (makeActive) select(programId)
        }
        program
    }.rejecting()

    // ---------------------------------------------------------------- the mechanism

    /**
     * The date an imported Program is planned to start on when the caller names none: the day it was
     * imported, read once in [zone].
     *
     * It is a *plan*, not a fact (§3): the imported Program's lifecycle stays `NOT_STARTED` and its
     * `actualStartDate` is `null`, so this writes no history and starts nothing. What it does is give the
     * Scheduler the anchor §27's creation unit needs — a fact of the Program, supplied by the layer that
     * decides what Program is created, rather than a date the Scheduler invents. A caller that *does* name
     * a date replaces this default; both travel the same way, as the import request's own fact.
     */
    private fun importedOn(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

    /**
     * Selects the imported Program through the layer that owns selection (§3, §21).
     *
     * The importer never writes `AppState` — it asks [ProgramLifecycleService], which is the single owner of
     * that fact and the only place the Standard-Program fallback and the state row live. A refusal or a
     * failure here propagates, which is what makes the selection part of the import's atomic unit: an import
     * whose creation failed cannot have left a selection pointing at a Program that does not exist.
     */
    private suspend fun select(programId: ProgramId) {
        when (val selected = lifecycleService.selectProgram(programId)) {
            is ProgramOperationResult.Success -> Unit
            is ProgramOperationResult.Refused -> throw IllegalStateException(selected.reason.message)
            is ProgramOperationResult.Failure -> throw selected.cause
        }
    }
}
