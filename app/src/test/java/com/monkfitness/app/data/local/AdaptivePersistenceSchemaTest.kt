package com.monkfitness.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.domain.adaptive.AdaptiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The deployable half of the adaptive persistence contract: the schema a version-6 device receives
 * when it upgrades, and the stored representation of the adaptive vocabulary.
 *
 * This repository has no Robolectric, no `androidx.test` and no instrumentation source set, so Room's
 * own migration test helpers are not available here. What is available is the migration itself, which
 * is a plain function over `SupportSQLiteDatabase` — an interface, so it can be driven on the JVM by a
 * recording proxy that captures exactly the statements a device would execute. The DDL is then
 * compared token-for-token against the SQL Room generates for the two entities, and the entity
 * registration and database version are read from the source that declares them. Between them these
 * checks prove what a migration test would: the upgrade is additive, it creates the schema Room
 * expects to find, no existing table is touched, and history cannot be rewritten through the DAO.
 */
class AdaptivePersistenceSchemaTest {

    private val mainSources = File("src/main/java/com/monkfitness/app").let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    /** Every statement one migration executes, in order. */
    private fun migrateRecording(): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_6_7.migrate(database)
        return statements
    }

    /**
     * The statement with every whitespace run collapsed, and whitespace around punctuation removed, so
     * that a multi-line DDL and Room's single-line DDL are comparable token for token.
     */
    private fun normalized(sql: String): String = sql
        .replace(Regex("\\s+"), " ")
        .replace("( ", "(")
        .replace(" )", ")")
        .replace(", ", ",")
        .trim()

    private fun statementsFor(table: String): String =
        migrateRecording().single { it.contains("`$table`") }

    // ---- the migration ---------------------------------------------------------------------------

    @Test
    fun theMigrationMovesTheDatabaseFromVersion6ToVersion7() {
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun theMigrationCreatesExactlyTheTwoAdaptiveTables() {
        val statements = migrateRecording()

        assertEquals("one statement per new table, and nothing else", 2, statements.size)
        assertEquals(
            listOf("adaptive_decision_record", "family_progression_state"),
            statements.map { it.substringAfter("CREATE TABLE IF NOT EXISTS `").substringBefore("`") }
                .sorted()
        )
        assertTrue(
            "every statement is a CREATE TABLE: $statements",
            statements.all { it.startsWith("CREATE TABLE IF NOT EXISTS `") }
        )
    }

    @Test
    fun theMigrationTouchesNoExistingTable() {
        val existingTables = listOf(
            "user_progress",
            "posture_session_progress",
            "set_log",
            "body_weight_log",
            "program_day_state",
            "meal_cycles",
            "meals",
            "shopping_items"
        )

        val statements = migrateRecording()
        for (table in existingTables) {
            assertFalse(
                "the upgrade must not read, rewrite or drop `$table`",
                statements.any { it.contains("`$table`") }
            )
        }
        for (keyword in listOf("DROP", "DELETE", "ALTER", "UPDATE", "INSERT", "RENAME")) {
            assertFalse(
                "the upgrade is additive and contains no $keyword statement",
                statements.any { Regex("\\b$keyword\\b").containsMatchIn(it.uppercase()) }
            )
        }
    }

    @Test
    fun theMigrationCreatesTheSchemaRoomExpectsForTheTwoEntities() {
        // Room validates a migrated schema against its own expectations when it opens the database, so
        // the hand-written DDL has to be the SQL Room generates for these entities. These two literals
        // are that SQL, read from the KSP-generated `createAllTables` for this build.
        val expectedStateTable =
            "CREATE TABLE IF NOT EXISTS `family_progression_state` (`familyId` TEXT NOT NULL, " +
                "`progressionLevel` INTEGER NOT NULL, `currentExerciseId` TEXT, `adaptationState` " +
                "TEXT NOT NULL, `precedingProgressQualifyingWindows` INTEGER NOT NULL, " +
                "`precedingRegressQualifyingWindows` INTEGER NOT NULL, `precedingHighRiskWindows` " +
                "INTEGER NOT NULL, `recoveryQualifyingSessions` INTEGER NOT NULL, " +
                "`eligibleSessionsSinceLastProgressionChange` INTEGER, `programRevision` INTEGER " +
                "NOT NULL, `updatedAt` INTEGER NOT NULL, `policyVersion` INTEGER NOT NULL, " +
                "PRIMARY KEY(`programRevision`, `familyId`))"
        val expectedRecordTable =
            "CREATE TABLE IF NOT EXISTS `adaptive_decision_record` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `familyId` TEXT NOT NULL, `programRevision` INTEGER NOT " +
                "NULL, `cycleNumber` INTEGER NOT NULL, `programDay` INTEGER NOT NULL, `timestamp` " +
                "INTEGER NOT NULL, `previousState` TEXT NOT NULL, `newState` TEXT NOT NULL, " +
                "`actions` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `policyVersion` INTEGER NOT NULL)"

        assertEquals(
            normalized(expectedStateTable),
            normalized(statementsFor("family_progression_state"))
        )
        assertEquals(
            normalized(expectedRecordTable),
            normalized(statementsFor("adaptive_decision_record"))
        )
    }

    @Test
    fun theCurrentStateIdentityIsUniqueInTheDatabaseItself() {
        val ddl = normalized(statementsFor("family_progression_state"))

        assertTrue(
            "the family-state identity is a database constraint, not a caller convention: $ddl",
            ddl.contains("PRIMARY KEY(`programRevision`,`familyId`)")
        )
        assertFalse(
            "and it is not cycle-scoped: a cycle rollover preserves the family's state",
            ddl.contains("cycleNumber")
        )
    }

    @Test
    fun theDecisionRecordIdentityIsDatabaseAssignedAndKeepsItsFamily() {
        val ddl = normalized(statementsFor("adaptive_decision_record"))

        assertTrue("the row identity is assigned by the database", ddl.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue("the audited family is stored, not dropped", ddl.contains("`familyId` TEXT NOT NULL"))
        assertTrue("the program revision is stored too", ddl.contains("`programRevision` INTEGER NOT NULL"))
    }

    // ---- the database declaration ------------------------------------------------------------------

    @Test
    fun theDatabaseDeclaresOnlyTheRetainedAndTheTargetSchema() {
        val source = File(mainSources, "data/local/AppDatabase.kt").readText()

        assertTrue(
            "the database version moves with the schema: 12 is §30 step 15, which retires the " +
                "shipped 56-day program's tables and the Stage-1 adaptive pair",
            source.contains("version = 12")
        )

        val registered = Regex("entities = \\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(source)!!
            .groupValues[1]
            .let { Regex("(\\w+)::class").findAll(it).map { match -> match.groupValues[1] }.toList() }

        assertEquals(
            "the five retained global entities and the fifteen target ones — and **nothing that stood " +
                "in for a retired table**. `UserProgress`, `SetLog`, `ProgramDayState`, " +
                "`FamilyProgressionState` and `AdaptiveDecisionRecord` are gone from the declaration " +
                "because `MIGRATION_11_12` drops them, not because a replacement was added",
            listOf(
                "PostureSessionProgress",
                "BodyWeightEntry",
                "MealCycle",
                "MealEntity",
                "ShoppingItemEntity",
                "ProgramEntity",
                "AppStateEntity",
                "ProgramRevisionEntity",
                "ProgramDayEntity",
                "ProgramExerciseEntity",
                "ProgramWorkoutSlotEntity",
                "WorkoutSessionEntity",
                "SessionSnapshotEntity",
                "SessionSnapshotExerciseEntity",
                "SessionExerciseEntity",
                "SetLogEntity",
                "ProgramPauseEntity",
                "FamilyProgressionStateEntity",
                "AdaptiveDecisionRecordEntity",
                "AdaptiveAdjustmentEntity"
            ),
            registered
        )

        val migrations = Regex("addMigrations\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(source)!!
            .groupValues[1]
        for (migration in listOf(
            "MIGRATION_1_2",
            "MIGRATION_2_3",
            "MIGRATION_3_4",
            "MIGRATION_4_5",
            "MIGRATION_5_6",
            "MIGRATION_6_7",
            "MIGRATION_7_8",
            "MIGRATION_8_9",
            "MIGRATION_9_10",
            "MIGRATION_10_11",
            "MIGRATION_11_12"
        )) {
            assertTrue("$migration is registered", migrations.contains(migration))
        }

        // The retained and target accessors are reachable from the database...
        for (accessor in listOf(
            "fun nutritionDao(): NutritionDao",
            "fun postureProgressDao(): PostureProgressDao",
            "fun maintenanceDao(): MaintenanceDao"
        )) {
            assertTrue("$accessor is reachable from the database", source.contains(accessor))
        }

        // ...and the retired ones are not declared at all. This is the §33 half of the claim: a
        // schema that dropped a table must not still hand out a DAO over it.
        for (retired in listOf(
            "fun progressDao(): ProgressDao",
            "fun familyProgressionStateDao(): FamilyProgressionStateDao",
            "fun adaptiveDecisionHistoryDao(): AdaptiveDecisionHistoryDao"
        )) {
            assertFalse("the retired accessor $retired is gone", source.contains(retired))
        }
    }

    @Test
    fun theTargetAdaptiveDaosExposeNoWayToRewriteHistory() {
        val decisionDao = File(mainSources, "data/local/ProgramAdaptiveDecisionDao.kt").readText()
        val familyStateDao = File(mainSources, "data/local/ProgramFamilyProgressionStateDao.kt").readText()

        assertTrue("a decision is appended", decisionDao.contains("suspend fun insertDecision"))
        for (mutator in listOf("@Update", "@Delete", "@Upsert", "UPDATE ")) {
            assertFalse(
                "a decision record is append-only: the target decision DAO must not contain $mutator",
                decisionDao.contains(mutator)
            )
        }

        // A family's current state is a *position*, so it is replaced rather than accumulated — and it is
        // never deleted through this DAO either.
        assertTrue(
            "a family state is upserted",
            familyStateDao.contains("@Insert(onConflict = OnConflictStrategy.REPLACE)")
        )
        assertFalse("a family state DAO does not delete", familyStateDao.contains("@Delete"))

        // The one place either table is cleared is §16's Full reset, and it says so in one file.
        val maintenanceDao = File(mainSources, "data/local/MaintenanceDao.kt").readText()
        assertTrue(
            "the reset clears the target decision rows",
            maintenanceDao.contains("DELETE FROM `program_adaptive_decision_record`")
        )
        assertTrue(
            "the reset clears the target family state rows",
            maintenanceDao.contains("DELETE FROM `program_family_progression_state`")
        )
    }

    // ---- the stored vocabulary ----------------------------------------------------------------------

    @Test
    fun everyAdaptationStateRoundTripsThroughItsStoredName() {
        val converters = AdaptiveTypeConverters()

        assertEquals(
            "the four-state vocabulary, each one stored under its own name",
            AdaptiveState.entries.toList(),
            AdaptiveState.entries.map { state ->
                val stored = converters.adaptiveStateToName(state)
                assertEquals("stored as the enum's own name, not its ordinal", state.name, stored)
                converters.adaptiveStateFromName(stored)
            }
        )
    }

    @Test
    fun anUnknownStoredNameFailsLoudlyRatherThanResolvingToSomethingElse() {
        val converters = AdaptiveTypeConverters()

        assertThrows(IllegalArgumentException::class.java) { converters.adaptiveStateFromName("PROMOTED") }
        // The action and reason-code halves of this probe belonged to the Stage-1 vocabulary and its
        // converter pair, both retired by §30 step 15; what a *stored name* must do — resolve or fail
        // loudly — is asserted for the vocabulary that survives.
        assertThrows(IllegalArgumentException::class.java) {
            converters.adaptiveStateFromName("RECOVERY_LOAD")
        }
    }

    @Test
    fun theStoredVocabularyIsTheExactTokenEarlierBuildsWrote() {
        val converters = AdaptiveTypeConverters()

        // The token per value, spelled out. A renamed enum member must fail here rather than silently
        // orphan every row an earlier build wrote under the old name — which is what makes a record
        // written before a schema evolution still readable after it.
        assertEquals(
            mapOf(
                AdaptiveState.HOLD to "HOLD",
                AdaptiveState.PROGRESS to "PROGRESS",
                AdaptiveState.REGRESS to "REGRESS",
                AdaptiveState.RECOVERY to "RECOVERY"
            ),
            AdaptiveState.entries.associateWith { converters.adaptiveStateToName(it) }
        )
    }
}
