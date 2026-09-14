package com.monkfitness.app.viewmodel

import com.monkfitness.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The C3 "Full Reset" failure decision table — Room tables and DataStore preferences cannot share a
 * transaction, so the two phases fail differently and the outcome must say which one happened:
 *
 * | Room clear | Preference clear | Outcome                                            |
 * |------------|------------------|----------------------------------------------------|
 * | ok         | ok               | Success                                            |
 * | failed     | (not reached)    | Failure — nothing was erased, everything intact    |
 * | ok         | failed           | Failure — the partial state is NAMED, not hidden   |
 *
 * The third row is the regression this suite exists for: an earlier revision swallowed the
 * preference-clear failure and reported Success while the database was empty and the preferences
 * still held the old start date and cycle number. The user would believe a finished reset that was
 * half done.
 *
 * [runFullReset] is extracted from `MainViewModel` (an `AndroidViewModel`; the project has no
 * Robolectric harness and CI is JVM-only) so the decision table is directly testable.
 */
class FullResetDecisionTest {

    /** Records what the maintenance path logged, so a failure is proven logged, not assumed. */
    private val logged = mutableListOf<String>()

    private val logger: FailureLogger = { _, message, _ ->
        logged += message
    }

    @Test
    fun bothPhasesSucceedReportsSuccess() {
        var roomCleared = false
        var prefsCleared = false

        val result = runBlocking {
            runFullReset(
                clearRoomData = { roomCleared = true },
                clearPreferences = { prefsCleared = true },
                log = logger
            )
        }

        assertTrue("the room tables were cleared", roomCleared)
        assertTrue("the preferences were cleared", prefsCleared)
        assertTrue("both phases green is Success", result is MaintenanceResult.Success)
        assertEquals(
            R.string.program_controls_reset_done,
            result.messageRes
        )
        assertTrue(
            "nothing is logged when nothing failed",
            logged.isEmpty()
        )
    }

    @Test
    fun roomClearFailureReportsFailureAndClearsNothing() {
        var roomCleared = false
        var prefsCleared = false

        val result = runBlocking {
            runFullReset(
                clearRoomData = { throw IllegalStateException("database: disk full") },
                clearPreferences = { prefsCleared = true },
                log = logger
            )
        }

        assertFalse("the room clear threw, so its flag never flipped", roomCleared)
        assertFalse(
            "a Room failure never reaches the preference phase",
            prefsCleared
        )
        assertTrue("a failed wipe is a Failure", result is MaintenanceResult.Failure)
        assertEquals(
            "the message says the data is intact and retryable",
            R.string.program_controls_reset_failed,
            result.messageRes
        )
        assertTrue(
            "the failure was logged, not swallowed",
            logged.any { it.contains("not performed") }
        )
    }

    @Test
    fun preferenceClearFailureAfterAClearedDatabaseReportsFailure() {
        // The regression: Room succeeded, so the database IS empty, but the preferences still hold
        // the old start date, cycle number and settings. This is a partial destructive state and
        // must NOT be reported as success.
        var roomCleared = false
        var prefsCleared = false

        val result = runBlocking {
            runFullReset(
                clearRoomData = { roomCleared = true },
                clearPreferences = { throw IllegalStateException("datastore: write failed") },
                log = logger
            )
        }

        assertTrue("the room tables were cleared before the failure", roomCleared)
        assertFalse(
            "the preference clear threw, so its flag never flipped",
            prefsCleared
        )
        assertTrue(
            "a half-finished reset is a Failure, not a Success",
            result is MaintenanceResult.Failure
        )
        assertNotEquals(
            "the partial state is not reported with the success message",
            R.string.program_controls_reset_done,
            result.messageRes
        )
        assertEquals(
            "the message names the exact partial state: database cleared, preferences not",
            R.string.program_controls_reset_db_cleared_prefs_failed,
            result.messageRes
        )
        assertTrue(
            "the partial failure was logged, not swallowed",
            logged.any { it.contains("preference clear failed") }
        )
    }

    @Test
    fun thePartialFailureMessageIsADifferentMessageFromTheTotalFailure() {
        // Both failures must be distinguishable in the UI: one means "nothing happened, retry",
        // the other means "half of it happened, here is how to finish".
        assertNotEquals(
            "a partial reset is not reported as if nothing had been erased",
            R.string.program_controls_reset_failed,
            R.string.program_controls_reset_db_cleared_prefs_failed
        )
    }
}
