package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.AppStateRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.MyPrograms
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.ProgramTransition
import com.monkfitness.app.domain.program.ProgramLifecyclePolicy
import com.monkfitness.app.domain.program.ProgramTransitionResult
import com.monkfitness.app.domain.program.applying
import com.monkfitness.app.domain.program.storageOutcome
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.program.ProgramOperationRefusal.HasInProgressSession
import com.monkfitness.app.domain.program.ProgramOperationRefusal.IllegalTransition
import com.monkfitness.app.domain.program.ProgramOperationRefusal.ProgramNotFound
import com.monkfitness.app.domain.program.ProgramOperationRefusal.StandardProgramCannotBeDeleted
import com.monkfitness.app.domain.program.ProgramOperationRefusal.StandardProgramCannotBeEdited
import com.monkfitness.app.domain.program.ProgramTransition.ARCHIVE
import com.monkfitness.app.domain.program.ProgramTransition.COMPLETE
import com.monkfitness.app.domain.program.ProgramTransition.PAUSE
import com.monkfitness.app.domain.program.ProgramTransition.RESUME
import com.monkfitness.app.domain.program.ProgramTransition.START
import com.monkfitness.app.domain.program.ProgramTransition.UNARCHIVE
import com.monkfitness.app.domain.workout.SessionStatus
import java.time.Instant
import java.time.LocalDate

/**
 * The Program System's lifecycle and selection decisions, over the persistence primitives.
 *
 * Its whole job is the decisions a repository must not make, and they are all rules about Programs: which
 * lifecycle transitions are legal (a planned start date is not one), that selection is one global fact and
 * never a Program flag, that deleting the selected Program falls back to the built-in Standard Program as a
 * *technical* fallback while archiving the selection is refused, that the built-in Program is selectable and
 * copyable but not editable or deletable, that a Program with an `IN_PROGRESS` session is not deletable and
 * the selection is never cleared behind the user's back, and that a lifecycle, selection, rename, archive or
 * planned-start change creates **no** revision.
 *
 * The built-in Program is *required* rather than seeded here: deciding what the app's own program plans is a
 * content decision above this layer, so a caller that has not seeded one gets `StandardProgramNotSeeded`
 * instead of a silently missing fallback.
 *
 * Delete order is load-bearing: the selection moves **before** the Program is deleted, because
 * `app_state.selectedProgramId` is `ON DELETE NO ACTION` — the database itself refuses to delete a Program
 * the state still names, which makes "never silently clear the selection" a storage guarantee.
 *
 * @param programRepository the Program aggregate: read, update its non-structural facts, delete it.
 * @param scheduleRepository the slots and pause intervals; read for the open-pause fact, write for opening
 *   and closing an interval.
 * @param appStateRepository the single global state row: the selection and the next-Program facts.
 * @param planRepository read-only here: [copyProgram] needs the plan it copies, and nothing in this stage
 *   mints a revision except a structural edit, which the editor owns.
 * @param clock `actualStartDate` is a fact, so the moment a Program started is read from here and never from
 *   a field.
 * @param standardProgramId the identity of the app's built-in Program. Required, not defaulted.
 * @param inTransaction runs a block in one database transaction, for the operations that must be atomic.
 */
class ProgramLifecycleService(
    private val programRepository: ProgramRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val appStateRepository: AppStateRepository,
    private val planRepository: ProgramPlanRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val standardProgramId: ProgramId,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The My Programs view: every saved Program, each paired with the global selection and its open-pause
     * fact.
     *
     * The join has to happen above the repositories — a Program carries no selection flag and no pause
     * state — and it is a *read*, so a failure propagates rather than becoming an empty list.
     */
    suspend fun myPrograms(): ProgramOperationResult<MyPrograms> = storageOutcome {
        val state = appStateRepository.state()
        val programs = programRepository.programs()
        val openPauses = programs
            .filter { it.lifecycleStatus == LifecycleStatus.PAUSED }
            .map { it.programId }
            .toSet()
        MyPrograms.from(programs, state, openPauses)
    }

    /**
     * The refusal rather than `null`: a caller asking for a Program it has an id for has either a stale id
     * or a bug, and turning an absent row into an empty result would hide both.
     */
    suspend fun program(programId: ProgramId): ProgramOperationResult<Program> = storageOutcome {
        programRepository.programById(programId)
            ?: throw ProgramMissing(programId)
    }.refusingOn(programId)

    /**
     * The revision [programId]'s `currentRevisionId` points at — the pointer decides, this method never
     * falls back to "the newest by number".
     *
     * A Program whose pointer names a revision that is not stored is **invalid persisted data** and
     * propagates as a failure rather than being reported as a Program with no plan.
     */
    suspend fun currentRevision(programId: ProgramId): ProgramOperationResult<ProgramRevision> =
        storageOutcome {
            val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
            planRepository.currentRevision(programId) ?: throw RevisionNotStored(program.currentRevisionId)
        }.refusingOn(programId)

    /**
     * Selection is the one global `AppState` row, so this touches no Program — which is what makes "one
     * selected Program at a time" a storage fact: the row has one `selectedProgramId`, so a second
     * selection *replaces* the first.
     *
     * Selecting a Program that is not stored is refused, because a state row pointing at nothing is the
     * stale pointer the schema's `NO ACTION` exists to prevent.
     */
    suspend fun selectProgram(programId: ProgramId): ProgramOperationResult<AppState> = storageOutcome {
        programRepository.programById(programId) ?: throw ProgramMissing(programId)
        val state = appStateRepository.state() ?: AppState()
        val updated = state.copy(selectedProgramId = programId)
        appStateRepository.save(updated)
        updated
    }.refusingOn(programId)

    /**
     * Both halves are the user's explicit choice and separate facts: auto-start defaults to off, and it
     * **never resumes a paused Program**.
     *
     * That rule is not enforced here on purpose — it constrains the scheduler that consumes this state, and
     * a guard here would be a second place that could disagree with it.
     */
    suspend fun configureNextProgram(
        nextProgramId: ProgramId?,
        autoStart: Boolean
    ): ProgramOperationResult<AppState> = storageOutcome {
        val state = appStateRepository.state() ?: AppState()
        val updated = state.copy(nextProgramId = nextProgramId, nextProgramAutoStart = autoStart)
        appStateRepository.save(updated)
        updated
    }

    /**
     * Starts [programId]: `NOT_STARTED → RUNNING`, recording the moment as the factual `actualStartDate`.
     *
     * The two rules are invisible in the body, and that is the point: nothing here reads `plannedStartDate`
     * — the only path from `NOT_STARTED` to `RUNNING` is this call and it is the user's — and the stamp is
     * the clock's `now()`, written once, not a plan.
     */
    suspend fun startProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, START)

    /** Pauses [programId]: `RUNNING → PAUSED`, opening a pause interval. */
    suspend fun pauseProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, PAUSE)

    /** Resumes [programId]: `PAUSED → RUNNING`, closing the open pause interval. */
    suspend fun resumeProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, RESUME)

    /** Completes [programId]: `RUNNING|PAUSED → COMPLETED`, terminal. */
    suspend fun completeProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, COMPLETE)

    /**
     * Archives [programId]: a stamp, not a lifecycle state.
     *
     * Archiving retains all history, stops future planning and creates no revision; an archived Program
     * keeps the lifecycle it reached. It is refused only by the one rule that makes it unsafe — archiving
     * the *selected* Program requires the user to choose another one first.
     */
    suspend fun archiveProgram(programId: ProgramId): ProgramOperationResult<Program> {
        val refusal = guardSelectionForArchive(programId)
        if (refusal != null) return refusal
        return lifecycle(programId, ARCHIVE)
    }

    /** Removes the archive stamp. Lifecycle untouched, history untouched. */
    suspend fun unarchiveProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, UNARCHIVE)

    /**
     * Renames [programId] and/or sets its description. Not a structural change, so no revision.
     *
     * The blank-name guard is a domain invariant of [Program], so it applies here as everywhere.
     */
    suspend fun renameProgram(
        programId: ProgramId,
        name: String? = null,
        description: String? = null
    ): ProgramOperationResult<Program> = storageOutcome {
        val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
        guardEditable(program)
        val renamed = program.copy(
            name = name ?: program.name,
            description = description ?: program.description,
            updatedAt = clock.now()
        )
        programRepository.updateProgram(renamed)
        renamed
    }.refusingOn(programId)

    /**
     * Sets the date a Program is *planned* to start: a plan, not a fact.
     *
     * This method's entire content is the rule it does not enforce — it writes a `LocalDate`, changes no
     * lifecycle, sets no `actualStartDate` and creates no revision. Only [startProgram] starts.
     */
    suspend fun setPlannedStartDate(
        programId: ProgramId,
        plannedStartDate: LocalDate?
    ): ProgramOperationResult<Program> = storageOutcome {
        val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
        guardEditable(program)
        val planned = program.copy(plannedStartDate = plannedStartDate, updatedAt = clock.now())
        programRepository.updateProgram(planned)
        planned
    }.refusingOn(programId)

    /**
     * Deletes [programId] and everything it owns.
     *
     * Three rules gate it, in the order the database's own constraints force: the built-in Program is not
     * deletable; a Program with an `IN_PROGRESS` session is not deletable and the selection is **not**
     * cleared to make it pass; and when the Program being deleted *is* the selection, the selection moves to
     * the built-in Program first — the "technical fallback", not a user choice, done *before* the delete so
     * `ON DELETE NO ACTION` can hold rather than being worked around.
     *
     * The cascade itself is the schema's; the global tables that are not Program-owned survive.
     */
    suspend fun deleteProgram(programId: ProgramId): ProgramOperationResult<ProgramId> {
        if (programId == standardProgramId) {
            return ProgramOperationResult.Refused(programId, StandardProgramCannotBeDeleted)
        }
        val guard = guardDeletable(programId)
        if (guard != null) return guard
        val existing = programRepository.programById(programId)
            ?: return ProgramOperationResult.Refused(programId, ProgramNotFound(programId))

        return storageOutcome {
            inTransaction {
                val state = appStateRepository.state()
                if (state?.selectedProgramId == programId) {
                    moveSelectionToStandard(programId)
                }
                programRepository.deleteProgram(programId)
            }
            existing.programId
        }
    }

    /**
     * Copies [programId] into a new user-owned Program with the same plan.
     *
     * The copy is how the built-in Program gets edited — "editing Standard means creating a user copy first"
     * — and a copy of any Program is the user's own, so it may be edited, renamed and deleted freely. The
     * name is the caller's because the UI decides how "Copy of X" is phrased; the copy's [ProgramSource] is
     * [ProgramSource.USER] regardless of the source's, which is what "a copy is the user's own" means.
     *
     * No revision of the *source* is created: copying changes nothing about the Program it copies.
     */
    suspend fun copyProgram(
        programId: ProgramId,
        name: String,
        description: String = ""
    ): ProgramOperationResult<Program> = storageOutcome {
        require(name.isNotBlank()) { "a copied Program has a name" }
        val source = programRepository.programWithCurrentRevision(programId)
            ?: throw ProgramMissing(programId)
        val now = clock.now()
        val copiedId = ProgramId(idGenerator.newId())
        // Copied as *new rows*: fresh identities for the revision, every day and every occurrence, because
        // reusing the source's would make two Programs share one plan instead of one copying the other.
        // Structure and prescriptions carry across unchanged — that is what is being copied.
        val copiedRevision = source.currentRevision.copy(
            revisionId = com.monkfitness.app.domain.common.RevisionId(idGenerator.newId()),
            programId = copiedId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            createdAt = now,
            days = source.currentRevision.days.map { day ->
                day.copy(
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId(idGenerator.newId()),
                    exercises = day.exercises.map { element ->
                        element.copy(
                            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId(
                                idGenerator.newId()
                            )
                        )
                    }
                )
            }
        )
        val copy = Program(
            programId = copiedId,
            name = name,
            description = description,
            source = ProgramSource.USER,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = copiedRevision.revisionId,
            createdAt = now,
            updatedAt = now,
            plannedStartDate = null,
            actualStartDate = null,
            archivedAt = null
        )
        programRepository.createProgram(copy, copiedRevision)
        copy
    }.refusingOn(programId)

    /**
     * One lifecycle transition: decide it, refuse it, or apply it.
     *
     * The decision is [ProgramLifecyclePolicy]'s — pure, over two statuses, the open-pause fact and the
     * archive stamp. What this method owns is everything that turns that decision into stored state: a
     * refused transition is returned and nothing is written (an expected state is a result, not a failure);
     * an allowed one applies [Program.applying], which changes the lifecycle and its stamps and nothing else
     * — in particular no `currentRevisionId`; `PAUSE` opens an interval and `RESUME`/`COMPLETE` close it,
     * because a pause *is* an interval; and the write is the non-structural update, so no plan row is touched.
     */
    private suspend fun lifecycle(
        programId: ProgramId,
        transition: ProgramTransition
    ): ProgramOperationResult<Program> = storageOutcome {
        val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
        val openPause = scheduleRepository.pausesOfProgram(programId).any { it.isOpen }
        val decision = if (transition == ARCHIVE || transition == UNARCHIVE) {
            // Archive is not a lifecycle state: it has its own guard and never consults the transition
            // table, so an archived Program can still be completed or resumed.
            if (transition == ARCHIVE) {
                ProgramLifecyclePolicy.archiveDecision(program.lifecycleStatus, program.isArchived)
            } else {
                if (!program.isArchived) {
                    ProgramTransitionResult.AlreadyThere(UNARCHIVE, program.lifecycleStatus, archived = true)
                } else {
                    ProgramTransitionResult.Allowed(UNARCHIVE, program.lifecycleStatus, archived = false)
                }
            }
        } else {
            ProgramLifecyclePolicy.decision(program.lifecycleStatus, transition, openPause, program.isArchived)
        }
        when (decision) {
            is ProgramTransitionResult.Refused -> throw RefusedTransition(decision)
            is ProgramTransitionResult.AlreadyThere -> program
            is ProgramTransitionResult.Allowed -> {
                val at = clock.now()
                val updated = program.applying(transition, at)
                if (transition == PAUSE) {
                    scheduleRepository.addPause(
                        ProgramPause(
                            pauseId = PauseId(idGenerator.newId()),
                            programId = programId,
                            startedAt = at
                        )
                    )
                } else if (transition == RESUME || transition == COMPLETE) {
                    closeOpenPause(programId, at)
                }
                programRepository.updateProgram(updated)
                updated
            }
        }
    }.refusingOn(programId)

    /** Closes the currently open pause interval of [programId] at [at], if there is one. */
    private suspend fun closeOpenPause(programId: ProgramId, at: Instant) {
        val open = scheduleRepository.pausesOfProgram(programId).firstOrNull { it.isOpen } ?: return
        scheduleRepository.closePause(open.pauseId, at)
    }

    /**
     * The `IN_PROGRESS` guard, before any row is removed.
     *
     * Read through the session repository's list read because "this Program has a workout in flight" is a
     * fact about sessions the delete path must see. Not counted in SQL on purpose: the one session that
     * matters is easier to name in the refusal than a number is to interpret.
     */
    private suspend fun guardDeletable(programId: ProgramId): ProgramOperationResult.Refused? {
        val inProgress = sessionRepository.sessionsOfProgram(programId)
            .any { it.status == SessionStatus.IN_PROGRESS }
        return if (inProgress) {
            ProgramOperationResult.Refused(programId, HasInProgressSession(programId))
        } else {
            null
        }
    }

    /** The archive-of-selection guard: archiving the selected Program requires choosing another one. */
    private suspend fun guardSelectionForArchive(programId: ProgramId): ProgramOperationResult.Refused? {
        val state = appStateRepository.state() ?: return null
        return if (state.selectedProgramId == programId) {
            ProgramOperationResult.Refused(
                programId,
                ProgramOperationRefusal.ArchivingTheSelectionRequiresAnotherSelection(programId)
            )
        } else {
            null
        }
    }

    /**
     * The copy-before-edit rule, for operations that change a Program's own content: the built-in Program's
     * content is the app's, not the user's.
     *
     * Selection, copying and — through [copyProgram] — editing-by-copy are unaffected, because those are the
     * three things the built-in Program does receive.
     */
    private fun guardEditable(program: Program) {
        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)
    }

    /**
     * Moves the selection to the built-in Standard Program, the technical fallback.
     *
     * Refused loudly when the Program is not seeded: the fallback is a rule the architecture depends on, so
     * a caller that reaches it with nothing to fall back to learns that rather than having the selection
     * silently cleared.
     */
    private suspend fun moveSelectionToStandard(deletedProgramId: ProgramId) {
        val standard = programRepository.programById(standardProgramId)
            ?: throw StandardProgramNotSeeded(standardProgramId, deletedProgramId)
        appStateRepository.save(
            (appStateRepository.state() ?: AppState()).copy(selectedProgramId = standard.programId)
        )
    }

}

/**
 * Thrown inside the operation so the result boundary can map it to a refusal naming `ProgramNotFound`; it
 * never reaches a caller as an exception.
 */
internal class ProgramMissing(programId: ProgramId) : RuntimeException(ProgramNotFound(programId).message)

/**
 * The row and its pointer disagree: invalid persisted data rather than an expected state, so it reaches the
 * caller as a failure instead of a refusal or an empty plan.
 */
internal class RevisionNotStored(revisionId: RevisionId) :
    RuntimeException("the revision '${revisionId.value}' a Program's pointer names is not stored (§23)")

/** The built-in Standard Program's content is protected. */
internal class StandardProgramProtected(programId: ProgramId) :
    RuntimeException(StandardProgramCannotBeEdited.message + " — program '${programId.value}'")

/**
 * The delete-fallback needed the built-in Program and it is not stored: the declared id names nothing, so
 * the fallback cannot fire.
 *
 * Reported rather than absorbed, because clearing the selection instead would be exactly the silent state
 * repair the delete path forbids.
 */
class StandardProgramNotSeeded(
    val expectedStandardProgramId: ProgramId,
    val deletedProgramId: ProgramId
) : RuntimeException(
    "the Standard Program '${expectedStandardProgramId.value}' is not stored, so the selection " +
        "cannot fall back to it after deleting '${deletedProgramId.value}' (§3)"
)

/** A lifecycle decision was refused, reported as a typed result rather than raised. */
internal class RefusedTransition(val decision: ProgramTransitionResult.Refused) :
    RuntimeException(decision.reason)

private fun <T> ProgramOperationResult<T>.refusingOn(programId: ProgramId): ProgramOperationResult<T> =
    when (this) {
        is ProgramOperationResult.Success -> this
        is ProgramOperationResult.Refused -> this
        is ProgramOperationResult.Failure -> when (val cause = cause) {
            is ProgramMissing -> ProgramOperationResult.Refused(programId, ProgramNotFound(programId))
            is StandardProgramProtected ->
                ProgramOperationResult.Refused(programId, StandardProgramCannotBeEdited)
            is RefusedTransition ->
                ProgramOperationResult.Refused(programId, IllegalTransition(cause.decision))
            else -> this
        }
    }
