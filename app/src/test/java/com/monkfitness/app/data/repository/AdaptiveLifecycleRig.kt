package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.ProgramStatisticsSnapshot
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.SetLogRow
import com.monkfitness.app.data.model.ShoppingItemEntity
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.VolumeHistoryPoint
import com.monkfitness.app.data.model.WorkoutFrequencyPoint
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ExerciseResult
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.adaptive.Workload
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * The JVM rig the adaptive lifecycle suites share: the three in-memory DAOs the lifecycle path touches
 * (workout history, family progression, decision history), a clock the recorder is given, and the
 * fixtures the calibrated signal windows are built from.
 *
 * ## Why fakes, and what they model
 *
 * This project has no Robolectric, no `androidx.test` and no in-memory-Room harness — the existing
 * data-layer suites fake their DAO the same way (`ProgramMaintenanceTest`, `SessionHistoryAdapterTest`,
 * `AdaptiveRepositoryTest`). Each fake here models the SQL contract its queries declare, not the code
 * under test:
 *
 *  * [FakeProgressHistoryDao] is the workout-history side — `UserProgress` keyed by `(cycleNumber, day)`,
 *    `set_log` rows read by session date, `program_day_state` read per cycle — plus the cycle-scoped and
 *    table-wide deletes the two C3 maintenance operations issue;
 *  * [FakeAdaptiveStateDao] is `INSERT OR REPLACE` on the composite `(programRevision, familyId)` key;
 *  * [FakeAdaptiveHistoryDao] is append-only with database-assigned ids, and counts a window's records
 *    for the one family the window is asked about.
 *
 * The transaction is supplied by the caller (`AppDatabase.withTransaction` in production), so a
 * committing and a rolling-back runner produce observably different databases — which is what makes
 * "both tables or neither" and "the whole session's decisions or none" provable here.
 */
internal class AdaptiveLifecycleRig(
    userProgress: List<UserProgress> = emptyList(),
    programDayStates: List<ProgramDayState> = emptyList(),
    setLogs: List<SetLog> = emptyList(),
    familyStates: List<FamilyProgressionState> = emptyList(),
    records: List<AdaptiveDecisionRecord> = emptyList()
) {

    val progressDao = FakeProgressHistoryDao(userProgress, programDayStates, setLogs)
    val stateDao = FakeAdaptiveStateDao(familyStates)
    val historyDao = FakeAdaptiveHistoryDao(records)

    /** The clock the recorder reads: pinned, so a write stamp is an assertion instead of a mystery. */
    var now: Long = DEFAULT_NOW

    /** How many transactions the persistence path opened, however many writes ran inside them. */
    var transactions: Int = 0
        private set

    val generator = WorkoutGenerator()

    /** A transaction that commits the whole block, exactly as `database.withTransaction` does. */
    val committing: suspend (suspend () -> Unit) -> Unit = { block ->
        transactions++
        block()
    }

    /**
     * A transaction that rolls the block back on failure: it snapshots the tables, runs the block and
     * restores the snapshot if the block throws — the observable outcome of a Room rollback.
     */
    fun rollingBack(): suspend (suspend () -> Unit) -> Unit = { block ->
        transactions++
        val stateBefore = stateDao.rows.toList()
        val historyBefore = historyDao.rows.toList()
        try {
            block()
        } catch (e: Exception) {
            stateDao.rows.apply { clear(); addAll(stateBefore) }
            historyDao.rows.apply { clear(); addAll(historyBefore) }
            throw e
        }
    }

    fun repository(inTransaction: suspend (suspend () -> Unit) -> Unit = committing) =
        AdaptiveRepository(stateDao, historyDao, inTransaction)

    /** A recorder whose window is the given history, exactly as a test wants to calibrate it. */
    fun recorder(
        history: List<SessionObservation>,
        inTransaction: suspend (suspend () -> Unit) -> Unit = committing
    ) = AdaptiveSessionDecisionRecorder(
        repository = repository(inTransaction),
        readHistory = { history },
        generator = generator,
        now = { now }
    )

    /**
     * A recorder wired to the production reads: Task 3's history adapter over the rig's own persisted
     * rows, and the two adaptive tables. This is the path the app runs, minus the database engine.
     */
    fun recorderOverPersistedHistory(
        programStartDate: LocalDate = PROGRAM_START,
        inTransaction: suspend (suspend () -> Unit) -> Unit = committing
    ) = AdaptiveSessionDecisionRecorder(
        repository = repository(inTransaction),
        readHistory = { startDate ->
            SessionHistoryAdapter(
                progressDao = progressDao,
                workoutGenerator = generator,
                programStartDate = startDate
            ).observations()
        },
        generator = generator,
        now = { now }
    )

    // ---- fixtures -------------------------------------------------------------------------------

    fun request(
        programCycle: Int = 1,
        programDay: Int,
        programRevision: Int = 0,
        enabledExerciseIds: Set<String> = setOf("pushups"),
        programStartDate: LocalDate = PROGRAM_START,
        availableEquipment: Set<Equipment> = emptySet()
    ) = SessionFinalizationRequest(
        programCycle = programCycle,
        programDay = programDay,
        programRevision = programRevision,
        configuration = WorkoutConfigurationSnapshot(
            configurationVersion = 1,
            enabledExerciseIds = enabledExerciseIds
        ),
        programStartDate = programStartDate,
        availableEquipment = availableEquipment
    )

    /**
     * One session as the signal layer reads it: a repetition exercise prescribed in full and performed
     * to [completedRepsPerSet] of its target per set, so a window's exposure is exactly the fraction
     * the fixture names.
     */
    fun repSession(
        programDay: Int,
        cycleNumber: Int = 1,
        exerciseId: String = "pushups",
        plannedSets: Int = 3,
        plannedRepsPerSet: Int = 10,
        completedSets: Int = plannedSets,
        completedRepsPerSet: Int = plannedRepsPerSet,
        outcome: SessionOutcome = SessionOutcome.COMPLETED
    ): SessionObservation {
        // A session that never started observed nothing: the observation's own contract requires it.
        val performedSets = if (outcome == SessionOutcome.NOT_STARTED) 0 else completedSets
        val performedRepsPerSet = if (outcome == SessionOutcome.NOT_STARTED) 0 else completedRepsPerSet

        val plannedReps = plannedSets * plannedRepsPerSet
        val completedReps = performedSets * performedRepsPerSet
        val started = outcome != SessionOutcome.NOT_STARTED

        return SessionObservation(
            cycleNumber = cycleNumber,
            programDay = programDay,
            startedAt = if (started) DEFAULT_NOW else null,
            finishedAt = if (outcome == SessionOutcome.COMPLETED) DEFAULT_NOW else null,
            outcome = outcome,
            plannedExercises = 1,
            completedExercises = if (outcome == SessionOutcome.NOT_STARTED) 0 else 1,
            plannedWork = Workload(sets = plannedSets, reps = plannedReps),
            actualWork = Workload(sets = performedSets, reps = completedReps),
            exerciseResults = listOf(
                ExerciseResult(
                    exerciseId = exerciseId,
                    plannedSets = plannedSets,
                    completedSets = performedSets,
                    plannedReps = plannedReps,
                    completedReps = completedReps,
                    plannedDurationSeconds = 0,
                    completedDurationSeconds = 0
                )
            )
        )
    }

    /**
     * The sustaining history: [count] consecutive fully-performed sessions, each prescribed and
     * completed in full. It is the window every confirmed-PROGRESS case is measured against
     * (`eligibleSessionCount` `count`, exposure score and adherence `1.0`, no measurable load increase,
     * a flat trend).
     */
    fun fullExposureSessions(
        count: Int = 6,
        cycleNumber: Int = 1,
        exerciseId: String = "pushups"
    ): List<SessionObservation> = (1..count).map { day -> repSession(programDay = day, cycleNumber = cycleNumber, exerciseId = exerciseId) }

    /**
     * The documented high-load deterioration window: seven low-volume days followed by seven days at
     * double the sets and a fraction of the prescribed repetitions, so the recent seven-day load is
     * double the preceding one (`loadRatio = 2.0`, bucket HIGH) while the weighted exposure score sits
     * far below the policy's strong-low-exposure threshold. That is the pattern RECOVERY is entered on.
     */
    fun highLoadDeteriorationSessions(
        cycleNumber: Int = 1,
        exerciseId: String = "pushups"
    ): List<SessionObservation> = (1..14).map { day ->
        if (day <= 7) {
            repSession(
                programDay = day,
                cycleNumber = cycleNumber,
                exerciseId = exerciseId,
                plannedSets = 1,
                plannedRepsPerSet = 20,
                completedSets = 1,
                completedRepsPerSet = 20
            )
        } else {
            repSession(
                programDay = day,
                cycleNumber = cycleNumber,
                exerciseId = exerciseId,
                plannedSets = 2,
                plannedRepsPerSet = 20,
                completedSets = 2,
                completedRepsPerSet = 3
            )
        }
    }

    /** The calendar date one program position falls on: one program day is one calendar day. */
    fun sessionDate(programDay: Int, cycleNumber: Int = 1, programStartDate: LocalDate = PROGRAM_START): LocalDate =
        programStartDate.plusDays(((cycleNumber - 1) * TOTAL_PROGRAM_DAYS + programDay - 1).toLong())

    /** The epoch-millisecond stamp the day-level completion source stores for that date. */
    fun completionMillis(programDay: Int, cycleNumber: Int = 1, programStartDate: LocalDate = PROGRAM_START): Long =
        sessionDate(programDay, cycleNumber, programStartDate).toEpochDay() * MILLIS_PER_DAY

    companion object {
        const val DEFAULT_NOW: Long = 1_800_000_000_000L
        const val MILLIS_PER_DAY: Long = 86_400_000L
        const val TOTAL_PROGRAM_DAYS: Int = 56
        /** A second family's exercise, for the cases that need more than one family in a window. */
        const val PLANK_EXERCISE_ID: String = "plank"

        /** A fixed program start, so every fixture's calendar position is deterministic. */
        val PROGRAM_START: LocalDate = LocalDate.of(2026, 9, 1)
    }
}

/**
 * The persisted current state one family is seeded with, usable before a rig exists — a rig's own
 * constructor seeds its state table with these.
 */
internal fun persistedFamilyState(
    familyId: String = "pushups",
    progressionLevel: Int = 0,
    currentExerciseId: String? = null,
    adaptationState: AdaptiveState = AdaptiveState.HOLD,
    precedingProgressQualifyingWindows: Int = 0,
    precedingRegressQualifyingWindows: Int = 0,
    precedingHighRiskWindows: Int = 0,
    recoveryQualifyingSessions: Int = 0,
    eligibleSessionsSinceLastProgressionChange: Int? = null,
    programRevision: Int = 0,
    updatedAt: Long = AdaptiveLifecycleRig.DEFAULT_NOW,
    policyVersion: Int = 1
) = FamilyProgressionState(
    familyId = familyId,
    progressionLevel = progressionLevel,
    currentExerciseId = currentExerciseId,
    adaptationState = adaptationState,
    precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
    precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
    precedingHighRiskWindows = precedingHighRiskWindows,
    recoveryQualifyingSessions = recoveryQualifyingSessions,
    eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange,
    programRevision = programRevision,
    updatedAt = updatedAt,
    policyVersion = policyVersion
)

/**
 * In-memory `ProgressDao`: the workout-history side of the rig, plus the two C3 destructive operations
 * the lifecycle suites drive.
 */
internal class FakeProgressHistoryDao(
    userProgress: List<UserProgress> = emptyList(),
    programDayStates: List<ProgramDayState> = emptyList(),
    setLogs: List<SetLog> = emptyList()
) : ProgressDao {

    var userProgress = userProgress.toMutableList()
    var programDayStates = programDayStates.toMutableList()
    var setLogs = setLogs.toMutableList()

    val callLog = mutableListOf<String>()

    override suspend fun getCompletedDays(): List<UserProgress> = userProgress.filter { it.isCompleted }

    override suspend fun getDayProgressSnapshot(): List<UserProgress> = userProgress

    override suspend fun getSessionDates(): List<String> = setLogs.map { it.sessionDate }.distinct().sorted()

    override suspend fun getProgramCycles(): List<Int> = programDayStates.map { it.cycleNumber }.distinct().sorted()

    override suspend fun getSetLogsForSessionDate(sessionDate: String): List<SetLogRow> = setLogs
        .filter { it.sessionDate == sessionDate }
        .sortedBy { it.timestamp }
        .map {
            SetLogRow(
                exerciseId = it.exerciseId,
                sessionDate = it.sessionDate,
                timestamp = it.timestamp,
                repsCompleted = it.repsCompleted,
                durationSeconds = it.durationSeconds
            )
        }

    override suspend fun getProgressByDay(cycleNumber: Int, day: Int): UserProgress? =
        userProgress.firstOrNull { it.cycleNumber == cycleNumber && it.day == day }

    override suspend fun getProgramDayStatesSnapshot(cycleNumber: Int): List<ProgramDayState> =
        programDayStates.filter { it.cycleNumber == cycleNumber }

    override suspend fun getProgramDayState(cycleNumber: Int, day: Int): ProgramDayState? =
        programDayStates.firstOrNull { it.cycleNumber == cycleNumber && it.programDay == day }

    override suspend fun deleteUserProgressForCycle(cycleNumber: Int) {
        callLog += "deleteUserProgressForCycle($cycleNumber)"
        userProgress.removeAll { it.cycleNumber == cycleNumber }
    }

    override suspend fun deletePostureProgressForCycle(cycleNumber: Int) {
        callLog += "deletePostureProgressForCycle($cycleNumber)"
    }

    override suspend fun deleteProgramDayStatesForCycle(cycleNumber: Int) {
        callLog += "deleteProgramDayStatesForCycle($cycleNumber)"
        programDayStates.removeAll { it.cycleNumber == cycleNumber }
    }

    override suspend fun clearUserProgress() {
        callLog += "clearUserProgress"
        userProgress.clear()
    }

    override suspend fun clearPostureProgress() {
        callLog += "clearPostureProgress"
    }

    override suspend fun clearProgramDayStates() {
        callLog += "clearProgramDayStates"
        programDayStates.clear()
    }

    override suspend fun clearSetLogs() {
        callLog += "clearSetLogs"
        setLogs.clear()
    }

    override suspend fun clearBodyWeightEntries() {
        callLog += "clearBodyWeightEntries"
    }

    // --- not exercised by the lifecycle contract --------------------------------------------------

    override fun getAllProgress(cycleNumber: Int): Flow<List<UserProgress>> = flowOf(userProgress)
    override fun getCompletedDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
    override suspend fun updateProgress(progress: UserProgress) {}
    override suspend fun insertSetLog(setLog: SetLog) {}
    override suspend fun insertEntry(entry: BodyWeightEntry) {}
    override fun getEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> = flowOf(emptyList())
    override suspend fun getLatestEntry(): BodyWeightEntry? = null
    override suspend fun deleteLatestSetLogForExerciseOnDate(exerciseId: String, sessionDate: String) {}
    override fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
    override fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
    override fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>> = flowOf(emptyList())
    override fun getAllPostureProgress(cycleNumber: Int): Flow<List<PostureSessionProgress>> = flowOf(emptyList())
    override fun getCompletedPostureDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
    override suspend fun getPostureProgressByDay(cycleNumber: Int, day: Int): PostureSessionProgress? = null
    override suspend fun updatePostureProgress(progress: PostureSessionProgress) {}
    override fun getProgramDayStates(cycleNumber: Int): Flow<List<ProgramDayState>> = flowOf(programDayStates)
    override suspend fun upsertProgramDayStates(states: List<ProgramDayState>) {}
    override suspend fun upsertProgramDayState(state: ProgramDayState) {}
    override fun getProgramStatistics(cycleNumber: Int): Flow<ProgramStatisticsSnapshot> =
        flowOf(ProgramStatisticsSnapshot(0, 0, 0, 0, 0, 0, 0))
    override suspend fun getMaxProgramDayStateCycle(): Int? = null
    override fun getMealCycles(): Flow<List<MealCycle>> = flowOf(emptyList())
    override suspend fun getMealCyclesSnapshot(): List<MealCycle> = emptyList()
    override suspend fun insertMealCycle(cycle: MealCycle): Long = 0
    override suspend fun insertMeals(meals: List<MealEntity>) {}
    override suspend fun insertShoppingItems(items: List<ShoppingItemEntity>) {}
    override suspend fun deleteMealsForCycle(cycleId: Long) {}
    override suspend fun deleteShoppingItemsForCycle(cycleId: Long) {}
    override fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>> = flowOf(emptyList())
    override fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>> = flowOf(emptyList())
    override suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity> = emptyList()
    override suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity> = emptyList()
    override suspend fun getMealForCycleAndType(cycleId: Long, programDay: Int, mealTypeKey: String): MealEntity? = null
    override suspend fun upsertMeal(meal: MealEntity) {}
}

/** In-memory `FamilyProgressionStateDao`: `INSERT OR REPLACE` on `(programRevision, familyId)`. */
internal class FakeAdaptiveStateDao(
    familyStates: List<FamilyProgressionState> = emptyList()
) : FamilyProgressionStateDao {

    val rows = familyStates.toMutableList()
    val callLog = mutableListOf<String>()

    /** Set to make the state write fail, which is how "neither table lands" is driven. */
    var failure: String? = null

    override suspend fun getFamilyStates(programRevision: Int): List<FamilyProgressionState> =
        rows.filter { it.programRevision == programRevision }.sortedBy { it.familyId }

    override suspend fun getFamilyState(programRevision: Int, familyId: String): FamilyProgressionState? =
        rows.firstOrNull { it.programRevision == programRevision && it.familyId == familyId }

    override suspend fun upsertFamilyState(state: FamilyProgressionState) {
        callLog += "upsertFamilyState(${state.programRevision}, ${state.familyId})"
        failure?.let { throw IllegalStateException(it) }
        // SQLite's INSERT OR REPLACE deletes the conflicting row and inserts a new one.
        rows.removeAll { it.programRevision == state.programRevision && it.familyId == state.familyId }
        rows += state
    }

    override suspend fun clearFamilyStates() {
        callLog += "clearFamilyStates"
        rows.clear()
    }
}

/** In-memory `AdaptiveDecisionHistoryDao`: append-only, database-assigned ids, ordered reads. */
internal class FakeAdaptiveHistoryDao(
    records: List<AdaptiveDecisionRecord> = emptyList()
) : AdaptiveDecisionHistoryDao {

    val rows = records.toMutableList()
    val callLog = mutableListOf<String>()

    /** Set to make the append fail, which is how "neither table lands" is driven. */
    var failure: String? = null

    private var nextId: Long = (records.maxOfOrNull { it.id } ?: 0L) + 1L

    override suspend fun appendDecision(record: AdaptiveDecisionRecord): Long {
        callLog += "appendDecision(${record.programRevision}, ${record.cycleNumber}, ${record.programDay}, ${record.familyId})"
        failure?.let { throw IllegalStateException(it) }
        val stored = if (record.id == 0L) record.copy(id = nextId++) else record
        rows += stored
        return stored.id
    }

    override suspend fun getDecisionHistory(programRevision: Int): List<AdaptiveDecisionRecord> =
        rows.filter { it.programRevision == programRevision }
            .sortedWith(compareBy({ it.cycleNumber }, { it.programDay }, { it.id }))

    override suspend fun getDecisionHistoryForFamily(familyId: String): List<AdaptiveDecisionRecord> =
        rows.filter { it.familyId == familyId }
            .sortedWith(compareBy({ it.programRevision }, { it.cycleNumber }, { it.programDay }, { it.id }))

    override suspend fun countDecisionsFor(
        programRevision: Int,
        cycleNumber: Int,
        programDay: Int,
        familyId: String
    ): Int = rows.count {
        it.programRevision == programRevision &&
            it.cycleNumber == cycleNumber &&
            it.programDay == programDay &&
            it.familyId == familyId
    }

    override suspend fun clearDecisionHistory() {
        callLog += "clearDecisionHistory"
        failure?.let { throw IllegalStateException(it) }
        rows.clear()
    }
}
