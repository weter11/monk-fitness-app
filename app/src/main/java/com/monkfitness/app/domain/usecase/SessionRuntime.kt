package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.data.repository.ProgramAdaptiveRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.SessionAlreadyInProgress
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.adaptive.decision.presentedWorkout
import com.monkfitness.app.domain.adaptive.decision.standingAdjustments
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.AdaptiveCompletion
import com.monkfitness.app.domain.workout.AdaptiveOutcome
import com.monkfitness.app.domain.workout.SessionCompletion
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Session runtime — §30 step 8: starting a workout, confirming its sets, going back from it,
 * cancelling it and finishing it.
 *
 * ### The shape of the layer
 *
 * The blueprint's layering is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI` (§25), and this is the
 * fourth of those. The *values* are the domain's ([WorkoutSession], [WorkoutSessionSnapshot],
 * [SetResult], [SessionCompletion]); the *rows* are the repositories'; this class is what makes one
 * operation out of them, with §19's rules applied and §27's units of work opened.
 *
 * ```text
 * SessionRuntime
 *     ├── ProgramPlanRepository        ← the revision the opportunity was scheduled from: READ only
 *     ├── ProgramScheduleRepository    ← the opportunity, and the outcome a completion records
 *     ├── WorkoutSessionRepository     ← the session graph: start, read, append a set, finish
 *     ├── ProgramAdaptiveRepository    ← the decision a completion persists, when it has one
 *     ├── clock: Clock                 ← when the workout started, and when a set was confirmed (§26)
 *     ├── idGenerator: IdGenerator     ← the identity of a session, its occurrences and its sets (§26)
 *     └── inTransaction                ← one unit per operation that writes more than one thing
 * ```
 *
 * ### The snapshot is the session's truth
 *
 * Starting captures a **complete, immutable** presentation — the plan day's elements in their own
 * order, each with the prescription in effect, and the ids of the adjustments that were applied — and
 * the session is bound to it, to its slot, its Program and its revision at construction
 * ([WorkoutSession] checks that binding). Every read after that is
 * [WorkoutSessionRepository.sessionById], which assembles the session from its own rows: `restore` never
 * consults a revision, a plan day, a plan element or an adjustment, so a §6 Save, a later edit or a
 * superseding adjustment cannot re-explain what the user was shown. The composition of the *new*
 * presentation at start is the only place the live plan is read at all, and it is read for the reason
 * stated in `SlotPresentation`: a presentation is *the opportunity's own revision + the adjustments
 * still standing for it* (§16).
 *
 * ### The states, and what each operation may do
 *
 * ```text
 * start    —             IN_PROGRESS    one transaction: the session, its snapshot, its occurrences
 * restore  any            any            a read of the session's own rows and nothing else
 * confirm  IN_PROGRESS   IN_PROGRESS    one appended set row; the position follows from the stored rows
 * back     IN_PROGRESS   IN_PROGRESS    nothing at all — the attempt is persisted runtime state (§19)
 * cancel   IN_PROGRESS   CANCELLED      the session row alone; the opportunity is NOT taken
 * finish   IN_PROGRESS   COMPLETED      one transaction: the session, the slot and the adaptive decision
 * ```
 *
 * `Back` is deliberately not an operation: leaving the screen is not a fact about the workout, so no
 * call exists that would write anything for it, and the next start of that screen restores the session
 * that is already stored. Only [cancelSession] ends an attempt that way, and only [finishSession]
 * completes one.
 *
 * ### What it deliberately does not own
 *
 * The absences are the guarantees, and each is asserted mechanically by `SessionRuntimeArchitectureTest`:
 *
 *  * **no scheduling.** No date is computed, no opportunity is created, no status is derived from a
 *    date, no horizon is extended and no date is ever *chosen*: `ProgramScheduler` owns §20, and
 *    *"let the Scheduler create Session"* (§33) is a sentence this class could not carry out. It does
 *    hold a `zone`, and that is a deliberate line rather than a hole in that rule: the zone is used for
 *    exactly **one** comparison — the day an adaptive decision was taken on against the day of the
 *    opportunity it names ([adaptiveTargetRefusalOf]) — because a date and an instant are different
 *    facts and §26 puts their conversion in the layer that owns the clock. The Producer's target rule is
 *    stated in the same zone (`docs/PROGRAM_ADAPTIVE_INTEGRATION.md` §3), so the two sides of §4's
 *    contract share one calendar semantics; the composition root hands both the same value.
 *    `SchedulingDecisionsAreNotMadeHere`-style guards in `SessionRuntimeArchitectureTest` pin both
 *    halves: the scheduling vocabulary is still absent, and the zone is never acquired here.
 *  * **no lifecycle policy.** The Program is not read here at all: whether it is paused, archived or
 *    completed is §3's and §29's, and taking an opportunity the user holds is not a lifecycle
 *    transition.
 *  * **no revision writes, and no plan reads after the start.** The plan repository is used for one
 *    read — the revision an opportunity names — and no `saveNewRevision` call exists in this file (§6).
 *  * **no adaptive engine, policy, signal or progression.** Not one of those types reaches this class.
 *    The only adaptive values that cross it are the stored adjustments of one slot (read, applied and
 *    captured) and the decision a completion is handed — the decision is *stored*, never produced, and a
 *    completion without one stores nothing and reports that explicitly
 *    ([AdaptiveOutcome.NothingDecided]).
 *  * **no progress, statistics or UI.** Nothing counts completions, aggregates volume, derives a score
 *    or notifies a screen: the operations return values (§30 steps 9, 14).
 *
 * ### The errors it reports
 *
 * Every rule of §19 that can refuse an operation is a value ([SessionRefusal]) rather than an exception,
 * and a storage failure is surfaced as [SessionRuntimeResult.Failure] rather than absorbed into an empty
 * result (§28, §33). One refusal is special, and it is the one the brief asked to be proved at the
 * persistence boundary: **a second `IN_PROGRESS` attempt at one slot**. It is not a check this class
 * makes before writing — there is no read before the write in [startSession] — it is the predicate of the
 * insert that stores the session, which stores nothing when the slot is occupied; the refused write is
 * what [SessionRefusal.SlotIsAlreadyBeingWorkedOut] reports. The whole start is one transaction, so a
 * refused one leaves no session, no snapshot and no occurrence behind.
 *
 * @param planRepository the revision mechanism, read for one thing: the revision an opportunity names.
 *   A revision is read, never saved — nothing in this file writes a plan row (§6).
 * @param scheduleRepository the opportunities: read for the one being started, and written once per
 *   completion, for the §20 outcome that says the opportunity was taken.
 * @param sessionRepository the session's own persistence: the start (which carries the occupancy rule),
 *   the reads that assemble a session from its snapshot, the appended set rows and the outcome writes.
 * @param adaptiveRepository the target adaptive tables: read for the adjustments a presentation
 *   consumes, written once per completion for the decision the adaptive stage handed over.
 * @param clock the clock every actual moment comes from — `startedAt`, `capturedAt`, `performedAt`,
 *   `finishedAt` — read once per operation, because one operation is one moment (§26).
 * @param idGenerator the identity source every new session, occurrence and set is minted through (§26).
 * @param zone the calendar the day of an instant is read in, and the only thing this class does with
 *   dates: one comparison, against the moment an adaptive decision was taken. It is a value it is given
 *   rather than one it acquires — no `systemDefault()` call exists in this file — so a caller that owns
 *   its own time (and a test) decides it, and the same value reaches the producer of that decision.
 * @param inTransaction runs a block in one database transaction. It is a collaborator rather than an
 *   assumption because §27's completion is **one** unit that spans two repositories, and because a test
 *   must be able to make that unit fail and measure what is left behind.
 */
class SessionRuntime(
    private val planRepository: ProgramPlanRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val adaptiveRepository: ProgramAdaptiveRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val zone: ZoneId,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    // ---------------------------------------------------------------- start

    /**
     * Starts a workout on [slotId] and stores it with the presentation it was started under.
     *
     * The opportunity is read, validated and composed into a presentation, and the whole thing is
     * written in one transaction — §27's `Start Workout → Session + complete Snapshot`. Nothing is
     * derived from the live plan afterwards, so the snapshot this returns is the only description of
     * this workout there will ever be.
     *
     * A refused start writes **nothing**, and the refusal the write itself decides
     * ([SessionRefusal.SlotIsAlreadyBeingWorkedOut]) is decided inside the transaction the write is in:
     * the insert stores the row only when the opportunity holds no other attempt in progress, and the
     * attempt that does hold it is read back only to name it in the refusal.
     */
    suspend fun startSession(slotId: SlotId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        val slot = scheduleRepository.slotById(slotId)
            ?: return@sessionOutcome refused(SessionRefusal.SlotNotFound(slotId))
        slot.startRefusal()?.let { return@sessionOutcome refused(it) }

        val revision = planRepository.revisionById(slot.revisionId)
            ?: return@sessionOutcome refused(SessionRefusal.RevisionNotFound(slot.revisionId))
        if (revision.programId != slot.programId) {
            return@sessionOutcome refused(
                SessionRefusal.RevisionIsOfAnotherProgram(
                    revisionId = slot.revisionId,
                    programId = slot.programId,
                    ownerProgramId = revision.programId
                )
            )
        }
        val day = revision.days.firstOrNull { it.programDayId == slot.programDayId }
            ?: return@sessionOutcome refused(
                SessionRefusal.SlotNamesAPlanDayTheRevisionDoesNotPresent(
                    slotId = slot.slotId,
                    programDayId = slot.programDayId,
                    revisionId = revision.revisionId
                )
            )
        if (day.exercises.isEmpty()) {
            return@sessionOutcome refused(SessionRefusal.PlanDayPresentsNothing(slotId, day.programDayId))
        }

        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(slotId))
        val unusable = standing.firstOrNull { adjustment ->
            day.exercises.none { it.programExerciseId == adjustment.after.programExerciseId }
        }
        if (unusable != null) {
            return@sessionOutcome refused(
                SessionRefusal.StoredAdjustmentIsNotOfThisRevision(
                    slotId = slotId,
                    adjustmentId = unusable.adjustmentId,
                    programExerciseId = unusable.after.programExerciseId
                )
            )
        }

        // One moment, read once: a workout is started at one instant, and the presentation was composed
        // at it. Reusing it is what keeps `computedAt <= capturedAt` true in the snapshot; §26 forbids a
        // *free-running* re-read of the clock for one write, not one instant used for one event.
        val startedAt = clock.now()
        val workout = presentedWorkout(day, slot, standing, startedAt)
        val sessionId = SessionId(idGenerator.newId())
        val session = WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = WorkoutSessionSnapshot(
                sessionId = sessionId,
                capturedAt = startedAt,
                workout = workout
            ),
            status = SessionStatus.IN_PROGRESS,
            startedAt = startedAt,
            exercises = workout.exercises.map { element ->
                SessionExercise(
                    sessionExerciseId = SessionExerciseId(idGenerator.newId()),
                    programExerciseId = element.programExerciseId,
                    exerciseId = element.exerciseId,
                    prescription = element.prescription
                )
            }
        )

        val refusedByTheWrite = try {
            sessionRepository.startSession(session)
            null
        } catch (alreadyInProgress: SessionAlreadyInProgress) {
            alreadyInProgress
        }
        if (refusedByTheWrite != null) {
            return@sessionOutcome refused(
                SessionRefusal.SlotIsAlreadyBeingWorkedOut(
                    slotId = slotId,
                    sessionId = sessionRepository.sessionsOfSlot(slotId)
                        .firstOrNull { it.isInProgress }
                        ?.sessionId
                )
            )
        }
        success(session)
    }

    // ---------------------------------------------------------------- read

    /**
     * Reads one attempt back — the operation a screen calls when it is recreated, or when the process
     * comes back to a workout that was never cancelled.
     *
     * The session is assembled from its own rows and its stored presentation: no revision, no plan day,
     * no plan element and no adjustment is read, so what comes back is what was captured, whether the
     * plan underneath it has changed since or not (§19). A session that is not stored is a
     * [SessionRefusal.SessionNotFound] rather than an empty value, and a session whose stored rows
     * violate §19's invariants fails loudly as invalid persisted data.
     */
    suspend fun restoreSession(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        success(
            sessionRepository.sessionById(sessionId)
                ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        )
    }

    // ---------------------------------------------------------------- confirm a set

    /**
     * Confirms one set of one occurrence and stores it immediately (§27's `Confirm Set → SetLog`).
     *
     * The set's **position** is not a parameter: it is *the stored sets of this occurrence + 1*, so the
     * stored rows are `1..n` with no gap by construction — the same invariant the read refuses to invent
     * a zero for (§12: a set that was not performed has no row at all).
     *
     * @param completedReps repetitions performed, for an occurrence prescribed in repetitions.
     * @param durationSeconds seconds performed, for an occurrence prescribed in time. A set is logged in
     *   the unit its prescription was written in, and a set that was not performed is not logged.
     */
    suspend fun confirmSet(
        sessionId: SessionId,
        sessionExerciseId: SessionExerciseId,
        completedReps: Int = 0,
        durationSeconds: Int = 0
    ): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        val session = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            return@sessionOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }
        val occurrence = session.exercises.firstOrNull { it.sessionExerciseId == sessionExerciseId }
            ?: return@sessionOutcome refused(
                SessionRefusal.OccurrenceIsNotOfThisSession(sessionId, sessionExerciseId)
            )
        if (occurrence.skipped) {
            return@sessionOutcome refused(SessionRefusal.OccurrenceWasSkipped(sessionId, sessionExerciseId))
        }
        if (!occurrence.isLoggedIn(completedReps, durationSeconds)) {
            return@sessionOutcome refused(
                SessionRefusal.SetIsNotInThePrescribedUnit(
                    sessionExerciseId = sessionExerciseId,
                    dimension = occurrence.prescription.dimension,
                    completedReps = completedReps,
                    durationSeconds = durationSeconds
                )
            )
        }

        sessionRepository.appendSet(
            sessionExerciseId = sessionExerciseId,
            set = SetResult(
                setLogId = SetLogId(idGenerator.newId()),
                setIndex = occurrence.completedSetCount + 1,
                completedReps = completedReps,
                durationSeconds = durationSeconds,
                performedAt = clock.now()
            )
        )
        success(requireStored(sessionId))
    }

    // ---------------------------------------------------------------- cancel

    /**
     * Ends the attempt as `CANCELLED`, keeping everything it observed.
     *
     * A cancellation is not a completion, and the two are not the same fact written twice: the session
     * row records the end, the opportunity is **left exactly as it was** (it is not taken, and no column
     * of it is written here at all), and the sets the attempt confirmed stay recorded as partial
     * exposure (§12, §19). Cancelling an attempt that already ended is refused — `CANCELLED` is not a
     * state a completion can be built on, and `COMPLETED` is not overwritten.
     */
    suspend fun cancelSession(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        val session = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            return@sessionOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }

        val cancelled = session.copy(status = SessionStatus.CANCELLED, finishedAt = clock.now())
        inTransaction { sessionRepository.recordSessionOutcome(cancelled) }
        success(requireStored(sessionId))
    }

    // ---------------------------------------------------------------- finish

    /**
     * Completes the attempt: the session becomes `COMPLETED`, the opportunity it holds becomes
     * `COMPLETED`, and the adaptive half the caller hands over is stored — **in one transaction**
     * (§27's `Complete Workout → Session + Slot + Adaptive state + Decisions + Adjustments`).
     *
     * The legs are one fact and land together or not at all: a failure anywhere inside the unit leaves the
     * pre-completion state exactly as it was — the session `IN_PROGRESS`, the opportunity untouched, no
     * family state, no decision and no adjustment stored. That is why the transaction is opened *here* and
     * not by either repository: each of them owns its own rows, and this layer owns the composition.
     *
     * [adaptive] is the adaptive stage's half, as a value (§30 step 12 produces it):
     *
     * ```text
     * NothingDecided          nothing adaptive is written — no window was evaluated
     * WindowEvaluated(state)  the family's state after a window that decided nothing is written
     * Decided(decision, …)    the family's state, the decision and, when it applied one, the adjustment
     * ```
     *
     * A `NOT_APPLIED` decision is stored rather than dropped: §18 keeps a decision the aggregate load
     * guard filtered out, and dropping it would erase the only evidence that the program wanted to change
     * something and was refused. This method never invents a decision: a completion is not evidence for
     * one, and a fabricated `NOT_APPLIED` row would claim a decision nobody made.
     *
     * A decision must be about a **future** opportunity of this completion's Program and revision — not
     * about the opportunity the completion just took, and not about one that is no longer ahead of the
     * user — because that is where an adjustment is consumed (§4, §16). [SessionRefusal
     * .AdaptiveTargetRefusal] names which clause of the rule a refused decision broke.
     */
    suspend fun finishSession(
        sessionId: SessionId,
        adaptive: AdaptiveCompletion = AdaptiveCompletion.NothingDecided
    ): SessionRuntimeResult<SessionCompletion> = sessionOutcome {
        val session = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            // `CANCELLED` never becomes `COMPLETED`, and a completed attempt is not completed twice
            // (§19). This is the one transition the runtime refuses rather than rewriting.
            return@sessionOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }
        val slot = scheduleRepository.slotById(session.slotId)
            ?: return@sessionOutcome refused(SessionRefusal.SlotNotFound(session.slotId))
        if (slot.status == SlotStatus.COMPLETED) {
            return@sessionOutcome refused(SessionRefusal.SlotIsAlreadyCompleted(slot.slotId))
        }

        val decided: AdaptiveCompletion.Decided? = when (adaptive) {
            AdaptiveCompletion.NothingDecided, is AdaptiveCompletion.WindowEvaluated -> null
            is AdaptiveCompletion.Decided -> {
                val refusedTarget = adaptiveTargetRefusalOf(session, adaptive.decision)
                if (refusedTarget != null) return@sessionOutcome refused(refusedTarget)
                adaptive
            }
        }
        // §27's *adaptive state* leg: the family's current state after the window this completion
        // evaluated. It travels with both shapes that evaluated one — a decision and a bare window — and
        // the state of a decision is the state of the family the decision is about.
        val familyState = when (adaptive) {
            AdaptiveCompletion.NothingDecided -> null
            is AdaptiveCompletion.WindowEvaluated -> adaptive.familyState
            is AdaptiveCompletion.Decided -> adaptive.familyState
        }
        if (familyState != null) {
            require(familyState.revisionId == session.revisionId) {
                "the adaptive state a completion writes is state of the revision the workout ran under: " +
                    "state=${familyState.revisionId.value} session=${session.revisionId.value}"
            }
        }

        val finishedAt = clock.now()
        val completed = session.copy(status = SessionStatus.COMPLETED, finishedAt = finishedAt)
        // The attempt list is the schema's, not this value's: the DAO writes the status and the stamp,
        // while a slot's attempts are read from the session rows (§23) — which now include this one, so
        // the value handed over already names it.
        val completedSlot = slot.copy(status = SlotStatus.COMPLETED, completedAt = finishedAt)

        inTransaction {
            sessionRepository.finishSession(completed, completedSlot)
            decided?.let { adaptiveRepository.persistDecision(it.decision, it.adjustment) }
            familyState?.let { adaptiveRepository.saveFamilyState(it) }
        }

        success(
            SessionCompletion(
                session = requireStored(sessionId),
                slot = scheduleRepository.slotById(session.slotId)
                    ?: error("the completed opportunity '${session.slotId.value}' is not readable"),
                adaptive = decided?.let {
                    AdaptiveOutcome.Stored(it.decision.decisionId, it.decision.adjustmentId)
                } ?: AdaptiveOutcome.NothingDecided
            )
        )
    }

    // ---------------------------------------------------------------- the rules it applies

    /**
     * Why an adaptive decision may not be recorded by this completion, or `null` when it may (§4, §16).
     *
     * The target of a decision is **the opportunity the change will be consumed by** — the next one the
     * user will start — so the decision has to name an opportunity that is still ahead of them, of the
     * same Program and the same revision as the workout that produced it, and on a day strictly after the
     * one the decision was taken on. Every clause is checked against stored facts rather than assumed: the
     * slot is read, and its ownership, its day, its status and its attempts decide the rest. The only
     * instant involved is the decision's own, so this method reads no clock.
     *
     * The fourth clause is the one §30 step 12 corrects. Before it, this check required the decision's slot
     * to *equal* the session's, because P8 had no real adaptive producer and the only decision a completion
     * could be handed was about its own opportunity. With the adaptive stage wired, a decision about the
     * completed opportunity is exactly the thing that cannot be consumed — its snapshot is already taken —
     * so the same-slot case is now a refusal of its own
     * ([SessionRefusal.AdaptiveTargetRefusal.THE_COMPLETED_SLOT_ITSELF]) instead of the rule.
     */
    private suspend fun adaptiveTargetRefusalOf(
        session: WorkoutSession,
        decision: AdaptiveDecision
    ): SessionRefusal? {
        fun refusal(reason: SessionRefusal.AdaptiveTargetRefusal): SessionRefusal =
            SessionRefusal.AdaptiveDecisionIsNotAboutAFutureOpportunityOfThisCompletion(
                sessionId = session.sessionId,
                completedSlotId = session.slotId,
                decisionId = decision.decisionId,
                decisionSlotId = decision.slotId,
                reason = reason
            )

        if (decision.programId != session.programId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_PROGRAM)
        }
        if (decision.revisionId != session.revisionId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_REVISION)
        }
        if (decision.slotId == session.slotId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.THE_COMPLETED_SLOT_ITSELF)
        }

        val target = scheduleRepository.slotById(decision.slotId)
            ?: return refusal(SessionRefusal.AdaptiveTargetRefusal.NO_SUCH_SLOT)
        if (target.programId != session.programId || target.revisionId != session.revisionId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_OF_ANOTHER_PLAN)
        }
        // The temporal clause, checked against the **decision's own moment** rather than against a fresh
        // reading of the clock: the decision says when it was taken, and the producer chose this
        // opportunity with the same comparison. §26 keeps the conversion here — the instant is the
        // decision's fact, the date is the opportunity's — and the zone is the one the composition root
        // hands both sides, so producer and consumer cannot disagree about which day a decision belongs
        // to.
        val decisionDay = LocalDate.ofInstant(decision.decidedAt, zone)
        if (!target.plannedFor.isAfter(decisionDay)) {
            return refusal(
                SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_NOT_STRICTLY_AHEAD_OF_THE_DECISION
            )
        }
        if (target.attempts.isNotEmpty()) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_ALREADY_STARTED)
        }
        if (!target.isStartable) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_NOT_AHEAD_OF_THE_USER)
        }
        return null
    }

    /**
     * Why this opportunity cannot be started, or `null` when it can.
     *
     * `PLANNED` and `MISSED` are startable. A missed opportunity is one whose *date* passed, and the
     * blueprint neither slides it nor forbids it: a user who missed Tuesday and trains on Wednesday
     * attempted that opportunity, and the completion then records it as taken (§20's missed detection is
     * attempt-agnostic and rewrites no decision). `COMPLETED` and `SUPERSEDED` are not startable — the
     * first was taken by a finished workout, whose stamp a second attempt would have to overwrite, and
     * the second was withdrawn by a later plan, so the user was not expected to train it.
     */
    private fun WorkoutSlot.startRefusal(): SessionRefusal? = when (status) {
        SlotStatus.PLANNED, SlotStatus.MISSED -> null
        SlotStatus.COMPLETED -> SessionRefusal.SlotIsAlreadyCompleted(slotId)
        SlotStatus.SUPERSEDED -> SessionRefusal.SlotIsSuperseded(slotId)
    }

    /**
     * Whether [completedReps] and [durationSeconds] are an amount of work in the unit this occurrence is
     * prescribed in (§10): exactly one of the two, and the one its dimension measures.
     *
     * The three reserved dimensions (§10's `SET_BASED`, `DIFFICULTY_BASED`, `REST_BASED`) carry no unit
     * contract yet, so nothing beyond *"one amount: not zero and not two"* is claimed about them — which
     * is the same line `SessionExercise` draws.
     */
    private fun SessionExercise.isLoggedIn(completedReps: Int, durationSeconds: Int): Boolean =
        when (prescription.dimension) {
            PrescriptionDimension.REP_BASED -> completedReps > 0 && durationSeconds == 0
            PrescriptionDimension.TIME_BASED -> durationSeconds > 0 && completedReps == 0
            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED -> (completedReps > 0) != (durationSeconds > 0)
        }

    /** The session as it is stored now, or a failure — a write that cannot be read back is a defect. */
    private suspend fun requireStored(sessionId: SessionId): WorkoutSession =
        sessionRepository.sessionById(sessionId)
            ?: error("the session '${sessionId.value}' was written and is not readable")
}

/**
 * Runs one operation, mapping anything it throws onto [SessionRuntimeResult.Failure] rather than
 * absorbing it (§28's `SYSTEM_FAILURE`, §33's prohibition on turning a failure into an empty result).
 *
 * The refusals are *not* failures: every one of them is computed before the operation writes anything,
 * so they are returned as values from inside the operation and never travel as a thrown reason — with
 * the single exception of the occupancy rule, which the storage layer raises because it is the write
 * that decides it, and which [SessionRuntime.startSession] catches where it happens so it can name the
 * attempt that holds the opportunity.
 */
private suspend fun <T> sessionOutcome(
    block: suspend () -> SessionRuntimeResult<T>
): SessionRuntimeResult<T> = try {
    block()
} catch (failure: Throwable) {
    SessionRuntimeResult.Failure(failure)
}

/** A refusal, as the result envelope carries it. */
private fun refused(reason: SessionRefusal): SessionRuntimeResult<Nothing> =
    SessionRuntimeResult.Refused(reason)

/** A value the operation produced, as the result envelope carries it. */
private fun <T> success(value: T): SessionRuntimeResult<T> = SessionRuntimeResult.Success(value)
