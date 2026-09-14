package com.monkfitness.app.viewmodel

import com.monkfitness.app.R

/**
 * The outcome of a C3 maintenance action. A destructive operation must never end in a state the
 * user cannot distinguish from success, so every path reports either what it changed or the
 * failure that stopped it — including the partial one, see [runFullReset].
 */
sealed interface MaintenanceResult {
    /** The user-facing string for this outcome — a confirmation or a failure reason. */
    val messageRes: Int

    /** The action completed. */
    data class Success(override val messageRes: Int) : MaintenanceResult
    /** The action failed; [messageRes] names what did not complete. */
    data class Failure(override val messageRes: Int) : MaintenanceResult
}

private const val TAG = "MainViewModel"

/**
 * Called when a C3 phase fails, with a short tag and the exception. Defaulted to `android.util.Log`
 * and replaced in unit tests, where the Android `Log` static is not available.
 */
typealias FailureLogger = (tag: String, message: String, error: Throwable) -> Unit

private val defaultLogger: FailureLogger = { tag, message, error ->
    android.util.Log.e(tag, message, error)
}

/**
 * The two phases of C3 "Full Reset": the Room tables are wiped, then the DataStore preferences are
 * cleared. The two stores cannot share a transaction, so the reset is an ordered sequence, not an
 * atomic one — and the failure modes are NOT interchangeable:
 *
 * - [clearRoomData] fails → nothing was erased at all. The database is fully intact, the
 *   preferences are untouched, and the user can retry. Reported with
 *   [R.string.program_controls_reset_failed].
 * - [clearRoomData] succeeds but [clearPreferences] fails → a PARTIAL destructive state: the
 *   database is empty while the preferences still hold the old start date, cycle number and
 *   settings. This is reported as a **Failure**, never as a Success, with
 *   [R.string.program_controls_reset_db_cleared_prefs_failed], which names the exact state and
 *   tells the user how to finish the reset. Reporting success here would leave the user believing
 *   a finished reset that is half done.
 *
 * Extracted to a top-level function so the decision table above is unit-testable on a JVM
 * (`MainViewModel` is an `AndroidViewModel` and the project has no Robolectric harness).
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
