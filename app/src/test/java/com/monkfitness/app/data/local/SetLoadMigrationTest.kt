package com.monkfitness.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-set load step (21 -> 22), executed on a real SQLite engine — the strongest proof available on
 * this JVM, because `MigrationTestHelper` needs instrumentation the CI does not run.
 *
 * The contract is narrow and the whole point of the suite: one nullable `REAL` column is appended, every
 * row a version-21 database already holds keeps every value it held, and the rows that existed before the
 * column did read `NULL` rather than `0` — an absence, which no volume may add as zero kilograms.
 */
class SetLoadMigrationTest {

    /** A populated version-21 database: every step before the load column. */
    private fun versionTwentyOneDatabase(): SqliteTestDatabase {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        database.migrate(AppDatabase.MIGRATION_8_9)
        database.migrate(AppDatabase.MIGRATION_9_10)
        database.migrate(AppDatabase.MIGRATION_10_11)
        database.migrate(AppDatabase.MIGRATION_11_12)
        database.migrate(AppDatabase.MIGRATION_12_13)
        database.migrate(AppDatabase.MIGRATION_13_14)
        database.migrate(AppDatabase.MIGRATION_14_15)
        database.migrate(AppDatabase.MIGRATION_15_16)
        database.migrate(AppDatabase.MIGRATION_16_17)
        database.migrate(AppDatabase.MIGRATION_17_18)
        database.migrate(AppDatabase.MIGRATION_18_19)
        database.migrate(AppDatabase.MIGRATION_19_20)
        database.migrate(AppDatabase.MIGRATION_20_21)
        return database
    }

    /**
     * A complete Program graph at version 21, including one confirmed set (`set-log-1`, 12 repetitions)
     * written before the load column existed — which is exactly the row this step must not reshape.
     */
    private fun seedOneSet(database: SqliteTestDatabase) {
        ProgramGraphInserts.insertCompleteProgram(
            database,
            "1",
            targetOccurrenceRows = true,
            targetScheduleSourceRows = true
        )
    }

    @Test
    fun theStepAppendsOneNullableRealColumnAndWritesNoRow() {
        val database = versionTwentyOneDatabase()
        seedOneSet(database)
        val before = database.rows("SELECT * FROM `program_set_log`").single()

        database.migrate(AppDatabase.MIGRATION_21_22)

        assertEquals(
            "the column is appended to the row's existing columns, in the order the entity declares them",
            ProgramSchemaFixture.columnsNow("program_set_log").map { it.name },
            database.columnNames("program_set_log")
        )
        assertEquals(
            "and it is REAL: a load is measured to two decimals, not counted in whole units",
            "REAL",
            database.columnType("program_set_log", "loadKg")
        )
        val after = database.rows("SELECT * FROM `program_set_log`").single()
        assertEquals(
            "the set the version-21 database held keeps every value it held",
            before,
            after.filterKeys { key -> key in before.keys }
        )
        assertNull(
            "and its load is absent rather than zero: no weight was recorded for a set performed before " +
                "the screen could ask for one",
            after["loadKg"]
        )
    }

    @Test
    fun aRowThatWasNeverWeighedStaysAbsentAcrossTheStep() {
        val database = versionTwentyOneDatabase()
        seedOneSet(database)

        database.migrate(AppDatabase.MIGRATION_21_22)

        assertEquals(
            "the row count is unchanged: the step adds a column, never a row",
            1,
            database.count("program_set_log")
        )
        assertEquals(
            "and no row claims a load: a `DEFAULT 0` would have made every historical set weigh nothing",
            "0",
            database.scalar("SELECT COUNT(*) FROM `program_set_log` WHERE `loadKg` IS NOT NULL")
        )
        assertTrue(
            "the table's own definition records the new column with no default",
            requireNotNull(database.masterSql()["program_set_log"]).let { ddl ->
                ddl.contains("`loadKg` REAL") && !ddl.contains("loadKg` REAL DEFAULT")
            }
        )
    }

    @Test
    fun aWeighedSetSurvivesTheRoundTripAndTheUnweighedOneStaysNull() {
        val database = versionTwentyOneDatabase()
        seedOneSet(database)
        database.migrate(AppDatabase.MIGRATION_21_22)
        database.exec(
            "INSERT INTO `program_set_log` (`setLogId`, `sessionExerciseId`, `setIndex`, " +
                "`completedReps`, `durationSeconds`, `performedAt`, `loadKg`) " +
                "VALUES ('set-new', 'session-exercise-1', 2, 8, 0, 1700000010000, 62.5)"
        )

        val rows = database.rows(
            "SELECT `setLogId`, `loadKg` FROM `program_set_log` ORDER BY `setIndex`"
        )

        assertEquals("the old set reads as an absence", null, rows[0]["loadKg"])
        assertEquals("the weighed set reads its two-decimal load back", 62.5, rows[1]["loadKg"]?.toDouble())
    }

    @Test
    fun theStepIsAdditiveForEveryOtherTable() {
        val database = versionTwentyOneDatabase()
        seedOneSet(database)
        val schemaBefore = database.masterSql()

        database.migrate(AppDatabase.MIGRATION_21_22)

        assertEquals(
            "no other table, index or bookkeeping entry is rewritten",
            schemaBefore.filterKeys { table -> table != "program_set_log" },
            database.masterSql().filterKeys { table -> table != "program_set_log" }
        )
        assertEquals(
            "and every table still exists",
            schemaBefore.keys.toSet(),
            database.masterSql().keys.toSet()
        )
    }
}
