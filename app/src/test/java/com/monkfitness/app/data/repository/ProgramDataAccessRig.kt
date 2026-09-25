package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.LegacyV7Schema
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramTargetOccurrenceDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.SessionExerciseDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceComponentEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The repository suites' rig: a migrated version-8 database, the target DAOs executed on the real
 * SQLite engine, the seven repositories wired exactly as `AppContainer` will wire them (§26), and a
 * transaction runner that performs a genuine `COMMIT`/`ROLLBACK`.
 *
 * Everything a suite needs is reachable from here: [database] for direct SQL (mutating a live plan
 * underneath a session, counting rows after a rollback), the repositories for the behaviour under
 * test, and [restart] for reading the same rows through a second repository instance — which is what
 * proves a value came from storage rather than from a cache in memory.
 *
 * The clock is a value, not a hidden call: [now] can be set by a test, because the only timestamp this
 * layer stamps is a family state's write stamp and a test must be able to state when that was.
 */
internal class ProgramDataAccessRig(key: String = "a", supplied: SqliteTestDatabase? = null) {

    /**
     * The migrated database: the version-7 schema, then the production migrations to version 9.
     *
     * A rig built over a **supplied** database does not migrate it: the caller already did, and the
     * deployed chain is not idempotent (a second `ALTER TABLE ... ADD COLUMN` is an error, exactly as it
     * would be on a device opening the same file twice). That is what lets two rigs — two *processes*, as
     * far as SQLite is concerned — share one file: one opens it and runs the chain, the other opens the
     * same file and reads what the first wrote.
     */
    val database: SqliteTestDatabase = supplied ?: SqliteTestDatabase.inMemory().also(::migrate)

    /**
     * The transaction runner the repositories receive. Production passes
     * `AppDatabase.withTransaction`; here it is the engine's own transaction, so an atomicity claim is
     * decided by SQLite rather than by the test's bookkeeping.
     */
    val transaction: suspend (suspend () -> Unit) -> Unit = { block -> database.transaction { block() } }

    /** The clock the adaptive repository stamps family state with. A test may move it. */
    var now: Instant = ProgramGraphFixture.CREATED

    /** The faults a suite can plant, to prove a failure part-way through a transaction is rolled back. */
    val faults = ProgramDaoFaults()

    val programDao = SqliteProgramDao(database)
    val appStateDao = SqliteAppStateDao(database)
    val revisionDao = SqliteProgramRevisionDao(database)
    val dayDao = SqliteProgramDayDao(database)
    val exerciseDao: ProgramExerciseDao = FailingProgramExerciseDao(SqliteProgramExerciseDao(database), faults)
    val slotDao: ProgramWorkoutSlotDao = FailingProgramWorkoutSlotDao(SqliteProgramWorkoutSlotDao(database), faults)
    // §30 step 14: the target occurrence's semantic payload, with one switchable failure on the
    // *component* insert — the second leg of the atomic unit whose first leg is the slot insert, so a
    // rollback claim is measurable at a point other than the last write.
    val targetOccurrenceDao: ProgramTargetOccurrenceDao =
        FailingProgramTargetOccurrenceDao(SqliteProgramTargetOccurrenceDao(database), faults)
    val sessionDao = SqliteWorkoutSessionDao(database)
    val snapshotDao: SessionSnapshotDao = FailingSessionSnapshotDao(SqliteSessionSnapshotDao(database), faults)
    val snapshotExerciseDao = SqliteSessionSnapshotExerciseDao(database)
    val sessionExerciseDao: SessionExerciseDao =
        FailingSessionExerciseDao(SqliteSessionExerciseDao(database), faults)
    val setLogDao: ProgramSetLogDao = FailingProgramSetLogDao(SqliteProgramSetLogDao(database), faults)
    val pauseDao = SqliteProgramPauseDao(database)
    val familyStateDao: ProgramFamilyProgressionStateDao =
        FailingFamilyProgressionStateDao(SqliteProgramFamilyProgressionStateDao(database), faults)
    val decisionDao: ProgramAdaptiveDecisionDao =
        FailingAdaptiveDecisionDao(SqliteProgramAdaptiveDecisionDao(database), faults)
    val adjustmentDao: AdaptiveAdjustmentDao =
        FailingAdaptiveAdjustmentDao(SqliteAdaptiveAdjustmentDao(database), faults)

    val programRepository = ProgramRepository(programDao, revisionDao, dayDao, exerciseDao, slotDao, transaction)
    val programPlanRepository = ProgramPlanRepository(programDao, revisionDao, dayDao, exerciseDao, transaction)
    val programScheduleRepository = ProgramScheduleRepository(slotDao, sessionDao, pauseDao)
    val targetScheduleOccurrenceRepository = TargetScheduleOccurrenceRepository(targetOccurrenceDao)
    val workoutSessionRepository = WorkoutSessionRepository(
        sessionDao, snapshotDao, snapshotExerciseDao, sessionExerciseDao, setLogDao, slotDao, transaction
    )
    val appStateRepository = AppStateRepository(appStateDao)
    val programAdaptiveRepository = ProgramAdaptiveRepository(
        familyStateDao, decisionDao, adjustmentDao, now = { now }, inTransaction = transaction
    )
    val programProgressRepository = ProgramProgressRepository(slotDao, sessionDao, setLogDao)

    /** The graph this rig was created for: a Program, its first revision and its initial slots. */
    val graph: ProgramGraph = ProgramGraphFixture.graph(key)

    /**
     * Persists the rig's own graph through the repository, so a suite starts from a Program that was
     * created the way the future use case will create one.
     */
    suspend fun createGraph() = programRepository.createProgram(graph.program, graph.revision, graph.slots)

    /**
     * Starts a session and confirms the sets its value describes, one confirmed set at a time.
     *
     * `startSession` deliberately writes only the session and its captured presentation — a set is
     * confirmed *during* the workout (§27), not at start — so a suite that expects performed sets has to
     * append them the way the session runtime will.
     */
    suspend fun startSessionWithSets(session: WorkoutSession) {
        workoutSessionRepository.startSession(session)
        session.exercises.forEach { occurrence ->
            occurrence.results.forEach { set ->
                workoutSessionRepository.appendSet(occurrence.sessionExerciseId, set)
            }
        }
    }

    /**
     * The same rows read through freshly built repositories: they hold no state of their own, so a
     * value that survives this is a value that came from storage.
     */
    fun freshProgramRepository() = ProgramRepository(programDao, revisionDao, dayDao, exerciseDao, slotDao, transaction)

    fun freshPlanRepository() = ProgramPlanRepository(programDao, revisionDao, dayDao, exerciseDao, transaction)

    fun freshSessionRepository() = WorkoutSessionRepository(
        sessionDao, snapshotDao, snapshotExerciseDao, sessionExerciseDao, setLogDao, slotDao, transaction
    )

    fun freshAdaptiveRepository() = ProgramAdaptiveRepository(
        familyStateDao, decisionDao, adjustmentDao, now = { now }, inTransaction = transaction
    )

    fun close() = database.close()

    companion object {

        /**
         * Runs the **deployed** migration chain on an open database: the shipped version-7 schema, then
         * the production migrations to version 11.
         *
         * It is the rig's own setup, exposed because a file-backed database has to be migrated by the
         * first connection that opens it and *not* by the second: a device at version 7 upgrades once, and
         * every later open finds the chain already applied.
         */
        fun migrate(database: SqliteTestDatabase) {
            database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
            // The deployed chain: the target schema, the schedule-frequency correction, the Goal/Focus
            // columns, then §30 step 12's window bookkeeping. Stopping short would exercise a database no
            // device opens.
            database.migrate(AppDatabase.MIGRATION_7_8)
            database.migrate(AppDatabase.MIGRATION_8_9)
            database.migrate(AppDatabase.MIGRATION_9_10)
            database.migrate(AppDatabase.MIGRATION_10_11)
            // §30 step 15: the rig's database is the schema the app opens, so it runs the retirement
            // step too — otherwise every repository suite would be exercising a database the app can
            // no longer produce.
            database.migrate(AppDatabase.MIGRATION_11_12)
            database.migrate(AppDatabase.MIGRATION_12_13)
            // §30 step 14: the target occurrence's semantic tables. Stopping short would leave every
            // repository suite below exercising a database the app can no longer produce.
            database.migrate(AppDatabase.MIGRATION_13_14)
        }
    }
}

/**
 * The failure points a suite can plant in the DAO layer, so "the whole graph lands or none of it does"
 * is measured on a real transaction rather than argued from the code's shape.
 */
internal class ProgramDaoFaults {

    /** When set, inserting the revision's plan elements fails — the "exercise insert fails" case. */
    var failExerciseInsert: Boolean = false

    /** When set, inserting the session's snapshot fails. */
    var failSnapshotInsert: Boolean = false

    /** When set, inserting the occurrences a session runs fails — the failure *after* the snapshot. */
    var failSessionExerciseInsert: Boolean = false

    /** When set, recording a slot's outcome fails — the middle leg of a completion. */
    var failSlotOutcomeUpdate: Boolean = false

    /** When set, appending a confirmed set fails. */
    var failSetLogInsert: Boolean = false

    /** When set, appending the adjustment of an applied decision fails. */
    var failAdjustmentInsert: Boolean = false

    /** When set, appending the decision itself fails — the first leg of the adaptive half. */
    var failDecisionInsert: Boolean = false

    /** When set, writing the family's state after a window fails — the last leg of §27's unit. */
    var failFamilyStateInsert: Boolean = false

    /**
     * When set, reading a Program's opportunities fails — the read a Scheduler pass makes of the stored
     * slots (`ProgramScheduler.pass` reads them before it decides). It plants §28's `SYSTEM_FAILURE` in the
     * path a *read* takes, which is what the Program UI has to tell apart from the ordinary absence of a
     * next date.
     */
    var failSlotRead: Boolean = false

    /**
     * When set, inserting a target occurrence's **components** fails — the second leg of §30 step 14's
     * atomic unit, the first leg being the slot insert that precedes it.
     *
     * It is deliberately the component insert and not the parent insert: failing the parent would fail
     * the unit at its first statement, which proves only that nothing ran. Failing the *second* leg
     * proves the rollback is real, because the parent row and the slot row have both been written by
     * then and both have to disappear.
     */
    var failTargetOccurrenceComponentInsert: Boolean = false
}

/**
 * The target occurrence's semantic rows, with one switchable failure on the component insert.
 *
 * The failure sits on the **second** of the unit's two writes on purpose. §30 step 14's claim is that
 * the target slot and the target occurrence it presents are one atomic operation, and the only way to
 * measure that is to fail after the first write has already landed: a fault on the first write proves
 * only that nothing started, while a fault on the second proves the rollback reaches back and undoes a
 * row that was genuinely written.
 */
private class FailingProgramTargetOccurrenceDao(
    private val delegate: ProgramTargetOccurrenceDao,
    private val faults: ProgramDaoFaults
) : ProgramTargetOccurrenceDao {
    override suspend fun insertOccurrences(occurrences: List<ProgramTargetOccurrenceEntity>) =
        delegate.insertOccurrences(occurrences)

    override suspend fun insertComponents(components: List<ProgramTargetOccurrenceComponentEntity>) {
        if (faults.failTargetOccurrenceComponentInsert) {
            throw IllegalStateException("planted fault: target occurrence component insert")
        }
        delegate.insertComponents(components)
    }

    override suspend fun occurrenceOf(
        programId: String,
        occurrenceKey: String
    ): ProgramTargetOccurrenceEntity? = delegate.occurrenceOf(programId, occurrenceKey)

    override suspend fun componentsOf(
        programId: String,
        occurrenceKey: String
    ): List<ProgramTargetOccurrenceComponentEntity> = delegate.componentsOf(programId, occurrenceKey)

    override suspend fun occurrencesOfProgram(
        programId: String
    ): List<ProgramTargetOccurrenceEntity> = delegate.occurrencesOfProgram(programId)
}

private class FailingAdaptiveDecisionDao(
    private val delegate: ProgramAdaptiveDecisionDao,
    private val faults: ProgramDaoFaults
) : ProgramAdaptiveDecisionDao {
    override suspend fun insertDecision(decision: AdaptiveDecisionRecordEntity) {
        if (faults.failDecisionInsert) throw IllegalStateException("planted fault: decision insert")
        delegate.insertDecision(decision)
    }

    override suspend fun decisionById(decisionId: String): AdaptiveDecisionRecordEntity? =
        delegate.decisionById(decisionId)

    override suspend fun decisionsOfProgram(programId: String): List<AdaptiveDecisionRecordEntity> =
        delegate.decisionsOfProgram(programId)

    override suspend fun decisionsOfRevision(revisionId: String): List<AdaptiveDecisionRecordEntity> =
        delegate.decisionsOfRevision(revisionId)

    override suspend fun decisionsOfSlot(slotId: String): List<AdaptiveDecisionRecordEntity> =
        delegate.decisionsOfSlot(slotId)
}

private class FailingFamilyProgressionStateDao(
    private val delegate: ProgramFamilyProgressionStateDao,
    private val faults: ProgramDaoFaults
) : ProgramFamilyProgressionStateDao {
    override suspend fun upsertState(state: FamilyProgressionStateEntity) {
        if (faults.failFamilyStateInsert) throw IllegalStateException("planted fault: family state")
        delegate.upsertState(state)
    }

    override suspend fun statesOfRevision(revisionId: String): List<FamilyProgressionStateEntity> =
        delegate.statesOfRevision(revisionId)

    override suspend fun stateOf(revisionId: String, familyId: String): FamilyProgressionStateEntity? =
        delegate.stateOf(revisionId, familyId)
}

private class FailingProgramExerciseDao(
    private val delegate: ProgramExerciseDao,
    private val faults: ProgramDaoFaults
) : ProgramExerciseDao {
    override suspend fun insertExercises(exercises: List<ProgramExerciseEntity>) {
        if (faults.failExerciseInsert) throw IllegalStateException("planted fault: exercise insert")
        delegate.insertExercises(exercises)
    }

    override suspend fun exercisesOfRevision(revisionId: String): List<ProgramExerciseEntity> =
        delegate.exercisesOfRevision(revisionId)
}

private class FailingSessionSnapshotDao(
    private val delegate: SessionSnapshotDao,
    private val faults: ProgramDaoFaults
) : SessionSnapshotDao {
    override suspend fun insertSnapshot(snapshot: SessionSnapshotEntity) {
        if (faults.failSnapshotInsert) throw IllegalStateException("planted fault: snapshot insert")
        delegate.insertSnapshot(snapshot)
    }

    override suspend fun snapshotsOf(sessionIds: List<String>): List<SessionSnapshotEntity> =
        delegate.snapshotsOf(sessionIds)
}

/**
 * The session's occurrences, with one switchable failure.
 *
 * It exists for the third deliberate-failure point of a start: a snapshot that landed and a session
 * that did not keep its occurrences is as forbidden as a session with no snapshot at all, and only a
 * real transaction can prove that.
 */
private class FailingSessionExerciseDao(
    private val delegate: SessionExerciseDao,
    private val faults: ProgramDaoFaults
) : SessionExerciseDao {
    override suspend fun insertSessionExercises(exercises: List<SessionExerciseEntity>) {
        if (faults.failSessionExerciseInsert) throw IllegalStateException("planted fault: occurrence insert")
        delegate.insertSessionExercises(exercises)
    }

    override suspend fun sessionExercisesOfSessions(sessionIds: List<String>): List<SessionExerciseEntity> =
        delegate.sessionExercisesOfSessions(sessionIds)

    override suspend fun sessionExerciseById(sessionExerciseId: String): SessionExerciseEntity? =
        delegate.sessionExerciseById(sessionExerciseId)
}

/**
 * The opportunities, with one switchable failure on the outcome write — the middle leg of a completion
 * (the session is written first, the adaptive decision last), which is what makes "the whole completion
 * or none of it" measurable at a point other than the last write.
 */
private class FailingProgramWorkoutSlotDao(
    private val delegate: ProgramWorkoutSlotDao,
    private val faults: ProgramDaoFaults
) : ProgramWorkoutSlotDao {
    override suspend fun insertSlots(slots: List<ProgramWorkoutSlotEntity>) = delegate.insertSlots(slots)

    override suspend fun slotById(slotId: String): ProgramWorkoutSlotEntity? = delegate.slotById(slotId)

    override suspend fun slotByTargetOccurrenceKey(
        programId: String,
        targetOccurrenceKey: String
    ): ProgramWorkoutSlotEntity? = delegate.slotByTargetOccurrenceKey(programId, targetOccurrenceKey)

    override suspend fun slotsOfProgram(programId: String): List<ProgramWorkoutSlotEntity> {
        if (faults.failSlotRead) throw IllegalStateException("planted fault: slot read")
        return delegate.slotsOfProgram(programId)
    }

    override suspend fun slotsOfRevision(revisionId: String): List<ProgramWorkoutSlotEntity> =
        delegate.slotsOfRevision(revisionId)

    override suspend fun slotsFrom(
        programId: String,
        fromDate: String,
        status: String
    ): List<ProgramWorkoutSlotEntity> = delegate.slotsFrom(programId, fromDate, status)

    override suspend fun updateOutcome(slotId: String, status: String, completedAt: Long?) {
        if (faults.failSlotOutcomeUpdate) throw IllegalStateException("planted fault: slot outcome")
        delegate.updateOutcome(slotId, status, completedAt)
    }

    override suspend fun countByStatus(programId: String, status: String): Int =
        delegate.countByStatus(programId, status)
}

private class FailingAdaptiveAdjustmentDao(
    private val delegate: AdaptiveAdjustmentDao,
    private val faults: ProgramDaoFaults
) : AdaptiveAdjustmentDao {
    override suspend fun insertAdjustment(adjustment: AdaptiveAdjustmentEntity) {
        if (faults.failAdjustmentInsert) throw IllegalStateException("planted fault: adjustment insert")
        delegate.insertAdjustment(adjustment)
    }

    override suspend fun adjustmentById(adjustmentId: String): AdaptiveAdjustmentEntity? =
        delegate.adjustmentById(adjustmentId)

    override suspend fun adjustmentsOfDecision(decisionId: String): List<AdaptiveAdjustmentEntity> =
        delegate.adjustmentsOfDecision(decisionId)

    override suspend fun adjustmentsOfSlot(slotId: String): List<AdaptiveAdjustmentEntity> =
        delegate.adjustmentsOfSlot(slotId)

    override suspend fun adjustmentsOfProgram(programId: String): List<AdaptiveAdjustmentEntity> =
        delegate.adjustmentsOfProgram(programId)

    override suspend fun adjustmentsOfRevision(revisionId: String): List<AdaptiveAdjustmentEntity> =
        delegate.adjustmentsOfRevision(revisionId)
}

private class FailingProgramSetLogDao(
    private val delegate: ProgramSetLogDao,
    private val faults: ProgramDaoFaults
) : ProgramSetLogDao {
    override suspend fun insertSet(setLog: SetLogEntity) {
        if (faults.failSetLogInsert) throw IllegalStateException("planted fault: set log insert")
        delegate.insertSet(setLog)
    }

    override suspend fun setsOfSessionExercise(sessionExerciseId: String): List<SetLogEntity> =
        delegate.setsOfSessionExercise(sessionExerciseId)

    override suspend fun setsOfSessions(sessionIds: List<String>): List<SetLogEntity> =
        delegate.setsOfSessions(sessionIds)

    override suspend fun countSetsOfProgram(programId: String): Int =
        delegate.countSetsOfProgram(programId)
}

/** One Program, its first revision and the slots a creation would produce. */
internal data class ProgramGraph(
    val program: Program,
    val revision: ProgramRevision,
    val slots: List<WorkoutSlot>
) {
    /** The revision's plan days by position, for the assertions that read the plan back. */
    val days: List<ProgramDay> get() = revision.days

    /** The slot that presents the day at [position]. */
    fun slotFor(position: Int): WorkoutSlot = slots.single { it.programDayId == dayId(position) }

    fun dayId(position: Int): ProgramDayId = revision.days.single { it.position == position }.programDayId

    fun dayIdValue(position: Int): String = dayId(position).value
}

/**
 * The domain graphs the suites build on: a deliberately non-trivial plan (two training days with a
 * rest day between them, one exercise occurring twice in a day, a per-set repetition prescription and
 * a per-set duration prescription) and a session whose captured presentation differs from the live
 * plan.
 *
 * Every id carries the graph's key, so two Programs can coexist in one database and "the other
 * Program survived" is measurable rather than asserted.
 */
internal fun failureOf(block: suspend () -> Unit): Throwable {
    val thrown = try {
        kotlinx.coroutines.runBlocking { block() }
        null
    } catch (failure: Throwable) {
        failure
    }
    return requireNotNull(thrown) { "expected the call to fail, and it did not" }
}

internal object ProgramGraphFixture {

    val CREATED: Instant = Instant.parse("2026-09-13T08:00:00Z")
    val STARTED: Instant = Instant.parse("2026-09-14T07:30:00Z")
    val UPDATED: Instant = Instant.parse("2026-09-15T09:00:00Z")
    val COMPUTED: Instant = Instant.parse("2026-09-21T07:20:00Z")
    val CAPTURED: Instant = Instant.parse("2026-09-21T07:30:00Z")
    val SET_ONE: Instant = Instant.parse("2026-09-21T07:40:00Z")
    val SET_TWO: Instant = Instant.parse("2026-09-21T07:45:00Z")
    val SET_THREE: Instant = Instant.parse("2026-09-21T07:50:00Z")
    val FINISHED: Instant = Instant.parse("2026-09-21T08:20:00Z")

    fun programId(key: String) = "program-$key"

    fun revisionId(key: String) = "revision-$key"

    fun slotId(key: String, position: Int) = "slot-$key-$position"

    fun dayId(key: String, position: Int) = "day-$key-$position"

    fun planExerciseId(key: String, position: Int) = "plan-ex-$key-$position"

    /** A running Program: it has started (a lifecycle other than NOT_STARTED requires the stamp). */
    fun program(
        key: String,
        lifecycleStatus: LifecycleStatus = LifecycleStatus.RUNNING,
        plannedStartDate: LocalDate? = LocalDate.parse("2026-09-14"),
        archivedAt: Instant? = null
    ) = Program(
        programId = ProgramId(programId(key)),
        name = "Program $key",
        description = "the fixture program $key",
        source = ProgramSource.USER,
        lifecycleStatus = lifecycleStatus,
        currentRevisionId = RevisionId(revisionId(key)),
        createdAt = CREATED,
        updatedAt = UPDATED,
        plannedStartDate = plannedStartDate,
        actualStartDate = if (lifecycleStatus == LifecycleStatus.NOT_STARTED) null else STARTED,
        archivedAt = archivedAt
    )

    /**
     * The three-day plan:
     *
     * ```text
     * day 1  TRAINING  plan-ex-1 pushup 12/10/8/6 (generated), plan-ex-2 pike_pushup 8/8 (user, pinned)
     * day 2  REST      no elements
     * day 3  TRAINING  plan-ex-3 pushup 5, plan-ex-4 pushup 5 (the same exercise twice), plank 30/30/45
     * ```
     */
    fun days(key: String): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId(dayId(key, 1)),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Push day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId(planExerciseId(key, 1)),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(12, 10, 8, 6)),
                    origin = ProgramExerciseOrigin.GENERATED
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId(planExerciseId(key, 2)),
                    exerciseId = "pike_pushup",
                    prescription = RepPrescription(listOf(8, 8)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED,
                    isPinned = true
                )
            )
        ),
        ProgramDay(
            programDayId = ProgramDayId(dayId(key, 2)),
            position = 2,
            type = ProgramDayType.REST
        ),
        ProgramDay(
            programDayId = ProgramDayId(dayId(key, 3)),
            position = 3,
            type = ProgramDayType.TRAINING,
            name = "Repeat day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId(planExerciseId(key, 3)),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(5)),
                    origin = ProgramExerciseOrigin.GENERATED
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId(planExerciseId(key, 4)),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(5)),
                    origin = ProgramExerciseOrigin.GENERATED
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId(planExerciseId(key, 5)),
                    exerciseId = "plank",
                    prescription = TimePrescription(listOf(30, 30, 45)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        )
    )

    /**
     * A structurally different second revision of the same Program: a new revision identity, new day
     * identities and new occurrence identities, and a different prescription.
     *
     * The identities have to be new: a day and an occurrence are rows of their own, so a second
     * revision that reused them would be rewriting the first one's plan rather than adding a plan
     * (§6, §9).
     */
    fun nextRevision(key: String, revisionNumber: Int = 2): ProgramRevision {
        val tag = "$key-$revisionNumber"
        val revisedDays = days(key).map { day ->
            day.copy(
                programDayId = ProgramDayId("day-$tag-${day.position}"),
                name = day.name?.let { "Revised $it" },
                exercises = day.exercises.mapIndexed { index, element ->
                    element.copy(
                        programExerciseId = ProgramExerciseId("plan-ex-$tag-${day.position}-${index + 1}"),
                        prescription = RepPrescription(listOf(6))
                    )
                }
            )
        }
        return secondRevision(key, tag, revisionNumber, revisedDays)
    }

    private fun secondRevision(
        key: String,
        tag: String,
        revisionNumber: Int,
        days: List<ProgramDay>
    ): ProgramRevision = ProgramRevision(
        revisionId = RevisionId("revision-$tag"),
        programId = ProgramId(programId(key)),
        revisionNumber = revisionNumber,
        mode = ProgramMode.GENERATED,
        duration = ProgramDuration.Indefinite,
        schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY)),
        days = days,
        createdAt = COMPUTED
    )

    /**
     * Runs [block] and returns the `Throwable` it failed with, failing the test when it did not fail.
     *
     * Every failure claim in the repository suites goes through this, so "the repository propagates the
     * failure" is measured rather than argued.
     */
    fun revision(key: String, revisionNumber: Int = 1): ProgramRevision = ProgramRevision(
        revisionId = RevisionId(revisionId(key)),
        programId = ProgramId(programId(key)),
        revisionNumber = revisionNumber,
        mode = ProgramMode.MANUAL,
        duration = ProgramDuration.FixedDays(30),
        schedule = ProgramSchedule.FixedWeekdays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        ),
        days = days(key),
        createdAt = CREATED
    )

    /** One planned slot per plan day, in the plan's order, all still open. */
    fun slots(key: String): List<WorkoutSlot> = (1..3).map { position ->
        WorkoutSlot(
            slotId = SlotId(slotId(key, position)),
            programId = ProgramId(programId(key)),
            revisionId = RevisionId(revisionId(key)),
            programDayId = ProgramDayId(dayId(key, position)),
            plannedFor = LocalDate.parse("2026-09-2$position"),
            status = SlotStatus.PLANNED
        )
    }

    /** The whole graph of one Program, ready for `createProgram`. */
    fun graph(key: String): ProgramGraph = ProgramGraph(
        program = program(key),
        revision = revision(key),
        slots = slots(key)
    )

    /**
     * A session for the slot presenting [position], whose captured presentation is an **adjusted**
     * version of the live plan: the first element is presented as `knee_pushup 10/8` while the revision
     * says `pushup 12/10/8/6`, and one adjustment is recorded as applied.
     *
     * That difference is the point of the snapshot suite: after the live plan is rewritten, the session
     * must still describe what it captured.
     */
    fun session(
        key: String,
        slot: WorkoutSlot,
        status: SessionStatus = SessionStatus.IN_PROGRESS,
        finishedAt: Instant? = null,
        sets: List<SetResult> = listOf(
            SetResult(SetLogId("set-$key-1"), 1, completedReps = 12, durationSeconds = 0, performedAt = SET_ONE),
            SetResult(SetLogId("set-$key-2"), 2, completedReps = 10, durationSeconds = 0, performedAt = SET_TWO)
        ),
        skipped: Boolean = false
    ): WorkoutSession {
        val firstOccurrence = ProgramExerciseId(planExerciseId(key, 1))
        val secondOccurrence = ProgramExerciseId(planExerciseId(key, 2))
        val sessionId = SessionId("session-$key")
        val captured = WorkoutSessionSnapshot(
            sessionId = sessionId,
            capturedAt = CAPTURED,
            workout = EffectiveWorkout(
                slotId = slot.slotId,
                programId = slot.programId,
                revisionId = slot.revisionId,
                plannedFor = slot.plannedFor,
                computedAt = COMPUTED,
                exercises = listOf(
                    EffectiveExercise(firstOccurrence, "knee_pushup", RepPrescription(listOf(10, 8))),
                    EffectiveExercise(secondOccurrence, "pike_pushup", RepPrescription(listOf(8, 8)))
                ),
                appliedAdjustmentIds = listOf(AdjustmentId("adjustment-$key-1"))
            )
        )
        return WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = captured,
            status = status,
            startedAt = CAPTURED,
            finishedAt = finishedAt,
            exercises = listOf(
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("session-ex-$key-1"),
                    programExerciseId = firstOccurrence,
                    exerciseId = "knee_pushup",
                    prescription = RepPrescription(listOf(10, 8)),
                    results = sets
                ),
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("session-ex-$key-2"),
                    programExerciseId = secondOccurrence,
                    exerciseId = "pike_pushup",
                    prescription = RepPrescription(listOf(8, 8)),
                    skipped = skipped
                )
            )
        )
    }

    /** A completed slot for the same opportunity, as a finished session leaves it. */
    fun completedSlot(key: String, slot: WorkoutSlot): WorkoutSlot = slot.copy(
        status = SlotStatus.COMPLETED,
        attempts = listOf(SessionId("session-$key")),
        completedAt = FINISHED
    )
}
