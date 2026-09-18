package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.LegacyV7Schema
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
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
internal class ProgramDataAccessRig(key: String = "a") {

    /** The migrated database: the version-7 schema, then the production migrations to version 9. */
    val database: SqliteTestDatabase = SqliteTestDatabase.inMemory().also { database ->
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        // The deployed chain: the target schema, then the schedule-frequency correction. Stopping at
        // version 8 would exercise a database no device opens.
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
    }

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
    val slotDao = SqliteProgramWorkoutSlotDao(database)
    val sessionDao = SqliteWorkoutSessionDao(database)
    val snapshotDao: SessionSnapshotDao = FailingSessionSnapshotDao(SqliteSessionSnapshotDao(database), faults)
    val snapshotExerciseDao = SqliteSessionSnapshotExerciseDao(database)
    val sessionExerciseDao = SqliteSessionExerciseDao(database)
    val setLogDao: ProgramSetLogDao = FailingProgramSetLogDao(SqliteProgramSetLogDao(database), faults)
    val pauseDao = SqliteProgramPauseDao(database)
    val familyStateDao = SqliteProgramFamilyProgressionStateDao(database)
    val decisionDao = SqliteProgramAdaptiveDecisionDao(database)
    val adjustmentDao: AdaptiveAdjustmentDao =
        FailingAdaptiveAdjustmentDao(SqliteAdaptiveAdjustmentDao(database), faults)

    val programRepository = ProgramRepository(programDao, revisionDao, dayDao, exerciseDao, slotDao, transaction)
    val programPlanRepository = ProgramPlanRepository(programDao, revisionDao, dayDao, exerciseDao, transaction)
    val programScheduleRepository = ProgramScheduleRepository(slotDao, sessionDao, pauseDao)
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

    /** When set, appending a confirmed set fails. */
    var failSetLogInsert: Boolean = false

    /** When set, appending the adjustment of an applied decision fails. */
    var failAdjustmentInsert: Boolean = false
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
