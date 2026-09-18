package com.monkfitness.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
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
    fun theDatabaseKeepsEveryShippedEntityAndAddsTheTargetSchema() {
        val source = File(mainSources, "data/local/AppDatabase.kt").readText()

        assertTrue(
            "the database version moves with the schema (7 → 8 for the Program System target schema)",
            source.contains("version = 8")
        )

        val registered = Regex("entities = \\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(source)!!
            .groupValues[1]
            .let { Regex("(\\w+)::class").findAll(it).map { match -> match.groupValues[1] }.toList() }

        assertEquals(
            "the ten shipped entities are untouched, and the Program System target tables are added " +
                "beside them",
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
                "AdaptiveDecisionRecord",
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
            "MIGRATION_7_8"
        )) {
            assertTrue("$migration is registered", migrations.contains(migration))
        }
        assertTrue(
            "the state DAO is reachable from the database",
            source.contains("fun familyProgressionStateDao(): FamilyProgressionStateDao")
        )
        assertTrue(
            "the history DAO is reachable from the database",
            source.contains("fun adaptiveDecisionHistoryDao(): AdaptiveDecisionHistoryDao")
        )
    }

    @Test
    fun theAdaptiveDaosExposeNoWayToRewriteHistory() {
        val stateDao = File(mainSources, "data/local/FamilyProgressionStateDao.kt").readText()
        val historyDao = File(mainSources, "data/local/AdaptiveDecisionHistoryDao.kt").readText()

        assertFalse("a current-state row is written, never deleted one at a time", stateDao.contains("@Delete"))

        for (mutator in listOf("@Update", "@Delete", "@Upsert", "OnConflictStrategy", "UPDATE ")) {
            assertFalse(
                "a decision record is append-only: the history DAO must not contain $mutator",
                historyDao.contains(mutator)
            )
        }

        // The only delete either DAO may expose is the C3 "Full Reset" clear, which takes the whole
        // program record at once — Task 8 deliberately left the reset semantics to the lifecycle task,
        // and a reset that kept adaptive progression while erasing the workout history it was derived
        // from is not a reset. What still must be impossible is the delete that REWRITES history: one
        // that names a row, a window, a family or a revision. Pinning the exact literals, and asserting
        // that none of them carries a WHERE clause, is that rule — a narrowed delete cannot hide here.
        assertEquals(
            "the state DAO clears the whole table and nothing narrower",
            listOf("DELETE FROM family_progression_state"),
            deleteStatementsIn(stateDao)
        )
        assertEquals(
            "the history DAO clears the whole trail and nothing narrower",
            listOf("DELETE FROM adaptive_decision_record"),
            deleteStatementsIn(historyDao)
        )

        assertTrue("history is appended", historyDao.contains("suspend fun appendDecision"))
        assertTrue(
            "and both history queries name a total order",
            Regex("ORDER BY").findAll(historyDao).count() == 2 &&
                Regex("ORDER BY[^\\\"]*id ASC").findAll(historyDao).count() == 2
        )
    }

    /**
     * Every `DELETE FROM …` literal a DAO source declares, verbatim: the count and the text of each are
     * the assertion, so a narrowed (row-scoped) delete shows up as a different literal rather than passing
     * as "a delete exists".
     */
    private fun deleteStatementsIn(daoSource: String): List<String> =
        Regex("""DELETE FROM [a-zA-Z_]+(?: WHERE [^"\n]*)?""")
            .findAll(daoSource)
            .map { it.value.trim() }
            .toList()

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
    fun everyActionRoundTripsThroughItsStoredName() {
        val converters = AdaptiveTypeConverters()

        assertEquals(
            "the action vocabulary, each one stored under its own name",
            AdaptiveAction.entries.toList(),
            AdaptiveAction.entries.map { action ->
                val stored = converters.actionsToNames(listOf(action))
                assertEquals("stored as the enum's own name, not its ordinal", action.name, stored)
                converters.actionsFromNames(stored).single()
            }
        )
        assertEquals(
            "an ordered action list keeps its order",
            listOf(AdaptiveAction.RECOVERY_LOAD, AdaptiveAction.REDUCE_STIMULUS),
            converters.actionsFromNames(
                converters.actionsToNames(
                    listOf(AdaptiveAction.RECOVERY_LOAD, AdaptiveAction.REDUCE_STIMULUS)
                )
            )
        )
        assertEquals("no actions is an empty list, not a parse failure", emptyList<AdaptiveAction>(), converters.actionsFromNames(""))
    }

    @Test
    fun everyReasonCodeRoundTripsThroughItsStoredName() {
        val converters = AdaptiveTypeConverters()

        assertEquals(
            "the reason vocabulary, including the code reserved for the custom-program stage",
            AdaptiveReasonCode.entries.toList(),
            AdaptiveReasonCode.entries.map { reason ->
                val stored = converters.reasonCodeToName(reason)
                assertEquals("stored as the enum's own name, not its ordinal", reason.name, stored)
                converters.reasonCodeFromName(stored)
            }
        )
    }

    @Test
    fun anUnknownStoredNameFailsLoudlyRatherThanResolvingToSomethingElse() {
        val converters = AdaptiveTypeConverters()

        assertThrows(IllegalArgumentException::class.java) { converters.adaptiveStateFromName("PROMOTED") }
        assertThrows(IllegalArgumentException::class.java) { converters.reasonCodeFromName("BECAUSE") }
        assertThrows(IllegalArgumentException::class.java) { converters.actionsFromNames("INCREASE_STIMULUS,SPIN") }
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
        assertEquals(
            mapOf(
                AdaptiveAction.MAINTAIN_STIMULUS to "MAINTAIN_STIMULUS",
                AdaptiveAction.INCREASE_STIMULUS to "INCREASE_STIMULUS",
                AdaptiveAction.REDUCE_STIMULUS to "REDUCE_STIMULUS",
                AdaptiveAction.RECOVERY_LOAD to "RECOVERY_LOAD"
            ),
            AdaptiveAction.entries.associateWith { converters.actionsToNames(listOf(it)) }
        )
        assertEquals(
            mapOf(
                AdaptiveReasonCode.INSUFFICIENT_EVIDENCE to "INSUFFICIENT_EVIDENCE",
                AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE to "SUSTAINED_POSITIVE_PERFORMANCE",
                AdaptiveReasonCode.SUSTAINED_DECLINE to "SUSTAINED_DECLINE",
                AdaptiveReasonCode.HIGH_LOAD_DETERIORATION to "HIGH_LOAD_DETERIORATION",
                AdaptiveReasonCode.RECOVERY to "RECOVERY",
                AdaptiveReasonCode.PROGRESSION_COOLDOWN to "PROGRESSION_COOLDOWN",
                AdaptiveReasonCode.CUSTOM_CONFIGURATION_LIMITATION to "CUSTOM_CONFIGURATION_LIMITATION"
            ),
            AdaptiveReasonCode.entries.associateWith { converters.reasonCodeToName(it) }
        )

        // A stored row is read back from constant literals, not from values computed out of the enums:
        // this is the read an upgraded device performs on data an earlier build wrote.
        assertEquals(AdaptiveState.RECOVERY, converters.adaptiveStateFromName("RECOVERY"))
        assertEquals(
            listOf(AdaptiveAction.REDUCE_STIMULUS, AdaptiveAction.RECOVERY_LOAD),
            converters.actionsFromNames("REDUCE_STIMULUS,RECOVERY_LOAD")
        )
        assertEquals(
            AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
            converters.reasonCodeFromName("HIGH_LOAD_DETERIORATION")
        )
    }
}
