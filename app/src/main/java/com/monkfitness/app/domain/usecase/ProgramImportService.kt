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
 * Importing a Program: bytes → transfer document → validated draft → one new, independent Program.
 *
 * Each step is its own concern with its own findings, and the order is the pipeline's: an unsupported version is
 * *that* answer and not a pile of schema complaints, an unknown exercise is *that* answer and not a
 * semantic one, and a document that cannot become a draft never reaches a repository.
 *
 * Identity is all minted, never reused: the format carries no identity, the draft's handles are replaced
 * at save, so an imported Program has no foreign linkage and importing the same file twice yields two
 * fully independent Programs.
 *
 * The save is **one transaction** — Program, first revision, days, elements and the revision's initial
 * slots, plus the selection when it is on — so a failure at any leg leaves no partial Program.
 *
 * The planned start date is written here because the creation unit must include the initial slots and the
 * Scheduler refuses to plan without an anchor; inventing one there would be the Scheduler deciding when
 * the user's program begins. It is a *plan*: the lifecycle stays `NOT_STARTED` and `actualStartDate` is
 * `null`, so nothing starts.
 *
 * @param programRepository the creation unit for the four Program-owned tables plus the slots. It exposes
 *   no lifecycle, no selection and no deletion, so an import cannot move a Program it did not create.
 * @param scheduler the timing owner: it decides which opportunities the imported revision receives. This
 *   class never builds a slot or assembles a scheduling request.
 * @param lifecycleService the selection owner, so the importer never writes `AppState`.
 * @param clock every timestamp of the imported graph, and the day it is planned to start on. Read once per
 *   save, so one import is one moment.
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
     * The draft's own handles, minted the way the editor mints its own. Minting one stores nothing and the
     * save replaces every one of them, so no handle that exists because a document was parsed can reach a
     * row.
     */
    private val draftIds: DraftIdSource = DraftIdSource { idGenerator.newId() }

    /**
     * The pipeline up to and including the Import Draft: everything knowable **before** anything is
     * written, so reviewing an import that is never accepted leaves no trace.
     *
     * The document is refused as a whole for every failure — an unknown exercise, a malformed prescription,
     * an invalid focus — and never partially accepted.
     */
    suspend fun review(bytes: ByteArray): ProgramTransferResult<ProgramImportDraft> = transferResult {
        val text = ProgramTransferFormat.decode(bytes)
        val document = ProgramTransferReader.read(text)

        // exerciseId validation: ask the library that owns the answer, invent and drop nothing, and name
        // every id that could not be resolved.
        val unknown = document.referencedExerciseIds.filter { exerciseId ->
            !exerciseLibrary.knows(exerciseId)
        }
        if (unknown.isNotEmpty()) throw UnknownDocumentExercises(unknown)

        val issues = ProgramTransferValidation.issuesIn(document)
        if (issues.isNotEmpty()) throw SemanticViolation(issues)

        // The mapping is total for a validated document, so the catch is a finding about a *document*
        // rather than a crash: the file the user picked produced a value the domain refuses, and blaming
        // the system would attribute a defect to the file's owner.
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

        // The draft is checked by the *domain's* own validation, the same one `Save` consults in the
        // editor, so an import can never produce a draft the editor would then refuse.
        val findings = plan.validation().issues
        if (findings.isNotEmpty()) {
            throw SemanticViolation(listOf(ProgramTransferIssue.PlanNotSavable(findings)))
        }

        ProgramImportDraft(document, plan)
    }.rejecting()

    /**
     * Saves an accepted draft as a **new, independent, user-owned Program**.
     *
     * @param draft a draft produced by [review]; it is the only source of the imported plan.
     * @param makeActive the one optional choice, **off by default**. When off, the selection is not touched
     *   at all — not cleared, not kept, not compared. When on, the imported Program becomes selected through
     *   [ProgramLifecycleService], inside the same transaction as the creation.
     * @param plannedStartDate the date the imported Program is **planned** to start on, or `null` for the day
     *   it arrived. It travels here rather than being read back from the row just written, because the
     *   initial slots are planned from this date: importing and then moving the date would leave the first
     *   slots anchored to a date the user never chose.
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
            // An imported Program is never the Program it came from and never the built-in one; the format
            // carries no source, so nothing here could disagree with this line.
            source = ProgramSource.IMPORTED,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = revision.revisionId,
            createdAt = at,
            updatedAt = at,
            plannedStartDate = plannedStartDate ?: importedOn(at),
            actualStartDate = null,
            archivedAt = null
        )

        // The Scheduler decides what opportunities the imported revision receives. A refusal (unreachable
        // given the start date above) and a failure are both propagated rather than skipped, so the whole
        // import rolls back.
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

    /**
     * The start date when the caller names none: the day it was imported, read once in [zone].
     *
     * It is a *plan*, not a fact — the lifecycle stays `NOT_STARTED` and `actualStartDate` is `null` — but it
     * gives the Scheduler the anchor it needs instead of a date the Scheduler invents.
     */
    private fun importedOn(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

    /**
     * Selects through the layer that owns selection: the importer never writes `AppState`. A refusal
     * propagates, which keeps the selection inside the import's atomic unit — a failed creation cannot
     * leave a selection pointing at a Program that does not exist.
     */
    private suspend fun select(programId: ProgramId) {
        when (val selected = lifecycleService.selectProgram(programId)) {
            is ProgramOperationResult.Success -> Unit
            is ProgramOperationResult.Refused -> throw IllegalStateException(selected.reason.message)
            is ProgramOperationResult.Failure -> throw selected.cause
        }
    }
}
