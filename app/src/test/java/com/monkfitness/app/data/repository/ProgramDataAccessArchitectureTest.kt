package com.monkfitness.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The data layer's boundaries, asserted against the sources themselves.
 *
 * A comment saying "the repository does not decide policy" erodes on the first convenient import, so the
 * rules are checked mechanically: the layering (`Entity → Room`, `DAO → Room + Entity`,
 * `Mapper → Entity + Domain`, `Repository → DAO + Mapper + Domain`), the absence of a self-acquired
 * database, the absence of generator/scheduler/policy/clock vocabulary in a persistence layer, the
 * separation from the Stage-1 tables, and the correspondence between the queries the DAOs declare and
 * the statements the repository suites execute on a real engine.
 *
 * Comments are stripped before every scan: this layer's KDoc has to be able to *say* "the Scheduler
 * decides that" and "production passes `AppDatabase.withTransaction`" without tripping a rule about
 * doing it.
 */
class ProgramDataAccessArchitectureTest {

    private val appRoot: File = listOf(
        File("src/main/java/com/monkfitness/app"),
        File("app/src/main/java/com/monkfitness/app")
    ).first { it.isDirectory }

    private val targetDaos = listOf(
        "ProgramDao", "AppStateDao", "ProgramRevisionDao", "ProgramDayDao", "ProgramExerciseDao",
        "ProgramWorkoutSlotDao", "WorkoutSessionDao", "SessionSnapshotDao", "SessionSnapshotExerciseDao",
        "SessionExerciseDao", "ProgramSetLogDao", "ProgramPauseDao", "ProgramFamilyProgressionStateDao",
        "ProgramAdaptiveDecisionDao", "AdaptiveAdjustmentDao"
    )

    private val targetMappers = listOf(
        "StoredValues", "ProgramMappers", "PlanMappers", "ScheduleMappers", "SessionMappers",
        "AdaptiveMappers", "AppStateMappers"
    )

    private val targetRepositories = listOf(
        "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
        "WorkoutSessionRepository", "ProgramAdaptiveRepository", "ProgramProgressRepository",
        "AppStateRepository"
    )

    private fun dao(name: String) = File(appRoot, "data/local/$name.kt")

    private fun mapper(name: String) = File(appRoot, "data/mapper/$name.kt")

    private fun repository(name: String) = File(appRoot, "data/repository/$name.kt")

    private fun targetDaoFiles() = targetDaos.map { dao(it) }

    private fun targetMapperFiles() = targetMappers.map { mapper(it) }

    private fun targetRepositoryFiles() = targetRepositories.map { repository(it) }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun codeOf(files: List<File>): List<Pair<String, String>> =
        files.map { it.name to code(it.readText()) }

    private fun offenders(files: List<File>, forbidden: List<String>): List<String> =
        codeOf(files).flatMap { (name, text) ->
            text.lines().map { it.trim() }
                .filter { line -> line.startsWith("import ") && forbidden.any { line.startsWith(it) } }
                .map { "$name: $it" }
        }

    private fun tokensIn(files: List<File>, tokens: List<String>): List<String> =
        codeOf(files).flatMap { (name, text) ->
            tokens.filter { text.contains(it) }.map { "$name: $it" }
        }

    // ---- layering ---------------------------------------------------------------------------------

    @Test
    fun theDataLayerImportsNothingFromTheLayersAboveIt() {
        val mapperOffenders = offenders(
            targetMapperFiles(),
            listOf(
                "import android.", "import androidx", "import kotlinx", "import com.monkfitness.app.ui.",
                "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.data.repository.",
                "import com.monkfitness.app.data.local.", "import com.monkfitness.app.animation.",
                "import com.monkfitness.app.poses.", "import com.monkfitness.app.R"
            )
        )
        val daoOffenders = offenders(
            targetDaoFiles(),
            listOf(
                "import android.", "import androidx.compose", "import com.monkfitness.app.domain.",
                "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
                "import com.monkfitness.app.data.repository.", "import com.monkfitness.app.data.mapper."
            )
        )
        val repositoryOffenders = offenders(
            targetRepositoryFiles(),
            listOf(
                "import android.", "import androidx.compose", "import com.monkfitness.app.ui.",
                "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.animation.",
                "import com.monkfitness.app.poses.", "import com.monkfitness.app.R"
            )
        )

        assertTrue(
            "a mapper is Entity + Domain only — not a place to reach for Room, the data layer or the UI: " +
                "$mapperOffenders",
            mapperOffenders.isEmpty()
        )
        assertTrue(
            "a DAO is Room + Entity only, never domain logic or the UI: $daoOffenders",
            daoOffenders.isEmpty()
        )
        assertTrue(
            "a repository is DAO + Mapper + Domain and must not reach for the UI: $repositoryOffenders",
            repositoryOffenders.isEmpty()
        )
    }

    @Test
    fun noRepositoryHandsARoomEntityUpward() {
        val entityTypes = TARGET_ENTITY_TABLES.keys
        val offenders = targetRepositories.flatMap { name ->
            val type = Class.forName("com.monkfitness.app.data.repository.$name")
            type.methods
                .filterNot { it.isSynthetic || it.isBridge }
                .filter { method ->
                    method.returnType in entityTypes ||
                        method.parameterTypes.any { parameter -> parameter in entityTypes }
                }
                .map { "$name: ${it.name}" }
        }

        assertTrue(
            "an entity never leaves the data layer (§25, §33): a repository's public surface is domain " +
                "types and primitives only — $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noRepositoryAcquiresItsOwnDatabaseOrBecomesASingleton() {
        val offenders = codeOf(targetRepositoryFiles())
            .filter { (_, text) ->
                listOf("AppDatabase", "Room.databaseBuilder", "getDatabase(", "INSTANCE")
                    .any { text.contains(it) }
            }
            .map { it.first }

        assertTrue(
            "the database and the transaction are constructor arguments, not something a repository " +
                "reaches for (§26): $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePersistenceLayerHoldsNoSchedulerGeneratorPolicyOrClock() {
        val offenders = tokensIn(
            targetDaoFiles() + targetMapperFiles() + targetRepositoryFiles(),
            listOf(
                "WorkoutGenerator", "AdaptivePolicy", "AdaptiveProgramEngine", "ProgressionResolver",
                "FocusPlanner", "ProgramCalendar", "Scheduler", "System.currentTimeMillis",
                "Instant.now(", "Random", "generatePlan", "ProgramConfiguration", "SettingsManager"
            )
        )

        assertTrue(
            "these are decisions this layer does not make (§24): a clock is injected, a plan is not " +
                "generated and a policy is not evaluated here — $offenders",
            offenders.isEmpty()
        )
    }

    // ---- the two persistence generations ----------------------------------------------------------

    @Test
    fun noPersistenceSourceNamesARetiredTable() {
        val retiredTables = listOf(
            "`set_log`", "`family_progression_state`", "`adaptive_decision_record`",
            "`user_progress`", "`program_day_state`"
        )
        val offenders = codeOf(targetDaoFiles() + targetMapperFiles() + targetRepositoryFiles())
            .flatMap { (name, text) -> retiredTables.filter { text.contains(it) }.map { "$name: $it" } }

        assertTrue(
            "the target tables have their own names and nothing here reaches a retired one " +
                "(§30 step 15 retires all five): $offenders",
            offenders.isEmpty()
        )

        // §30 step 15 inverted this half. The shipped Stage-1 persistence used to still own its own
        // tables, so a test could read its DAOs and check they named them; now those tables are dropped
        // by `MIGRATION_11_12` **and** their DAOs are gone, so the claim is that no such file exists at
        // all — a stronger fact than "it names the right table", and the one that cannot silently rot.
        for (retiredDao in listOf(
            "data/local/FamilyProgressionStateDao.kt",
            "data/local/AdaptiveDecisionHistoryDao.kt",
            "data/local/ProgressDao.kt"
        )) {
            assertFalse(
                "the retired DAO $retiredDao is gone, not merely unreferenced",
                File(appRoot, retiredDao).exists()
            )
        }
    }

    // ---- the DAOs and the harness agree -----------------------------------------------------------

    @Test
    fun everyDeclaredDaoQueryIsTheOneTheRepositorySuitesExecute() {
        val declared = mutableMapOf<String, String>()
        targetDaos.forEach { name ->
            val text = dao(name).readText()
            Regex("@Query\\(\"([^\"]*)\"\\)\\s*\\n\\s*suspend fun (\\w+)").findAll(text).forEach { match ->
                declared["$name.${match.groupValues[2]}"] = match.groupValues[1]
            }
        }

        assertEquals(
            "every @Query the target DAOs declare is executed by the repository suites, one statement each",
            declared.keys.sorted(),
            ProgramDaoSql.ALL.keys.sorted()
        )
        declared.forEach { (key, sql) ->
            assertEquals("$key is exercised from its own DAO's literal", sql, ProgramDaoSql.ALL[key])
        }
        assertEquals(
            "the hand-written writes are the two statements no entity generates: the Program row's " +
                "update, and the conditional insert §19's occupancy rule is carried by. §30 step 8 " +
                "revised this rule rather than relaxing it — the list stays exact, and the shape of both " +
                "statements is asserted below",
            listOf("ProgramDao.updateProgram", "WorkoutSessionDao.insertSessionIfSlotIsNotOccupied"),
            ProgramDaoSql.WRITES.keys.sorted()
        )
    }

    @Test
    fun everyHandWrittenWriteNamesItsTableAndIsScopedToARow() {
        val write = ProgramDaoSql.WRITES.getValue("ProgramDao.updateProgram")

        assertTrue("a hand-written write updates a target table: $write", write.startsWith("UPDATE `program`"))
        assertTrue(
            "and it is scoped to one row, never table-wide: $write",
            write.contains(" WHERE `programId` = ?")
        )
        assertTrue(
            "it writes every column but the identity",
            listOf(
                "`name`", "`description`", "`source`", "`lifecycleStatus`", "`currentRevisionId`",
                "`createdAt`", "`updatedAt`", "`plannedStartDate`", "`actualStartDate`", "`archivedAt`"
            ).all { write.contains("$it = ?") }
        )

        // The second hand-written write, added by §30 step 8, is asserted for what makes it the mechanism
        // §19's occupancy rule needed: it is an insert into the session table, it stores exactly that
        // table's columns, and the row it stores is the one the predicate admits.
        val conditionalInsert =
            ProgramDaoSql.WRITES.getValue("WorkoutSessionDao.insertSessionIfSlotIsNotOccupied")

        assertTrue(
            "the write that starts a session inserts into `workout_session`: $conditionalInsert",
            conditionalInsert.startsWith("INSERT INTO `workout_session`")
        )
        assertTrue(
            "and it writes the table's columns, in the table's own order, so a column the entity gains " +
                "cannot be silently missed: $conditionalInsert",
            conditionalInsert.contains(
                "(`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`, `finishedAt`)"
            )
        )
        assertTrue(
            "the row is decided by the statement's own predicate, not by a caller's check: $conditionalInsert",
            conditionalInsert.contains(" WHERE NOT EXISTS (")
        )
        assertTrue(
            "and the predicate is the occupancy rule — the slot, in the status this session starts in. " +
                "The two are parameters of their own rather than a repeated name, so the statement says " +
                "what each occurrence means: " + conditionalInsert,
            conditionalInsert.contains(
                "SELECT 1 FROM `workout_session` WHERE `slotId` = :occupiedSlotId AND `status` = :occupiedStatus"
            )
        )
        assertTrue(
            "the row's own slot and status are selected before that predicate, so the write and the " +
                "guard name the same pair: $conditionalInsert",
            conditionalInsert.startsWith(
                "INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, " +
                    "`status`, `startedAt`, `finishedAt`) SELECT :sessionId, :slotId, :programId, " +
                    ":revisionId, :status, :startedAt, :finishedAt WHERE NOT EXISTS ("
            )
        )
        assertTrue(
            "it is scoped to the slot the new row attempts and changes no existing row (an insert that " +
                "is refused stores nothing, and no update or delete is involved): $conditionalInsert",
            !conditionalInsert.contains("UPDATE ") && !conditionalInsert.contains("DELETE ")
        )
        assertEquals(
            "and the statement that reports its effect is SQLite's own count for the statement just " +
                "executed — read on the same connection, inside the same transaction",
            "SELECT changes()",
            ProgramDaoSql.ALL.getValue("WorkoutSessionDao.changedRowCount")
        )
    }

    @Test
    fun thereIsNoTableWideUpdateOrDeleteInTheTargetPersistence() {
        val offenders = codeOf(targetDaoFiles() + targetRepositoryFiles())
            .flatMap { (name, text) ->
                Regex("\"((?:UPDATE|DELETE)[^\"]*)\"")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .filter { !it.contains(" WHERE ") }
                    .map { "$name: $it" }
                    .toList()
            }

        assertTrue(
            "a statement that rewrites or removes a whole table has no place in this layer: $offenders",
            offenders.isEmpty()
        )
        assertTrue(
            "every mutating statement the target DAOs declare is scoped by a predicate",
            codeOf(targetDaoFiles()).flatMap { (_, text) ->
                Regex("\"((?:UPDATE|DELETE)[^\"]*)\"").findAll(text).map { it.groupValues[1] }.toList()
            }.all { it.contains(" WHERE ") }
        )
    }

    // ---- nothing is reachable from the UI ---------------------------------------------------------

    @Test
    fun noUiOrViewModelSourceReachesTheTargetPersistence() {
        val targetTypes = listOf(
            "ProgramEntity", "AppStateEntity", "ProgramRevisionEntity", "ProgramDayEntity",
            "ProgramExerciseEntity", "ProgramWorkoutSlotEntity", "WorkoutSessionEntity",
            "SessionSnapshotEntity", "SessionSnapshotExerciseEntity", "SessionExerciseEntity",
            "SetLogEntity", "ProgramPauseEntity", "FamilyProgressionStateEntity",
            "AdaptiveDecisionRecordEntity", "AdaptiveAdjustmentEntity", "data.mapper."
        ) + targetDaos.filterNot { it == "ProgramDao" } + targetRepositories

        val offenders = listOf(File(appRoot, "ui"), File(appRoot, "viewmodel"))
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                    file.readText().lines()
                        .map { it.trim() }
                        .filter { line -> line.startsWith("import ") && targetTypes.any { line.contains(it) } }
                        .map { "${root.name}/${file.name}: $it" }
                }.toList()
            }

        assertTrue("entities never leave the data layer (§25, §33): $offenders", offenders.isEmpty())
    }

    // ---- the entities and their tables agree ------------------------------------------------------

    @Test
    fun everyTargetEntityDeclaresExactlyTheColumnsItsTableHas() {
        val database = ProgramDataAccessRig("schema").database
        try {
            TARGET_ENTITY_TABLES.forEach { (entity, table) ->
                val columns = database.columnNames(table)
                val fields = entity.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
                    .map { it.name }

                assertTrue("$table exists in the migrated schema", columns.isNotEmpty())
                assertEquals(
                    "$table stores exactly the fields ${entity.simpleName} declares, in that order",
                    columns,
                    fields
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun theHarnessRegistersTheFifteenTargetTablesAndNothingElse() {
        assertEquals(
            "the harness's entity-to-table map is the target schema, not a wider one",
            listOf(
                "adaptive_adjustment", "app_state", "program", "program_adaptive_decision_record",
                "program_day", "program_exercise", "program_family_progression_state", "program_pause",
                "program_revision", "program_set_log", "program_workout_slot",
                "session_exercise", "session_snapshot", "session_snapshot_exercise", "workout_session"
            ),
            TARGET_ENTITY_TABLES.values.sorted()
        )
    }
}
