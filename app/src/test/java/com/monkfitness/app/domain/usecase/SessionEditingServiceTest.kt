package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two corrections a running attempt accepts, against a real SQLite engine: undo removes exactly the
 * set performed last and leaves the rest numbered `1..n`, and a skip is a flag an occurrence with no sets
 * can carry — while the runtime still refuses work on a skipped one.
 */
class SessionEditingServiceTest {

    private val rig = SessionRuntimeRig("editing")
    private val editing = SessionEditingService(rig.freshSessionRepository())

    private suspend fun started(): WorkoutSession {
        rig.createProgram()
        val started = rig.runtime.startSession(rig.slotId(1))
        return (started as SessionRuntimeResult.Success).value
    }

    private suspend fun confirm(session: WorkoutSession, occurrence: Int, reps: Int) {
        // Strictly increasing, so "the set performed last" is a fact the clock states rather than a tie.
        rig.clock.instant = rig.clock.instant.plusSeconds(60)
        val exercise = session.exercises[occurrence]
        val confirmed = rig.runtime.confirmSet(
            sessionId = session.sessionId,
            sessionExerciseId = exercise.sessionExerciseId,
            completedReps = reps
        )
        check(confirmed is SessionRuntimeResult.Success) { "the set was not stored: $confirmed" }
    }

    private suspend fun stored(session: WorkoutSession): WorkoutSession =
        requireNotNull(rig.freshSessionRepository().sessionById(session.sessionId))

    private fun setIndexes(session: WorkoutSession, occurrence: Int): List<Int> =
        session.exercises[occurrence].results.map { set -> set.setIndex }

    @Test
    fun undoRemovesExactlyTheSetPerformedLast() = runBlocking {
        val session = started()
        confirm(session, 0, reps = 12)
        confirm(session, 1, reps = 8)
        confirm(session, 0, reps = 10)
        assertEquals("three sets are recorded", 3, rig.database.count("program_set_log"))

        val undone = editing.undoLastSet(session.sessionId) as SessionRuntimeResult.Success
        assertEquals(
            "the last set of the occurrence it belonged to is gone",
            listOf(1),
            setIndexes(stored(session), 0)
        )
        assertEquals("the other occurrence is untouched", listOf(1), setIndexes(undone.value, 1))
        assertEquals(2, rig.database.count("program_set_log"))

        editing.undoLastSet(session.sessionId)
        assertEquals("then the other occurrence's only set", listOf(1), setIndexes(stored(session), 0))
        assertEquals(emptyList<Int>(), setIndexes(stored(session), 1))
        assertEquals(1, rig.database.count("program_set_log"))

        editing.undoLastSet(session.sessionId)
        assertEquals(0, rig.database.count("program_set_log"))

        val refused = editing.undoLastSet(session.sessionId)
        assertTrue(
            "there is nothing left to take back",
            refused is SessionRuntimeResult.Refused && refused.reason is SessionRefusal.NothingToUndo
        )
        assertEquals("and a refused undo writes nothing", 0, rig.database.count("program_set_log"))
    }

    @Test
    fun undoIsRefusedOnAnAttemptThatIsNoLongerRunning() = runBlocking {
        val session = started()
        confirm(session, 0, reps = 12)
        rig.runtime.cancelSession(session.sessionId)

        val refused = editing.undoLastSet(session.sessionId)
        assertTrue(
            "a cancelled attempt is not corrected",
            refused is SessionRuntimeResult.Refused && refused.reason is SessionRefusal.SessionIsNotInProgress
        )
        assertEquals("its set is still recorded", 1, rig.database.count("program_set_log"))
    }

    @Test
    fun skipRecordsTheFlagAndWritesNoSetRow() = runBlocking {
        val session = started()
        val second = session.exercises[1].sessionExerciseId

        val skipped = editing.skipOccurrence(session.sessionId, second) as SessionRuntimeResult.Success

        assertTrue("the flag is stored", skipped.value.exercises[1].skipped)
        assertFalse("and the first occurrence is not skipped", skipped.value.exercises[0].skipped)
        assertEquals("a skip writes no set row", 0, rig.database.count("program_set_log"))

        val reloaded = stored(session)
        assertTrue(
            "a fresh read sees it, so the flag is the row rather than a cache",
            reloaded.exercises.single { exercise -> exercise.sessionExerciseId == second }.skipped
        )

        val again = editing.skipOccurrence(session.sessionId, second)
        assertTrue("skipping twice is answered, not refused", again is SessionRuntimeResult.Success)
        assertTrue((again as SessionRuntimeResult.Success).value.exercises[1].skipped)
    }

    @Test
    fun skipIsRefusedWhenTheOccurrenceAlreadyHoldsSets() = runBlocking {
        val session = started()
        confirm(session, 1, reps = 8)
        val second = session.exercises[1].sessionExerciseId

        val refused = editing.skipOccurrence(session.sessionId, second)

        assertTrue(
            "a skipped occurrence observed nothing, so it cannot stand beside a set",
            refused is SessionRuntimeResult.Refused &&
                refused.reason is SessionRefusal.SkippingWouldEraseConfirmedSets
        )
        assertEquals(
            "and its set is still recorded",
            1,
            rig.database.count("program_set_log")
        )
        assertFalse(stored(session).exercises[1].skipped)
    }

    @Test
    fun theRuntimeRefusesASetOnASkippedOccurrence() = runBlocking {
        val session = started()
        val second = SessionExerciseId(session.exercises[1].sessionExerciseId.value)
        editing.skipOccurrence(session.sessionId, second)

        val refused = rig.runtime.confirmSet(
            sessionId = session.sessionId,
            sessionExerciseId = second,
            completedReps = 8
        )

        assertTrue(
            "a skipped occurrence takes no work",
            refused is SessionRuntimeResult.Refused && refused.reason is SessionRefusal.OccurrenceWasSkipped
        )
        assertEquals(0, rig.database.count("program_set_log"))
    }
}
