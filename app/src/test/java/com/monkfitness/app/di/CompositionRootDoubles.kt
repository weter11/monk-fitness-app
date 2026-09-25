package com.monkfitness.app.di

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.MaintenanceDao
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.local.NutritionDao
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgramPauseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramTargetOccurrenceDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.local.PostureProgressDao
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
import com.monkfitness.app.data.repository.SqliteProgramTargetOccurrenceDao
import com.monkfitness.app.data.repository.SqliteProgramWorkoutSlotDao
import com.monkfitness.app.data.repository.SqliteSessionExerciseDao
import com.monkfitness.app.data.repository.SqliteSessionSnapshotDao
import com.monkfitness.app.data.repository.SqliteSessionSnapshotExerciseDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.local.LegacyV7Schema
import com.monkfitness.app.data.repository.SqliteWorkoutSessionDao
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
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

    // --- the target DAOs (§30 step 3), on the engine -------------------------------------------

    private val program by lazy { SqliteProgramDao(engine) }
    private val appState by lazy { SqliteAppStateDao(engine) }
    private val revision by lazy { SqliteProgramRevisionDao(engine) }
    private val day by lazy { SqliteProgramDayDao(engine) }
    private val exercise by lazy { ProgramExerciseDaoFence(SqliteProgramExerciseDao(engine), faults) }
    private val slot by lazy { SqliteProgramWorkoutSlotDao(engine) }
    private val targetOccurrence by lazy { SqliteProgramTargetOccurrenceDao(engine) }
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

    private val maintenance by lazy { SqliteMaintenanceDao(engine) }

    override fun programDao(): ProgramDao = program.also { asked("programDao") }

    override fun appStateDao(): AppStateDao = appState.also { asked("appStateDao") }

    override fun programRevisionDao(): ProgramRevisionDao = revision.also { asked("programRevisionDao") }

    override fun programDayDao(): ProgramDayDao = day.also { asked("programDayDao") }

    override fun programExerciseDao(): ProgramExerciseDao = exercise.also { asked("programExerciseDao") }

    override fun programWorkoutSlotDao(): ProgramWorkoutSlotDao = slot.also { asked("programWorkoutSlotDao") }

    override fun programTargetOccurrenceDao(): ProgramTargetOccurrenceDao =
        targetOccurrence.also { asked("programTargetOccurrenceDao") }

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

    /**
     * Settings → Full reset's statements, on the same engine. The composition root constructs the
     * reset's repository eagerly, so this accessor is asked on every container test.
     */
    override fun maintenanceDao(): MaintenanceDao = maintenance.also { asked("maintenanceDao") }

    /**
     * The retained global stores — nutrition and the posture / mobility track — which the Program
     * System's graph must never ask for.
     *
     * Asking here is a **failure** rather than a stub: §30 step 15's whole point is that no Program
     * path reaches an unrelated global store, so an accidental reach has to be loud. A test that wants
     * those tables uses the DAO's own suite over `SqliteTestDatabase`, not the container.
     */
    override fun nutritionDao(): NutritionDao =
        throw UnsupportedOperationException(
            "the Program System composition root has no business reading the nutrition tables"
        )

    override fun postureProgressDao(): PostureProgressDao =
        throw UnsupportedOperationException(
            "the Program System composition root has no business reading the posture track"
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
        database.migrate(AppDatabase.MIGRATION_10_11)
        database.migrate(AppDatabase.MIGRATION_11_12)
        database.migrate(AppDatabase.MIGRATION_12_13)
        database.migrate(AppDatabase.MIGRATION_13_14)
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

/**
 * [MaintenanceDao] — Settings → Full reset's statements — executed on SQLite through
 * [SqliteTestDatabase].
 *
 * The statements are [MaintenanceDao]'s own, spelled out here for the same reason every other stub in
 * this file spells its SQL out: nothing is Room-generated on the JVM, so a reset test measures the
 * schema's real behaviour (including the `app_state` foreign key that decides the order) rather than a
 * mock's bookkeeping.
 */
internal class SqliteMaintenanceDao(private val engine: SqliteTestDatabase) : MaintenanceDao {

    override suspend fun resetAppState(standardProgramId: String) = engine.exec(
        "UPDATE `app_state` SET `selectedProgramId` = ?, `nextProgramId` = NULL, " +
            "`nextProgramAutoStart` = 0",
        standardProgramId
    )

    override suspend fun clearConfirmedSets() = engine.exec("DELETE FROM `program_set_log`")

    override suspend fun clearSessionExercises() = engine.exec("DELETE FROM `session_exercise`")

    override suspend fun clearSnapshotElements() = engine.exec("DELETE FROM `session_snapshot_exercise`")

    override suspend fun clearSnapshots() = engine.exec("DELETE FROM `session_snapshot`")

    override suspend fun clearSessions() = engine.exec("DELETE FROM `workout_session`")

    override suspend fun clearAdjustments() = engine.exec("DELETE FROM `adaptive_adjustment`")

    override suspend fun clearAdaptiveDecisions() =
        engine.exec("DELETE FROM `program_adaptive_decision_record`")

    override suspend fun clearFamilyStates() =
        engine.exec("DELETE FROM `program_family_progression_state`")

    override suspend fun clearPauses() = engine.exec("DELETE FROM `program_pause`")

    override suspend fun clearSlots() = engine.exec("DELETE FROM `program_workout_slot`")

    override suspend fun clearUserPrograms(standardProgramId: String) =
        engine.exec("DELETE FROM `program` WHERE `programId` != ?", standardProgramId)

    override suspend fun clearPostureTrack() = engine.exec("DELETE FROM `posture_session_progress`")

    override suspend fun clearBodyWeightLog() = engine.exec("DELETE FROM `body_weight_log`")
}
