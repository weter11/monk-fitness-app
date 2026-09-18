package com.monkfitness.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.DayOfWeek

/**
 * The target Program System schema, read from the three places that can disagree with each other:
 * the entities the database declares, the statements the version-7 → version-8 migration executes,
 * and the DDL Room generates for those entities.
 *
 * The contract these tests assert lives in [ProgramSchemaFixture]; this class proves the shipped
 * schema *is* that contract, and that the architecture's prohibitions are structurally impossible
 * here — a Program that owns a mode or a plan, a slot that carries an amount of work, a snapshot that
 * follows the live revision, a session that may only be attempted once, an entity that leaks a domain
 * type, a prescription flattened into a scalar.
 *
 * Where the schema is executed rather than read — the upgrade of a populated version-7 database,
 * cascade and `SET NULL` behaviour, multiplicity and uniqueness — a real SQLite engine does the work:
 * see `ProgramMigrationPreservationTest` and `ProgramOwnershipCascadeTest`.
 */
class ProgramSchemaTest {

    private val mainSources = File("src/main/java/com/monkfitness/app").let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    private fun sourceFile(relativePath: String): String =
        File(mainSources, relativePath).readText()

    private val databaseSource: String by lazy { sourceFile("data/local/AppDatabase.kt") }

    /** Every statement the version-7 → version-8 migration executes, in order. */
    private fun migrationStatements(): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_7_8.migrate(database)
        return statements
    }

    private fun normalized(statements: List<String>): List<String> =
        statements.map { ProgramSchemaFixture.normalized(it) }

    /** The one `CREATE TABLE` statement for [table] — matched by its own name, never as a substring. */
    private fun statementFor(table: String): String =
        migrationStatements().single { it.startsWith("CREATE TABLE IF NOT EXISTS `$table` ") }

    private fun columnsOf(table: String): List<String> =
        ProgramSchemaFixture.COLUMNS.getValue(table).map { it.name }

    private fun primaryKeyOf(statement: String): List<String> =
        Regex("PRIMARY KEY\\((.*?)\\)")
            .find(statement)!!
            .groupValues[1]
            .split(", ")
            .map { it.trim().trim('`') }

    private fun foreignKeysOf(table: String): List<List<String>> =
        Regex(
            "FOREIGN KEY\\(`(\\w+)`\\) REFERENCES `(\\w+)`\\(`(\\w+)`\\) " +
                "ON UPDATE NO ACTION ON DELETE (CASCADE|SET NULL|NO ACTION)"
        ).findAll(statementFor(table)).map { match -> match.groupValues.drop(1) }.toList()

    /** The `@Database(entities = [...])` list, read from the source that declares it. */
    private fun registeredEntities(): List<String> =
        Regex("entities = \\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(databaseSource)!!
            .groupValues[1]
            .let { list ->
                Regex("(\\w+)::class").findAll(list).map { it.groupValues[1] }.toList()
            }

    /** A source file with its comments removed, so a rule is not "violated" by its own explanation. */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

    private fun snakeUpper(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private fun entityFiles(): List<String> =
        ProgramSchemaFixture.ENTITIES.map { (entity, _) -> "data/model/$entity.kt" }

    // ---- the entity set ------------------------------------------------------------------------------

    @Test
    fun theTargetEntitySetIsExactlyTheOneTheArchitectureNames() {
        // Referencing the classes — not their names — makes this a compile-time claim as well as a
        // runtime one: an entity that stops existing, or a sixteenth one, cannot hide here.
        val declared = listOf(
            ProgramEntity::class.java,
            AppStateEntity::class.java,
            ProgramRevisionEntity::class.java,
            ProgramDayEntity::class.java,
            ProgramExerciseEntity::class.java,
            ProgramWorkoutSlotEntity::class.java,
            WorkoutSessionEntity::class.java,
            SessionSnapshotEntity::class.java,
            SessionSnapshotExerciseEntity::class.java,
            SessionExerciseEntity::class.java,
            SetLogEntity::class.java,
            ProgramPauseEntity::class.java,
            FamilyProgressionStateEntity::class.java,
            AdaptiveDecisionRecordEntity::class.java,
            AdaptiveAdjustmentEntity::class.java
        ).map { it.simpleName }

        assertEquals(
            "the target schema is the §23 entity set plus the two auxiliary snapshot tables",
            ProgramSchemaFixture.ENTITIES.map { it.first },
            declared
        )
        assertEquals(
            "the database declares the version-7 entities unchanged, then the target ones",
            listOf(
                "UserProgress",
                "PostureSessionProgress",
                "SetLog",
                "BodyWeightEntry",
                "ProgramDayState",
                "MealCycle",
                "MealEntity",
                "ShoppingItemEntity",
                "FamilyProgressionState",
                "AdaptiveDecisionRecord"
            ) + ProgramSchemaFixture.ENTITIES.map { it.first },
            registeredEntities()
        )
    }

    @Test
    fun theEntityFilesDeclareThePhysicalTablesOfTheContract() {
        for ((entity, table) in ProgramSchemaFixture.ENTITIES) {
            val source = sourceFile("data/model/$entity.kt")
            assertTrue(
                "$entity is stored in `$table`",
                Regex("@Entity\\(\\s*(tableName\\s*=\\s*)?\"$table\"\\s*[,)]").containsMatchIn(source)
            )
        }
        assertEquals(
            "every physical table name is distinct",
            ProgramSchemaFixture.TABLES.size,
            ProgramSchemaFixture.TABLES.toSet().size
        )
    }

    @Test
    fun theDatabaseMovesFromVersionSevenToVersionEightAndRegistersTheMigration() {
        assertTrue("the schema version moves with the schema", databaseSource.contains("version = 8"))
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, AppDatabase.MIGRATION_7_8.endVersion)

        val migrations = Regex("addMigrations\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(databaseSource)!!
            .groupValues[1]
        for (migration in listOf(
            "MIGRATION_1_2",
            "MIGRATION_2_3",
            "MIGRATION_3_4",
            "MIGRATION_4_5",
            "MIGRATION_5_6",
            "MIGRATION_6_7",
            "MIGRATION_7_8"
        )) {
            assertTrue("$migration is registered", migrations.contains(migration))
        }
    }

    // ---- the migration -------------------------------------------------------------------------------

    @Test
    fun theMigrationExecutesExactlyTheStatementsRoomGeneratesForTheTargetEntities() {
        assertEquals(
            "the migration creates the tables and the indices Room generates for the target " +
                "entities, in the same order and with the same clauses",
            normalized(ProgramSchemaFixture.EXPECTED_MIGRATION_STATEMENTS),
            normalized(migrationStatements())
        )
    }

    @Test
    fun theMigrationCreatesEveryTargetTableExactlyOnceAndNothingElse() {
        val statements = migrationStatements()
        val tables = statements
            .filter { it.startsWith("CREATE TABLE IF NOT EXISTS ") }
            .map { it.substringAfter("CREATE TABLE IF NOT EXISTS `").substringBefore("`") }

        assertEquals("one CREATE TABLE per target table, in the declared order", ProgramSchemaFixture.TABLES, tables)
        assertEquals("no table is created twice", tables.size, tables.toSet().size)
        assertTrue(
            "every statement is a CREATE TABLE or a CREATE INDEX: $statements",
            statements.all {
                it.startsWith("CREATE TABLE IF NOT EXISTS `") ||
                    it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS `") ||
                    it.startsWith("CREATE INDEX IF NOT EXISTS `")
            }
        )
        assertEquals(
            "the migration is exactly the tables and indices of the contract",
            ProgramSchemaFixture.EXPECTED_MIGRATION_STATEMENTS.size,
            statements.size
        )
    }

    @Test
    fun theMigrationNeverNamesALegacyTable() {
        val statements = migrationStatements()

        for (table in LegacyV7Schema.TABLES + "room_master_table") {
            assertFalse(
                "the upgrade must not read, rewrite, rename, empty or drop `$table`",
                statements.any { it.contains(ProgramSchemaFixture.tableToken(table)) }
            )
        }
    }

    @Test
    fun theMigrationNeverDropsDeletesAltersUpdatesInsertsOrRenamesAnything() {
        // The only `DELETE` the migration may contain is the delete action of a foreign key
        // (`ON DELETE CASCADE`), so those clauses are removed before the verbs are looked for: what the
        // rule forbids is a statement that acts on an existing table.
        val statements = migrationStatements().map { statement ->
            statement.replace(
                Regex("ON UPDATE NO ACTION ON DELETE (CASCADE|SET NULL|NO ACTION)"),
                ""
            )
        }

        for (keyword in listOf("DROP", "DELETE", "ALTER", "UPDATE", "INSERT", "RENAME")) {
            assertFalse(
                "the upgrade is additive and contains no $keyword statement",
                statements.any { Regex("\\b$keyword\\b").containsMatchIn(it.uppercase()) }
            )
        }
        assertFalse(
            "no table is dropped, legacy or otherwise",
            migrationStatements().any { it.uppercase().startsWith("DROP TABLE") }
        )
    }

    // ---- what the schema must not be able to represent -------------------------------------------------

    @Test
    fun theSlotCarriesNoAmountOfWork() {
        val columns = columnsOf("program_workout_slot")

        for (forbidden in listOf(
            "reps",
            "repsCompleted",
            "completedReps",
            "sets",
            "completedSets",
            "durationSeconds",
            "durationPerformed",
            "amount",
            "completionAmount",
            "progress",
            "performance",
            "score",
            "volume"
        )) {
            assertFalse(
                "a slot is an opportunity, not a performance: it has no `$forbidden` column, so a " +
                    "missed slot cannot be read as a workout that scored zero (§12, §20)",
                columns.contains(forbidden)
            )
        }
        assertEquals(
            "it carries its identity, its owners, the day it presents, its planned date, what " +
                "happened to it and when that happened — and nothing else",
            listOf("slotId", "programId", "revisionId", "programDayId", "plannedFor", "status", "completedAt"),
            columns
        )
    }

    @Test
    fun theProgramCarriesNoModeNoPlanNoCycleAndNoSelection() {
        val columns = columnsOf("program")

        for (forbidden in listOf(
            "mode",
            "programDay",
            "programDayId",
            "cycleNumber",
            "sessionDate",
            "isSelected",
            "selected",
            "isActive",
            "active",
            "revisionNumber"
        )) {
            assertFalse(
                "`$forbidden` is not a Program fact: mode and the plan belong to the revision, " +
                    "selection to AppState, and a cycle number or a session date is never ownership (§1, §23)",
                columns.contains(forbidden)
            )
        }
        assertTrue(
            "the Program holds its own identity and a reference to its current revision",
            columns.containsAll(listOf("programId", "currentRevisionId"))
        )
    }

    @Test
    fun aLegacyCoordinateIsNeverAnIdentity() {
        val identifiers = ProgramSchemaFixture.PRIMARY_KEYS.values.flatten()

        for (legacyInDisguise in listOf(
            "programRevision",
            "revisionNumber",
            "cycleNumber",
            "sessionDate",
            "programDay",
            "date",
            "day"
        )) {
            assertFalse(
                "`$legacyInDisguise` is a legacy coordinate, not an identity (§23)",
                identifiers.contains(legacyInDisguise)
            )
        }
        assertEquals(
            "the revision's identity is the revision id; its number is human-readable only",
            listOf("revisionId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_revision")
        )
        assertTrue(
            "the number is stored, and it is unique within its program",
            columnsOf("program_revision").contains("revisionNumber") &&
                ProgramSchemaFixture.INDEXES.getValue("program_revision").single().let {
                    it.unique && it.columns == listOf("programId", "revisionNumber")
                }
        )
    }

    @Test
    fun theSessionRemembersPlannedAndActualTimeApart() {
        val sessionColumns = columnsOf("workout_session")

        assertTrue(
            "the actual start and the actual finish are the session's own facts",
            sessionColumns.containsAll(listOf("startedAt", "finishedAt"))
        )
        for (planned in listOf("plannedFor", "plannedDate", "sessionDate", "date")) {
            assertFalse(
                "`$planned` is a plan date; replacing the actual timestamps with it is what §19 forbids",
                sessionColumns.contains(planned)
            )
        }
        assertTrue(
            "the planned date is preserved in the captured presentation instead",
            columnsOf("session_snapshot").contains("plannedFor")
        )
    }

    @Test
    fun thePresentationIsStoredWithTheSessionRatherThanJoinedToTheLivePlan() {
        for (table in ProgramSchemaFixture.AUXILIARY_TABLES + "session_exercise") {
            val parents = foreignKeysOf(table).map { it[1] }
            for (livePlan in listOf("program_revision", "program_exercise", "program_day")) {
                assertFalse(
                    "`$table` must not depend on `$livePlan`: a later revision or edit must not be " +
                        "able to change what a session was shown (§19)",
                    parents.contains(livePlan)
                )
            }
        }
        assertTrue(
            "the snapshot stores the exercise and the prescription it presented, as its own values",
            columnsOf("session_snapshot_exercise").containsAll(
                listOf("programExerciseId", "exerciseId", "prescriptionDimension", "perSetTargets")
            )
        )
        assertTrue(
            "and the session exercise does too, so nothing is re-derived from the live plan at read time",
            columnsOf("session_exercise").containsAll(
                listOf("programExerciseId", "exerciseId", "prescriptionDimension", "perSetTargets")
            )
        )
        assertFalse(
            "the adjustments a snapshot captured are recorded ids, not a foreign key that could " +
                "disappear and leave the capture incomplete",
            foreignKeysOf("session_snapshot").any { it[0] == "appliedAdjustmentIds" }
        )
    }

    @Test
    fun oneSlotMayCarrySeveralSessionAttempts() {
        val sessionIndexes = ProgramSchemaFixture.INDEXES.getValue("workout_session")

        assertFalse(
            "a slot may be attempted more than once, so its column may not be unique (§19)",
            sessionIndexes.any { it.unique && it.columns == listOf("slotId") }
        )
        assertTrue(
            "the slot is indexed for the lookup, not constrained to a single row",
            sessionIndexes.any { !it.unique && it.columns == listOf("slotId") }
        )
        assertNotEquals(
            "and a slot is not the session's identity",
            listOf("slotId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("workout_session")
        )
    }

    @Test
    fun theSameExerciseMayOccurSeveralTimesInOneDay() {
        val exerciseIndexes = ProgramSchemaFixture.INDEXES.getValue("program_exercise")

        assertFalse(
            "a day may use the same exercise repeatedly, each occurrence its own row (§9)",
            exerciseIndexes.any { it.unique && it.columns.contains("exerciseId") }
        )
        assertEquals(
            "the occurrence's order in the day is the unique coordinate, not the exercise",
            listOf(listOf("programDayId", "position")),
            exerciseIndexes.filter { it.unique }.map { it.columns }
        )
    }

    @Test
    fun aPositionIsAnOrderingAndNeverAnIdentity() {
        for (table in listOf(
            "program_day",
            "program_exercise",
            "session_exercise",
            "session_snapshot_exercise"
        )) {
            assertFalse(
                "`position` orders rows inside `$table`; identity is the entity's own id",
                ProgramSchemaFixture.PRIMARY_KEYS.getValue(table).contains("position")
            )
        }
        assertEquals(
            "a day's identity is its id",
            listOf("programDayId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_day")
        )
        assertTrue(
            "and its position in the revision is unique",
            ProgramSchemaFixture.INDEXES.getValue("program_day").single().let {
                it.unique && it.columns == listOf("revisionId", "position")
            }
        )
        assertEquals(
            "the exercise occurrence's identity is its own id, not its exercise",
            listOf("programExerciseId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_exercise")
        )
    }

    @Test
    fun theTargetSetLogIsNotTheLegacySetLog() {
        val target = "program_set_log"

        assertNotEquals(LegacyV7Schema.LEGACY_SET_LOG_TABLE, target)
        assertTrue("the target set log is its own table", ProgramSchemaFixture.TABLES.contains(target))
        assertFalse(
            "the legacy logging shape is not borrowed: the target row is one confirmed set of one " +
                "session exercise, not a date-stamped log line (§19, §23)",
            columnsOf(target).contains("sessionDate")
        )
        assertEquals(
            "a confirmed set belongs to the session exercise that performed it",
            listOf(
                listOf("sessionExerciseId", "session_exercise", "sessionExerciseId", "CASCADE")
            ),
            foreignKeysOf(target)
        )
        assertFalse(
            "and the migration never touches the legacy table",
            migrationStatements().any {
                it.contains(ProgramSchemaFixture.tableToken(LegacyV7Schema.LEGACY_SET_LOG_TABLE))
            }
        )
    }

    @Test
    fun theTargetAdaptiveTablesCoexistWithTheStageOneOnes() {
        // The Stage-1 pair is consumed by shipped DAO/repository code keyed by the legacy integer
        // revision, so it keeps its contract; the target pair is keyed by the typed identities the
        // Program System uses, and the two coexist until §30 step 15 retires the old persistence.
        for (target in listOf(
            "program_family_progression_state",
            "program_adaptive_decision_record",
            "adaptive_adjustment"
        )) {
            assertTrue("$target is part of the target schema", ProgramSchemaFixture.TABLES.contains(target))
        }
        assertEquals(
            "the target adaptive tables are scoped by the typed identities, not by the legacy revision integer",
            listOf("revisionId", "familyId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_family_progression_state")
        )
        assertEquals(
            "a decision belongs to a real slot of a real revision",
            listOf("decisionId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_adaptive_decision_record")
        )
        for (target in listOf("program_family_progression_state", "program_adaptive_decision_record")) {
            assertFalse(
                "`$target` carries no cycle number and no program day number",
                Regex("`(cycleNumber|programDay|programRevision)`").containsMatchIn(statementFor(target))
            )
        }
        assertTrue(
            "an adjustment records its own identity, its decision, its scope and its supersession",
            columnsOf("adaptive_adjustment").containsAll(
                listOf(
                    "adjustmentId",
                    "decisionId",
                    "slotId",
                    "supersedesAdjustmentId",
                    "beforeExerciseId",
                    "beforePerSetTargets",
                    "afterExerciseId",
                    "afterPerSetTargets"
                )
            )
        )
    }

    // ---- ownership ------------------------------------------------------------------------------------

    @Test
    fun everyProgramOwnedChildPointsAtItsTrueOwnerWithCascade() {
        for (table in ProgramSchemaFixture.TABLES) {
            assertEquals(
                "`$table`'s foreign keys are the ownership graph of §23",
                ProgramSchemaFixture.FOREIGN_KEYS.getValue(table).map {
                    listOf(it.childColumn, it.parentTable, it.parentColumn, it.onDelete)
                },
                foreignKeysOf(table)
            )
        }
    }

    @Test
    fun everyDeleteActionIsCascadeExceptTheTwoGlobalReferences() {
        val allKeys = ProgramSchemaFixture.TABLES.flatMap { foreignKeysOf(it) }

        assertEquals(
            "the blueprint's one SET NULL: the next program is a nullable global reference (§29)",
            listOf(listOf("nextProgramId", "program", "programId", "SET NULL")),
            allKeys.filter { it[3] == "SET NULL" }
        )
        assertEquals(
            "the selection is not silently nulled: deleting the selected program is refused until " +
                "the lifecycle handles the fallback (§3)",
            listOf(listOf("selectedProgramId", "program", "programId", "NO ACTION")),
            allKeys.filter { it[3] == "NO ACTION" }
        )
        for (table in ProgramSchemaFixture.PROGRAM_OWNED_TABLES) {
            for (key in ProgramSchemaFixture.FOREIGN_KEYS.getValue(table)) {
                assertEquals(
                    "`$table`.`${key.childColumn}` is Program-owned and cascades with its owner",
                    "CASCADE",
                    key.onDelete
                )
            }
        }
        assertTrue(
            "no global entity is cascaded: the Exercise Library, body weight and nutrition are not " +
                "children of a program",
            allKeys.none { it[1] in listOf("body_weight_log", "meal_cycles", "meals", "shopping_items") }
        )
    }

    @Test
    fun everyTargetEntityStandsOnTheOwnershipGraph() {
        val notProgramOwned = ProgramSchemaFixture.TABLES.filter { table ->
            table != "program" && table !in ProgramSchemaFixture.PROGRAM_OWNED_TABLES
        }

        assertEquals(
            "every target table except the Program and the global single-row AppState is destroyed " +
                "with the Program that owns it",
            listOf("app_state"),
            notProgramOwned
        )
        assertEquals(
            "the Program itself owns no parent",
            emptyList<List<String>>(),
            ProgramSchemaFixture.FOREIGN_KEYS.getValue("program")
        )
        assertTrue(
            "and AppState is nonetheless tied to programs only through its two references, neither of " +
                "which makes it a child",
            ProgramSchemaFixture.FOREIGN_KEYS.getValue("app_state").all { it.onDelete != "CASCADE" }
        )
    }

    // ---- identity and metadata -------------------------------------------------------------------------

    @Test
    fun everyTargetEntityHasTheIdentityTheArchitectureGivesIt() {
        for (table in ProgramSchemaFixture.TABLES) {
            assertEquals(
                "`$table`'s primary key",
                ProgramSchemaFixture.PRIMARY_KEYS.getValue(table),
                primaryKeyOf(statementFor(table))
            )
        }
        assertEquals(
            "the family's progression state is revision-scoped and keyed by the revision's identity — " +
                "not by the legacy revision integer (§1, §23)",
            listOf("revisionId", "familyId"),
            ProgramSchemaFixture.PRIMARY_KEYS.getValue("program_family_progression_state")
        )
    }

    @Test
    fun everyDeclaredColumnIsStoredAsTheContractSays() {
        for (table in ProgramSchemaFixture.TABLES) {
            val statement = ProgramSchemaFixture.normalized(statementFor(table))
            for (column in ProgramSchemaFixture.COLUMNS.getValue(table)) {
                val expected =
                    "`${column.name}` ${column.type}${if (column.nullable) "" else " NOT NULL"}"
                assertTrue("`$table`.`${column.name}` is declared as `$expected`", statement.contains(expected))
            }
        }
    }

    @Test
    fun theStoredVocabularyIsSpelledAsTheDomainVocabulary() {
        // Every stored token is the domain enum member's own name — never an ordinal, and never a
        // second vocabulary invented in the data layer. Renaming a domain member fails here, so the
        // schema decision is forced rather than discovered later on a device full of orphaned rows.
        assertEquals(listOf("STANDARD", "USER", "IMPORTED"), ProgramSource.entries.map { it.name })
        assertEquals(listOf("NOT_STARTED", "RUNNING", "PAUSED", "COMPLETED"), LifecycleStatus.entries.map { it.name })
        assertEquals(listOf("MANUAL", "GENERATED"), ProgramMode.entries.map { it.name })
        assertEquals(listOf("TRAINING", "MOBILITY", "POSTURE_MOBILITY", "REST"), ProgramDayType.entries.map { it.name })
        assertEquals(listOf("PLANNED", "COMPLETED", "MISSED", "SUPERSEDED"), SlotStatus.entries.map { it.name })
        assertEquals(listOf("IN_PROGRESS", "COMPLETED", "CANCELLED"), SessionStatus.entries.map { it.name })
        assertEquals(listOf("GENERATED", "USER_AUTHORED"), ProgramExerciseOrigin.entries.map { it.name })
        assertEquals(
            "the five dimensions, including the three this stage deliberately does not implement (§10)",
            listOf("REP_BASED", "TIME_BASED", "SET_BASED", "DIFFICULTY_BASED", "REST_BASED"),
            PrescriptionDimension.entries.map { it.name }
        )
        assertEquals(
            listOf("HOLD", "PROGRESS", "REGRESS", "CHANGE_VARIANT", "CHANGE_REST"),
            AdaptiveAction.entries.map { it.name }
        )
        assertEquals(listOf("APPLIED", "NOT_APPLIED"), DecisionOutcome.entries.map { it.name })
        assertEquals(listOf("INSUFFICIENT", "STABLE", "STRONG"), EvidenceLevel.entries.map { it.name })
        assertEquals(listOf("LOW", "MODERATE", "HIGH"), ConfidenceLevel.entries.map { it.name })
        assertEquals(listOf("FAVORABLE", "CAUTIOUS", "UNKNOWN"), RecoveryContext.entries.map { it.name })
        assertEquals(listOf("EXERCISE", "FAMILY", "FOCUS", "SESSION"), AdaptiveScope.entries.map { it.name })

        // The two sealed forms have no enum member to borrow a name from, so the discriminator token
        // is the subtype's own name in the same convention. The relation is asserted, not eyeballed.
        assertEquals("FIXED_DAYS", snakeUpper(ProgramDuration.FixedDays::class.java.simpleName))
        assertEquals("INDEFINITE", snakeUpper(ProgramDuration.Indefinite::class.java.simpleName))
        assertEquals("FIXED_WEEKDAYS", snakeUpper(ProgramSchedule.FixedWeekdays::class.java.simpleName))
        assertEquals("FLEXIBLE_PER_WEEK", snakeUpper(ProgramSchedule.FlexiblePerWeek::class.java.simpleName))

        // Every vocabulary column is stored as a token, and every one of them is declared in the contract.
        for ((table, columns) in ProgramSchemaFixture.VOCABULARY_COLUMNS) {
            for (column in columns) {
                val declared = ProgramSchemaFixture.COLUMNS.getValue(table).single { it.name == column }
                assertEquals("`$table`.`$column` stores a token, not a number", "TEXT", declared.type)
            }
        }
    }

    @Test
    fun everyCollectionColumnIsStoredAsASingleDeterministicTextValue() {
        for ((table, columns) in ProgramSchemaFixture.COLLECTION_COLUMNS) {
            for (column in columns) {
                val declared = ProgramSchemaFixture.COLUMNS.getValue(table).single { it.name == column }
                assertEquals(
                    "`$table`.`$column` stores a converted collection, never a scalar column",
                    "TEXT",
                    declared.type
                )
            }
        }
    }

    // ---- the data-layer rule ---------------------------------------------------------------------------

    @Test
    fun noTargetEntityImportsTheDomainOrHoldsADomainValueObject() {
        val domainTypes = listOf(
            "ProgramId",
            "RevisionId",
            "ProgramDayId",
            "ProgramExerciseId",
            "SlotId",
            "SessionId",
            "SessionExerciseId",
            "SetLogId",
            "PauseId",
            "AdjustmentId",
            "DecisionId",
            "Prescription",
            "RepPrescription",
            "TimePrescription",
            "ProgramDuration",
            "ProgramSchedule",
            "WorkoutSessionSnapshot",
            "EffectiveWorkout"
        )

        for (file in entityFiles() + "data/local/ProgramTypeConverters.kt") {
            val code = withoutComments(sourceFile(file))
            assertFalse(
                "$file must not import the domain: the mapper layer translates, the entity stores",
                code.contains("com.monkfitness.app.domain")
            )
            for (type in domainTypes) {
                assertFalse(
                    "$file holds no `$type` — entities use storage representations, not domain values",
                    Regex("\\b$type\\b").containsMatchIn(code)
                )
            }
            assertFalse(
                "$file must not extend or wrap a domain class",
                Regex(":\\s*(Prescription|ProgramDuration|ProgramSchedule|WorkoutSessionSnapshot|EffectiveWorkout)\\b")
                    .containsMatchIn(code)
            )
        }
    }

    // ---- the entity-level representability guards -------------------------------------------------------

    @Test
    fun aSetLogCannotRecordWorkThatDidNotHappen() {
        val performed = SetLogEntity(
            setLogId = "set-1",
            sessionExerciseId = "session-exercise-1",
            setIndex = 1,
            completedReps = 12,
            durationSeconds = 0,
            performedAt = 1_700_000_000_000L
        )
        assertEquals(12, performed.completedReps)

        val timed = SetLogEntity(
            setLogId = "set-2",
            sessionExerciseId = "session-exercise-1",
            setIndex = 2,
            completedReps = 0,
            durationSeconds = 45,
            performedAt = 1_700_000_000_000L
        )
        assertEquals(45, timed.durationSeconds)

        val noWork = assertThrows(IllegalArgumentException::class.java) {
            SetLogEntity("set-3", "session-exercise-1", 3, 0, 0, 1L)
        }
        assertTrue(
            "a set that was not performed is absent, never a row of zeroes (§12, §19): ${noWork.message}",
            noWork.message!!.contains("performed")
        )

        val bothUnits = assertThrows(IllegalArgumentException::class.java) {
            SetLogEntity("set-4", "session-exercise-1", 4, 12, 30, 1L)
        }
        assertTrue(
            "a set is measured in one unit: ${bothUnits.message}",
            bothUnits.message!!.contains("performed")
        )
    }

    @Test
    fun aSlotIsCompletedOnlyWithACompletionStamp() {
        val planned = ProgramWorkoutSlotEntity(
            slotId = "slot-1",
            programId = "program-1",
            revisionId = "revision-1",
            programDayId = "day-1",
            plannedFor = "2026-09-18",
            status = "PLANNED"
        )
        assertEquals("PLANNED", planned.status)

        val completed = planned.copy(status = "COMPLETED", completedAt = 1_700_000_000_000L)
        assertEquals("COMPLETED", completed.status)

        val missingStamp = assertThrows(IllegalArgumentException::class.java) {
            planned.copy(status = "COMPLETED")
        }
        assertTrue(
            "only a COMPLETED slot happened, and it says when: ${missingStamp.message}",
            missingStamp.message!!.contains("completedAt")
        )

        val missedWithStamp = assertThrows(IllegalArgumentException::class.java) {
            planned.copy(status = "MISSED", completedAt = 1_700_000_000_000L)
        }
        assertTrue(
            "and a slot that did not happen cannot claim to: ${missedWithStamp.message}",
            missedWithStamp.message!!.contains("completedAt")
        )
    }

    @Test
    fun aSessionStatusAgreesWithItsFinishStamp() {
        val running = WorkoutSessionEntity(
            sessionId = "session-1",
            slotId = "slot-1",
            programId = "program-1",
            revisionId = "revision-1",
            status = "IN_PROGRESS",
            startedAt = 1_700_000_000_000L
        )
        assertEquals("IN_PROGRESS", running.status)

        assertEquals("CANCELLED", running.copy(status = "CANCELLED", finishedAt = 1_700_000_001_000L).status)

        val finishedWhileRunning = assertThrows(IllegalArgumentException::class.java) {
            running.copy(finishedAt = 1_700_000_001_000L)
        }
        assertTrue(
            "an in-progress session has not finished: ${finishedWhileRunning.message}",
            finishedWhileRunning.message!!.contains("finishedAt")
        )

        val endedWithoutStamp = assertThrows(IllegalArgumentException::class.java) {
            running.copy(status = "COMPLETED")
        }
        assertTrue(
            "a finished session says when: ${endedWithoutStamp.message}",
            endedWithoutStamp.message!!.contains("finishedAt")
        )
    }

    @Test
    fun aRevisionCannotDescribeADurationOrAScheduleItsDiscriminatorDenies() {
        val revision = ProgramRevisionEntity(
            revisionId = "revision-1",
            programId = "program-1",
            revisionNumber = 1,
            mode = "MANUAL",
            durationType = "FIXED_DAYS",
            durationDays = 30,
            scheduleType = "FLEXIBLE_PER_WEEK",
            scheduleWeekdays = null,
            createdAt = 1_700_000_000_000L
        )
        assertEquals(30, revision.durationDays)

        val indefiniteWithATotal = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(durationType = "INDEFINITE")
        }
        assertTrue(
            "an indefinite revision has no total, and no fake N / 30 either: ${indefiniteWithATotal.message}",
            indefiniteWithATotal.message!!.contains("durationDays")
        )

        val fixedWithoutDays = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(durationDays = null)
        }
        assertTrue(
            "FIXED_DAYS names how many days: ${fixedWithoutDays.message}",
            fixedWithoutDays.message!!.contains("durationDays")
        )

        val weekdaysWithoutASchedule = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(scheduleType = "FIXED_WEEKDAYS", scheduleWeekdays = null)
        }
        assertTrue(
            "FIXED_WEEKDAYS names its weekdays: ${weekdaysWithoutASchedule.message}",
            weekdaysWithoutASchedule.message!!.contains("scheduleWeekdays")
        )

        val weekdaysOnAFrequencySchedule = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(scheduleWeekdays = setOf(DayOfWeek.MONDAY))
        }
        assertTrue(
            "a deterministic frequency does not name weekdays: ${weekdaysOnAFrequencySchedule.message}",
            weekdaysOnAFrequencySchedule.message!!.contains("scheduleWeekdays")
        )
    }

    @Test
    fun anAdjustmentThatChangesNothingIsNotAnAdjustment() {
        val adjustment = AdaptiveAdjustmentEntity(
            adjustmentId = "adjustment-1",
            decisionId = "decision-1",
            programId = "program-1",
            revisionId = "revision-1",
            slotId = "slot-1",
            programExerciseId = "program-exercise-1",
            beforeExerciseId = "pushup",
            beforePrescriptionDimension = "REP_BASED",
            beforePerSetTargets = listOf(12, 10, 8, 6),
            afterExerciseId = "pushup",
            afterPrescriptionDimension = "REP_BASED",
            afterPerSetTargets = listOf(12, 10, 10, 8),
            createdAt = 1_700_000_000_000L
        )
        assertEquals(listOf(12, 10, 8, 6), adjustment.beforePerSetTargets)

        val nothingToChange = assertThrows(IllegalArgumentException::class.java) {
            adjustment.copy(afterPerSetTargets = listOf(12, 10, 8, 6))
        }
        assertTrue(
            "a change that changes nothing is NothingToChange, an expected result (§28): ${nothingToChange.message}",
            nothingToChange.message!!.contains("changes nothing")
        )

        val variant = adjustment.copy(
            afterExerciseId = "knee_pushup",
            afterPerSetTargets = listOf(12, 10, 8, 6)
        )
        assertEquals("knee_pushup", variant.afterExerciseId)
    }

    @Test
    fun theAppStateHoldsOneRowAndItsTwoProgramReferences() {
        val state = AppStateEntity(
            id = AppStateEntity.SINGLE_ROW_ID,
            selectedProgramId = "program-1",
            nextProgramId = "program-2",
            nextProgramAutoStart = false
        )
        assertEquals(1, state.id)

        assertTrue(
            "the selection and the next program are global runtime state, never Program-owned fields (§21)",
            columnsOf("app_state") == listOf("id", "selectedProgramId", "nextProgramId", "nextProgramAutoStart")
        )
        val wrongRow = assertThrows(IllegalArgumentException::class.java) { state.copy(id = 2) }
        assertTrue(
            "a second AppState row is not representable: ${wrongRow.message}",
            wrongRow.message!!.contains("single row")
        )
    }

    // ---- the stored collections --------------------------------------------------------------------------

    @Test
    fun perSetTargetsSurviveStorageWithoutCollapsingIntoAScalar() {
        val converters = ProgramTypeConverters()

        assertEquals(
            "12 / 10 / 8 / 6 is four set targets, not one number",
            "12,10,8,6",
            converters.toPerSetTargets(listOf(12, 10, 8, 6))
        )
        assertEquals(listOf(12, 10, 8, 6), converters.fromPerSetTargets("12,10,8,6"))
        assertEquals(
            "the uniform case keeps its set count as well",
            "10,10,10,10",
            converters.toPerSetTargets(listOf(10, 10, 10, 10))
        )
        assertEquals(listOf(10, 10, 10, 10), converters.fromPerSetTargets("10,10,10,10"))
        assertNotEquals(
            "a collapsed scalar could not answer targetForSet(3)",
            "10",
            converters.toPerSetTargets(listOf(12, 10, 8, 6))
        )
        assertNotEquals(
            "nor could it tell one set from four",
            converters.toPerSetTargets(listOf(10)),
            converters.toPerSetTargets(listOf(10, 10, 10, 10))
        )
    }

    @Test
    fun perSetDurationsSurviveStorageWithoutCollapsingIntoAScalar() {
        val converters = ProgramTypeConverters()

        assertEquals("30 / 30 / 45 seconds", "30,30,45", converters.toPerSetTargets(listOf(30, 30, 45)))
        assertEquals(listOf(30, 30, 45), converters.fromPerSetTargets("30,30,45"))
        assertEquals("45,45,45", converters.toPerSetTargets(listOf(45, 45, 45)))
        assertNotEquals(
            "the middle set of 30 is not the prescription",
            "30",
            converters.toPerSetTargets(listOf(30, 30, 45))
        )
    }

    @Test
    fun aStoredTargetListWrittenByAnEarlierBuildStaysReadable() {
        val converters = ProgramTypeConverters()

        // Read back from constant literals, not from values this build produced: this is the read an
        // upgraded device performs on rows an earlier build wrote.
        assertEquals(listOf(12, 10, 8, 6), converters.fromPerSetTargets("12,10,8,6"))
        assertEquals(listOf(30, 30, 45), converters.fromPerSetTargets("30,30,45"))
        assertEquals(listOf(8), converters.fromPerSetTargets("8"))
        assertThrows(IllegalArgumentException::class.java) { converters.fromPerSetTargets("12,,8") }
        assertThrows(IllegalArgumentException::class.java) { converters.fromPerSetTargets("12,x,8") }
    }

    @Test
    fun weekdaySetsAreStoredInOneDeterministicOrder() {
        val converters = ProgramTypeConverters()

        assertEquals(
            "MONDAY,WEDNESDAY,FRIDAY",
            converters.toScheduleWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        )
        assertEquals(
            "the same set inserted in another order stores the same bytes",
            converters.toScheduleWeekdays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
            converters.toScheduleWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        )
        assertEquals(
            "and the day names are the platform's own, not ordinals",
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            converters.fromScheduleWeekdays(converters.toScheduleWeekdays(
                setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
            ))
        )
        assertEquals("SUNDAY", converters.toScheduleWeekdays(setOf(DayOfWeek.SUNDAY)))
        assertEquals(emptySet<DayOfWeek>(), converters.fromScheduleWeekdays(""))
    }

    @Test
    fun capturedAdjustmentIdsKeepTheirOrderAndTheirEmptiness() {
        val converters = ProgramTypeConverters()

        assertEquals(
            "adjustments are recorded in application order",
            "adjustment-2,adjustment-1",
            converters.toAppliedAdjustmentIds(listOf("adjustment-2", "adjustment-1"))
        )
        assertEquals(
            listOf("adjustment-2", "adjustment-1"),
            converters.fromAppliedAdjustmentIds("adjustment-2,adjustment-1")
        )
        assertEquals(
            "a workout with no adaptive change stores an empty capture, not a null",
            "",
            converters.toAppliedAdjustmentIds(emptyList())
        )
        assertEquals(emptyList<String>(), converters.fromAppliedAdjustmentIds(""))
    }
}
