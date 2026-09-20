package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Query

/**
 * The statements **Full Reset** is made of — and nothing else.
 *
 * They live in their own DAO rather than on one of the table owners, because a full reset is not an
 * operation *of* any of them: it is the app's own maintenance action, and it touches the Program System's
 * tables, the global state row and the two retained daily tracks in one unit. Keeping the statements here
 * makes the contract readable in one place — which tables it clears, which row survives and which tables
 * are deliberately absent.
 *
 * ### The plan, mapped from the contract that already existed
 *
 * The C3 **Full Reset** contract was *"everything the program records, except nutrition plans"*, expressed
 * as two lists in the retired `ProgramMaintenance`. §30 step 15 did not invent a new contract; it moved
 * those two lists onto the tables that exist now:
 *
 * ```text
 * cleared   every Program the user made, every opportunity, attempt, snapshot and confirmed set, every
 *           pause, the target adaptive rows, the selection state, the posture/mobility track and the
 *           body-weight log
 * kept      the built-in Standard Program's own *definition* (it is a built-in, §12) and the nutrition
 *           plans (their meal-cycle calendar is independent of any Program)
 * ```
 *
 * Every retired table the old contract named (`user_progress`, `program_day_state`, `set_log`,
 * `family_progression_state`, `adaptive_decision_record`) is **absent on purpose**: those tables no longer
 * exist, and a reference to one would be a reference to something the schema does not have.
 *
 * The order of the statements is the order of their foreign keys, leaf first: `app_state` is cleared
 * before any Program row disappears (its `selectedProgramId` column is `NO_ACTION`, so a Program it still
 * named could not be deleted), and the attempt graph is emptied before the opportunities it hangs from.
 */
@Dao
interface MaintenanceDao {

    /**
     * Returns `app_state` to the app's built-in starting point: the selection is the Standard Program and
     * the next-Program pointer is cleared.
     *
     * It is an `UPDATE` rather than a `DELETE` because the row is a singleton the rest of the app reads; and
     * it must happen **before** the Program rows go, because `app_state.selectedProgramId` refuses the
     * deletion of a Program it still names.
     *
     * @param standardProgramId the built-in Standard Program's id (§12). The row keeps a real Program as its
     *   selection, which is what makes *"one target Standard Program identity, always selectable"* still
     *   true after a reset.
     */
    @Query(
        """
        UPDATE `app_state`
        SET `selectedProgramId` = :standardProgramId,
            `nextProgramId` = NULL,
            `nextProgramAutoStart` = 0
        """
    )
    suspend fun resetAppState(standardProgramId: String)

    // ---- the attempt graph, leaves first ---------------------------------------------------------

    @Query("DELETE FROM `program_set_log`")
    suspend fun clearConfirmedSets()

    @Query("DELETE FROM `session_exercise`")
    suspend fun clearSessionExercises()

    @Query("DELETE FROM `session_snapshot_exercise`")
    suspend fun clearSnapshotElements()

    @Query("DELETE FROM `session_snapshot`")
    suspend fun clearSnapshots()

    @Query("DELETE FROM `workout_session`")
    suspend fun clearSessions()

    // ---- the target adaptive state ---------------------------------------------------------------

    @Query("DELETE FROM `adaptive_adjustment`")
    suspend fun clearAdjustments()

    @Query("DELETE FROM `program_adaptive_decision_record`")
    suspend fun clearAdaptiveDecisions()

    @Query("DELETE FROM `program_family_progression_state`")
    suspend fun clearFamilyStates()

    // ---- the plan's opportunities and pauses -----------------------------------------------------

    @Query("DELETE FROM `program_pause`")
    suspend fun clearPauses()

    @Query("DELETE FROM `program_workout_slot`")
    suspend fun clearSlots()

    /**
     * Removes every Program the user made, and keeps the built-in one.
     *
     * The deletion cascades (`program_revision` → `program_day` → `program_exercise`), which is what makes
     * this one statement rather than six. The Standard Program's *definition* survives, so §12's rules about
     * it stay true; its opportunities and attempts were already cleared by the statements above, so the
     * Scheduler will simply plan it again on the next pass.
     */
    @Query("DELETE FROM `program` WHERE `programId` != :standardProgramId")
    suspend fun clearUserPrograms(standardProgramId: String)

    // ---- the retained daily tracks ---------------------------------------------------------------

    @Query("DELETE FROM `posture_session_progress`")
    suspend fun clearPostureTrack()

    @Query("DELETE FROM `body_weight_log`")
    suspend fun clearBodyWeightLog()
}
