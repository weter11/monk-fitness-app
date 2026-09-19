package com.monkfitness.app.data.local

/**
 * The Program System's target schema as a *data structure*: what every target table is called, what
 * it stores, who owns it and which powers the database itself has to enforce.
 *
 * This file is the single statement of the schema contract. The three Program System test classes
 * read it from different directions — the migration's own statements, Room's generated DDL, and a
 * real SQLite engine's `sqlite_master`/`pragma` metadata — so a table, key, index or delete action
 * that drifts from the architecture fails in more than one place at once.
 *
 * The DDL is **derived** from the tables below following Room's emitter conventions (columns in
 * declaration order, then the primary key, then the foreign keys; `index_<table>_<columns>` for
 * indices). Deriving it means the migration cannot quietly grow a table this contract does not
 * describe, and that the expected SQL Room generates for the entities has exactly one author.
 */
internal object ProgramSchemaFixture {

    /**
     * The entities of §23 in the order `AppDatabase` declares them, with the physical table each one
     * is stored in.
     *
     * Where the §23 entity's own snake_case name is already taken by a shipped table, the target
     * table carries the `program_` ownership prefix instead: the version-2..7 database already owns
     * `set_log` (the legacy logging table), `family_progression_state` and `adaptive_decision_record`
     * (the Stage-1 adaptive pair), and none of those may be reused, renamed or reshaped here.
     */
    val ENTITIES: List<Pair<String, String>> = listOf(
        "ProgramEntity" to "program",
        "AppStateEntity" to "app_state",
        "ProgramRevisionEntity" to "program_revision",
        "ProgramDayEntity" to "program_day",
        "ProgramExerciseEntity" to "program_exercise",
        "ProgramWorkoutSlotEntity" to "program_workout_slot",
        "WorkoutSessionEntity" to "workout_session",
        "SessionSnapshotEntity" to "session_snapshot",
        "SessionSnapshotExerciseEntity" to "session_snapshot_exercise",
        "SessionExerciseEntity" to "session_exercise",
        "SetLogEntity" to "program_set_log",
        "ProgramPauseEntity" to "program_pause",
        "FamilyProgressionStateEntity" to "program_family_progression_state",
        "AdaptiveDecisionRecordEntity" to "program_adaptive_decision_record",
        "AdaptiveAdjustmentEntity" to "adaptive_adjustment"
    )

    /** The physical tables the target schema adds. */
    val TABLES: List<String> = ENTITIES.map { it.second }

    /**
     * The two auxiliary tables the immutable Session Snapshot (§19) is stored in. They are not §23
     * entities of their own: they exist because a snapshot has to survive a revision it no longer
     * matches, which no foreign key into the live plan can express.
     */
    val AUXILIARY_TABLES: List<String> = listOf("session_snapshot", "session_snapshot_exercise")

    /** The physical tables of the version-7 database, which this schema adds to and never rewrites. */
    val LEGACY_TABLES: List<String> = LegacyV7Schema.TABLES

    /** Every table the version-8 database contains. */
    val ALL_TABLES: List<String> = LEGACY_TABLES + TABLES

    /** One declared column. */
    data class Column(val name: String, val type: String, val nullable: Boolean = false)

    /** One declared foreign key, with the action the architecture requires on delete. */
    data class ExpectedForeignKey(
        val childColumn: String,
        val parentTable: String,
        val parentColumn: String,
        val onDelete: String
    )

    /** One declared index. */
    data class ExpectedIndex(val name: String, val columns: List<String>, val unique: Boolean)

    private val TEXT = "TEXT"
    private val INTEGER = "INTEGER"

    /**
     * Every target table's columns, in declaration order.
     *
     * `perSetTargets`, `appliedAdjustmentIds`, `scheduleWeekdays` and the adjustment's before/after
     * prescriptions are `TEXT` on purpose: they are converted, deterministic single-column
     * representations of an ordered (or canonical-order) collection, not scalars.
     */
    val COLUMNS: Map<String, List<Column>> = mapOf(
        "program" to listOf(
            Column("programId", TEXT),
            Column("name", TEXT),
            Column("description", TEXT),
            Column("source", TEXT),
            Column("lifecycleStatus", TEXT),
            Column("currentRevisionId", TEXT),
            Column("createdAt", INTEGER),
            Column("updatedAt", INTEGER),
            Column("plannedStartDate", TEXT, nullable = true),
            Column("actualStartDate", INTEGER, nullable = true),
            Column("archivedAt", INTEGER, nullable = true)
        ),
        "app_state" to listOf(
            Column("id", INTEGER),
            Column("selectedProgramId", TEXT, nullable = true),
            Column("nextProgramId", TEXT, nullable = true),
            Column("nextProgramAutoStart", INTEGER)
        ),
        "program_revision" to listOf(
            Column("revisionId", TEXT),
            Column("programId", TEXT),
            Column("revisionNumber", INTEGER),
            Column("mode", TEXT),
            Column("durationType", TEXT),
            Column("durationDays", INTEGER, nullable = true),
            Column("scheduleType", TEXT),
            Column("scheduleWeekdays", TEXT, nullable = true),
            Column("createdAt", INTEGER)
        ),
        "program_day" to listOf(
            Column("programDayId", TEXT),
            Column("revisionId", TEXT),
            Column("position", INTEGER),
            Column("type", TEXT),
            Column("name", TEXT, nullable = true)
        ),
        "program_exercise" to listOf(
            Column("programExerciseId", TEXT),
            Column("programDayId", TEXT),
            Column("position", INTEGER),
            Column("exerciseId", TEXT),
            Column("prescriptionDimension", TEXT),
            Column("perSetTargets", TEXT),
            Column("origin", TEXT),
            Column("isPinned", INTEGER)
        ),
        "program_workout_slot" to listOf(
            Column("slotId", TEXT),
            Column("programId", TEXT),
            Column("revisionId", TEXT),
            Column("programDayId", TEXT),
            Column("plannedFor", TEXT),
            Column("status", TEXT),
            Column("completedAt", INTEGER, nullable = true)
        ),
        "workout_session" to listOf(
            Column("sessionId", TEXT),
            Column("slotId", TEXT),
            Column("programId", TEXT),
            Column("revisionId", TEXT),
            Column("status", TEXT),
            Column("startedAt", INTEGER),
            Column("finishedAt", INTEGER, nullable = true)
        ),
        "session_snapshot" to listOf(
            Column("sessionId", TEXT),
            Column("capturedAt", INTEGER),
            Column("plannedFor", TEXT),
            Column("computedAt", INTEGER),
            Column("appliedAdjustmentIds", TEXT)
        ),
        "session_snapshot_exercise" to listOf(
            Column("sessionId", TEXT),
            Column("programExerciseId", TEXT),
            Column("position", INTEGER),
            Column("exerciseId", TEXT),
            Column("prescriptionDimension", TEXT),
            Column("perSetTargets", TEXT)
        ),
        "session_exercise" to listOf(
            Column("sessionExerciseId", TEXT),
            Column("sessionId", TEXT),
            Column("position", INTEGER),
            Column("programExerciseId", TEXT),
            Column("exerciseId", TEXT),
            Column("prescriptionDimension", TEXT),
            Column("perSetTargets", TEXT),
            Column("skipped", INTEGER)
        ),
        "program_set_log" to listOf(
            Column("setLogId", TEXT),
            Column("sessionExerciseId", TEXT),
            Column("setIndex", INTEGER),
            Column("completedReps", INTEGER),
            Column("durationSeconds", INTEGER),
            Column("performedAt", INTEGER)
        ),
        "program_pause" to listOf(
            Column("pauseId", TEXT),
            Column("programId", TEXT),
            Column("startedAt", INTEGER),
            Column("endedAt", INTEGER, nullable = true)
        ),
        "program_family_progression_state" to listOf(
            Column("revisionId", TEXT),
            Column("familyId", TEXT),
            Column("progressionLevel", INTEGER),
            Column("adaptationState", TEXT),
            Column("currentExerciseId", TEXT, nullable = true),
            Column("updatedAt", INTEGER)
        ),
        "program_adaptive_decision_record" to listOf(
            Column("decisionId", TEXT),
            Column("programId", TEXT),
            Column("revisionId", TEXT),
            Column("slotId", TEXT),
            Column("targetScope", TEXT),
            Column("targetId", TEXT, nullable = true),
            Column("action", TEXT),
            Column("outcome", TEXT),
            Column("evidence", TEXT),
            Column("confidence", TEXT),
            Column("recovery", TEXT),
            Column("decidedAt", INTEGER)
        ),
        "adaptive_adjustment" to listOf(
            Column("adjustmentId", TEXT),
            Column("decisionId", TEXT),
            Column("programId", TEXT),
            Column("revisionId", TEXT),
            Column("slotId", TEXT),
            Column("programExerciseId", TEXT),
            Column("beforeExerciseId", TEXT),
            Column("beforePrescriptionDimension", TEXT),
            Column("beforePerSetTargets", TEXT),
            Column("afterExerciseId", TEXT),
            Column("afterPrescriptionDimension", TEXT),
            Column("afterPerSetTargets", TEXT),
            Column("createdAt", INTEGER),
            Column("supersedesAdjustmentId", TEXT, nullable = true)
        )
    )

    /**
     * The identity of every target entity. Identity is the entity's own id — never a date, a cycle
     * number, a day number or a legacy revision integer (§1, §23).
     */
    val PRIMARY_KEYS: Map<String, List<String>> = mapOf(
        "program" to listOf("programId"),
        "app_state" to listOf("id"),
        "program_revision" to listOf("revisionId"),
        "program_day" to listOf("programDayId"),
        "program_exercise" to listOf("programExerciseId"),
        "program_workout_slot" to listOf("slotId"),
        "workout_session" to listOf("sessionId"),
        "session_snapshot" to listOf("sessionId"),
        "session_snapshot_exercise" to listOf("sessionId", "programExerciseId"),
        "session_exercise" to listOf("sessionExerciseId"),
        "program_set_log" to listOf("setLogId"),
        "program_pause" to listOf("pauseId"),
        "program_family_progression_state" to listOf("revisionId", "familyId"),
        "program_adaptive_decision_record" to listOf("decisionId"),
        "adaptive_adjustment" to listOf("adjustmentId")
    )

    /**
     * The ownership graph, expressed as the delete action each child's foreign key carries.
     *
     * Every Program-owned child cascades with its owner, so a Program is destroyed atomically
     * (§27, §29). Two references deliberately do not: `app_state.nextProgramId` is the blueprint's
     * explicit `SET NULL` (§29), and `app_state.selectedProgramId` is `NO ACTION` so that deleting
     * the selected Program is *refused* until the selection is handled — the fallback rule (§3) is a
     * lifecycle decision, and a `SET NULL` there would silently erase which program the user chose.
     */
    val FOREIGN_KEYS: Map<String, List<ExpectedForeignKey>> = mapOf(
        "program" to emptyList(),
        "app_state" to listOf(
            ExpectedForeignKey("selectedProgramId", "program", "programId", "NO ACTION"),
            ExpectedForeignKey("nextProgramId", "program", "programId", "SET NULL")
        ),
        "program_revision" to listOf(
            ExpectedForeignKey("programId", "program", "programId", "CASCADE")
        ),
        "program_day" to listOf(
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE")
        ),
        "program_exercise" to listOf(
            ExpectedForeignKey("programDayId", "program_day", "programDayId", "CASCADE")
        ),
        "program_workout_slot" to listOf(
            ExpectedForeignKey("programId", "program", "programId", "CASCADE"),
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE"),
            ExpectedForeignKey("programDayId", "program_day", "programDayId", "CASCADE")
        ),
        "workout_session" to listOf(
            ExpectedForeignKey("slotId", "program_workout_slot", "slotId", "CASCADE"),
            ExpectedForeignKey("programId", "program", "programId", "CASCADE"),
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE")
        ),
        "session_snapshot" to listOf(
            ExpectedForeignKey("sessionId", "workout_session", "sessionId", "CASCADE")
        ),
        "session_snapshot_exercise" to listOf(
            ExpectedForeignKey("sessionId", "session_snapshot", "sessionId", "CASCADE")
        ),
        "session_exercise" to listOf(
            ExpectedForeignKey("sessionId", "workout_session", "sessionId", "CASCADE")
        ),
        "program_set_log" to listOf(
            ExpectedForeignKey("sessionExerciseId", "session_exercise", "sessionExerciseId", "CASCADE")
        ),
        "program_pause" to listOf(
            ExpectedForeignKey("programId", "program", "programId", "CASCADE")
        ),
        "program_family_progression_state" to listOf(
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE")
        ),
        "program_adaptive_decision_record" to listOf(
            ExpectedForeignKey("programId", "program", "programId", "CASCADE"),
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE"),
            ExpectedForeignKey("slotId", "program_workout_slot", "slotId", "CASCADE")
        ),
        "adaptive_adjustment" to listOf(
            ExpectedForeignKey("decisionId", "program_adaptive_decision_record", "decisionId", "CASCADE"),
            ExpectedForeignKey("programId", "program", "programId", "CASCADE"),
            ExpectedForeignKey("revisionId", "program_revision", "revisionId", "CASCADE"),
            ExpectedForeignKey("slotId", "program_workout_slot", "slotId", "CASCADE")
        )
    )

    /**
     * The indices the architecture asks for: a foreign key column is indexed, an ordering that must
     * be unique is, and the lookups the schema is designed for are covered.
     *
     * Note what is **not** here: nothing makes `workout_session.slotId` unique, because one slot may
     * carry several attempts (§19); nothing makes `program_exercise` unique on its exercise, because
     * a day may use the same exercise more than once (§9).
     */
    val INDEXES: Map<String, List<ExpectedIndex>> = mapOf(
        "program" to emptyList(),
        "app_state" to listOf(
            ExpectedIndex("index_app_state_selectedProgramId", listOf("selectedProgramId"), unique = false),
            ExpectedIndex("index_app_state_nextProgramId", listOf("nextProgramId"), unique = false)
        ),
        "program_revision" to listOf(
            ExpectedIndex(
                "index_program_revision_programId_revisionNumber",
                listOf("programId", "revisionNumber"),
                unique = true
            )
        ),
        "program_day" to listOf(
            ExpectedIndex(
                "index_program_day_revisionId_position",
                listOf("revisionId", "position"),
                unique = true
            )
        ),
        "program_exercise" to listOf(
            ExpectedIndex(
                "index_program_exercise_programDayId_position",
                listOf("programDayId", "position"),
                unique = true
            )
        ),
        "program_workout_slot" to listOf(
            ExpectedIndex("index_program_workout_slot_programId", listOf("programId"), unique = false),
            ExpectedIndex("index_program_workout_slot_programDayId", listOf("programDayId"), unique = false),
            ExpectedIndex(
                "index_program_workout_slot_revisionId_plannedFor",
                listOf("revisionId", "plannedFor"),
                unique = false
            )
        ),
        "workout_session" to listOf(
            ExpectedIndex("index_workout_session_slotId", listOf("slotId"), unique = false),
            ExpectedIndex(
                "index_workout_session_programId_startedAt",
                listOf("programId", "startedAt"),
                unique = false
            ),
            ExpectedIndex("index_workout_session_revisionId", listOf("revisionId"), unique = false)
        ),
        "session_snapshot" to emptyList(),
        "session_snapshot_exercise" to listOf(
            ExpectedIndex(
                "index_session_snapshot_exercise_sessionId_position",
                listOf("sessionId", "position"),
                unique = true
            )
        ),
        "session_exercise" to listOf(
            ExpectedIndex(
                "index_session_exercise_sessionId_position",
                listOf("sessionId", "position"),
                unique = true
            )
        ),
        "program_set_log" to listOf(
            ExpectedIndex(
                "index_program_set_log_sessionExerciseId_setIndex",
                listOf("sessionExerciseId", "setIndex"),
                unique = true
            )
        ),
        "program_pause" to listOf(
            ExpectedIndex("index_program_pause_programId", listOf("programId"), unique = false)
        ),
        "program_family_progression_state" to emptyList(),
        "program_adaptive_decision_record" to listOf(
            ExpectedIndex("index_program_adaptive_decision_record_programId", listOf("programId"), unique = false),
            ExpectedIndex("index_program_adaptive_decision_record_revisionId", listOf("revisionId"), unique = false),
            ExpectedIndex("index_program_adaptive_decision_record_slotId", listOf("slotId"), unique = false)
        ),
        "adaptive_adjustment" to listOf(
            ExpectedIndex("index_adaptive_adjustment_decisionId", listOf("decisionId"), unique = false),
            ExpectedIndex("index_adaptive_adjustment_programId", listOf("programId"), unique = false),
            ExpectedIndex("index_adaptive_adjustment_revisionId", listOf("revisionId"), unique = false),
            ExpectedIndex("index_adaptive_adjustment_slotId", listOf("slotId"), unique = false)
        )
    )

    /**
     * The columns that store a **stored vocabulary**: a single token, spelled as the domain enum
     * member's own name (never an ordinal), so a renumbered enum cannot silently re-point a row.
     */
    val VOCABULARY_COLUMNS: Map<String, List<String>> = mapOf(
        "program" to listOf("source", "lifecycleStatus"),
        "program_revision" to listOf("mode", "durationType", "scheduleType", "focusGoal"),
        "program_day" to listOf("type"),
        "program_exercise" to listOf("prescriptionDimension", "origin"),
        "program_workout_slot" to listOf("status"),
        "workout_session" to listOf("status"),
        "session_snapshot_exercise" to listOf("prescriptionDimension"),
        "session_exercise" to listOf("prescriptionDimension"),
        "program_family_progression_state" to listOf("adaptationState"),
        "program_adaptive_decision_record" to listOf(
            "targetScope",
            "action",
            "outcome",
            "evidence",
            "confidence",
            "recovery"
        ),
        "adaptive_adjustment" to listOf("beforePrescriptionDimension", "afterPrescriptionDimension")
    )

    /**
     * The columns that store a collection, converted to one deterministic `TEXT` value. These are
     * the columns that must never be flattened into a scalar.
     */
    val COLLECTION_COLUMNS: Map<String, List<String>> = mapOf(
        "program_revision" to listOf("scheduleWeekdays"),
        "program_exercise" to listOf("perSetTargets"),
        "session_snapshot" to listOf("appliedAdjustmentIds"),
        "session_snapshot_exercise" to listOf("perSetTargets"),
        "session_exercise" to listOf("perSetTargets"),
        "adaptive_adjustment" to listOf("beforePerSetTargets", "afterPerSetTargets")
    )

    /**
     * Every **additive step** of the migration chain, in the order a device runs them, with the
     * columns each one appends to a table the version-8 migration created.
     *
     * They are kept apart from [COLUMNS] on purpose: an `ALTER TABLE ... ADD COLUMN` is not part of the
     * `CREATE TABLE` the version-8 migration executes, and folding these columns into [COLUMNS] would
     * make that contract describe a table the migration does not create — the schema-equality test would
     * then fail for the right reason but with the wrong fix available. Keeping them apart *per step* is
     * the same discipline one level up: a step's statement list and the columns that step declares are
     * each other's contract, so a column added by a later step may not be expected of an earlier one.
     *
     * ```text
     * MIGRATION_8_9    program_revision.scheduleSessionsPerWeek   the schedule-frequency correction (§20)
     * MIGRATION_9_10   program_revision.focusGoal                 the Goals & Focus configuration (§6, §8)
     *                  program_revision.focusTargets
     * ```
     *
     * See `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` for the first and
     * `docs/PROGRAM_GENERATED_PLANNER.md` for the second.
     */
    val ADDITIVE_STEPS: List<Pair<String, Map<String, List<Column>>>> = listOf(
        "MIGRATION_8_9" to mapOf(
            "program_revision" to listOf(Column("scheduleSessionsPerWeek", INTEGER, nullable = true))
        ),
        "MIGRATION_9_10" to mapOf(
            "program_revision" to listOf(
                Column("focusGoal", TEXT, nullable = true),
                Column("focusTargets", TEXT, nullable = true)
            )
        )
    )

    /**
     * Every column a later additive step appended to a version-8 table, in the order the chain appends
     * them — which is also the order the entity declares them, because that equality is what makes a
     * freshly created database and an upgraded one hold byte-identical table definitions.
     */
    val ADDED_COLUMNS: Map<String, List<Column>> = ADDITIVE_STEPS
        .flatMap { (_, step) -> step.flatMap { (table, columns) -> columns.map { table to it } } }
        .groupBy({ it.first }, { it.second })

    /**
     * Every statement one additive step must execute, in the order SQLite receives them: one
     * `ADD COLUMN` per column that step declares. No default appears anywhere — a frequency the user
     * never chose and a goal they never stated are exactly what a default would invent.
     */
    fun expectedAdditiveStatements(step: String): List<String> =
        ADDITIVE_STEPS.first { it.first == step }.second.flatMap { (table, columns) ->
            columns.map { column -> "ALTER TABLE `$table` ADD COLUMN `${column.name}` ${column.type}" }
        }

    /** Every additive statement the whole chain executes, in chain order. */
    val EXPECTED_ADDITIVE_STATEMENTS: List<String> =
        ADDITIVE_STEPS.flatMap { (step, _) -> expectedAdditiveStatements(step) }

    /**
     * The columns one additive step appends to [table], so a test can assert a step's own contract
     * without reading the cumulative list.
     */
    fun columnsAddedBy(step: String, table: String): List<Column> =
        ADDITIVE_STEPS.first { it.first == step }.second[table].orEmpty()

    /** What a table holds at the **current** version: the version-8 columns plus the added ones. */
    fun columnsNow(table: String): List<Column> =
        COLUMNS.getValue(table) + ADDED_COLUMNS[table].orEmpty()

    /** The tables of the target schema that are owned by a Program and cascade with it. */
    val PROGRAM_OWNED_TABLES: List<String> =
        TABLES.filter { FOREIGN_KEYS.getValue(it).any { key -> key.onDelete == "CASCADE" } }

    // ---- the DDL Room generates for the schema above -----------------------------------------------

    /**
     * The `CREATE TABLE` statement Room emits for one target table as the **version-8 migration**
     * creates it: columns in declaration order, then the primary key, then the foreign keys.
     */
    fun expectedTableDdl(table: String): String = tableDdl(table, COLUMNS.getValue(table))

    /**
     * The `CREATE TABLE` statement the same table has at the **current** version: the version-8 columns
     * plus the added ones. A freshly created database and an upgraded one hold the same definition,
     * because an appended column keeps the declaration order of the entity.
     */
    fun expectedCurrentTableDdl(table: String): String = tableDdl(table, columnsNow(table))

    private fun tableDdl(table: String, tableColumns: List<Column>): String {
        val columns = tableColumns.joinToString(", ") { column ->
            "`${column.name}` ${column.type}${if (column.nullable) "" else " NOT NULL"}"
        }
        val primaryKey =
            "PRIMARY KEY(" + PRIMARY_KEYS.getValue(table).joinToString(", ") { "`$it`" } + ")"
        val foreignKeys = FOREIGN_KEYS.getValue(table).joinToString("") { key ->
            ", FOREIGN KEY(`${key.childColumn}`) REFERENCES `${key.parentTable}`(`${key.parentColumn}`)" +
                " ON UPDATE NO ACTION ON DELETE ${key.onDelete} "
        }
        return "CREATE TABLE IF NOT EXISTS `$table` ($columns, $primaryKey$foreignKeys)"
    }

    /** The `CREATE [UNIQUE] INDEX` statement Room emits for one declared index. */
    fun expectedIndexDdl(table: String, index: ExpectedIndex): String {
        val kind = if (index.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
        val columns = index.columns.joinToString(", ") { "`$it`" }
        return "$kind IF NOT EXISTS `${index.name}` ON `$table` ($columns)"
    }

    /**
     * Every statement the version-7 → version-8 migration must execute, in the order Room emits them:
     * per entity, its table and then its indices.
     */
    val EXPECTED_MIGRATION_STATEMENTS: List<String> = TABLES.flatMap { table ->
        listOf(expectedTableDdl(table)) + INDEXES.getValue(table).map { expectedIndexDdl(table, it) }
    }

    /** Whitespace-collapsed SQL, so a multi-line statement and Room's single-line one compare equal. */
    fun normalized(sql: String): String = sql
        .replace(Regex("\\s+"), " ")
        .replace("( ", "(")
        .replace(" )", ")")
        .replace(", ", ",")
        .trim()

    /** A bare column-name reference, so `set_log` is not matched inside `program_set_log`. */
    fun columnToken(name: String): String = "`$name`"

    /** A bare table-name reference, so `set_log` is not matched inside `program_set_log`. */
    fun tableToken(name: String): String = "`$name`"
}
