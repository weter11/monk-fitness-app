package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgramPauseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.SessionAttemptRow
import com.monkfitness.app.data.local.SessionExerciseDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SessionSnapshotExerciseDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.AppStateEntity
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.model.ProgramPauseEntity
import com.monkfitness.app.data.model.ProgramRevisionEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.model.WorkoutSessionEntity
import java.lang.reflect.Modifier
import java.time.DayOfWeek

/**
 * The target DAOs, implemented against a **real SQLite engine**.
 *
 * Room's DAOs cannot run on this repository's unit-test JVM — there is no Robolectric, no
 * instrumentation source set and no `androidx.room.testing`, so the generated implementations have
 * nowhere to execute. The repository suites therefore need DAO implementations that are the engine's,
 * not the test's, and this file is exactly that: every method below executes the statement its DAO
 * carries ([ProgramDaoSql]) on the same SQLite engine Room opens on a device, with bound parameters,
 * `PRAGMA foreign_keys = ON` and genuine `BEGIN`/`COMMIT`/`ROLLBACK` transactions.
 *
 * What that buys is the difference between "the repository composes reads correctly" and the claims
 * this stage actually has to make:
 *
 *  * a creation transaction that fails partway leaves **nothing** behind, because the rollback is
 *    SQLite's;
 *  * deleting a Program really destroys its owned graph, because the cascade is the schema's foreign
 *    keys rather than a hand-written child delete;
 *  * deleting the **selected** Program is really refused (`ON DELETE NO ACTION`) and the refusal really
 *    propagates, because the constraint is the engine's;
 *  * `app_state.nextProgramId` is really nulled while its row survives, because that is `SET NULL`;
 *  * a session really keeps its snapshot when the live plan changes underneath it, because the read
 *    touches only snapshot tables and the plan is mutated by SQL between the two loads.
 *
 * ### Where these implementations could drift, and why they cannot
 *
 * Queries are the DAOs' own SQL text ([ProgramDaoSql]); inserts and updates have no SQL literal to
 * copy (Room generates them from the entity), so they are written here and guarded instead:
 *
 *  * [insertRow] binds **every** field of the entity and refuses to run unless the entity's fields are
 *    exactly the table's columns in declaration order — a column the entity adds without this harness
 *    noticing is a test failure, not a silent omission;
 *  * `ProgramDataAccessArchitectureTest` reads the DAO sources and the statements here and asserts the
 *    correspondence in both directions, plus the shape of every hand-written write.
 */

/** The physical table each target entity is stored in. Asserted against the schema contract. */
internal val TARGET_ENTITY_TABLES: Map<Class<*>, String> = mapOf(
    ProgramEntity::class.java to "program",
    AppStateEntity::class.java to "app_state",
    ProgramRevisionEntity::class.java to "program_revision",
    ProgramDayEntity::class.java to "program_day",
    ProgramExerciseEntity::class.java to "program_exercise",
    ProgramWorkoutSlotEntity::class.java to "program_workout_slot",
    WorkoutSessionEntity::class.java to "workout_session",
    SessionSnapshotEntity::class.java to "session_snapshot",
    SessionSnapshotExerciseEntity::class.java to "session_snapshot_exercise",
    SessionExerciseEntity::class.java to "session_exercise",
    SetLogEntity::class.java to "program_set_log",
    ProgramPauseEntity::class.java to "program_pause",
    FamilyProgressionStateEntity::class.java to "program_family_progression_state",
    AdaptiveDecisionRecordEntity::class.java to "program_adaptive_decision_record",
    AdaptiveAdjustmentEntity::class.java to "adaptive_adjustment"
)

/**
 * Stores one entity, binding every declared field.
 *
 * @param replace whether the statement is `INSERT OR REPLACE` — the form the DAOs that upsert a single
 *   current row use (`AppStateDao.upsertState`, `ProgramFamilyProgressionStateDao.upsertState`). Every
 *   other writer is a plain insert, exactly as its `@Insert` declares.
 */
internal fun SqliteTestDatabase.insertRow(entity: Any, replace: Boolean = false) {
    val table = requireNotNull(TARGET_ENTITY_TABLES[entity.javaClass]) {
        "no target table is registered for ${entity.javaClass.simpleName}"
    }
    val fields = entity.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
    fields.forEach { it.isAccessible = true }
    val columns = fields.map { it.name }
    require(columns == columnNames(table)) {
        "`$table` stores ${columnNames(table)} but ${entity.javaClass.simpleName} declares $columns: " +
            "the harness would drop or misplace a column"
    }
    val verb = if (replace) "INSERT OR REPLACE" else "INSERT"
    val names = columns.joinToString(", ") { "`$it`" }
    val placeholders = columns.joinToString(", ") { "?" }
    exec("$verb INTO `$table` ($names) VALUES ($placeholders)", *fields.map {
        storedValue(it.get(entity))
    }.toTypedArray())
}

/** The stored form of one entity field: epoch millis and counters as they are, collections joined. */
private fun storedValue(value: Any?): Any? = when (value) {
    null -> null
    is Boolean -> if (value) 1 else 0
    is List<*> -> value.joinToString(",") { it.toString() }
    is Set<*> -> value.filterIsInstance<DayOfWeek>().sortedBy { it.value }.joinToString(",") { it.name }
    else -> value
}

/** Expands one `IN (:parameter)` list into a placeholder per value, as Room does at runtime. */
private fun inClause(sql: String, parameter: String, values: List<String>): String {
    val expanded = sql.replace("IN (:$parameter)", "IN (${values.joinToString(", ") { "?" }})")
    require(expanded != sql) { "the statement declares no 'IN (:$parameter)': $sql" }
    return expanded
}

private fun Map<String, String?>.text(column: String): String = requireNotNull(this[column]) {
    "the row has no '$column': $this"
}

private fun Map<String, String?>.number(column: String): Int = text(column).toInt()

private fun Map<String, String?>.millis(column: String): Long = text(column).toLong()

private fun Map<String, String?>.flag(column: String): Boolean = text(column) != "0"

private fun Map<String, String?>.targets(column: String): List<Int> =
    text(column).split(",").map { it.trim().toInt() }

private fun Map<String, String?>.weekdays(column: String): Set<DayOfWeek>? {
    val stored = this[column] ?: return null
    if (stored.isBlank()) return emptySet()
    return stored.split(",").map { name ->
        DayOfWeek.entries.first { it.name == name.trim() }
    }.toSet()
}

private fun Map<String, String?>.identifiers(column: String): List<String> =
    this[column]?.let { stored -> if (stored.isBlank()) emptyList() else stored.split(",").map { it.trim() } }
        ?: emptyList()

private fun Map<String, String?>.programEntity() = ProgramEntity(
    programId = text("programId"),
    name = text("name"),
    description = text("description"),
    source = text("source"),
    lifecycleStatus = text("lifecycleStatus"),
    currentRevisionId = text("currentRevisionId"),
    createdAt = millis("createdAt"),
    updatedAt = millis("updatedAt"),
    plannedStartDate = this["plannedStartDate"],
    actualStartDate = this["actualStartDate"]?.toLong(),
    archivedAt = this["archivedAt"]?.toLong()
)

private fun Map<String, String?>.revisionEntity() = ProgramRevisionEntity(
    revisionId = text("revisionId"),
    programId = text("programId"),
    revisionNumber = number("revisionNumber"),
    mode = text("mode"),
    durationType = text("durationType"),
    durationDays = this["durationDays"]?.toInt(),
    scheduleType = text("scheduleType"),
    scheduleWeekdays = weekdays("scheduleWeekdays"),
    createdAt = millis("createdAt"),
    scheduleSessionsPerWeek = this["scheduleSessionsPerWeek"]?.toInt(),
    // The Goals & Focus columns (§6, §8), read as the raw stored bytes: the discriminator and the
    // tokens beside it. The *meaning* of the tokens belongs to the mapper, so the harness carries
    // them through unchanged rather than interpreting them.
    focusGoal = this["focusGoal"],
    focusTargets = this["focusTargets"]
)

private fun Map<String, String?>.dayEntity() = ProgramDayEntity(
    programDayId = text("programDayId"),
    revisionId = text("revisionId"),
    position = number("position"),
    type = text("type"),
    name = this["name"]
)

private fun Map<String, String?>.exerciseEntity() = ProgramExerciseEntity(
    programExerciseId = text("programExerciseId"),
    programDayId = text("programDayId"),
    position = number("position"),
    exerciseId = text("exerciseId"),
    prescriptionDimension = text("prescriptionDimension"),
    perSetTargets = targets("perSetTargets"),
    origin = text("origin"),
    isPinned = flag("isPinned")
)

private fun Map<String, String?>.slotEntity() = ProgramWorkoutSlotEntity(
    slotId = text("slotId"),
    programId = text("programId"),
    revisionId = text("revisionId"),
    programDayId = text("programDayId"),
    plannedFor = text("plannedFor"),
    status = text("status"),
    completedAt = this["completedAt"]?.toLong(),
    targetOccurrenceKey = this["targetOccurrenceKey"]
)

private fun Map<String, String?>.sessionEntity() = WorkoutSessionEntity(
    sessionId = text("sessionId"),
    slotId = text("slotId"),
    programId = text("programId"),
    revisionId = text("revisionId"),
    status = text("status"),
    startedAt = millis("startedAt"),
    finishedAt = this["finishedAt"]?.toLong()
)

private fun Map<String, String?>.snapshotEntity() = SessionSnapshotEntity(
    sessionId = text("sessionId"),
    capturedAt = millis("capturedAt"),
    plannedFor = text("plannedFor"),
    computedAt = millis("computedAt"),
    appliedAdjustmentIds = identifiers("appliedAdjustmentIds")
)

private fun Map<String, String?>.snapshotExerciseEntity() = SessionSnapshotExerciseEntity(
    sessionId = text("sessionId"),
    programExerciseId = text("programExerciseId"),
    position = number("position"),
    exerciseId = text("exerciseId"),
    prescriptionDimension = text("prescriptionDimension"),
    perSetTargets = targets("perSetTargets")
)

private fun Map<String, String?>.sessionExerciseEntity() = SessionExerciseEntity(
    sessionExerciseId = text("sessionExerciseId"),
    sessionId = text("sessionId"),
    position = number("position"),
    programExerciseId = text("programExerciseId"),
    exerciseId = text("exerciseId"),
    prescriptionDimension = text("prescriptionDimension"),
    perSetTargets = targets("perSetTargets"),
    skipped = flag("skipped")
)

private fun Map<String, String?>.setLogEntity() = SetLogEntity(
    setLogId = text("setLogId"),
    sessionExerciseId = text("sessionExerciseId"),
    setIndex = number("setIndex"),
    completedReps = number("completedReps"),
    durationSeconds = number("durationSeconds"),
    performedAt = millis("performedAt")
)

private fun Map<String, String?>.pauseEntity() = ProgramPauseEntity(
    pauseId = text("pauseId"),
    programId = text("programId"),
    startedAt = millis("startedAt"),
    endedAt = this["endedAt"]?.toLong()
)

private fun Map<String, String?>.appStateEntity() = AppStateEntity(
    id = number("id"),
    selectedProgramId = this["selectedProgramId"],
    nextProgramId = this["nextProgramId"],
    nextProgramAutoStart = flag("nextProgramAutoStart")
)

private fun Map<String, String?>.familyStateEntity() = FamilyProgressionStateEntity(
    revisionId = text("revisionId"),
    familyId = text("familyId"),
    progressionLevel = number("progressionLevel"),
    adaptationState = text("adaptationState"),
    currentExerciseId = this["currentExerciseId"],
    updatedAt = millis("updatedAt"),
    // §30 step 12's window bookkeeping: read here exactly as the entity declares it, so a read that
    // dropped a counter would fail a round trip rather than quietly zeroing one.
    precedingProgressQualifyingWindows = this["precedingProgressQualifyingWindows"]?.toInt(),
    precedingRegressQualifyingWindows = this["precedingRegressQualifyingWindows"]?.toInt(),
    precedingRecoveryQualifyingWindows = this["precedingRecoveryQualifyingWindows"]?.toInt(),
    qualifyingWindowsSinceLastChange = this["qualifyingWindowsSinceLastChange"]?.toInt(),
    recoveryQualifyingWindows = this["recoveryQualifyingWindows"]?.toInt()
)

private fun Map<String, String?>.decisionEntity() = AdaptiveDecisionRecordEntity(
    decisionId = text("decisionId"),
    programId = text("programId"),
    revisionId = text("revisionId"),
    slotId = text("slotId"),
    targetScope = text("targetScope"),
    targetId = this["targetId"],
    action = text("action"),
    outcome = text("outcome"),
    evidence = text("evidence"),
    confidence = text("confidence"),
    recovery = text("recovery"),
    decidedAt = millis("decidedAt"),
    // §13's stored reason token: read as it was written, and `null` for a row written before the
    // column existed — the absence is not guessed at here either.
    reason = this["reason"]
)

private fun Map<String, String?>.adjustmentEntity() = AdaptiveAdjustmentEntity(
    adjustmentId = text("adjustmentId"),
    decisionId = text("decisionId"),
    programId = text("programId"),
    revisionId = text("revisionId"),
    slotId = text("slotId"),
    programExerciseId = text("programExerciseId"),
    beforeExerciseId = text("beforeExerciseId"),
    beforePrescriptionDimension = text("beforePrescriptionDimension"),
    beforePerSetTargets = targets("beforePerSetTargets"),
    afterExerciseId = text("afterExerciseId"),
    afterPrescriptionDimension = text("afterPrescriptionDimension"),
    afterPerSetTargets = targets("afterPerSetTargets"),
    createdAt = millis("createdAt"),
    supersedesAdjustmentId = this["supersedesAdjustmentId"]
)

private fun Map<String, String?>.attemptRow() = SessionAttemptRow(
    slotId = text("slotId"),
    sessionId = text("sessionId"),
    startedAt = millis("startedAt")
)

private fun SqliteTestDatabase.first(sql: String, vararg args: Any?): Int =
    strings(sql, *args).firstOrNull()?.toInt()
        ?: throw IllegalStateException("the query returned no row: $sql")

// ---------------------------------------------------------------------------------------------
// The DAOs
// ---------------------------------------------------------------------------------------------

internal class SqliteProgramDao(private val database: SqliteTestDatabase) : ProgramDao {

    override suspend fun insertProgram(program: ProgramEntity) = database.insertRow(program)

    override suspend fun updateProgram(program: ProgramEntity) =
        database.exec(
            ProgramDaoSql.PROGRAM_DAO_UPDATE_PROGRAM,
            program.name, program.description, program.source, program.lifecycleStatus,
            program.currentRevisionId, program.createdAt, program.updatedAt, program.plannedStartDate,
            program.actualStartDate, program.archivedAt, program.programId
        )

    override suspend fun programById(programId: String): ProgramEntity? =
        database.rows(ProgramDaoSql.PROGRAM_DAO_PROGRAM_BY_ID, programId).firstOrNull()?.programEntity()

    override suspend fun programs(): List<ProgramEntity> =
        database.rows(ProgramDaoSql.PROGRAM_DAO_PROGRAMS).map { it.programEntity() }

    override suspend fun countPrograms(): Int = database.count("program")

    override suspend fun setCurrentRevision(programId: String, revisionId: String, updatedAt: Long) =
        database.exec(ProgramDaoSql.PROGRAM_DAO_SET_CURRENT_REVISION, revisionId, updatedAt, programId)

    override suspend fun deleteProgram(programId: String) =
        database.exec(ProgramDaoSql.PROGRAM_DAO_DELETE_PROGRAM, programId)
}

internal class SqliteAppStateDao(private val database: SqliteTestDatabase) : AppStateDao {

    override suspend fun state(id: Int): AppStateEntity? =
        database.rows(ProgramDaoSql.APP_STATE_DAO_STATE, id).firstOrNull()?.appStateEntity()

    override suspend fun upsertState(state: AppStateEntity) = database.insertRow(state, replace = true)
}

internal class SqliteProgramRevisionDao(private val database: SqliteTestDatabase) : ProgramRevisionDao {

    override suspend fun insertRevision(revision: ProgramRevisionEntity) = database.insertRow(revision)

    override suspend fun revisionById(revisionId: String): ProgramRevisionEntity? =
        database.rows(ProgramDaoSql.PROGRAM_REVISION_DAO_REVISION_BY_ID, revisionId)
            .firstOrNull()?.revisionEntity()

    override suspend fun revisionsOfProgram(programId: String): List<ProgramRevisionEntity> =
        database.rows(ProgramDaoSql.PROGRAM_REVISION_DAO_REVISIONS_OF_PROGRAM, programId)
            .map { it.revisionEntity() }

    override suspend fun countRevisionsOf(programId: String): Int =
        database.first(ProgramDaoSql.PROGRAM_REVISION_DAO_COUNT_REVISIONS_OF, programId)
}

internal class SqliteProgramDayDao(private val database: SqliteTestDatabase) : ProgramDayDao {

    override suspend fun insertDays(days: List<ProgramDayEntity>) =
        days.forEach { database.insertRow(it) }

    override suspend fun daysOfRevision(revisionId: String): List<ProgramDayEntity> =
        database.rows(ProgramDaoSql.PROGRAM_DAY_DAO_DAYS_OF_REVISION, revisionId).map { it.dayEntity() }
}

internal class SqliteProgramExerciseDao(private val database: SqliteTestDatabase) : ProgramExerciseDao {

    override suspend fun insertExercises(exercises: List<ProgramExerciseEntity>) =
        exercises.forEach { database.insertRow(it) }

    override suspend fun exercisesOfRevision(revisionId: String): List<ProgramExerciseEntity> =
        database.rows(ProgramDaoSql.PROGRAM_EXERCISE_DAO_EXERCISES_OF_REVISION, revisionId)
            .map { it.exerciseEntity() }
}

internal class SqliteProgramWorkoutSlotDao(private val database: SqliteTestDatabase) :
    ProgramWorkoutSlotDao {

    override suspend fun insertSlots(slots: List<ProgramWorkoutSlotEntity>) =
        slots.forEach { database.insertRow(it) }

    override suspend fun slotById(slotId: String): ProgramWorkoutSlotEntity? =
        database.rows(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_ID, slotId)
            .firstOrNull()?.slotEntity()

    override suspend fun slotByTargetOccurrenceKey(
        programId: String,
        targetOccurrenceKey: String
    ): ProgramWorkoutSlotEntity? = database.rows(
        ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_TARGET_OCCURRENCE_KEY,
        programId,
        targetOccurrenceKey
    ).firstOrNull()?.slotEntity()

    override suspend fun slotsOfProgram(programId: String): List<ProgramWorkoutSlotEntity> =
        database.rows(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_PROGRAM, programId)
            .map { it.slotEntity() }

    override suspend fun slotsOfRevision(revisionId: String): List<ProgramWorkoutSlotEntity> =
        database.rows(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_REVISION, revisionId)
            .map { it.slotEntity() }

    override suspend fun slotsFrom(
        programId: String,
        fromDate: String,
        status: String
    ): List<ProgramWorkoutSlotEntity> =
        database.rows(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOTS_FROM, programId, fromDate, status)
            .map { it.slotEntity() }

    override suspend fun updateOutcome(slotId: String, status: String, completedAt: Long?) =
        database.exec(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_UPDATE_OUTCOME, status, completedAt, slotId)

    override suspend fun countByStatus(programId: String, status: String): Int =
        database.first(ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_COUNT_BY_STATUS, programId, status)
}

internal class SqliteWorkoutSessionDao(private val database: SqliteTestDatabase) : WorkoutSessionDao {

    /**
     * Executes the DAO's conditional insert verbatim, binding the values it selects and the pair the
     * predicate guards on.
     *
     * The harness adds nothing to the rule: the *statement* carries it, and this method is only how the
     * engine reaches it — which is the point of driving the DAOs' own SQL, since the occupancy rule is
     * then exercised as SQLite evaluates it rather than as a test believes it does.
     */
    override suspend fun insertSessionIfSlotIsNotOccupied(
        sessionId: String,
        slotId: String,
        programId: String,
        revisionId: String,
        status: String,
        startedAt: Long,
        finishedAt: Long?,
        occupiedSlotId: String,
        occupiedStatus: String
    ) = database.exec(
        ProgramDaoSql.WORKOUT_SESSION_DAO_INSERT_IF_SLOT_NOT_OCCUPIED,
        sessionId, slotId, programId, revisionId, status, startedAt, finishedAt,
        occupiedSlotId, occupiedStatus
    )

    override suspend fun changedRowCount(): Int =
        database.scalar(ProgramDaoSql.WORKOUT_SESSION_DAO_CHANGED_ROW_COUNT)!!.toInt()

    override suspend fun sessionById(sessionId: String): WorkoutSessionEntity? =
        database.rows(ProgramDaoSql.WORKOUT_SESSION_DAO_SESSION_BY_ID, sessionId)
            .firstOrNull()?.sessionEntity()

    override suspend fun sessionsOfSlot(slotId: String): List<WorkoutSessionEntity> =
        database.rows(ProgramDaoSql.WORKOUT_SESSION_DAO_SESSIONS_OF_SLOT, slotId)
            .map { it.sessionEntity() }

    override suspend fun sessionsOfProgram(programId: String): List<WorkoutSessionEntity> =
        database.rows(ProgramDaoSql.WORKOUT_SESSION_DAO_SESSIONS_OF_PROGRAM, programId)
            .map { it.sessionEntity() }

    override suspend fun attemptsOfProgram(programId: String): List<SessionAttemptRow> =
        database.rows(ProgramDaoSql.WORKOUT_SESSION_DAO_ATTEMPTS_OF_PROGRAM, programId)
            .map { it.attemptRow() }

    override suspend fun attemptsOfRevision(revisionId: String): List<SessionAttemptRow> =
        database.rows(ProgramDaoSql.WORKOUT_SESSION_DAO_ATTEMPTS_OF_REVISION, revisionId)
            .map { it.attemptRow() }

    override suspend fun updateOutcome(sessionId: String, status: String, finishedAt: Long?) =
        database.exec(ProgramDaoSql.WORKOUT_SESSION_DAO_UPDATE_OUTCOME, status, finishedAt, sessionId)

    override suspend fun countByStatus(programId: String, status: String): Int =
        database.first(ProgramDaoSql.WORKOUT_SESSION_DAO_COUNT_BY_STATUS, programId, status)
}

internal class SqliteSessionSnapshotDao(private val database: SqliteTestDatabase) : SessionSnapshotDao {

    override suspend fun insertSnapshot(snapshot: SessionSnapshotEntity) = database.insertRow(snapshot)

    override suspend fun snapshotsOf(sessionIds: List<String>): List<SessionSnapshotEntity> {
        if (sessionIds.isEmpty()) return emptyList()
        val sql = inClause(ProgramDaoSql.SESSION_SNAPSHOT_DAO_SNAPSHOTS_OF, "sessionIds", sessionIds)
        return database.rows(sql, *sessionIds.toTypedArray()).map { it.snapshotEntity() }
    }
}

internal class SqliteSessionSnapshotExerciseDao(private val database: SqliteTestDatabase) :
    SessionSnapshotExerciseDao {

    override suspend fun insertSnapshotExercises(exercises: List<SessionSnapshotExerciseEntity>) =
        exercises.forEach { database.insertRow(it) }

    override suspend fun snapshotExercisesOfSessions(
        sessionIds: List<String>
    ): List<SessionSnapshotExerciseEntity> {
        if (sessionIds.isEmpty()) return emptyList()
        val sql = inClause(
            ProgramDaoSql.SESSION_SNAPSHOT_EXERCISE_DAO_SNAPSHOT_EXERCISES_OF_SESSIONS,
            "sessionIds",
            sessionIds
        )
        return database.rows(sql, *sessionIds.toTypedArray()).map { it.snapshotExerciseEntity() }
    }
}

internal class SqliteSessionExerciseDao(private val database: SqliteTestDatabase) : SessionExerciseDao {

    override suspend fun insertSessionExercises(exercises: List<SessionExerciseEntity>) =
        exercises.forEach { database.insertRow(it) }

    override suspend fun sessionExercisesOfSessions(sessionIds: List<String>): List<SessionExerciseEntity> {
        if (sessionIds.isEmpty()) return emptyList()
        val sql = inClause(
            ProgramDaoSql.SESSION_EXERCISE_DAO_SESSION_EXERCISES_OF_SESSIONS,
            "sessionIds",
            sessionIds
        )
        return database.rows(sql, *sessionIds.toTypedArray()).map { it.sessionExerciseEntity() }
    }

    override suspend fun sessionExerciseById(sessionExerciseId: String): SessionExerciseEntity? =
        database.rows(ProgramDaoSql.SESSION_EXERCISE_DAO_SESSION_EXERCISE_BY_ID, sessionExerciseId)
            .firstOrNull()?.sessionExerciseEntity()
}

internal class SqliteProgramSetLogDao(private val database: SqliteTestDatabase) : ProgramSetLogDao {

    override suspend fun insertSet(setLog: SetLogEntity) = database.insertRow(setLog)

    override suspend fun setsOfSessionExercise(sessionExerciseId: String): List<SetLogEntity> =
        database.rows(ProgramDaoSql.PROGRAM_SET_LOG_DAO_SETS_OF_SESSION_EXERCISE, sessionExerciseId)
            .map { it.setLogEntity() }

    override suspend fun setsOfSessions(sessionIds: List<String>): List<SetLogEntity> {
        if (sessionIds.isEmpty()) return emptyList()
        val sql = inClause(ProgramDaoSql.PROGRAM_SET_LOG_DAO_SETS_OF_SESSIONS, "sessionIds", sessionIds)
        return database.rows(sql, *sessionIds.toTypedArray()).map { it.setLogEntity() }
    }

    override suspend fun countSetsOfProgram(programId: String): Int =
        database.first(ProgramDaoSql.PROGRAM_SET_LOG_DAO_COUNT_SETS_OF_PROGRAM, programId)
}

internal class SqliteProgramPauseDao(private val database: SqliteTestDatabase) : ProgramPauseDao {

    override suspend fun insertPause(pause: ProgramPauseEntity) = database.insertRow(pause)

    override suspend fun pauseById(pauseId: String): ProgramPauseEntity? =
        database.rows(ProgramDaoSql.PROGRAM_PAUSE_DAO_PAUSE_BY_ID, pauseId).firstOrNull()?.pauseEntity()

    override suspend fun pausesOfProgram(programId: String): List<ProgramPauseEntity> =
        database.rows(ProgramDaoSql.PROGRAM_PAUSE_DAO_PAUSES_OF_PROGRAM, programId).map { it.pauseEntity() }

    override suspend fun closePause(pauseId: String, endedAt: Long) =
        database.exec(ProgramDaoSql.PROGRAM_PAUSE_DAO_CLOSE_PAUSE, endedAt, pauseId)
}

internal class SqliteProgramFamilyProgressionStateDao(private val database: SqliteTestDatabase) :
    ProgramFamilyProgressionStateDao {

    override suspend fun upsertState(state: FamilyProgressionStateEntity) =
        database.insertRow(state, replace = true)

    override suspend fun statesOfRevision(revisionId: String): List<FamilyProgressionStateEntity> =
        database.rows(
            ProgramDaoSql.PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATES_OF_REVISION,
            revisionId
        ).map { it.familyStateEntity() }

    override suspend fun stateOf(revisionId: String, familyId: String): FamilyProgressionStateEntity? =
        database.rows(ProgramDaoSql.PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATE_OF, revisionId, familyId)
            .firstOrNull()?.familyStateEntity()
}

internal class SqliteProgramAdaptiveDecisionDao(private val database: SqliteTestDatabase) :
    ProgramAdaptiveDecisionDao {

    override suspend fun insertDecision(decision: AdaptiveDecisionRecordEntity) =
        database.insertRow(decision)

    override suspend fun decisionById(decisionId: String): AdaptiveDecisionRecordEntity? =
        database.rows(ProgramDaoSql.PROGRAM_ADAPTIVE_DECISION_DAO_DECISION_BY_ID, decisionId)
            .firstOrNull()?.decisionEntity()

    override suspend fun decisionsOfProgram(programId: String): List<AdaptiveDecisionRecordEntity> =
        database.rows(ProgramDaoSql.PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_PROGRAM, programId)
            .map { it.decisionEntity() }

    override suspend fun decisionsOfRevision(revisionId: String): List<AdaptiveDecisionRecordEntity> =
        database.rows(ProgramDaoSql.PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_REVISION, revisionId)
            .map { it.decisionEntity() }

    override suspend fun decisionsOfSlot(slotId: String): List<AdaptiveDecisionRecordEntity> =
        database.rows(ProgramDaoSql.PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_SLOT, slotId)
            .map { it.decisionEntity() }
}

internal class SqliteAdaptiveAdjustmentDao(private val database: SqliteTestDatabase) :
    AdaptiveAdjustmentDao {

    override suspend fun insertAdjustment(adjustment: AdaptiveAdjustmentEntity) =
        database.insertRow(adjustment)

    override suspend fun adjustmentById(adjustmentId: String): AdaptiveAdjustmentEntity? =
        database.rows(ProgramDaoSql.ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENT_BY_ID, adjustmentId)
            .firstOrNull()?.adjustmentEntity()

    override suspend fun adjustmentsOfDecision(decisionId: String): List<AdaptiveAdjustmentEntity> =
        database.rows(ProgramDaoSql.ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_DECISION, decisionId)
            .map { it.adjustmentEntity() }

    override suspend fun adjustmentsOfSlot(slotId: String): List<AdaptiveAdjustmentEntity> =
        database.rows(ProgramDaoSql.ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_SLOT, slotId)
            .map { it.adjustmentEntity() }

    override suspend fun adjustmentsOfProgram(programId: String): List<AdaptiveAdjustmentEntity> =
        database.rows(ProgramDaoSql.ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_PROGRAM, programId)
            .map { it.adjustmentEntity() }

    override suspend fun adjustmentsOfRevision(revisionId: String): List<AdaptiveAdjustmentEntity> =
        database.rows(ProgramDaoSql.ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_REVISION, revisionId)
            .map { it.adjustmentEntity() }
}
