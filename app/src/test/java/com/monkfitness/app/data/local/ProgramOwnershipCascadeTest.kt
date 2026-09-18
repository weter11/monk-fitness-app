package com.monkfitness.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership graph, executed: two complete Programs in one real SQLite database, one of them
 * deleted, and the rows that are allowed to move.
 *
 * What is under test is the database's own behaviour under `PRAGMA foreign_keys = ON`, the pragma
 * Room opens a database with. A cascade that only exists in a DDL string is not a cascade, and a
 * `SET NULL` that is never seen to null is not a `SET NULL`. The `DELETE` here is the statement the
 * eventual repository's delete transaction will run (§27, §29); no repository is written in this PR.
 */
class ProgramOwnershipCascadeTest {

    /** A migrated version-8 database holding two independent programs and the AppState row. */
    private fun migratedDatabase(): SqliteTestDatabase {
        val database = SqliteTestDatabase.inMemory()
        database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
        database.migrate(AppDatabase.MIGRATION_7_8)
        ProgramGraphInserts.insertCompleteProgram(database, "1")
        ProgramGraphInserts.insertCompleteProgram(database, "2")
        ProgramGraphInserts.insertAppState(database, selected = "program-1", next = "program-2")
        return database
    }

    /** Each target table's identifying column, and the value the surviving program's row carries. */
    private val survivingIdentity: List<Triple<String, String, String>> = listOf(
        Triple("program", "programId", "program-1"),
        Triple("program_revision", "revisionId", "revision-1"),
        Triple("program_day", "programDayId", "day-1"),
        Triple("program_exercise", "programExerciseId", "plan-exercise-1"),
        Triple("program_workout_slot", "slotId", "slot-1"),
        Triple("workout_session", "sessionId", "session-1"),
        Triple("session_snapshot", "sessionId", "session-1"),
        Triple("session_snapshot_exercise", "sessionId", "session-1"),
        Triple("session_exercise", "sessionExerciseId", "session-exercise-1"),
        Triple("program_set_log", "setLogId", "set-log-1"),
        Triple("program_pause", "pauseId", "pause-1"),
        Triple("program_family_progression_state", "revisionId", "revision-1"),
        Triple("program_adaptive_decision_record", "decisionId", "decision-1"),
        Triple("adaptive_adjustment", "adjustmentId", "adjustment-1")
    )

    @Test
    fun deletingAProgramRemovesEveryRowItOwnsAndLeavesTheOtherProgramAlone() {
        val database = migratedDatabase()

        assertEquals("two programs", 2, database.count("program"))
        for (table in ProgramSchemaFixture.PROGRAM_OWNED_TABLES) {
            assertEquals("`$table` is owned twice, once per program", 2, database.count(table))
        }

        database.exec("DELETE FROM `program` WHERE `programId` = 'program-2'")

        assertEquals(
            "every table of the contract was exercised by the delete, except the global AppState row " +
                "which is not a child of any program",
            ProgramSchemaFixture.TABLES.size - 1,
            survivingIdentity.size
        )
        for ((table, column, survivor) in survivingIdentity) {
            assertEquals(
                "`$table` lost exactly the deleted program's row",
                1,
                database.count(table)
            )
            assertEquals(
                "`$table` kept the other program's row, not an arbitrary one",
                listOf(survivor),
                database.strings("SELECT `$column` FROM `$table`")
            )
        }
        assertEquals("no owned table was wiped wholesale", 13, ProgramSchemaFixture.PROGRAM_OWNED_TABLES.size)
    }

    @Test
    fun theReferenceToTheNextProgramIsNulledWhenThatProgramIsDeleted() {
        val database = migratedDatabase()

        database.exec("DELETE FROM `program` WHERE `programId` = 'program-2'")

        assertNull(
            "nextProgramId is the blueprint's SET NULL: a plan to start that program next cannot " +
                "outlive the program itself (§29)",
            database.scalar("SELECT nextProgramId FROM `app_state`")
        )
        assertEquals(
            "while the selection is a different fact, untouched by that deletion",
            "program-1",
            database.scalar("SELECT selectedProgramId FROM `app_state`")
        )
        assertEquals("and the AppState row itself survives", 1, database.count("app_state"))
    }

    @Test
    fun deletingTheSelectedProgramIsRefusedUntilTheSelectionIsHandled() {
        val database = migratedDatabase()
        database.exec("UPDATE `app_state` SET `selectedProgramId` = 'program-2'")

        val refusal = database.expectError("DELETE FROM `program` WHERE `programId` = 'program-2'")
        assertTrue(
            "the database refuses to delete the selected program, so the lifecycle has to choose " +
                "the fallback (§3) instead of the reference silently disappearing: $refusal",
            refusal.contains("FOREIGN KEY")
        )
        assertEquals("nothing was deleted by the refused statement", 2, database.count("program"))
        assertEquals(
            "and nothing under it was deleted either",
            2,
            database.count("program_revision")
        )

        database.exec("UPDATE `app_state` SET `selectedProgramId` = 'program-1'")
        database.exec("DELETE FROM `program` WHERE `programId` = 'program-2'")
        assertEquals("with the selection handled, the delete goes through", 1, database.count("program"))
    }

    @Test
    fun oneSlotMayCarrySeveralSessionAttempts() {
        val database = migratedDatabase()
        val attemptsBefore = database.count("workout_session")

        database.exec(
            "INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, " +
                "`status`, `startedAt`, `finishedAt`) VALUES ('session-1-second', 'slot-1', " +
                "'program-1', 'revision-1', 'IN_PROGRESS', 1700000010000, NULL)"
        )

        assertEquals(
            "a slot may be attempted again: the first attempt is history, not a constraint (§19)",
            attemptsBefore + 1,
            database.count("workout_session")
        )
        assertEquals(
            "both attempts are for the same slot",
            2,
            database.scalar("SELECT COUNT(*) FROM `workout_session` WHERE slotId = 'slot-1'")!!.toInt()
        )
        assertEquals(
            "and the slot itself is unchanged by the attempt",
            "PLANNED",
            database.scalar("SELECT status FROM `program_workout_slot` WHERE slotId = 'slot-1'")
        )

        val duplicateIdentity = database.expectError(
            "INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, " +
                "`status`, `startedAt`, `finishedAt`) VALUES ('session-1-second', 'slot-1', " +
                "'program-1', 'revision-1', 'IN_PROGRESS', 1700000020000, NULL)"
        )
        assertTrue(
            "a session is created once and its identity is its own: $duplicateIdentity",
            duplicateIdentity.contains("UNIQUE")
        )
    }

    @Test
    fun theSameExerciseMayBePlannedTwiceInOneDay() {
        val database = migratedDatabase()

        database.exec(
            "INSERT INTO `program_exercise` (`programExerciseId`, `programDayId`, `position`, " +
                "`exerciseId`, `prescriptionDimension`, `perSetTargets`, `origin`, `isPinned`) " +
                "VALUES ('plan-exercise-1-again', 'day-1', 2, 'pushup', 'REP_BASED', '8,8', " +
                "'USER_AUTHORED', 0)"
        )

        assertEquals(
            "the same library exercise is two occurrences with two identities (§9)",
            2,
            database.scalar(
                "SELECT COUNT(*) FROM `program_exercise` WHERE programDayId = 'day-1' AND exerciseId = 'pushup'"
            )!!.toInt()
        )
        assertEquals(
            "and each occurrence carries its own prescription and its own origin",
            listOf("12,10,8,6", "8,8"),
            database.strings(
                "SELECT perSetTargets FROM `program_exercise` WHERE programDayId = 'day-1' ORDER BY position"
            )
        )

        val duplicatePosition = database.expectError(
            "INSERT INTO `program_exercise` (`programExerciseId`, `programDayId`, `position`, " +
                "`exerciseId`, `prescriptionDimension`, `perSetTargets`, `origin`, `isPinned`) " +
                "VALUES ('plan-exercise-duplicate-position', 'day-1', 2, 'squat', 'REP_BASED', " +
                "'8,8', 'GENERATED', 0)"
        )
        assertTrue(
            "but two occurrences cannot claim the same place in the day: $duplicatePosition",
            duplicatePosition.contains("UNIQUE")
        )
    }

    @Test
    fun theIdentityCoordinatesOfTheSchemaRefuseDuplicates() {
        val database = migratedDatabase()

        val duplicateRevisionNumber = database.expectError(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, `createdAt`) " +
                "VALUES ('revision-1-clash', 'program-1', 1, 'MANUAL', 'FIXED_DAYS', 30, " +
                "'FLEXIBLE_PER_WEEK', NULL, 1700000000000)"
        )
        assertTrue(
            "a revision number is taken once inside its program: $duplicateRevisionNumber",
            duplicateRevisionNumber.contains("UNIQUE")
        )

        val duplicateDayPosition = database.expectError(
            "INSERT INTO `program_day` (`programDayId`, `revisionId`, `position`, `type`, `name`) " +
                "VALUES ('day-1-clash', 'revision-1', 1, 'TRAINING', NULL)"
        )
        assertTrue(
            "a day's place in the revision is unique: $duplicateDayPosition",
            duplicateDayPosition.contains("UNIQUE")
        )

        val duplicateSessionExercisePosition = database.expectError(
            "INSERT INTO `session_exercise` (`sessionExerciseId`, `sessionId`, `position`, " +
                "`programExerciseId`, `exerciseId`, `prescriptionDimension`, `perSetTargets`, " +
                "`skipped`) VALUES ('session-exercise-1-clash', 'session-1', 1, " +
                "'plan-exercise-1', 'pushup', 'REP_BASED', '12,10,8,6', 0)"
        )
        assertTrue(
            "an occurrence runs at one place in one session: $duplicateSessionExercisePosition",
            duplicateSessionExercisePosition.contains("UNIQUE")
        )

        val duplicateSetIndex = database.expectError(
            "INSERT INTO `program_set_log` (`setLogId`, `sessionExerciseId`, `setIndex`, " +
                "`completedReps`, `durationSeconds`, `performedAt`) VALUES ('set-log-1-clash', " +
                "'session-exercise-1', 1, 12, 0, 1700000001000)"
        )
        assertTrue(
            "a confirmed set has one index inside its occurrence: $duplicateSetIndex",
            duplicateSetIndex.contains("UNIQUE")
        )

        val duplicateFamilyState = database.expectError(
            "INSERT INTO `program_family_progression_state` (`revisionId`, `familyId`, " +
                "`progressionLevel`, `adaptationState`, `currentExerciseId`, `updatedAt`) VALUES " +
                "('revision-1', 'push-family', 4, 'PROGRESS', 'pushup', 1700000000000)"
        )
        assertTrue(
            "one family has one current state per revision: $duplicateFamilyState",
            duplicateFamilyState.contains("UNIQUE")
        )
    }

    @Test
    fun theTargetTablesExistBecauseTheMigrationRan() {
        val migrated = migratedDatabase()
        val versionSeven = SqliteTestDatabase.inMemory()
        versionSeven.execAll(LegacyV7Schema.TABLE_STATEMENTS)

        assertEquals(
            "a version-7 database does not contain the target tables",
            0,
            versionSeven.tableNames().count { it in ProgramSchemaFixture.TABLES }
        )
        assertEquals(
            "the migrated one does",
            ProgramSchemaFixture.TABLES.size,
            migrated.tableNames().count { it in ProgramSchemaFixture.TABLES }
        )
    }

    @Test
    fun deletingAProgramTakesItsRunningSessionAndEveryRowUnderIt() {
        val database = migratedDatabase()

        assertEquals(
            "the session is in progress before the delete",
            "IN_PROGRESS",
            database.scalar("SELECT status FROM `workout_session` WHERE sessionId = 'session-1'")
        )
        // The selection is pointing at program-1, and deleting the *selected* program is refused until
        // it is handled (see the test above), so the lifecycle moves the selection first — the two
        // statements are what the eventual delete transaction does, in this order.
        database.exec("UPDATE `app_state` SET `selectedProgramId` = 'program-2'")

        database.exec("DELETE FROM `program` WHERE `programId` = 'program-1'")

        val orphansOfTheDeletedSession = mapOf(
            "workout_session" to "SELECT COUNT(*) FROM `workout_session` WHERE sessionId = 'session-1'",
            "session_snapshot" to "SELECT COUNT(*) FROM `session_snapshot` WHERE sessionId = 'session-1'",
            "session_snapshot_exercise" to
                "SELECT COUNT(*) FROM `session_snapshot_exercise` WHERE sessionId = 'session-1'",
            "session_exercise" to
                "SELECT COUNT(*) FROM `session_exercise` WHERE sessionExerciseId = 'session-exercise-1'",
            "program_set_log" to
                "SELECT COUNT(*) FROM `program_set_log` WHERE setLogId = 'set-log-1'"
        )
        for ((table, query) in orphansOfTheDeletedSession) {
            assertEquals(
                "`$table` left no orphan row behind — the cascade runs to the bottom of the graph",
                "0",
                database.scalar(query)
            )
        }
        assertEquals(
            "the other program's session is untouched",
            listOf("session-2"),
            database.strings("SELECT sessionId FROM `workout_session`")
        )
        assertEquals(
            "the AppState row outlives the program it pointed at",
            1,
            database.count("app_state")
        )
    }

    @Test
    fun anAdaptiveAdjustmentIsOwnedThroughItsDecisionAndItsProgram() {
        val database = migratedDatabase()

        assertEquals("one adjustment per program", 2, database.count("adaptive_adjustment"))
        database.exec(
            "INSERT INTO `adaptive_adjustment` (`adjustmentId`, `decisionId`, `programId`, " +
                "`revisionId`, `slotId`, `programExerciseId`, `beforeExerciseId`, " +
                "`beforePrescriptionDimension`, `beforePerSetTargets`, `afterExerciseId`, " +
                "`afterPrescriptionDimension`, `afterPerSetTargets`, `createdAt`, " +
                "`supersedesAdjustmentId`) VALUES ('adjustment-1-next', 'decision-1', 'program-1', " +
                "'revision-1', 'slot-1', 'plan-exercise-1', 'pushup', 'REP_BASED', '12,10,8,6', " +
                "'knee_pushup', 'REP_BASED', '10,8,8,6', 1700000004000, 'adjustment-1')"
        )
        assertEquals(
            "a superseded adjustment is not rewritten or removed when its successor is recorded — " +
                "the chain is history (§16)",
            listOf("adjustment-1", "adjustment-1-next"),
            database.strings("SELECT adjustmentId FROM `adaptive_adjustment` WHERE programId = 'program-1' ORDER BY createdAt")
        )
        assertEquals(
            "and the successor names the adjustment it replaces",
            "adjustment-1",
            database.scalar("SELECT supersedesAdjustmentId FROM `adaptive_adjustment` WHERE adjustmentId = 'adjustment-1-next'")
        )

        val decisionWithoutProgram = database.expectError(
            "INSERT INTO `program_adaptive_decision_record` (`decisionId`, `programId`, `revisionId`, " +
                "`slotId`, `targetScope`, `targetId`, `action`, `outcome`, `evidence`, `confidence`, " +
                "`recovery`, `decidedAt`) VALUES ('decision-orphan', 'no-such-program', 'revision-1', " +
                "'slot-1', 'FAMILY', 'push-family', 'HOLD', 'NOT_APPLIED', 'STABLE', 'LOW', " +
                "'UNKNOWN', 1700000005000)"
        )
        assertTrue(
            "a decision cannot belong to a program that does not exist: $decisionWithoutProgram",
            decisionWithoutProgram.contains("FOREIGN KEY")
        )
    }
}
