package com.monkfitness.app.data.repository

import androidx.room.withTransaction
import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgressDao

/**
 * The destructive half of the C3 maintenance actions, separated from the storage that backs it.
 *
 * Both C3 wipes ([deleteProgressForCycle], [clearAllProgressData]) are multi-table operations that
 * must land as ALL-or-NOTHING: a half-applied "Restart Current Cycle" leaves a cycle whose
 * workout completions are gone but whose posture completions survive (or vice versa), and a
 * half-applied "Full Reset" leaves a mixture of cleared and uncleared history. Both are silent
 * and indistinguishable from a completed reset in the UI.
 *
 * The all-or-nothing guarantee is [AppDatabase.withTransaction]; this type is the part that can be
 * unit-tested on a JVM (the transaction is supplied by the production caller, and the test supplies
 * one that either applies everything or nothing).
 */
object ProgramMaintenance {

    /**
     * C3 "Restart Current Cycle": wipes the active cycle's three progress tables. Prior cycles'
     * rows are not matched by the `WHERE cycleNumber = :n` filters and stay as history.
     *
     * @param inTransaction runs the deletes inside one Room transaction; the whole block rolls back
     * if any delete throws, so the cycle is fully reset or not reset at all.
     * @throws Exception whatever the DAO throws, so the caller reports the failure instead of
     * suppressing it and leaving a partial wipe.
     */
    suspend fun deleteProgressForCycle(
        cycleNumber: Int,
        progressDao: ProgressDao,
        inTransaction: suspend (suspend () -> Unit) -> Unit
    ) {
        require(cycleNumber >= 1) { "cycleNumber must be >= 1 but was $cycleNumber" }
        inTransaction {
            progressDao.deleteUserProgressForCycle(cycleNumber)
            progressDao.deletePostureProgressForCycle(cycleNumber)
            progressDao.deleteProgramDayStatesForCycle(cycleNumber)
        }
    }

    /**
     * C3 "Full Reset": wipes every progress/history table the program owns — the calendar and logging
     * tables plus the two adaptive ones this stage added.
     *
     * The adaptive tables belong to this operation for the same reason the workout history does: the
     * reset returns the app to its true first-launch state, and adaptive progression and its audit trail
     * are part of what the program recorded. They are cleared in the SAME transaction as the rest, so a
     * half-applied reset cannot leave a user with no workout history and a progression level, and a
     * reset that kept them would leave the next session adapting from results it can no longer explain.
     *
     * @param inTransaction as above; rolls all seven deletes back together.
     * @throws Exception whatever the DAO throws, so the caller reports the failure instead of
     * leaving a mixture of cleared and uncleared tables.
     */
    suspend fun clearAllProgressData(
        progressDao: ProgressDao,
        familyStateDao: FamilyProgressionStateDao,
        decisionHistoryDao: AdaptiveDecisionHistoryDao,
        inTransaction: suspend (suspend () -> Unit) -> Unit
    ) {
        inTransaction {
            progressDao.clearUserProgress()
            progressDao.clearPostureProgress()
            progressDao.clearProgramDayStates()
            progressDao.clearSetLogs()
            progressDao.clearBodyWeightEntries()
            familyStateDao.clearFamilyStates()
            decisionHistoryDao.clearDecisionHistory()
        }
    }

    /**
     * The set of tables a Full Reset clears. Asserted by the regression suite so a future table
     * that joins the [AppDatabase] entities list is not silently forgotten by the reset path —
     * the reset's own specification is "everything the program records, except nutrition plans".
     */
    val clearedTables: List<String> = listOf(
        "user_progress",
        "posture_session_progress",
        "program_day_state",
        "set_log",
        "body_weight_log",
        "family_progression_state",
        "adaptive_decision_record"
    )

    /**
     * The tables a Full Reset deliberately leaves alone: nutrition plans are keyed by their own
     * meal-cycle calendar, independent of the program calendar, so they are not part of the
     * program data this action erases. The user-facing text says the same thing.
     */
    val preservedTables: List<String> = listOf("meal_cycles", "meals", "shopping_items")
}
