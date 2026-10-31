package com.monkfitness.app.viewmodel

import com.monkfitness.app.R

/**
 * A destructive action must never end in a state the user cannot distinguish from success, so every
 * path reports what it changed or the failure that stopped it — including the partial one.
 */
sealed interface MaintenanceResult {
    /** The user-facing string for this outcome — a confirmation or a failure reason. */
    val messageRes: Int

    data class Success(override val messageRes: Int) : MaintenanceResult
    /** The action failed; [messageRes] names what did not complete. */
    data class Failure(override val messageRes: Int) : MaintenanceResult
}

private const val TAG = "MainViewModel"

/**
 * Defaulted to `android.util.Log` and replaced in unit tests, where the Android `Log` static is not
 * available.
 */
typealias FailureLogger = (tag: String, message: String, error: Throwable) -> Unit

private val defaultLogger: FailureLogger = { tag, message, error ->
    android.util.Log.e(tag, message, error)
}

/**
 * Room is wiped, then preferences cleared: the two stores cannot share a transaction, so this is an
 * ordered sequence and a prefs failure after the wipe is a PARTIAL reset — reported as a Failure
 * naming that state, never as a Success. Top-level so it is unit-testable on a JVM.
 */
suspend fun runFullReset(
    clearRoomData: suspend () -> Unit,
    clearPreferences: suspend () -> Unit,
    log: FailureLogger = defaultLogger
): MaintenanceResult = try {
    clearRoomData()
    try {
        clearPreferences()
        MaintenanceResult.Success(R.string.program_controls_reset_done)
    } catch (e: Exception) {
        log(TAG, "fullReset: database cleared, preference clear failed", e)
        MaintenanceResult.Failure(R.string.program_controls_reset_db_cleared_prefs_failed)
    }
} catch (e: Exception) {
    log(TAG, "fullReset: not performed", e)
    MaintenanceResult.Failure(R.string.program_controls_reset_failed)
}
