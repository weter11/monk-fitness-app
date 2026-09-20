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
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.data.mapper.revisionDomain
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.generated.GenerationPolicy
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
import java.time.Instant

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

    /**
     * Every statement one migration executes, in order — the production migration object, driven through
     * a proxy that records what a device's SQLite would be handed.
     */
    private fun recordStatements(migration: androidx.room.migration.Migration): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        migration.migrate(database)
        return statements
    }

    /** Every statement the version-7 → version-8 migration executes, in order. */
    private fun migrationStatements(): List<String> = recordStatements(AppDatabase.MIGRATION_7_8)

    /** Every statement the version-8 → version-9 additive migration executes, in order. */
    private fun additiveStatements(): List<String> = recordStatements(AppDatabase.MIGRATION_8_9)

    /** The statements of the second additive step — the Goal/Focus columns of §8. */
    private fun focusStatements(): List<String> = recordStatements(AppDatabase.MIGRATION_9_10)

    /** Every additive statement the deployed chain executes, in chain order. */
    /** Every statement the version-10 → version-11 step executes: §30 step 12's window bookkeeping. */
    private fun windowStatements(): List<String> = recordStatements(AppDatabase.MIGRATION_10_11)

    private fun allAdditiveStatements(): List<String> =
        additiveStatements() + focusStatements() + windowStatements()

    /** The version the database declares, read from the source that declares it. */
    private fun currentVersion(): Int =
        Regex("version = (\\d+)").find(databaseSource)!!.groupValues[1].toInt()

    /** Every migration the database declares, as name → (from, to), read from its own source. */
    private fun declaredMigrations(): List<Pair<String, Pair<Int, Int>>> =
        Regex("MIGRATION_(\\d+)_(\\d+) = object : Migration\\((\\d+), (\\d+)\\)")
            .findAll(databaseSource)
            .map { match ->
                "MIGRATION_${match.groupValues[1]}_${match.groupValues[2]}" to
                    (match.groupValues[3].toInt() to match.groupValues[4].toInt())
            }
            .toList()

    /** The migration variables the `addMigrations(...)` call names, in the order it names them. */
    private fun registeredMigrationNames(): List<String> =
        Regex("addMigrations\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(databaseSource)!!
            .groupValues[1]
            .let { list -> Regex("(MIGRATION_\\d+_\\d+)").findAll(list).map { it.groupValues[1] }.toList() }

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
            "the database declares the five **retained global** entities and then the target ones. The " +
                "ten shipped Program/Stage-1 entities this used to list are gone because " +
                "`MIGRATION_11_12` drops their tables (§30 step 15) — none of them was renamed into a " +
                "target entity, and no target entity was added to stand in for one",
            listOf(
                "PostureSessionProgress",
                "BodyWeightEntry",
                "MealCycle",
                "MealEntity",
                "ShoppingItemEntity"
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
    fun theDatabaseMovesToTheCurrentVersionThroughOneUnbrokenRegisteredChain() {
        val declared = declaredMigrations()

        assertEquals(
            "every migration the database declares is registered, and nothing else is",
            declared.map { it.first },
            registeredMigrationNames().distinct()
        )
        assertEquals(
            "the registered chain starts at the first version and runs one step at a time to the " +
                "declared version, so no device can be left with no path to the current schema",
            (1 until currentVersion()).toList(),
            declared.map { it.second.first }.sorted()
        )
        assertEquals(
            "and every step ends where the next one begins",
            (2..currentVersion()).toList(),
            declared.map { it.second.second }.sorted()
        )
        assertEquals(
            "each migration's own name is the step it performs",
            declared.map { (name, pair) -> name == "MIGRATION_${pair.first}_${pair.second}" },
            List(declared.size) { true }
        )
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, AppDatabase.MIGRATION_7_8.endVersion)
        assertEquals(
            "the schedule-frequency correction is the step after the target schema",
            8,
            AppDatabase.MIGRATION_8_9.startVersion
        )
        assertEquals(9, AppDatabase.MIGRATION_8_9.endVersion)
        assertEquals(
            "the Goal/Focus correction is the step after that one (§8)",
            9,
            AppDatabase.MIGRATION_9_10.startVersion
        )
        assertEquals(10, AppDatabase.MIGRATION_9_10.endVersion)
        assertEquals(
            "the adaptive window bookkeeping is the step after that one (§15, §30 step 12)",
            10,
            AppDatabase.MIGRATION_10_11.startVersion
        )
        assertEquals(11, AppDatabase.MIGRATION_10_11.endVersion)
        assertEquals(
            "§30 step 15's retirement is the step after that one: it drops the retired tables and " +
                "renames the retained track's row identity",
            11,
            AppDatabase.MIGRATION_11_12.startVersion
        )
        assertEquals(12, AppDatabase.MIGRATION_11_12.endVersion)
        assertEquals(
            "and the declared version is where the chain ends",
            AppDatabase.MIGRATION_11_12.endVersion,
            currentVersion()
        )
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

    // ---- the additive correction ------------------------------------------------------------------

    @Test
    fun theAdditiveMigrationAddsExactlyTheDeclaredColumnsAndNothingElse() {
        val statements = additiveStatements()

        assertEquals(
            "the correction is the statements the contract derives, in order",
            normalized(ProgramSchemaFixture.expectedAdditiveStatements("MIGRATION_8_9")),
            normalized(statements)
        )
        assertEquals(
            "one statement per declared addition",
            ProgramSchemaFixture.ADDITIVE_STEPS.first { it.first == "MIGRATION_8_9" }
                .second.values.sumOf { it.size },
            statements.size
        )
        for (statement in statements) {
            assertTrue(
                "the correction only ever adds a column: $statement",
                statement.startsWith("ALTER TABLE `program_revision` ADD COLUMN ")
            )
        }
        assertTrue(
            "every statement names the table it corrects and no other: $statements",
            statements.all { statement ->
                statement.contains("`program_revision`") &&
                    ProgramSchemaFixture.TABLES.filterNot { it == "program_revision" }
                        .none { other -> statement.contains("`$other`") }
            }
        )
        assertTrue(
            "no statement creates, drops, rewrites, reinterprets or renames anything: $statements",
            statements.none {
                Regex("\\b(CREATE|DROP|DELETE|INSERT|UPDATE|RENAME)\\b").containsMatchIn(it)
            }
        )
        assertTrue(
            "no legacy table is named: $statements",
            statements.none { statement ->
                listOf("`set_log`", "`family_progression_state`", "`adaptive_decision_record`")
                    .any { statement.contains(it) }
            }
        )
    }

    @Test
    fun theAddedFrequencyColumnIsNullableAndInventsNoDefault() {
        val statements = additiveStatements()
        val column = ProgramSchemaFixture
            .columnsAddedBy("MIGRATION_8_9", "program_revision")
            .single()

        assertEquals("scheduleSessionsPerWeek", column.name)
        assertEquals("INTEGER", column.type)
        assertTrue(
            "the column is nullable, because only one of the two schedule forms has a frequency (§20)",
            column.nullable
        )
        assertTrue(
            "and it carries no default: a default would state a weekly frequency the user never " +
                "chose, for rows whose schedule has none at all — $statements",
            statements.none { it.contains("DEFAULT", ignoreCase = true) }
        )
        assertTrue(
            "nor may it be NOT NULL: the fixed-weekday form has nothing to put there",
            statements.none { it.contains("NOT NULL", ignoreCase = true) }
        )
    }

    @Test
    fun theFrequencySurvivesTheDeployedMigrationChainAndReconstructsTheDomainSchedule() {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)

        assertEquals(
            "the column is on the table a device holds after the chain",
            listOf("scheduleSessionsPerWeek"),
            database.columnNames("program_revision").filter { it == "scheduleSessionsPerWeek" }
        )
        assertEquals(
            "with the affinity the entity stores an Int in",
            "INTEGER",
            database.columnType("program_revision", "scheduleSessionsPerWeek")
        )
        assertEquals(
            "and nullable, so the fixed-weekday form stores nothing",
            "0",
            database.rows("SELECT * FROM pragma_table_info('program_revision')")
                .single { it["name"] == "scheduleSessionsPerWeek" }["notnull"]
        )

        database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, " +
                "`lifecycleStatus`, `currentRevisionId`, `createdAt`, `updatedAt`, " +
                "`plannedStartDate`, `actualStartDate`, `archivedAt`) VALUES ('program-1', 'Program', " +
                "'', 'USER', 'NOT_STARTED', 'revision-1', 1700000000000, 1700000000000, NULL, NULL, NULL)"
        )

        ProgramRevisionEntity.SESSIONS_PER_WEEK_RANGE.forEach { sessionsPerWeek ->
            val domain = ProgramSchedule.FlexiblePerWeek(sessionsPerWeek)
            val revisionId = "revision-$sessionsPerWeek"
            database.exec(
                "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, " +
                    "`mode`, `durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, " +
                    "`scheduleSessionsPerWeek`, `createdAt`) VALUES ('$revisionId', 'program-1', " +
                    "$sessionsPerWeek, 'MANUAL', 'FIXED_DAYS', 30, 'FLEXIBLE_PER_WEEK', NULL, " +
                    "$sessionsPerWeek, 1700000000000)"
            )

            val stored = database.scalar(
                "SELECT `scheduleSessionsPerWeek` FROM `program_revision` WHERE `revisionId` = '$revisionId'"
            )

            assertEquals(
                "the frequency the domain carries is the number the row holds",
                domain.sessionsPerWeek.toString(),
                stored
            )
            assertEquals(
                "and the row reconstructs the very same schedule — nothing lost, nothing invented",
                domain,
                ProgramSchedule.FlexiblePerWeek(stored!!.toInt())
            )
        }

        database.exec(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, " +
                "`scheduleSessionsPerWeek`, `createdAt`) VALUES ('revision-fixed', 'program-1', " +
                "99, 'MANUAL', 'FIXED_DAYS', 30, 'FIXED_WEEKDAYS', 'MONDAY,WEDNESDAY', NULL, " +
                "1700000000000)"
        )
        assertEquals(
            "a fixed-weekday schedule stores no frequency at all: the column stays empty rather than " +
                "being filled with a plausible number",
            listOf<String?>(null),
            database.strings(
                "SELECT `scheduleSessionsPerWeek` FROM `program_revision` " +
                    "WHERE `revisionId` = 'revision-fixed'"
            )
        )
        assertEquals(
            "and its weekdays are the schedule it does store",
            listOf("MONDAY,WEDNESDAY"),
            database.strings(
                "SELECT `scheduleWeekdays` FROM `program_revision` WHERE `revisionId` = 'revision-fixed'"
            )
        )
    }

    @Test
    fun theGoalFocusColumnsAreNullableTokensThatInventNoGoal() {
        val statements = focusStatements()

        assertEquals(
            "the correction is the statements the contract derives, in order",
            normalized(ProgramSchemaFixture.expectedAdditiveStatements("MIGRATION_9_10")),
            normalized(statements)
        )
        assertEquals("one statement per declared column", 2, statements.size)
        assertTrue(
            "each one appends a column to the revised table and touches nothing else: $statements",
            statements.all { it.startsWith("ALTER TABLE `program_revision` ADD COLUMN ") }
        )
        assertTrue(
            "neither carries a default: a default would state a goal the user never chose, for rows " +
                "written before Goals & Focus existed — $statements",
            statements.none { it.contains("DEFAULT", ignoreCase = true) }
        )
        assertTrue(
            "and neither may be NOT NULL: SQLite cannot append a NOT NULL column without one, and " +
                "the absence is what the mapper reads as BALANCED — $statements",
            statements.none { it.contains("NOT NULL", ignoreCase = true) }
        )

        val columns = ProgramSchemaFixture.columnsAddedBy("MIGRATION_9_10", "program_revision")
        assertEquals(
            "the two columns are the goal and the focuses it states",
            listOf("focusGoal", "focusTargets"),
            columns.map { it.name }
        )
        assertTrue(
            "both store TEXT — a vocabulary token and a converted token list — and both are nullable",
            columns.all { it.type == "TEXT" && it.nullable }
        )

        // The stored vocabulary is the domain's own, asserted rather than eyeballed, exactly as the
        // duration and schedule discriminators are.
        assertEquals(
            listOf("BALANCED", "FOCUSED", "CUSTOM"),
            Goal.entries.map { it.name }
        )
        assertEquals(ProgramRevisionEntity.BALANCED_GOAL, Goal.BALANCED.name)
        assertEquals(ProgramRevisionEntity.FOCUSED, Goal.FOCUSED.name)
        assertEquals(ProgramRevisionEntity.CUSTOM, Goal.CUSTOM.name)
        assertEquals(
            "and the policy's ceiling on secondary focuses is §8's own 0–2",
            2,
            GenerationPolicy.MAX_SECONDARY_FOCUSES
        )
    }

    @Test
    fun theAdaptiveWindowBookkeepingIsAppendedToTheFamilyStateTableAndInventsNoCount() {
        val statements = windowStatements()

        assertEquals(
            "the step is the statements the contract derives, in order",
            normalized(ProgramSchemaFixture.expectedAdditiveStatements("MIGRATION_10_11")),
            normalized(statements)
        )
        assertEquals("one statement per declared column", 6, statements.size)
        assertEquals(
            "the window bookkeeping is appended to the family-state table and the reason token to the " +
                "decision record, in that order",
            listOf(5, 1),
            listOf(
                statements.count {
                    it.startsWith("ALTER TABLE `program_family_progression_state` ADD COLUMN ")
                },
                statements.count {
                    it.startsWith("ALTER TABLE `program_adaptive_decision_record` ADD COLUMN ")
                }
            )
        )
        assertTrue(
            "every statement is an additive column and touches nothing else: $statements",
            statements.all { it.contains(" ADD COLUMN ") && !it.contains("DROP") }
        )
        assertTrue(
            "none carries a default: a default would state a count for rows written before the " +
                "adaptive stage stored any, and it is the *absence* the mapper reads — $statements",
            statements.none { it.contains("DEFAULT", ignoreCase = true) }
        )
        assertTrue(
            "and none may be NOT NULL: SQLite cannot append one without a default, and " +
                "`qualifyingWindowsSinceLastChange` has to stay absent for a family that has never " +
                "changed level — $statements",
            statements.none { it.contains("NOT NULL", ignoreCase = true) }
        )

        val columns = ProgramSchemaFixture
            .columnsAddedBy("MIGRATION_10_11", "program_family_progression_state")
        assertEquals(
            "the five columns are the engine's own window facts, in the entity's declaration order",
            ProgramSchemaFixture.WINDOW_BOOKKEEPING_COLUMNS,
            columns.map { it.name }
        )
        assertTrue(
            "every one of them is an INTEGER count and every one is nullable",
            columns.all { it.type == "INTEGER" && it.nullable }
        )
    }

    @Test
    fun theAdaptiveWindowBookkeepingSurvivesTheChainAndReconstructsTheDomainState() {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
        database.migrate(AppDatabase.MIGRATION_9_10)

        // The version-10 row set a device holds: a Program, its revision, and the family state the
        // adaptive stage had written before it could count anything.
        database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, `lifecycleStatus`, " +
                "`currentRevisionId`, `createdAt`, `updatedAt`, `plannedStartDate`, `actualStartDate`, " +
                "`archivedAt`) VALUES ('program-10', 'Program', '', 'USER', 'NOT_STARTED', " +
                "'revision-10', 1700000000000, 1700000000000, NULL, NULL, NULL)"
        )
        database.exec(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, " +
                "`scheduleSessionsPerWeek`, `createdAt`) VALUES ('revision-10', 'program-10', 1, " +
                "'GENERATED', 'INDEFINITE', NULL, 'FLEXIBLE_PER_WEEK', NULL, 3, 1700000000000)"
        )
        database.exec(
            "INSERT INTO `program_family_progression_state` (`revisionId`, `familyId`, " +
                "`progressionLevel`, `adaptationState`, `currentExerciseId`, `updatedAt`) VALUES " +
                "('revision-10', 'push-family', 3, 'PROGRESS', 'pushup', 1700000000000)"
        )
        val before = database.rows(
            "SELECT `revisionId`, `familyId`, `progressionLevel`, `adaptationState`, " +
                "`currentExerciseId`, `updatedAt` FROM `program_family_progression_state`"
        ).single()

        database.migrate(AppDatabase.MIGRATION_10_11)

        val names = ProgramSchemaFixture.WINDOW_BOOKKEEPING_COLUMNS
        assertEquals(
            "the columns are on the table a device holds after the chain",
            names.sorted(),
            database.columnNames("program_family_progression_state").filter { it in names.toSet() }.sorted()
        )
        assertTrue(
            "with the affinity the entity stores a count in",
            names.all { database.columnType("program_family_progression_state", it) == "INTEGER" }
        )
        assertTrue(
            "and nullable, so a row written before this step stores no count at all",
            names.all { name ->
                database.rows("SELECT * FROM pragma_table_info('program_family_progression_state')")
                    .single { it["name"] == name }["notnull"] == "0"
            }
        )

        val stored = database.rows(
            "SELECT * FROM `program_family_progression_state` WHERE `familyId` = 'push-family'"
        ).single()

        assertEquals(
            "every value the row already held survives the step unchanged",
            listOf(
                before["revisionId"], before["familyId"], before["progressionLevel"],
                before["adaptationState"], before["currentExerciseId"], before["updatedAt"]
            ),
            listOf(
                stored["revisionId"], stored["familyId"], stored["progressionLevel"],
                stored["adaptationState"], stored["currentExerciseId"], stored["updatedAt"]
            )
        )
        assertEquals(
            "and its counts are absent rather than zero",
            names.map { null },
            names.map { stored[it] }
        )
        assertEquals(
            "which the mapper reads as the count a family with no preceding window has — while the " +
                "cooldown position stays absent, because \"never changed level\" is not \"0 windows ago\"",
            listOf(3, AdaptiveState.PROGRESS, "pushup", 0, 0, 0, null, 0),
            storedRow(stored).let { state ->
                listOf(
                    state.progressionLevel,
                    state.adaptationState,
                    state.currentExerciseId,
                    state.precedingProgressQualifyingWindows,
                    state.precedingRegressQualifyingWindows,
                    state.precedingRecoveryQualifyingWindows,
                    state.qualifyingWindowsSinceLastChange,
                    state.recoveryQualifyingWindows
                )
            }
        )

        // A window the adaptive stage evaluated writes the counts it advanced; they survive the read.
        database.exec(
            "UPDATE `program_family_progression_state` SET " +
                "`precedingProgressQualifyingWindows` = 2, `precedingRegressQualifyingWindows` = 0, " +
                "`precedingRecoveryQualifyingWindows` = 0, `qualifyingWindowsSinceLastChange` = 1, " +
                "`recoveryQualifyingWindows` = 0 WHERE `familyId` = 'push-family'"
        )
        val written = storedRow(
            database.rows("SELECT * FROM `program_family_progression_state`").single()
        )
        assertEquals(
            "the counts a window advanced come back exactly as they were written",
            listOf(2, 0, 0, 1, 0),
            listOf(
                written.precedingProgressQualifyingWindows,
                written.precedingRegressQualifyingWindows,
                written.precedingRecoveryQualifyingWindows,
                written.qualifyingWindowsSinceLastChange,
                written.recoveryQualifyingWindows
            )
        )
    }

    /** One stored family-state row as the production mapper reads it. */
    private fun storedRow(row: Map<String, String?>) = FamilyProgressionStateEntity(
        revisionId = row["revisionId"]!!,
        familyId = row["familyId"]!!,
        progressionLevel = row["progressionLevel"]!!.toInt(),
        adaptationState = row["adaptationState"]!!,
        currentExerciseId = row["currentExerciseId"],
        updatedAt = row["updatedAt"]!!.toLong(),
        precedingProgressQualifyingWindows = row["precedingProgressQualifyingWindows"]?.toInt(),
        precedingRegressQualifyingWindows = row["precedingRegressQualifyingWindows"]?.toInt(),
        precedingRecoveryQualifyingWindows = row["precedingRecoveryQualifyingWindows"]?.toInt(),
        qualifyingWindowsSinceLastChange = row["qualifyingWindowsSinceLastChange"]?.toInt(),
        recoveryQualifyingWindows = row["recoveryQualifyingWindows"]?.toInt()
    ).toDomain()

    @Test
    fun theGoalFocusConfigurationSurvivesTheDeployedChainAndReconstructsTheDomainValue() {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
        database.migrate(AppDatabase.MIGRATION_9_10)

        assertEquals(
            "the columns are on the table a device holds after the chain",
            listOf("focusGoal", "focusTargets"),
            database.columnNames("program_revision").filter { it in setOf("focusGoal", "focusTargets") }
        )
        assertEquals(
            "with the affinity the entity stores text in",
            listOf("TEXT", "TEXT"),
            listOf(
                database.columnType("program_revision", "focusGoal"),
                database.columnType("program_revision", "focusTargets")
            )
        )
        assertEquals(
            "and nullable, so a revision that states no goal stores nothing",
            listOf("0", "0"),
            listOf("focusGoal", "focusTargets").map { column ->
                database.rows("SELECT * FROM pragma_table_info('program_revision')")
                    .single { it["name"] == column }["notnull"]
            }
        )

        database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, " +
                "`lifecycleStatus`, `currentRevisionId`, `createdAt`, `updatedAt`, " +
                "`plannedStartDate`, `actualStartDate`, `archivedAt`) VALUES ('program-1', 'Program', " +
                "'' , 'USER', 'NOT_STARTED', 'revision-1', 1700000000000, 1700000000000, NULL, NULL, NULL)"
        )

        // One row per configuration form, each written as raw SQL — what a device that upgraded
        // holds, and what the mapper has to read.
        val configurations = listOf(
            StoredConfiguration("revision-1", goal = null, targets = null, FocusPlan.Balanced),
            StoredConfiguration(
                "revision-2",
                goal = "FOCUSED",
                targets = "PUSH,PULL",
                FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL))
            ),
            StoredConfiguration(
                "revision-3",
                goal = "CUSTOM",
                targets = "PUSH:60,LEGS:40",
                FocusPlan.custom(
                    listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.LEGS, 40))
                )
            ),
            StoredConfiguration("revision-4", goal = "BALANCED", targets = null, FocusPlan.Balanced)
        )
        configurations.forEachIndexed { index, configuration ->
            val goalValue = configuration.goal?.let { "'$it'" } ?: "NULL"
            val targetValue = configuration.targets?.let { "'$it'" } ?: "NULL"
            database.exec(
                "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, " +
                    "`mode`, `durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, " +
                    "`createdAt`, `focusGoal`, `focusTargets`) VALUES ('${configuration.revisionId}', " +
                    "'program-1', ${index + 1}, 'GENERATED', 'INDEFINITE', NULL, " +
                    "'FLEXIBLE_PER_WEEK', NULL, 1700000000000, $goalValue, $targetValue)"
            )
        }

        val expected = mapOf(
            "revision-1" to FocusPlan.Balanced,
            "revision-2" to FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)),
            "revision-3" to FocusPlan.custom(
                listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.LEGS, 40))
            ),
            "revision-4" to FocusPlan.Balanced
        )

        for (configuration in configurations) {
            // The stored values, read back out of the engine — the entity below is only the carrier
            // the production mapper reads, so the column names and affinities are exercised too.
            val stored = database.rows(
                "SELECT `focusGoal`, `focusTargets` FROM `program_revision` " +
                    "WHERE `revisionId` = '${configuration.revisionId}'"
            ).single()

            assertEquals(
                "the row holds exactly the bytes the fixture wrote: a configuration that states " +
                    "nothing stores nothing, and one that states a goal stores the goal",
                listOf(configuration.goal, configuration.targets),
                listOf(stored["focusGoal"], stored["focusTargets"])
            )
            assertEquals(
                "and the loaded row reconstructs the very same configuration — nothing lost, " +
                    "nothing invented",
                configuration.focus,
                revisionDomain(
                    revision = revisionEntity(
                        revisionId = configuration.revisionId,
                        focusGoal = stored["focusGoal"],
                        focusTargets = stored["focusTargets"]
                    ),
                    dayRows = listOf(dayRow(configuration.revisionId)),
                    exerciseRows = listOf(exerciseRow(configuration.revisionId))
                ).focus
            )
        }
    }

    /**
     * A stored configuration the domain refuses: the bytes are written as raw SQL, so the refusal
     * under test is the **load**'s, not a constructor's.
     */
    @Test
    fun aStoredGoalFocusConfigurationTheDomainRefusesIsRefusedOnLoad() {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
        database.migrate(AppDatabase.MIGRATION_9_10)
        val tableColumns =
            "`revisionId`, `programId`, `revisionNumber`, `mode`, `durationType`, `durationDays`, " +
                "`scheduleType`, `scheduleWeekdays`, `createdAt`, `focusGoal`, `focusTargets`"
        database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, " +
                "`lifecycleStatus`, `currentRevisionId`, `createdAt`, `updatedAt`, " +
                "`plannedStartDate`, `actualStartDate`, `archivedAt`) VALUES ('program-1', 'Program', " +
                "'' , 'USER', 'NOT_STARTED', 'revision-sums', 1700000000000, 1700000000000, " +
                "NULL, NULL, NULL)"
        )

        // Three rows the *columns* accept and the domain does not: shares that do not sum to 100%, a
        // share token without its percentage, and a goal that is not a Goal.
        val malformed = listOf(
            MalformedConfiguration("revision-sums", "CUSTOM", "PUSH:50,LEGS:30", "must sum to 100%"),
            MalformedConfiguration("revision-token", "CUSTOM", "PUSH", "NAME"),
            MalformedConfiguration("revision-goal", "BALANCEDPLUS", null, "focusGoal")
        )
        malformed.forEachIndexed { index, (revisionId, goal, targets, _) ->
            val targetValue = targets?.let { "'$it'" } ?: "NULL"
            database.exec(
                "INSERT INTO `program_revision` ($tableColumns) VALUES ('$revisionId', 'program-1', " +
                    "${index + 1}, 'GENERATED', 'INDEFINITE', NULL, 'FLEXIBLE_PER_WEEK', NULL, " +
                    "1700000000000, '$goal', $targetValue)"
            )
        }

        malformed.forEach { (revisionId, storedGoal, storedTargets, expectedMessage) ->
            val stored = database.rows(
                "SELECT `focusGoal`, `focusTargets` FROM `program_revision` " +
                    "WHERE `revisionId` = '$revisionId'"
            ).single()
            assertEquals(
                "the engine really does hold the malformed bytes",
                listOf(storedGoal, storedTargets),
                listOf(stored["focusGoal"], stored["focusTargets"])
            )

            val refusal = assertThrows(IllegalArgumentException::class.java) {
                revisionDomain(
                    revision = revisionEntity(
                        revisionId = revisionId,
                        focusGoal = stored["focusGoal"],
                        focusTargets = stored["focusTargets"]
                    ),
                    dayRows = listOf(dayRow(revisionId)),
                    exerciseRows = listOf(exerciseRow(revisionId))
                )
            }
            assertTrue(
                "a stored configuration the domain cannot state is invalid persisted data and fails " +
                    "loudly on load, naming what it read: ${refusal.message}",
                refusal.message!!.contains(expectedMessage)
            )
        }
    }

    /** One malformed stored configuration: the bytes, and what the refusal must name. */
    private data class MalformedConfiguration(
        val revisionId: String,
        val goal: String,
        val targets: String?,
        val expectedMessage: String
    )

    /** One stored configuration fixture row: the bytes, and the domain value they must mean. */
    private data class StoredConfiguration(
        val revisionId: String,
        val goal: String?,
        val targets: String?,
        val focus: FocusPlan
    )

    /** The stored revision row a mapper reads, carrying the values the engine returned. */
    private fun revisionEntity(
        revisionId: String,
        focusGoal: String?,
        focusTargets: String?
    ): ProgramRevisionEntity = ProgramRevisionEntity(
        revisionId = revisionId,
        programId = "program-1",
        revisionNumber = 1,
        mode = "GENERATED",
        durationType = "INDEFINITE",
        scheduleType = "FLEXIBLE_PER_WEEK",
        scheduleSessionsPerWeek = 3,
        createdAt = 1700000000000,
        focusGoal = focusGoal,
        focusTargets = focusTargets
    )

    /** One plan day row of [revisionId] — a revision carries a plan, so the mapper needs one. */
    private fun dayRow(revisionId: String): ProgramDayEntity = ProgramDayEntity(
        programDayId = "$revisionId-day-1",
        revisionId = revisionId,
        position = 1,
        type = "TRAINING"
    )

    /** One plan element row of [revisionId]. */
    private fun exerciseRow(revisionId: String): ProgramExerciseEntity = ProgramExerciseEntity(
        programExerciseId = "$revisionId-element-1",
        programDayId = "$revisionId-day-1",
        position = 1,
        exerciseId = "pushup",
        prescriptionDimension = "REP_BASED",
        perSetTargets = listOf(10),
        origin = "GENERATED",
        isPinned = false
    )

    @Test
    fun theMigratedRevisionTableIsTheTableTheContractDescribes() {
        // Room validates a migrated table against the DDL it generates for the entity on open, so the
        // claim that matters is: the table a v9 device ends up with **is** the DDL the entity emits.
        // `expectedCurrentTableDdl` is that DDL, derived from the same contract that
        // `everyTargetEntityDeclaresItsColumnsInTheStoredOrder` compares the entity against — so
        // "a fresh install and an upgrade agree" is pinned by the two sides being the same statement,
        // not by executing the same chain twice.
        val upgraded = SqliteTestDatabase.inMemory()
        upgraded.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        upgraded.migrate(AppDatabase.MIGRATION_7_8)
        upgraded.migrate(AppDatabase.MIGRATION_8_9)
        upgraded.migrate(AppDatabase.MIGRATION_9_10)

        assertEquals(
            "the revision table the chain leaves behind is the one the contract describes: the " +
                "version-8 columns in declaration order, then the appended ones",
            ProgramSchemaFixture.normalized(
                ProgramSchemaFixture.expectedCurrentTableDdl("program_revision")
            ).replace("IF NOT EXISTS ", ""),
            ProgramSchemaFixture.normalized(
                upgraded.masterSql().getValue("program_revision")
            ).replace("IF NOT EXISTS ", "")
        )
        assertEquals(
            "its identity is the entity's own primary key",
            listOf("revisionId"),
            upgraded.primaryKey("program_revision")
        )
        assertEquals(
            "the columns the chain appends are exactly the ones the contract adds, in that order",
            ProgramSchemaFixture.ADDED_COLUMNS.getValue("program_revision").map { it.name },
            upgraded.columnNames("program_revision")
                .filterNot { name ->
                    ProgramSchemaFixture.COLUMNS.getValue("program_revision").any { it.name == name }
                }
        )
        assertEquals(
            "and the foreign key still cascades with the Program, so an upgrade does not reshape " +
                "ownership",
            listOf("programId -> program.programId CASCADE"),
            upgraded.foreignKeys("program_revision").map {
                "${it.childColumn} -> ${it.parentTable}.${it.parentColumn} ${it.onDelete}"
            }
        )
    }

    @Test
    fun theEntityStoresExactlyTheFrequenciesTheDomainScheduleAccepts() {
        assertEquals(
            "the stored range is exactly the range the domain schedule accepts, not a second decision",
            (0..10).filter { runCatching { ProgramSchedule.FlexiblePerWeek(it) }.isSuccess },
            ProgramRevisionEntity.SESSIONS_PER_WEEK_RANGE.toList()
        )

        for (frequency in 0..8) {
            val domainAccepts = runCatching { ProgramSchedule.FlexiblePerWeek(frequency) }.isSuccess
            val entityAccepts = runCatching { flexibleRevision(frequency) }.isSuccess

            assertEquals(
                "frequency $frequency: the entity accepts exactly what the domain accepts",
                domainAccepts,
                entityAccepts
            )
        }

        val withoutAFrequency = assertThrows(IllegalArgumentException::class.java) {
            flexibleRevision(null)
        }
        assertTrue(
            "a flexible-frequency revision that cannot say how many sessions a week it holds is not a " +
                "revision of that schedule — §20's frequency is required for this form, and the domain " +
                "type cannot even express its absence: ${withoutAFrequency.message}",
            withoutAFrequency.message!!.contains("sessions-per-week frequency")
        )

        val frequencyOnAFixedSchedule = assertThrows(IllegalArgumentException::class.java) {
            flexibleRevision(3).copy(scheduleType = "FIXED_WEEKDAYS", scheduleWeekdays = setOf(DayOfWeek.MONDAY))
        }
        assertTrue(
            "and the fixed-weekday form has no frequency to store: ${frequencyOnAFixedSchedule.message}",
            frequencyOnAFixedSchedule.message!!.contains("no frequency to store")
        )
    }

    /** One `FLEXIBLE_PER_WEEK` revision row with [sessionsPerWeek] — the guard's own subject. */
    private fun flexibleRevision(sessionsPerWeek: Int?) = ProgramRevisionEntity(
        revisionId = "revision-flex",
        programId = "program-1",
        revisionNumber = 1,
        mode = "MANUAL",
        durationType = "FIXED_DAYS",
        durationDays = 30,
        scheduleType = ProgramRevisionEntity.FLEXIBLE_PER_WEEK,
        scheduleWeekdays = null,
        scheduleSessionsPerWeek = sessionsPerWeek,
        createdAt = 1_700_000_000_000L
    )

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
    fun everyTargetEntityDeclaresItsColumnsInTheStoredOrder() {
        for ((entity, table) in ProgramSchemaFixture.ENTITIES) {
            val declared = Class.forName("com.monkfitness.app.data.model.$entity")
                .declaredFields
                .filterNot { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) || field.name.startsWith("$")
                }
                .map { it.name }

            assertEquals(
                "$entity declares its columns in the order `$table` stores them — which is what " +
                    "makes a freshly created database and an upgraded one hold the same table definition " +
                    "(an added column is appended, so the entity declares it last)",
                ProgramSchemaFixture.columnsNow(table).map { it.name },
                declared
            )
        }
    }

    @Test
    fun everyDeclaredColumnIsStoredInTheStatementThatIntroducesIt() {
        val added = ProgramSchemaFixture.normalized(allAdditiveStatements().joinToString(" "))

        for (table in ProgramSchemaFixture.TABLES) {
            val created = ProgramSchemaFixture.normalized(statementFor(table))
            for (column in ProgramSchemaFixture.COLUMNS.getValue(table)) {
                val expected =
                    "`${column.name}` ${column.type}${if (column.nullable) "" else " NOT NULL"}"
                assertTrue(
                    "`$table`.`${column.name}` is declared as `$expected` in the statement that " +
                        "creates the table",
                    created.contains(expected)
                )
            }
            for (column in ProgramSchemaFixture.ADDED_COLUMNS[table].orEmpty()) {
                val expected = "`${column.name}` ${column.type}"
                assertTrue(
                    "`$table`.`${column.name}` is declared as `$expected` in the statement that " +
                        "adds it — each step's statements are its own contract, so a column a later " +
                        "step adds is never expected of an earlier one",
                    added.contains(expected)
                )
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
                val declared = ProgramSchemaFixture.columnsNow(table).single { it.name == column }
                assertEquals("`$table`.`$column` stores a token, not a number", "TEXT", declared.type)
            }
        }
    }

    @Test
    fun everyCollectionColumnIsStoredAsASingleDeterministicTextValue() {
        for ((table, columns) in ProgramSchemaFixture.COLLECTION_COLUMNS) {
            for (column in columns) {
                val declared = ProgramSchemaFixture.columnsNow(table).single { it.name == column }
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
            scheduleSessionsPerWeek = 3,
            createdAt = 1_700_000_000_000L
        )
        assertEquals(30, revision.durationDays)
        assertEquals("a flexible schedule stores the frequency it runs at", 3, revision.scheduleSessionsPerWeek)

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

        val frequencyWithoutAValue = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(scheduleSessionsPerWeek = null)
        }
        assertTrue(
            "FLEXIBLE_PER_WEEK stores the frequency the Scheduler decided on: " +
                "${frequencyWithoutAValue.message}",
            frequencyWithoutAValue.message!!.contains("Scheduler")
        )

        val frequencyOutOfRange = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(scheduleSessionsPerWeek = 8)
        }
        assertTrue(
            "the stored frequency is a weekly count, within the range the domain accepts: " +
                "${frequencyOutOfRange.message}",
            frequencyOutOfRange.message!!.contains("1..7")
        )

        val frequencyOnAWeekdaySchedule = assertThrows(IllegalArgumentException::class.java) {
            revision.copy(scheduleType = "FIXED_WEEKDAYS", scheduleWeekdays = setOf(DayOfWeek.MONDAY))
        }
        assertTrue(
            "and the fixed-weekday form carries no frequency: ${frequencyOnAWeekdaySchedule.message}",
            frequencyOnAWeekdaySchedule.message!!.contains("no frequency to store")
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
