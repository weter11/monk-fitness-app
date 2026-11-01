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
 * The Session runtime: starting a workout, confirming its sets, cancelling it and finishing it.
 *
 * Starting captures an immutable presentation and binds the session to it; every later read assembles the
 * session from its own rows, so a Save, a later edit or a superseding adjustment cannot re-explain what
 * the user was shown.
 *
 * It owns no scheduling — no date is computed or derived, and the zone is used for exactly one comparison,
 * the day of an adaptive decision against the day of the opportunity it names — no lifecycle policy, no
 * revision writes, no plan reads after the start, no adaptive engine and no statistics. `Back` is not an
 * operation: leaving a screen is not a fact about the workout.
 *
 * Refusals are values; a storage failure is surfaced, never absorbed. A second `IN_PROGRESS` attempt at one
 * slot is not pre-checked — it is the predicate of the insert, and the start is one transaction, so a
 * refused one leaves no session, no snapshot and no occurrence behind.
 *
 * @param clock read once per operation, because one operation is one moment.
 * @param zone a value it is given rather than acquires, so the caller (and a test) decides it and the
 *   decision's producer gets the same one.
 * @param inTransaction a collaborator: a completion is one unit spanning two repositories, and a test must
 *   be able to make that unit fail and measure what is left behind.
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

    /**
     * Starts a workout on [slotId] and writes the session, its snapshot and its occurrences in one
     * transaction. Nothing is derived from the live plan afterwards, so the returned snapshot is the
     * only description of this workout there will ever be.
     *
     * A refused start writes nothing: the insert stores a row only when the slot holds no other
     * attempt in progress, and the attempt that does hold it is read back only to name it.
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

        // One moment, read once: a workout starts at one instant and its presentation was composed at
        // it. Reusing it keeps `computedAt <= capturedAt` true; the snapshot rule forbids a
        // free-running re-read, not one instant used for one event.
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

    /**
     * Reads one attempt back from its own rows and stored presentation — no revision, plan day or
     * adjustment is read — so what comes back is what was captured, whatever changed underneath.
     */
    suspend fun restoreSession(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        success(
            sessionRepository.sessionById(sessionId)
                ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        )
    }

    /**
     * Confirms one set of one occurrence and stores it immediately.
     *
     * The position is not a parameter: it is the stored sets of this occurrence + 1, so rows are
     * `1..n` with no gap by construction. A set is logged in the unit its prescription was written in
     * ([completedReps] for repetitions, [durationSeconds] for time); a set not performed has no row.
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

    /**
     * Ends the attempt as `CANCELLED`, keeping everything it observed. This is not a completion: the
     * session row records the end, the opportunity is left exactly as it was, and the confirmed sets
     * stay recorded as partial exposure. An attempt that already ended is refused.
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

    /**
     * Completes the attempt in one transaction: the session, the opportunity and the adaptive half the
     * caller hands over land together or not at all, which is why the transaction is opened here and
     * not by either repository — each owns its own rows, this layer owns the composition.
     *
     * A `NOT_APPLIED` decision is stored rather than dropped: it is the only evidence that the program
     * wanted to change something and was refused. This method never invents one.
     *
     * A decision must name a **future** opportunity of this completion's Program and revision — not the
     * one just completed, whose snapshot is already taken — because that is where an adjustment is
     * consumed. [SessionRefusal.AdaptiveTargetRefusal] names which clause was broken.
     */
    suspend fun finishSession(
        sessionId: SessionId,
        adaptive: AdaptiveCompletion = AdaptiveCompletion.NothingDecided
    ): SessionRuntimeResult<SessionCompletion> = sessionOutcome {
        val session = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            // `CANCELLED` never becomes `COMPLETED`, and a completed attempt is not completed twice:
            // the one transition this runtime refuses rather than rewriting.
            return@sessionOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }
        val slot = scheduleRepository.slotById(session.slotId)
            ?: return@sessionOutcome refused(SessionRefusal.SlotNotFound(session.slotId))
        if (slot.status == SlotStatus.COMPLETED) {
            return@sessionOutcome refused(SessionRefusal.SlotIsAlreadyCompleted(slot.slotId))
        }

        val legs: List<AdaptiveCompletion> = when (adaptive) {
            AdaptiveCompletion.NothingDecided -> emptyList()
            is AdaptiveCompletion.WindowEvaluated, is AdaptiveCompletion.Decided,
            is AdaptiveCompletion.NothingToAdapt -> listOf(adaptive)

            is AdaptiveCompletion.Completions -> adaptive.legs
        }

        val decided = mutableListOf<AdaptiveCompletion.Decided>()
        for (leg in legs) {
            if (leg !is AdaptiveCompletion.Decided) continue
            val refusedTarget = adaptiveTargetRefusalOf(session, leg.decision)
            if (refusedTarget != null) return@sessionOutcome refused(refusedTarget)
            decided += leg
        }
        // The family states this completion writes, one per family: a window that only advanced
        // bookkeeping carries one too, which is why every leg that has one is saved.
        val familyStates = legs.mapNotNull { leg ->
            val familyState = when (leg) {
                is AdaptiveCompletion.WindowEvaluated -> leg.familyState
                is AdaptiveCompletion.Decided -> leg.familyState
                else -> null
            }
            familyState?.also { state ->
                require(state.revisionId == session.revisionId) {
                    "the adaptive state a completion writes is state of the revision the workout ran under: " +
                        "state=${state.revisionId.value} session=${session.revisionId.value}"
                }
            }
        }

        val finishedAt = clock.now()
        val completed = session.copy(status = SessionStatus.COMPLETED, finishedAt = finishedAt)
        // The attempt list is the schema's, not this value's: the DAO writes the status and the stamp,
        // so the value handed over already names this attempt.
        val completedSlot = slot.copy(status = SlotStatus.COMPLETED, completedAt = finishedAt)

        inTransaction {
            sessionRepository.finishSession(completed, completedSlot)
            decided.forEach { leg -> adaptiveRepository.persistDecision(leg.decision, leg.adjustment) }
            familyStates.forEach { state -> adaptiveRepository.saveFamilyState(state) }
        }

        success(
            SessionCompletion(
                session = requireStored(sessionId),
                slot = scheduleRepository.slotById(session.slotId)
                    ?: error("the completed opportunity '${session.slotId.value}' is not readable"),
                adaptive = reportOf(legs)
            )
        )
    }

    /**
     * What the unit of work wrote, per family. A completion that carried one leg reports that leg's own
     * shape; several report one entry each, including the families that stored nothing.
     */
    private fun reportOf(legs: List<AdaptiveCompletion>): AdaptiveOutcome = when {
        legs.isEmpty() -> AdaptiveOutcome.NothingDecided
        legs.size == 1 -> {
            val leg = legs.single()
            if (leg is AdaptiveCompletion.Decided) {
                AdaptiveOutcome.Stored(leg.decision.decisionId, leg.decision.adjustmentId)
            } else {
                AdaptiveOutcome.NothingDecided
            }
        }

        else -> AdaptiveOutcome.PerFamily(
            legs.map { leg ->
                val decision = (leg as? AdaptiveCompletion.Decided)?.decision
                AdaptiveOutcome.Family(
                    familyId = requireNotNull(leg.familyId) {
                        "every leg of a completion names its family: $leg"
                    },
                    decisionId = decision?.decisionId,
                    adjustmentId = decision?.adjustmentId
                )
            }
        )
    }

    /**
     * Why an adaptive decision may not be recorded by this completion, or `null` when it may.
     *
     * The target is the opportunity the change will be consumed by — the next one the user starts — so
     * it must be of this Program and revision, on a day strictly after the one the decision was taken
     * on, and still ahead of the user. The decision's own instant is the only one involved, so this
     * method reads no clock. The completed slot itself is a refusal of its own rather than the rule.
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
        // Checked against the **decision's own moment**, not a fresh clock read: the decision says when
        // it was taken. The zone is the one the composition root hands both producer and consumer, so
        // the two cannot disagree about which day a decision belongs to.
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
     * Why this opportunity cannot be started, or `null` when it can. `MISSED` is startable: a user who
     * missed Tuesday and trains on Wednesday attempted that opportunity, and missed detection is
     * attempt-agnostic. `COMPLETED` and `SUPERSEDED` are not.
     */
    private fun WorkoutSlot.startRefusal(): SessionRefusal? = when (status) {
        SlotStatus.PLANNED, SlotStatus.MISSED -> null
        SlotStatus.COMPLETED -> SessionRefusal.SlotIsAlreadyCompleted(slotId)
        SlotStatus.SUPERSEDED -> SessionRefusal.SlotIsSuperseded(slotId)
    }

    /**
     * Whether the two amounts are exactly one amount of work in the unit this occurrence is prescribed
     * in. The reserved dimensions carry no unit contract yet, so only "not zero and not two" is claimed.
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
 * Runs one operation, mapping anything thrown onto [SessionRuntimeResult.Failure] rather than
 * absorbing it. Refusals are computed before anything is written, so they travel as values — except
 * the occupancy rule, which only the write can decide and [SessionRuntime.startSession] catches where
 * it happens.
 */
private suspend fun <T> sessionOutcome(
    block: suspend () -> SessionRuntimeResult<T>
): SessionRuntimeResult<T> = try {
    block()
} catch (failure: Throwable) {
    SessionRuntimeResult.Failure(failure)
}

private fun refused(reason: SessionRefusal): SessionRuntimeResult<Nothing> =
    SessionRuntimeResult.Refused(reason)

private fun <T> success(value: T): SessionRuntimeResult<T> = SessionRuntimeResult.Success(value)
