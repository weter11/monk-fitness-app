package com.monkfitness.app.data.repository

import androidx.room.withTransaction
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.ProgressionResolution
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.AdaptiveSessionRequest
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutIntegration
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The session the app has just finalized, described by the context that session froze when it started.
 *
 * Every field is a frozen fact rather than a live reading: the program day, cycle and revision the
 * session ran as, the program calendar its stored history is interpreted against, the configuration the
 * session was STARTED with (never the live store, which belongs to the next workout), and the equipment
 * the session could rely on.
 *
 * That is the whole point of the type. A session that started under one revision must be finalized under
 * that revision even if a `Start Revised Program` ran while it was alive, and the same `(cycle, day)`
 * means different dates in two program calendars — so "which session is this" is answered by the values
 * the session kept, never by whatever the app's live state reads at completion time.
 *
 * `programRevision` is carried separately from `programType` because persistence keys adaptive state by
 * revision — the row set that is current — while the type is the decision's own context; the type is
 * derived from the revision (see [programType]) rather than supplied beside it, so the two cannot
 * disagree.
 *
 * @property programDay the program day within that cycle (1-based).
 * @property programCycle the program cycle the finalized session belongs to (1-based).
 * @property programRevision the revision whose family progression this session continues.
 * @property configuration the configuration captured when this session started.
 * @property programStartDate the calendar the stored sessions are interpreted against — the program
 *   calendar of the revision this session belongs to, frozen with the session.
 * @property availableEquipment the equipment the session's generation could rely on, in the app's own
 *   semantics (empty means "not established", which permits everything).
 */
data class SessionFinalizationRequest(
    val programCycle: Int,
    val programDay: Int,
    val programRevision: Int,
    val configuration: WorkoutConfigurationSnapshot,
    val programStartDate: LocalDate,
    val availableEquipment: Set<Equipment> = emptySet()
) {

    /**
     * Which of the app's two program identities this session belongs to, derived from its own frozen
     * revision: [ProgramType.STANDARD] is the program as first started and [ProgramType.REVISED] one
     * restarted through the C3 "Start Revised Program" action.
     */
    val programType: ProgramType
        get() = if (programRevision == STANDARD_PROGRAM_REVISION) ProgramType.STANDARD else ProgramType.REVISED

    init {
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programRevision >= 0) { "programRevision must be >= 0, was $programRevision" }
    }

    companion object {
        /**
         * The revision of the program as first started, matching the persisted
         * `SettingsManager.PROGRAM_REVISION` and the revision column both adaptive tables carry: a
         * revision above it is a C3 "Start Revised Program".
         */
        const val STANDARD_PROGRAM_REVISION: Int = 0
    }
}

/**
 * What one call did: the outcome of the finalized session's own observation, and the families whose
 * state and record were written against the ones that already carried a record for that window.
 *
 * @property observationOutcome the outcome of the observation persistence establishes for the session,
 *   or `null` when it establishes no session there at all (a rest day, or a day the app never touched).
 * @property recordedFamilies families this call recorded, in the window's own order.
 * @property alreadyRecordedFamilies families this session had already recorded — a repeated
 *   finalization, which writes nothing.
 */
data class SessionFinalizationOutcome(
    val observationOutcome: SessionOutcome?,
    val recordedFamilies: List<String>,
    val alreadyRecordedFamilies: List<String>
) {

    /**
     * Whether the session was finalized, i.e. its own observation reached the completion the day-level
     * source records. Only a finalized session produces a decision.
     */
    val isFinalized: Boolean
        get() = observationOutcome == SessionOutcome.COMPLETED
}

/**
 * The finalized-session integration: the one place where a workout that has already been recorded
 * becomes persisted adaptive state and an immutable decision record.
 *
 * ```
 * the finalized session  →  its own SessionObservation  →  this session's decision window
 *                        →  the session's frozen configuration  →  permitted exercises
 *                        →  one decision per evaluated family, each resolved on its family's ladder
 *                        →  FamilyProgressionState + AdaptiveDecisionRecord, atomically
 * ```
 *
 * ## What it is
 *
 * An adapter, and the smallest one that closes the loop. It decides nothing: the signal layer measures,
 * the policy orders, the family's own ladder resolves the step, and the progression plan joins the two.
 * This layer establishes the window, asks the existing integration for the plan of that window, maps
 * what came back into the two persisted shapes and stores them through one transactional write. There is
 * no threshold, no state transition, no ladder and no exercise selection here, and no second pipeline:
 * the decision for a session is computed by exactly the components the session's generation uses.
 *
 * ## What finalizes a session
 *
 * The session's own observation, and nothing else. A session is finalized when the day-level completion
 * source says the workout was completed — which is the only durable end-of-session marker persistence
 * has, and the same source `UserProgress.isCompleted` gives the rest of the app. Consequently:
 *
 *  * a session that was never opened (`NOT_STARTED`), one still in progress, and one whose work was
 *    recorded without a completion all persist nothing, because their observation is not finalized;
 *  * the decision is measured over the stored history — the confirmed-set rows and the day-level
 *    completion — never over a generated preview, the live session state or an exercise's configured
 *    targets;
 *  * a rest day prescribes no planned work, so it is not a session and records nothing.
 *
 * ## Which session the window belongs to
 *
 * The request carries `(programRevision, programCycle, programDay)` **and** the program calendar those
 * belong to, and the recorder reads its history with that calendar ([readHistory] is asked for the
 * request's `programStartDate`). That is what makes a `(cycle, day)` mean the session it means: in one
 * program calendar the position is one range of dates, in another it is a different range, so a session
 * started under one revision can never be resolved onto another revision's workouts. Persistence carries
 * no revision column in the workout history — `user_progress` is keyed by `(cycleNumber, day)` and a set
 * row carries a date — so the calendar the session froze is the only thing that can answer the question,
 * and this layer refuses to answer it from whatever the app's live state reads at completion time.
 *
 * ## What it writes
 *
 * For every family the window evaluates, the state the policy ordered at the level the family's ladder
 * resolved, plus the immutable record of that decision. The two land in one transaction per session, and
 * a family that already carries a record for the session's window is left alone — see
 * [AdaptiveRepository.persistDecisionsOnce], which is the authority for both properties.
 *
 * The stored hysteresis counts are the ones the caller already had: the engine reads them and never
 * updates them, and no qualification outcome is published for this layer to advance them from. This
 * layer therefore neither advances nor resets a count; it stores exactly what it was handed.
 *
 * ## What it does not do
 *
 * It reads no clock of its own (the caller supplies one, so a stored stamp is one fact per session), it
 * writes no configuration, no calendar row and no workout row, and it never re-enables a family or an
 * exercise the session's own configuration left out. It also does not degrade a failed read into a
 * decision: a history it cannot read means it cannot know what the user did, so the failure is reported
 * to the caller instead of being recorded as a state change.
 *
 * @param repository the adaptive persistence adapter — the only thing this layer writes through.
 * @param readHistory the session's normalized history, oldest first, for the calendar it ran against.
 *   Production passes Task 3's adapter; the session's own observation is the entry of that history which
 *   matches the request's calendar position, so one read serves both the finalization gate and the
 *   decision window.
 * @param generator the app's workout builder, used for the family map and the equipment semantics the
 *   window's candidate set is resolved with — the same instance the session's generation uses.
 * @param now the clock the write stamp is read from, once per session.
 */
class AdaptiveSessionDecisionRecorder(
    private val repository: AdaptiveRepository,
    private val readHistory: suspend (LocalDate) -> List<SessionObservation>,
    private val generator: WorkoutGenerator = WorkoutGenerator(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val integration = AdaptiveWorkoutIntegration(generator)

    /**
     * Records the adaptive decisions of the session [request] describes, or reports why it recorded
     * none.
     *
     * The read and the evaluation are CPU work over the session's whole stored history — one generator
     * pass per planned opportunity, plus the signal calculation — so they are taken off the caller's
     * thread, exactly as the session's plan reader does it.
     *
     * @throws Exception when the history cannot be read or the write cannot be completed. Nothing is
     *   recorded in either case, and the caller reports it: a finished workout is already persisted
     *   before this runs, so a recording failure must never undo or block the completion.
     */
    suspend fun recordFinalizedSession(request: SessionFinalizationRequest): SessionFinalizationOutcome =
        withContext(Dispatchers.Default) {
            val history = readHistory(request.programStartDate)

            // The session's own observation, from the same read the decision window is measured over:
            // the workout that actually completed, not the one that was planned.
            val observation = history.firstOrNull { candidate ->
                candidate.cycleNumber == request.programCycle && candidate.programDay == request.programDay
            }
            if (observation?.outcome != SessionOutcome.COMPLETED) {
                return@withContext SessionFinalizationOutcome(
                    observationOutcome = observation?.outcome,
                    recordedFamilies = emptyList(),
                    alreadyRecordedFamilies = emptyList()
                )
            }

            val storedStates = repository.familyStates(request.programRevision)
            val plan = integration.planSession(
                AdaptiveSessionRequest(
                    programDay = request.programDay,
                    programCycle = request.programCycle,
                    programType = request.programType,
                    configuration = request.configuration,
                    availableEquipment = request.availableEquipment,
                    recentSessions = history,
                    familyStates = storedStates
                )
            )

            val updatedAt = now()
            val writes = plan.progression.families.map { family ->
                FamilyDecisionWrite(
                    state = nextState(
                        familyId = family.familyId,
                        prior = storedStates.firstOrNull { it.familyId == family.familyId },
                        resolution = family.resolution,
                        decision = family.decision,
                        programRevision = request.programRevision,
                        updatedAt = updatedAt
                    ),
                    record = repository.decisionRecord(
                        decision = family.decision,
                        programRevision = request.programRevision,
                        cycleNumber = request.programCycle,
                        programDay = request.programDay,
                        timestamp = updatedAt
                    )
                )
            }

            val persisted = repository.persistDecisionsOnce(writes)

            SessionFinalizationOutcome(
                observationOutcome = observation.outcome,
                recordedFamilies = persisted.recorded,
                alreadyRecordedFamilies = persisted.alreadyRecorded
            )
        }

    /**
     * The family's new current state: the state the decision ordered and the level its own ladder
     * resolved, on the exercise that resolution names — or on the exercise the family already carried
     * when the resolution ordered no target, because a HOLD is not a move.
     *
     * The hysteresis counts are carried from the prior row unchanged, and a family with no prior row
     * starts from the domain's documented `notYetTracked` composition: zero counts and no recorded
     * level change to cool down from.
     */
    private fun nextState(
        familyId: String,
        prior: FamilyProgressionState?,
        resolution: ProgressionResolution,
        decision: AdaptiveDecision,
        programRevision: Int,
        updatedAt: Long
    ): FamilyProgressionState = FamilyProgressionState(
        familyId = familyId,
        progressionLevel = resolution.level,
        currentExerciseId = resolution.exerciseId ?: prior?.currentExerciseId,
        adaptationState = decision.state,
        precedingProgressQualifyingWindows = prior?.precedingProgressQualifyingWindows ?: 0,
        precedingRegressQualifyingWindows = prior?.precedingRegressQualifyingWindows ?: 0,
        precedingHighRiskWindows = prior?.precedingHighRiskWindows ?: 0,
        recoveryQualifyingSessions = prior?.recoveryQualifyingSessions ?: 0,
        eligibleSessionsSinceLastProgressionChange = prior?.eligibleSessionsSinceLastProgressionChange,
        programRevision = programRevision,
        updatedAt = updatedAt,
        policyVersion = decision.policyVersion
    )

    companion object {

        /**
         * The production wiring: the app's own database, Task 3's history adapter, and Room's own
         * transaction runner for the adapter that owns both adaptive tables — the same shape
         * `SessionAdaptivePlanReader.of` uses for the read half of this boundary.
         */
        fun of(
            database: AppDatabase,
            generator: WorkoutGenerator = WorkoutGenerator()
        ): AdaptiveSessionDecisionRecorder {
            val progressDao = database.progressDao()
            val adaptiveRepository = AdaptiveRepository(
                stateDao = database.familyProgressionStateDao(),
                historyDao = database.adaptiveDecisionHistoryDao(),
                inTransaction = { block -> database.withTransaction { block() } }
            )

            return AdaptiveSessionDecisionRecorder(
                repository = adaptiveRepository,
                readHistory = { programStartDate ->
                    SessionHistoryAdapter(
                        progressDao = progressDao,
                        workoutGenerator = generator,
                        programStartDate = programStartDate
                    ).observations()
                },
                generator = generator
            )
        }
    }
}
