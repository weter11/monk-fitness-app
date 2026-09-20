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
 * The Program System's lifecycle and selection decisions — §30 step 5, over the persistence of steps
 * 3 and 4.
 *
 * ### What this layer is, and what it refuses to be
 *
 * The blueprint's layering is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI` (§25), and this class
 * is the fourth of those. Its entire job is the decisions the blueprint will not let the repositories
 * make — because a repository that decided them would be a data layer making policy — and the
 * blueprint is explicit about which ones those are (§3, §4, §29):
 *
 *  * which lifecycle transitions are legal, and that a planned start date is not one of them;
 *  * that selection is one global fact and never a Program flag;
 *  * that deleting the selected Program selects the built-in Standard Program as a *technical*
 *    fallback, and that archiving the selected Program is refused until the user picks another one;
 *  * that the built-in Standard Program is selectable, copyable and shareable but not editable or
 *    deletable directly;
 *  * that a Program with an `IN_PROGRESS` session is not deletable, and that the selection is not
 *    cleared behind the user's back to make such a delete succeed;
 *  * that a lifecycle, selection, rename, archive or planned-start change creates **no** revision,
 *    while a structural change must go through the immutable-revision mechanism.
 *
 * Each of those is a *rule about Programs*, so it lives above the repositories and is decided from
 * domain values; each repository below stays what step 3 made it — a persistence primitive. Nothing
 * here asks a DAO a question a DAO cannot answer, and no decision moves downward: [ProgramRepository]
 * still stores what it is told, [AppStateRepository] still writes one row, and
 * [ProgramScheduleRepository] still records an interval the caller decided to open or close.
 *
 * ### Why the Standard Program is not seeded here
 *
 * §3's delete-fallback rule needs the built-in Program to exist. Seeding it — deciding what the
 * app's own program plans — is a content decision with the editor, the scheduler and the Focus
 * Planner behind it (§30 steps 6–10), none of which exist yet. This layer therefore *requires* the
 * Standard Program rather than inventing it: [ProgramLifecycleService] is constructed with the id the
 * app has chosen, and [StandardProgram] documents the contract. A caller that has not seeded one yet
 * gets a [ProgramLifecycleService.StandardProgramNotSeeded] refusal rather than a silently-missing
 * fallback, because a fallback that is absent when the rule needs it is worse than no fallback.
 *
 * ### Transactions
 *
 * §27 names `Delete Program → complete ownership cascade` as an atomic operation, and the delete path
 * here is the composition of that: the selection is moved *before* the Program is deleted, because the
 * schema's `app_state.selectedProgramId` is `ON DELETE NO ACTION` — the database itself refuses to
 * delete a Program the state still names, which is the mechanism that makes "never silently clear the
 * selection" a storage-level guarantee rather than a convention. The move and the delete are therefore
 * ordered so the constraint can hold, and the delete is delegated to the cascade step 3 shipped. The
 * remaining atomic operations of §27 (`Start Workout`, `Confirm Set`, `Complete Workout + Adaptive`)
 * belong to steps 8 and 12 and are deliberately absent.
 *
 * @param programRepository the Program aggregate: read, update its non-structural facts, delete it.
 * @param scheduleRepository the slots and the pause intervals; read for the open-pause fact, write for
 *   opening and closing an interval.
 * @param sessionRepository the sessions; read for the `IN_PROGRESS` guard (§29).
 * @param appStateRepository the single global state row: the selection and the next-Program facts.
 * @param planRepository the revision mechanism a structural change must go through (§6). Read-only
 *   here — [copyProgram] needs the plan it copies, and nothing in this stage mints a revision except
 *   a structural edit, which the editor stage will own.
 * @param clock the clock the factual timestamps come from. `actualStartDate` is a fact (§3), so the
 *   moment a Program started is read from the injected clock and never from a field.
 * @param idGenerator mints a new Program's identity and its copy's revision identity (§26).
 * @param standardProgramId the identity of the app's built-in Program (§4). Required, not defaulted.
 * @param inTransaction runs a block in one database transaction, for the operations §27 requires to be
 *   atomic. Production takes it from the composition root, exactly as the repositories do.
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

    // ---------------------------------------------------------------- the read surface (§21, §22)

    /**
     * The My Programs view: every saved Program, each paired with the global selection and its
     * open-pause fact (§21).
     *
     * The join is here because it has to be somewhere above the repositories — a Program carries no
     * selection flag (§3) and no pause state, so a list of `Program` values cannot answer either
     * question on its own — and it is a *read*, so a failure propagates as [ProgramOperationResult.Failure]
     * rather than as an empty list (§33).
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
     * One Program, or a [ProgramNotFound] refusal when no such Program is stored.
     *
     * The refusal rather than `null`: §33 forbids turning an absent row into an empty result, and a
     * caller asking for a Program it has an id for has either a stale id or a bug — both worth
     * distinguishing from a successful empty.
     */
    suspend fun program(programId: ProgramId): ProgramOperationResult<Program> = storageOutcome {
        programRepository.programById(programId)
            ?: throw ProgramMissing(programId)
    }.refusingOn(programId)

    /**
     * The revision [programId]'s `currentRevisionId` points at — §22's *"current Revision"*, and the
     * only read of a Program's structural facts that does not go through the editor.
     *
     * §22's Program Detail is *current-state management*: it shows the Program's mode, its schedule and
     * which revision those facts belong to, and all three live on the revision rather than on the
     * Program (§6, §23). The detail screen cannot ask a repository for them (§25: the UI reaches no
     * data layer), so the read is here, beside the selection read, over the collaborator this service
     * already holds read-only for [copyProgram]. No new collaborator, no new decision: the pointer
     * decides which revision is current, exactly as the export path reads it (§17 of the transfer
     * document), and this method does not fall back to "the newest by number".
     *
     * A Program that is not stored is [ProgramOperationRefusal.ProgramNotFound]; a Program whose
     * pointer names a revision that is not stored is **invalid persisted data** and propagates as a
     * [ProgramOperationResult.Failure] rather than being reported as a Program with no plan (§23, §28,
     * §33).
     */
    suspend fun currentRevision(programId: ProgramId): ProgramOperationResult<ProgramRevision> =
        storageOutcome {
            val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
            planRepository.currentRevision(programId) ?: throw RevisionNotStored(program.currentRevisionId)
        }.refusingOn(programId)

    // ---------------------------------------------------------------- selection (§3, §21)

    /**
     * Selects [programId] as the Program the user is currently in.
     *
     * Selection is global `AppState`, so this writes the one state row and touches no Program —
     * which is what makes "only one selected Program at a time" a storage-level fact: the row has one
     * `selectedProgramId`, so selecting a second Program *replaces* the first rather than adding to a
     * set of them. Built-in Programs are selectable (§4); nothing else about the selection depends on
     * the source.
     *
     * A selection of a Program that is not stored is refused, because a state row pointing at nothing
     * is the stale pointer §29's `NO ACTION` exists to prevent.
     */
    suspend fun selectProgram(programId: ProgramId): ProgramOperationResult<AppState> = storageOutcome {
        programRepository.programById(programId) ?: throw ProgramMissing(programId)
        val state = appStateRepository.state() ?: AppState()
        val updated = state.copy(selectedProgramId = programId)
        appStateRepository.save(updated)
        updated
    }.refusingOn(programId)

    /**
     * Configures which Program starts next and whether that start is automatic (§3).
     *
     * Both halves are the user's explicit choice, and the two are separate facts: `nextProgramAutoStart`
     * defaults to off, and **auto-start never resumes a paused Program** (§3). This method makes no
     * attempt to enforce the latter, and that is deliberate — the rule constrains the *scheduler* that
     * will consume this state (§30 step 7), and a guard here would be a second place that could
     * disagree with it. It records the choice and nothing more.
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

    // ---------------------------------------------------------------- lifecycle (§3)

    /**
     * Starts [programId]: `NOT_STARTED → RUNNING`, recording the moment as the factual
     * `actualStartDate` (§3).
     *
     * The two rules this method exists to keep are both invisible in its body, and that is the point:
     *
     *  * **a planned start date never starts a Program.** Nothing here reads `plannedStartDate`. The
     *    only path from `NOT_STARTED` to `RUNNING` is this call, and it is the user's.
     *  * **`actualStartDate` is factual.** The stamp is the clock's `now()`, not a plan, and it is
     *    written once, here.
     *
     * The pause interval is closed if one is open, because a Program that starts cannot be paused; the
     * transition guard would have refused it otherwise.
     */
    suspend fun startProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, START)

    /** Pauses [programId]: `RUNNING → PAUSED`, opening a pause interval (§3). */
    suspend fun pauseProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, PAUSE)

    /** Resumes [programId]: `PAUSED → RUNNING`, closing the open pause interval (§3). */
    suspend fun resumeProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, RESUME)

    /** Completes [programId]: `RUNNING|PAUSED → COMPLETED`, terminal (§3). */
    suspend fun completeProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, COMPLETE)

    /**
     * Archives [programId]: a stamp, not a lifecycle state (§3, §29).
     *
     * Archiving retains all history and stops future planning, creates no revision, and is refused only
     * by the one rule that makes it unsafe — archiving the *selected* Program requires the user to
     * choose another one first (§3). The refusal is not "the Program is archived", and it is not the
     * lifecycle: an archived Program keeps the lifecycle it reached.
     */
    suspend fun archiveProgram(programId: ProgramId): ProgramOperationResult<Program> {
        val refusal = guardSelectionForArchive(programId)
        if (refusal != null) return refusal
        return lifecycle(programId, ARCHIVE)
    }

    /** Removes the archive stamp. Lifecycle untouched, history untouched (§29). */
    suspend fun unarchiveProgram(programId: ProgramId): ProgramOperationResult<Program> =
        lifecycle(programId, UNARCHIVE)

    /**
     * Renames [programId] and/or sets its description. Not a structural change, so no revision (§6).
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
     * Sets the date a Program is *planned* to start. A plan, not a fact (§3).
     *
     * This is the method whose entire content is the rule it does not enforce: it writes a
     * `LocalDate`, changes no lifecycle, sets no `actualStartDate` and creates no revision (§6).
     * Passing the planned date does not start the Program; only [startProgram] does.
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

    // ---------------------------------------------------------------- archive and delete (§4, §29)

    /**
     * Deletes [programId] and everything it owns (§29).
     *
     * Three rules gate it, in the order the database's own constraints force:
     *
     *  1. the built-in Standard Program is not deletable (§4);
     *  2. a Program with an `IN_PROGRESS` session is not deletable (§29), and the selection is **not**
     *     cleared to make it pass — a workout in flight has an owner;
     *  3. when the Program being deleted *is* the selection, the selection moves to the built-in
     *     Standard Program first (§3), because the schema's `ON DELETE NO ACTION` refuses to delete a
     *     Program the state row still names. That move is the "technical fallback" the blueprint names:
     *     it is not a user choice, it is what keeps the state row valid, and it happens *before* the
     *     delete so the constraint can hold rather than being worked around.
     *
     * The cascade itself is the schema's (step 3): revisions, days, elements, slots, sessions,
     * snapshots, occurrences, sets, pauses, family states, decisions and adjustments go with the
     * Program row, and the global tables that are not Program-owned survive (§29).
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
     * Copies [programId] into a new user-owned Program with the same plan (§4).
     *
     * The copy is the way the built-in Standard Program gets edited — "editing Standard means creating
     * a user copy first" (§4) — and a copy of any Program is the user's own, so a copy may be edited,
     * renamed and deleted freely. The plan is copied through the revision mechanism: a *new* revision
     * identity for the new Program, carrying the same structure, because reusing the source's revision
     * identity would make two Programs share one plan row (§6, §23). No revision of the *source* is
     * created — copying changes nothing about the Program it copies (§6).
     *
     * The name is the caller's, because the UI decides how "Copy of X" is phrased; the copy's
     * [ProgramSource] is [ProgramSource.USER] regardless of the source's, which is what
     * "a copy is the user's own" means (§4).
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
        // The plan is copied as *new rows*: a fresh revision identity, and fresh identities for every
        // day and every occurrence, because a day and an element are rows of their own and reusing the
        // source's would make two Programs share one plan instead of one copying the other (§6, §23).
        // Structure and prescriptions are carried across unchanged — that is what is being copied.
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

    // ---------------------------------------------------------------- the mechanism

    /**
     * One lifecycle transition: decide it, refuse it, or apply it.
     *
     * The decision is [ProgramLifecyclePolicy]'s — pure, over two statuses and the open-pause fact —
     * and this method owns everything the decision needs to *become* stored state:
     *
     *  * the Program is loaded so the decision is made on the truth rather than on an assumption;
     *  * a refused transition is returned, and nothing is written (§28: an expected state is a result);
     *  * an allowed transition applies [Program.applying], which changes the lifecycle and the stamps
     *    the lifecycle requires and nothing else — in particular no `currentRevisionId` (§6);
     *  * `PAUSE` opens a pause interval and `RESUME`/`COMPLETE` close it, because a pause is an
     *    interval and the interval is what "frozen" means (§3);
     *  * the write is the repository's non-structural update, so no plan row is touched (§6).
     */
    private suspend fun lifecycle(
        programId: ProgramId,
        transition: ProgramTransition
    ): ProgramOperationResult<Program> = storageOutcome {
        val program = programRepository.programById(programId) ?: throw ProgramMissing(programId)
        val openPause = scheduleRepository.pausesOfProgram(programId).any { it.isOpen }
        val decision = if (transition == ARCHIVE || transition == UNARCHIVE) {
            // Archive is not a lifecycle state (§3): it has its own guard and never consults the
            // transition table, so an archived Program can still be completed or resumed.
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
     * The §29 `IN_PROGRESS` guard, before any row is removed.
     *
     * Read through the session repository's own list read, because "this Program has a workout in
     * flight" is a fact about sessions the delete path must see. Not counted in SQL on purpose: the
     * count would have to be interpreted, and the one `IN_PROGRESS` session that matters is easier to
     * name in the refusal than a number is.
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

    /**
     * The §3 archive-of-selection guard: archiving the selected Program requires choosing another one.
     */
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
     * §4's copy-before-edit rule, for the operations that change a Program's own content: the built-in
     * Program's content is the app's, not the user's.
     *
     * Selection, copying and — through [copyProgram] — editing-by-copy are unaffected, because those
     * are the three things §4 lets the built-in Program receive.
     */
    private fun guardEditable(program: Program) {
        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)
    }

    /**
     * Moves the selection to the built-in Standard Program, the §3 technical fallback.
     *
     * Refused loudly when the Standard Program is not seeded: the fallback is a rule the architecture
     * depends on, so a caller that reaches it with nothing to fall back to learns that rather than
     * having the selection silently cleared (§33).
     */
    private suspend fun moveSelectionToStandard(deletedProgramId: ProgramId) {
        val standard = programRepository.programById(standardProgramId)
            ?: throw StandardProgramNotSeeded(standardProgramId, deletedProgramId)
        appStateRepository.save(
            (appStateRepository.state() ?: AppState()).copy(selectedProgramId = standard.programId)
        )
    }

}

// ---------------------------------------------------------------- the failures the mechanism throws

/**
 * The Program an operation was attempted on is not stored (§28 `INVALID_DATA`).
 *
 * Thrown inside the operation so the result boundary can map it to a [ProgramOperationResult.Refused]
 * naming [ProgramNotFound]; it never reaches a caller as an exception.
 */
internal class ProgramMissing(programId: ProgramId) : RuntimeException(ProgramNotFound(programId).message)

/**
 * A Program's `currentRevisionId` names a revision that is not stored (§23): the row and its pointer
 * disagree, which is invalid persisted data rather than an expected state, so it reaches the caller as
 * [ProgramOperationResult.Failure] instead of a refusal or an empty plan.
 */
internal class RevisionNotStored(revisionId: RevisionId) :
    RuntimeException("the revision '${revisionId.value}' a Program's pointer names is not stored (§23)")

/** §4: the built-in Standard Program's content is protected. */
internal class StandardProgramProtected(programId: ProgramId) :
    RuntimeException(StandardProgramCannotBeEdited.message + " — program '${programId.value}'")

/**
 * The §3 delete-fallback needed the built-in Program and it is not stored.
 *
 * The id the caller declared as the Standard Program names nothing, so the technical fallback cannot
 * fire. Reported rather than absorbed, because clearing the selection instead would be exactly the
 * silent state repair §3 and §29 forbid.
 */
class StandardProgramNotSeeded(
    val expectedStandardProgramId: ProgramId,
    val deletedProgramId: ProgramId
) : RuntimeException(
    "the Standard Program '${expectedStandardProgramId.value}' is not stored, so the selection " +
        "cannot fall back to it after deleting '${deletedProgramId.value}' (§3)"
)

/** A lifecycle decision was refused (§3), reported as a typed result rather than raised. */
internal class RefusedTransition(val decision: ProgramTransitionResult.Refused) :
    RuntimeException(decision.reason)

/** Maps a storage outcome's internal failures onto the typed refusals the caller receives. */
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
