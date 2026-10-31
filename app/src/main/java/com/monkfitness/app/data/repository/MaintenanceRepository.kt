package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.MaintenanceDao

/**
 * Wipes the device's Program data and retained daily-track data as one unit — Settings → **Full reset**.
 * [inTransaction] comes from the caller (the database's transaction runner in production) because the
 * reset must land all or nothing: half a reset is indistinguishable from a finished one in the UI.
 * It reads nothing, decides nothing and returns nothing; the caller reports the exception.
 */
class MaintenanceRepository(
    private val dao: MaintenanceDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * Clears every user Program, opportunity, attempt, snapshot, confirmed set, pause and adaptive row,
     * resets the selection to the built-in Standard Program (its id is handed in, not hardcoded here),
     * and clears the posture/mobility track and the body-weight log.
     *
     * Nutrition is untouched: meal cycles, meals and the shopping list are keyed by their own calendar
     * and are not part of what a reset erases — the same boundary the screen's own text states.
     */
    suspend fun clearAll(standardProgramId: String) = inTransaction {
        // the state row first: it names a Program, and a named Program cannot be deleted while it does.
        dao.resetAppState(standardProgramId)
        // the attempt graph, leaves first.
        dao.clearConfirmedSets()
        dao.clearSessionExercises()
        dao.clearSnapshotElements()
        dao.clearSnapshots()
        dao.clearSessions()
        dao.clearAdjustments()
        dao.clearAdaptiveDecisions()
        dao.clearFamilyStates()
        // the opportunities and the pauses, then the Programs they belong to.
        dao.clearPauses()
        dao.clearSlots()
        dao.clearUserPrograms(standardProgramId)
        dao.clearPostureTrack()
        dao.clearBodyWeightLog()
    }
}
