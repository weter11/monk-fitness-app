package com.monkfitness.app.di

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.AdaptiveTypeConverters
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.local.LegacyV7Schema
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgramPauseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.local.SessionExerciseDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SessionSnapshotExerciseDao
import com.monkfitness.app.data.repository.SqliteAdaptiveAdjustmentDao
import com.monkfitness.app.data.repository.SqliteAppStateDao
import com.monkfitness.app.data.repository.SqliteProgramAdaptiveDecisionDao
import com.monkfitness.app.data.repository.SqliteProgramDao
import com.monkfitness.app.data.repository.SqliteProgramDayDao
import com.monkfitness.app.data.repository.SqliteProgramExerciseDao
import com.monkfitness.app.data.repository.SqliteProgramFamilyProgressionStateDao
import com.monkfitness.app.data.repository.SqliteProgramPauseDao
import com.monkfitness.app.data.repository.SqliteProgramRevisionDao
import com.monkfitness.app.data.repository.SqliteProgramSetLogDao
import com.monkfitness.app.data.repository.SqliteProgramWorkoutSlotDao
import com.monkfitness.app.data.repository.SqliteSessionExerciseDao
import com.monkfitness.app.data.repository.SqliteSessionSnapshotDao
import com.monkfitness.app.data.repository.SqliteSessionSnapshotExerciseDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.SqliteWorkoutSessionDao
import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import java.time.Instant

/**
 * The composition root's test doubles.
 *
 * `AppContainer` is the one object in the app whose whole job is *construction*, so the claims that
 * matter about it are claims about what it constructs and what it hands over: that it takes the
 * database's DAOs once, that every repository it builds reads and writes the same store, that the
 * clock it was given is the clock a row is stamped with, and that nothing but the container
 * constructs any of it. Measurable versions of those claims need two things this repository's unit
 * tests do not otherwise have: a database the composition root accepts, and the two adaptive
 * generations on one engine.
 *
 * ### Why a database double rather than an in-memory stand-in
 *
 * A Room database cannot be opened on this JVM (no Robolectric, no instrumentation source set), so
 * `AppContainer` would be untestable if it could only be built over an opened one. It does not need
 * one, though: the DAO accessors it calls and the `RoomDatabase` it is handed are both types, and
 * `AppDatabase` is an abstract class whose accessors are abstract. [SqliteAppDatabase] is therefore
 * the real `AppDatabase` type — an `AbstractMethodError`-free subclass answering with PR 3's DAO
 * implementations over a **real SQLite engine** — so the container under test is the production
 * container, constructed with the production constructor, running the DAO SQL the device runs.
 * Nothing about the wiring is re-implemented for the test.
 *
 * Every accessor call is counted, which is what turns "each DAO is taken from the database once" into
 * a number rather than a reading of the source.
 */
internal class SqliteAppDatabase(private val engine: SqliteTestDatabase) : AppDatabase() {

    /**
     * How many times the composition root asked for each DAO, in the order it asked.
     *
     * `Room`'s accessors are memoized, so a second call is not a correctness bug — it is a *wiring*
     * fact, and the one that decides whether "exactly one DAO per table, shared by every repository"
     * is true. The container asserts the map is one call per accessor with no other key.
     */
    val accessorCalls: MutableMap<String, Int> = LinkedHashMap()

    private fun asked(name: String): Unit {
        accessorCalls[name] = (accessorCalls[name] ?: 0) + 1
    }

    /** The faults a suite can plant in the DAO layer, to prove a failed write is rolled back. */
    val faults = DatabaseFaults()

    /** The number of rows in one table of this database, as the engine reports it. */
    fun rowCount(table: String): Int = engine.count(table)

    // --- the fifteen target DAOs (§30 step 3), on the engine --------------------------------

    private val program by lazy { SqliteProgramDao(engine) }
    private val appState by lazy { SqliteAppStateDao(engine) }
    private val revision by lazy { SqliteProgramRevisionDao(engine) }
    private val day by lazy { SqliteProgramDayDao(engine) }
    private val exercise by lazy { ProgramExerciseDaoFence(SqliteProgramExerciseDao(engine), faults) }
    private val slot by lazy { SqliteProgramWorkoutSlotDao(engine) }
    private val session by lazy { SqliteWorkoutSessionDao(engine) }
    private val snapshot by lazy { SqliteSessionSnapshotDao(engine) }
    private val snapshotExercise by lazy { SqliteSessionSnapshotExerciseDao(engine) }
    private val sessionExercise by lazy { SqliteSessionExerciseDao(engine) }
    private val setLog by lazy { SqliteProgramSetLogDao(engine) }
    private val pause by lazy { SqliteProgramPauseDao(engine) }
    private val familyState by lazy { SqliteProgramFamilyProgressionStateDao(engine) }
    private val decision by lazy { SqliteProgramAdaptiveDecisionDao(engine) }
    private val adjustment by lazy { SqliteAdaptiveAdjustmentDao(engine) }

    // --- the shipped Stage-1 tables, on the same engine (§30 step 15 retires them) ----------

    private val legacyFamilyState by lazy { SqliteLegacyStateDao(engine) }
    private val legacyDecisionHistory by lazy { SqliteLegacyHistoryDao(engine) }

    override fun programDao(): ProgramDao = program.also { asked("programDao") }

    override fun appStateDao(): AppStateDao = appState.also { asked("appStateDao") }

    override fun programRevisionDao(): ProgramRevisionDao = revision.also { asked("programRevisionDao") }

    override fun programDayDao(): ProgramDayDao = day.also { asked("programDayDao") }

    override fun programExerciseDao(): ProgramExerciseDao = exercise.also { asked("programExerciseDao") }

    override fun programWorkoutSlotDao(): ProgramWorkoutSlotDao = slot.also { asked("programWorkoutSlotDao") }

    override fun workoutSessionDao(): WorkoutSessionDao = session.also { asked("workoutSessionDao") }

    override fun sessionSnapshotDao(): SessionSnapshotDao = snapshot.also { asked("sessionSnapshotDao") }

    override fun sessionSnapshotExerciseDao(): SessionSnapshotExerciseDao =
        snapshotExercise.also { asked("sessionSnapshotExerciseDao") }

    override fun sessionExerciseDao(): SessionExerciseDao =
        sessionExercise.also { asked("sessionExerciseDao") }

    override fun programSetLogDao(): ProgramSetLogDao = setLog.also { asked("programSetLogDao") }

    override fun programPauseDao(): ProgramPauseDao = pause.also { asked("programPauseDao") }

    override fun programFamilyProgressionStateDao(): ProgramFamilyProgressionStateDao =
        familyState.also { asked("programFamilyProgressionStateDao") }

    override fun programAdaptiveDecisionDao(): ProgramAdaptiveDecisionDao =
        decision.also { asked("programAdaptiveDecisionDao") }

    override fun adaptiveAdjustmentDao(): AdaptiveAdjustmentDao =
        adjustment.also { asked("adaptiveAdjustmentDao") }

    override fun familyProgressionStateDao(): FamilyProgressionStateDao =
        legacyFamilyState.also { asked("familyProgressionStateDao") }

    override fun adaptiveDecisionHistoryDao(): AdaptiveDecisionHistoryDao =
        legacyDecisionHistory.also { asked("adaptiveDecisionHistoryDao") }

    /**
     * The shipped progress DAO, which the composition root must never ask for (§23: the Program
     * System takes no new dependency on the shipped progress tables). Asking here is a failure rather
     * than a stub, so an accidental reach into the Stage-1 progress path cannot pass silently.
     */
    override fun progressDao(): ProgressDao =
        throw UnsupportedOperationException(
            "the Program System composition root has no business reading the shipped progress tables"
        )

    // --- what a JVM database cannot do ------------------------------------------------------

    /**
     * The database's transaction runner, on the engine: the same all-or-nothing unit Room's
     * `withTransaction` provides on a device (the container's own default, which is the database's
     * `withTransaction`, needs a database Room has opened and is replaced by this in the tests).
     *
     * `SqliteTestDatabase.transaction` performs a real `COMMIT`/`ROLLBACK`, so an atomicity claim made
     * through the container is decided by SQLite rather than by the container's bookkeeping.
     */
    val transaction: suspend (suspend () -> Unit) -> Unit = { block -> engine.transaction { block() } }

    /**
     * Room's own invalidation tracker is never used on this JVM: no DAO here is Room-generated, no
     * query goes through the tracker the container's graph does not otherwise need.
     */
    override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)

    override fun createOpenHelper(configuration: DatabaseConfiguration): SupportSQLiteOpenHelper =
        throw UnsupportedOperationException(
            "this database runs the DAOs' SQL on a real SQLite engine, not through Room's open helper"
        )

    override fun clearAllTables(): Unit =
        throw UnsupportedOperationException("this database holds no Room-managed tables")
}

// ---------------------------------------------------------------------------------------------
// The Stage-1 adaptive tables, on the same engine
// ---------------------------------------------------------------------------------------------

/**
 * The shipped [FamilyProgressionStateDao], executed on SQLite through [SqliteTestDatabase].
 *
 * PR 3's doubles cover the fifteen target DAOs; these two exist because the composition root holds
 * **both** adaptive generations over one database, and "each generation writes only its own tables"
 * is only measurable if both sides write somewhere the test can look. The statements are the ones the
 * schema declares (`LegacyV7Schema.STAGE_ONE_ADAPTIVE_TABLES`), and the stored vocabulary is
 * `AdaptiveTypeConverters`' own — enum names, comma-separated actions — so a value written here is
 * byte-identical to one a device would write.
 */
internal class SqliteLegacyStateDao(private val engine: SqliteTestDatabase) : FamilyProgressionStateDao {

    override suspend fun getFamilyStates(programRevision: Int): List<FamilyProgressionState> =
        engine.rows(
            "SELECT * FROM `family_progression_state` WHERE programRevision = ? ORDER BY familyId ASC",
            programRevision
        ).map { it.legacyFamilyState() }

    override suspend fun getFamilyState(programRevision: Int, familyId: String): FamilyProgressionState? =
        engine.rows(
            "SELECT * FROM `family_progression_state` WHERE programRevision = ? AND familyId = ? LIMIT 1",
            programRevision, familyId
        ).firstOrNull()?.legacyFamilyState()

    override suspend fun upsertFamilyState(state: FamilyProgressionState) {
        engine.exec(
            "INSERT OR REPLACE INTO `family_progression_state` (`familyId`, `progressionLevel`, " +
                "`currentExerciseId`, `adaptationState`, `precedingProgressQualifyingWindows`, " +
                "`precedingRegressQualifyingWindows`, `precedingHighRiskWindows`, " +
                "`recoveryQualifyingSessions`, `eligibleSessionsSinceLastProgressionChange`, " +
                "`programRevision`, `updatedAt`, `policyVersion`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            state.familyId, state.progressionLevel, state.currentExerciseId,
            AdaptiveTypeConverters().adaptiveStateToName(state.adaptationState),
            state.precedingProgressQualifyingWindows, state.precedingRegressQualifyingWindows,
            state.precedingHighRiskWindows, state.recoveryQualifyingSessions,
            state.eligibleSessionsSinceLastProgressionChange, state.programRevision, state.updatedAt,
            state.policyVersion
        )
    }

    override suspend fun clearFamilyStates() = engine.exec("DELETE FROM `family_progression_state`")
}

/** The shipped [AdaptiveDecisionHistoryDao], executed on SQLite through [SqliteTestDatabase]. */
internal class SqliteLegacyHistoryDao(private val engine: SqliteTestDatabase) : AdaptiveDecisionHistoryDao {

    override suspend fun appendDecision(record: AdaptiveDecisionRecord): Long {
        // `nullif(?, 0)` is Room's own form for an auto-generated key: id 0 means "let the database
        // assign one", exactly as the generated insert behaves.
        engine.exec(
            "INSERT INTO `adaptive_decision_record` (`id`, `familyId`, `programRevision`, `cycleNumber`, " +
                "`programDay`, `timestamp`, `previousState`, `newState`, `actions`, `reasonCode`, " +
                "`policyVersion`) VALUES (nullif(?, 0), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            record.id, record.familyId, record.programRevision, record.cycleNumber, record.programDay,
            record.timestamp, record.previousState.name, record.newState.name,
            AdaptiveTypeConverters().actionsToNames(record.actions), record.reasonCode.name,
            record.policyVersion
        )
        return requireNotNull(engine.scalar("SELECT last_insert_rowid()")).toLong()
    }

    override suspend fun getDecisionHistory(programRevision: Int): List<AdaptiveDecisionRecord> =
        engine.rows(
            "SELECT * FROM `adaptive_decision_record` WHERE programRevision = ? " +
                "ORDER BY cycleNumber ASC, programDay ASC, id ASC",
            programRevision
        ).map { it.legacyDecisionRecord() }

    override suspend fun getDecisionHistoryForFamily(familyId: String): List<AdaptiveDecisionRecord> =
        engine.rows(
            "SELECT * FROM `adaptive_decision_record` WHERE familyId = ? " +
                "ORDER BY programRevision ASC, cycleNumber ASC, programDay ASC, id ASC",
            familyId
        ).map { it.legacyDecisionRecord() }

    override suspend fun countDecisionsFor(
        programRevision: Int,
        cycleNumber: Int,
        programDay: Int,
        familyId: String
    ): Int = requireNotNull(
        engine.scalar(
            "SELECT COUNT(*) FROM `adaptive_decision_record` WHERE programRevision = ? AND " +
                "cycleNumber = ? AND programDay = ? AND familyId = ?",
            programRevision, cycleNumber, programDay, familyId
        )
    ).toInt()

    override suspend fun clearDecisionHistory() = engine.exec("DELETE FROM `adaptive_decision_record`")
}

// ---------------------------------------------------------------------------------------------
// Planted failures
// ---------------------------------------------------------------------------------------------

/**
 * The failure points a suite can plant in the database's DAO layer.
 *
 * "Every atomic operation of the graph runs in the database's own transaction" is a claim about
 * what SQLite does when a write fails part-way, so the failure has to be planted where the writes
 * happen. It is a flag rather than a mock, so a container built once can be driven through a failing
 * path and then through the same path again after the fault is cleared — which is what separates
 * "the transaction is the container's" from "the test's bookkeeping".
 */
internal class DatabaseFaults {

    /** When set, inserting a revision's plan elements fails — the mid-graph failure. */
    var failExerciseInsert: Boolean = false
}

/** The target exercise DAO with a plantable insert failure. */
internal class ProgramExerciseDaoFence(
    private val delegate: ProgramExerciseDao,
    private val faults: DatabaseFaults
) : ProgramExerciseDao {

    override suspend fun insertExercises(exercises: List<ProgramExerciseEntity>) {
        if (faults.failExerciseInsert) throw IllegalStateException("planted fault: exercise insert")
        delegate.insertExercises(exercises)
    }

    override suspend fun exercisesOfRevision(revisionId: String): List<ProgramExerciseEntity> =
        delegate.exercisesOfRevision(revisionId)
}

// ---------------------------------------------------------------------------------------------
// The rig
// ---------------------------------------------------------------------------------------------

/**
 * A composition root built the way the application builds one — the production constructor, a
 * migrated database, PR 3's DAO implementations — with the two things a device supplies and a test
 * chooses: the clock and the id generator.
 *
 * The engine runs the **deployed** migration chain (version 7 → 8 → 9), so the graph sits on the
 * schema a device opening this build has, and the Stage-1 tables are present beside the target ones.
 */
internal class CompositionRootRig(
    key: String = "container",
    /** The clock the container is given: a value a test can move between two writes. */
    val clock: MovableClock = MovableClock(ProgramGraphFixture.CREATED)
) {

    val engine: SqliteTestDatabase = SqliteTestDatabase.inMemory().also { database ->
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
        database.migrate(AppDatabase.MIGRATION_9_10)
    }

    val database = SqliteAppDatabase(engine)

    val ids = SequentialIds()

    val container = AppContainer(
        database = database,
        clock = clock,
        idGenerator = ids,
        inTransaction = database.transaction
    )

    /** The Program, its first revision and its slots — the graph a creation path will write. */
    val graph: ProgramGraph = ProgramGraphFixture.graph(key)

    /** The migrated database's own view of a table, for the assertions about what landed where. */
    fun rowCount(table: String): Int = engine.count(table)

    fun rows(table: String): List<Map<String, String?>> = engine.rows("SELECT * FROM `$table`")

    fun close() = engine.close()
}

// ---------------------------------------------------------------------------------------------
// The injected collaborators
// ---------------------------------------------------------------------------------------------

/**
 * A clock a test moves: the instant it returns is a value the test sets, so "the repository stamped
 * this row with the clock the container was given" is measured against an instant the test chose
 * rather than against a tolerance around the wall clock.
 */
internal class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/**
 * Deterministic identity: a counter with a tag, so every id a graph is built with is both readable
 * in a failure message and reproducible.
 */
internal class SequentialIds(private val tag: String = "container-id") : IdGenerator {

    var count: Int = 0
        private set

    override fun newId(): String {
        count += 1
        return "$tag-${count.toString().padStart(3, '0')}"
    }
}

// ---------------------------------------------------------------------------------------------
// Row → entity
// ---------------------------------------------------------------------------------------------

private fun Map<String, String?>.text(column: String): String =
    requireNotNull(this[column]) { "the row has no '$column': $this" }

private fun Map<String, String?>.number(column: String): Int = text(column).toInt()

private fun Map<String, String?>.millis(column: String): Long = text(column).toLong()

private fun Map<String, String?>.legacyFamilyState() = FamilyProgressionState(
    familyId = text("familyId"),
    progressionLevel = number("progressionLevel"),
    currentExerciseId = this["currentExerciseId"],
    adaptationState = AdaptiveState.valueOf(text("adaptationState")),
    precedingProgressQualifyingWindows = number("precedingProgressQualifyingWindows"),
    precedingRegressQualifyingWindows = number("precedingRegressQualifyingWindows"),
    precedingHighRiskWindows = number("precedingHighRiskWindows"),
    recoveryQualifyingSessions = number("recoveryQualifyingSessions"),
    eligibleSessionsSinceLastProgressionChange =
        this["eligibleSessionsSinceLastProgressionChange"]?.toInt(),
    programRevision = number("programRevision"),
    updatedAt = millis("updatedAt"),
    policyVersion = number("policyVersion")
)

private fun Map<String, String?>.legacyDecisionRecord() = AdaptiveDecisionRecord(
    id = number("id").toLong(),
    familyId = text("familyId"),
    programRevision = number("programRevision"),
    cycleNumber = number("cycleNumber"),
    programDay = number("programDay"),
    timestamp = millis("timestamp"),
    previousState = AdaptiveState.valueOf(text("previousState")),
    newState = AdaptiveState.valueOf(text("newState")),
    actions = AdaptiveTypeConverters().actionsFromNames(text("actions")),
    reasonCode = AdaptiveReasonCode.valueOf(text("reasonCode")),
    policyVersion = number("policyVersion")
)
