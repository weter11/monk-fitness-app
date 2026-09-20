package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.MaintenanceDao

/**
 * The device's Program data and retained daily-track data, wiped as one unit — the storage half of
 * Settings → **Full reset**.
 *
 * ### Why it is a repository and not a call on a DAO
 *
 * A full reset spans tables that different layers own, and it must land **all or nothing**: a reset that
 * stopped half-way would leave a user with no opportunities but a history, or with a cleared track and an
 * intact program, and neither half is distinguishable from a finished reset in the UI. The unit is
 * therefore supplied by the caller as [inTransaction], exactly as the retired `ProgramMaintenance` took it
 * — the production caller passes the database's own transaction runner, and a test passes one that either
 * applies everything or nothing, which is what makes the guarantee measurable on a JVM.
 *
 * ### What it clears, and what it leaves
 *
 * The list and the reasons are [MaintenanceDao]'s; this type adds only the order and the transaction. It
 * reads nothing, decides nothing and returns nothing: a reset has no result beyond "it happened", and the
 * caller reports the exception if it did not.
 *
 * The Standard Program's id is handed in rather than hardcoded here, because the constant belongs to the
 * layer that owns §12's rules for it.
 *
 * @param dao the reset's statements.
 * @param inTransaction runs the whole reset inside one database transaction.
 */
class MaintenanceRepository(
    private val dao: MaintenanceDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * Clears every Program the user created, every opportunity, attempt, snapshot, confirmed set, pause and
     * target adaptive row, resets the selection back to the built-in Standard Program, and clears the
     * posture/mobility track and the body-weight log.
     *
     * Nutrition is untouched: meal cycles, meals and the shopping list are keyed by their own calendar and
     * are not part of what a reset erases — the same boundary the screen's own text states.
     *
     * @param standardProgramId the built-in Standard Program's id (§12), kept and made the selection.
     */
    suspend fun clearAll(standardProgramId: String) = inTransaction {
        // 1. the state row first: it names a Program, and a named Program cannot be deleted while it does.
        dao.resetAppState(standardProgramId)
        // 2. the attempt graph, leaves first.
        dao.clearConfirmedSets()
        dao.clearSessionExercises()
        dao.clearSnapshotElements()
        dao.clearSnapshots()
        dao.clearSessions()
        // 3. the target adaptive rows.
        dao.clearAdjustments()
        dao.clearAdaptiveDecisions()
        dao.clearFamilyStates()
        // 4. the opportunities and the pauses, then the Programs they belong to.
        dao.clearPauses()
        dao.clearSlots()
        dao.clearUserPrograms(standardProgramId)
        // 5. the retained daily tracks.
        dao.clearPostureTrack()
        dao.clearBodyWeightLog()
    }
}
