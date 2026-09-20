package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkoutSessionRepository` against a real SQLite engine.
 *
 * The central claim of §19 is asserted the only way it can be believed: a session is persisted, the
 * **live plan is rewritten underneath it** (the presented element re-pointed and re-prescribed, and a
 * new revision saved), and the session still describes what it captured.
 */
class WorkoutSessionRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val slot get() = rig.graph.slotFor(1)

    private val session get() = ProgramGraphFixture.session("a", slot)

    private suspend fun startAndLoad(): com.monkfitness.app.domain.workout.WorkoutSession {
        rig.createGraph()
        rig.startSessionWithSets(session)
        return rig.freshSessionRepository().sessionById(SessionId("session-a"))!!
    }

    // ---- the snapshot guarantee (§19) --------------------------------------------------------------

    @Test
    fun theSessionKeepsItsCapturedPresentationWhenTheLivePlanIsRewritten() = runBlocking {
        val stored = startAndLoad()

        // The plan the session was started under is rewritten: the element it presented is re-pointed to
        // another exercise and its prescription replaced, and a whole new revision is saved on top.
        rig.database.exec(
            "UPDATE `program_exercise` SET `exerciseId` = 'diamond_pushup', " +
                "`perSetTargets` = '99,99,99,99' WHERE `programExerciseId` = 'plan-ex-a-1'"
        )
        rig.programPlanRepository.saveNewRevision(
            ProgramGraphFixture.nextRevision("a"),
            ProgramGraphFixture.FINISHED
        )

        val reloaded = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!

        assertEquals("the session is unchanged in every part", session, reloaded)
        assertEquals(
            "the presented exercise is the one captured, not the plan's current one",
            listOf("knee_pushup", "pike_pushup"),
            reloaded.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            "and the presented prescription is the captured one, per set",
            listOf(listOf(10, 8), listOf(8, 8)),
            reloaded.snapshot.workout.exercises.map { it.prescription.perSetTargets }
        )
        assertEquals(
            "the adjustments the snapshot had applied are returned as captured, not re-queried",
            listOf(AdjustmentId("adjustment-a-1")),
            reloaded.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "the live plan really did change underneath it",
            "diamond_pushup",
            rig.database.scalar("SELECT `exerciseId` FROM `program_exercise` WHERE `programExerciseId` = 'plan-ex-a-1'")
        )
        assertEquals(
            "and the session still names the revision it started under, not the new one",
            ProgramGraphFixture.revisionId("a"),
            reloaded.revisionId.value
        )
    }

    @Test
    fun aSessionWhoseSnapshotIsGoneIsInvalidPersistedDataAndNotASessionWithoutAPlan() = runBlocking {
        startAndLoad()
        rig.database.exec("DELETE FROM `session_snapshot_exercise`")
        rig.database.exec("DELETE FROM `session_snapshot`")

        val failure = failureOf { rig.freshSessionRepository().sessionById(SessionId("session-a")) }

        assertTrue(
            "a session cannot be loaded without the presentation it started under (§19): ${failure.message}",
            failure.message!!.contains("has no session_snapshot row")
        )
    }

    @Test
    fun theCapturedPresentationIsStoredWithTheSessionAndNotJoinedToThePlan() = runBlocking {
        startAndLoad()

        assertEquals(
            "the snapshot's elements are copied rows of their own",
            "knee_pushup",
            rig.database.scalar(
                "SELECT `exerciseId` FROM `session_snapshot_exercise` WHERE `sessionId` = 'session-a' AND `position` = 1"
            )
        )
        assertEquals(
            "while the plan row it came from still says what the plan says",
            "pushup",
            rig.database.scalar("SELECT `exerciseId` FROM `program_exercise` WHERE `programExerciseId` = 'plan-ex-a-1'")
        )
        assertEquals(
            "and the snapshot names the plan element without pointing at the live plan's row",
            "plan-ex-a-1",
            rig.database.scalar("SELECT `programExerciseId` FROM `session_snapshot_exercise` WHERE `sessionId` = 'session-a' AND `position` = 1")
        )
    }

    // ---- sets ------------------------------------------------------------------------------------

    @Test
    fun confirmedSetsAreRowsInProgramSetLogAndComeBackInSetOrder() = runBlocking {
        startAndLoad()
        val occurrence = SessionExerciseId("session-ex-a-1")

        rig.workoutSessionRepository.appendSet(occurrence, SetResult(SetLogId("set-a-3"), 3, 8, 0, ProgramGraphFixture.SET_THREE))
        val loaded = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!

        assertEquals(listOf(1, 2, 3), loaded.exercises.first().results.map { it.setIndex })
        assertEquals(
            "no set is lost, reordered or collapsed into a completed flag",
            listOf(12, 10, 8),
            loaded.exercises.first().results.map { it.completedReps }
        )
        assertEquals(
            "the third set was appended to the target table",
            "3",
            rig.database.scalar("SELECT COUNT(*) FROM `program_set_log`")
        )
        assertEquals(
            "and `program_set_log` is the only set log there is (§30 step 15 dropped the shipped one)",
            false,
            rig.database.tableNames().contains("set_log")
        )
    }

    @Test
    fun aMissingSetIsRefusedLoudlyRatherThanReconstructedAsZeroWork() = runBlocking {
        startAndLoad()
        rig.workoutSessionRepository.appendSet(
            SessionExerciseId("session-ex-a-1"),
            SetResult(SetLogId("set-a-3"), 3, 8, 0, ProgramGraphFixture.SET_THREE)
        )
        rig.database.exec("DELETE FROM `program_set_log` WHERE `setIndex` = 2")

        val failure = failureOf { rig.freshSessionRepository().sessionById(SessionId("session-a")) }

        assertTrue(
            "a gap in confirmed sets is invalid persisted data: ${failure.message}",
            failure.message!!.contains("[1, 3]")
        )
        assertTrue(
            "and nothing is invented in the gap's place",
            !failure.message!!.contains("[1, 0, 3]")
        )
    }

    @Test
    fun aSetIsRefusedForAnOccurrenceThatWasNeverStored() = runBlocking {
        rig.createGraph()

        val failure = failureOf {
            rig.workoutSessionRepository.appendSet(
                SessionExerciseId("session-ex-nowhere"),
                SetResult(SetLogId("set-x"), 1, 5, 0, ProgramGraphFixture.SET_ONE)
            )
        }

        assertTrue(failure.message!!.contains("session-ex-nowhere"))
        assertEquals(0, rig.database.count("program_set_log"))
    }

    @Test
    fun aSkippedOccurrenceCarriesNoSetsAtAll() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(
            ProgramGraphFixture.session("a", slot, skipped = true)
        )

        val loaded = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!

        assertTrue(loaded.exercises.last().skipped)
        assertEquals(emptyList<SetResult>(), loaded.exercises.last().results)
        assertEquals(
            "a skipped occurrence has no set rows, not zero rows (§12)",
            "0",
            rig.database.scalar("SELECT COUNT(*) FROM `program_set_log` WHERE `sessionExerciseId` = 'session-ex-a-2'")
        )
    }

    // ---- several attempts ------------------------------------------------------------------------

    @Test
    fun twoAttemptsAtOneSlotAreLegalAndBothLoadOnceTheFirstHasEnded() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(session)
        rig.workoutSessionRepository.finishSession(
            session.copy(status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED),
            ProgramGraphFixture.completedSlot("a", slot)
        )
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("b", slot))


        val attempts = rig.freshSessionRepository().sessionsOfSlot(slot.slotId)

        assertEquals("several attempts are legal and all of them are returned (§19)", 2, attempts.size)
        assertEquals(
            "in start order",
            listOf("session-a", "session-b"),
            attempts.map { it.sessionId.value }
        )
        assertEquals(
            "and the slot reports them as its attempts",
            listOf("session-a", "session-b"),
            rig.programScheduleRepository.slotById(slot.slotId)!!.attempts.map { it.value }
        )
        assertEquals(
            "the second attempt is a row of its own, not a replacement: the first one is still stored, " +
                "completed, and the slot was never made unique on `slotId`",
            "2",
            rig.database.scalar("SELECT COUNT(*) FROM `workout_session` WHERE `slotId` = 'slot-a-1'")
        )
    }

    /**
     * The rule counts *unfinished* attempts, not attempts in the same state as the row being written.
     *
     * Storing a finished attempt (a restored one, an imported one, a fixture's) is not what §19 forbids,
     * and such a row does **not** hold the opportunity: what the write refuses is a second attempt while
     * one is running. A finished attempt can therefore be stored, a new attempt can be started beside it,
     * and only then is a third attempt refused.
     */
    @Test
    fun aFinishedAttemptIsStoredAndDoesNotHoldTheOpportunity() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(finishedAttempt())
        assertEquals(
            "a finished attempt at the opportunity is a row like any other",
            listOf("COMPLETED"),
            rig.database.strings("SELECT `status` FROM `workout_session` WHERE `slotId` = 'slot-a-1'")
        )

        rig.workoutSessionRepository.startSession(session)
        assertEquals(
            "and a new attempt may be started beside it, because nothing was in progress",
            listOf("IN_PROGRESS", "COMPLETED"),
            rig.database.strings(
                "SELECT `status` FROM `workout_session` WHERE `slotId` = 'slot-a-1' ORDER BY `sessionId` ASC"
            )
        )

        val failure = failureOf {
            rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("c", slot))
        }
        assertTrue(
            "while a second attempt in progress is refused: ${failure.message}",
            failure is SessionAlreadyInProgress
        )
        assertEquals(
            "and the finished attempt is untouched by any of it",
            "COMPLETED",
            rig.database.scalar("SELECT `status` FROM `workout_session` WHERE `sessionId` = 'session-done'")
        )
    }

    /** A finished attempt at the fixture's slot: its own identity, its own occurrences, no sets. */
    private fun finishedAttempt() = session.copy(
        sessionId = SessionId("session-done"),
        status = SessionStatus.COMPLETED,
        finishedAt = ProgramGraphFixture.FINISHED,
        snapshot = session.snapshot.copy(sessionId = SessionId("session-done")),
        exercises = session.exercises.mapIndexed { index, occurrence ->
            occurrence.copy(
                sessionExerciseId = SessionExerciseId("session-ex-done-${index + 1}"),
                results = emptyList()
            )
        }
    )

    // ---- the occupancy rule, decided by the write (§19) ------------------------------------------

    /**
     * §19's *"no more than one session per slot is `IN_PROGRESS`"*, proved where it is enforced.
     *
     * This test calls the repository **directly**, with no use case above it: it is the persistence
     * boundary's own proof. The second start is refused because the statement that stores a session
     * stores nothing when the slot already holds an attempt in the same status — not because something
     * read the table first and decided otherwise.
     */
    @Test
    fun aSecondInProgressAttemptAtOneSlotIsRefusedByTheWriteAndLeavesNothingBehind() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(session)
        val before = sessionTableCounts()

        val failure = failureOf {
            rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("b", slot))
        }

        assertTrue(
            "the refused start reports the rule it broke and the slot it was attempted on: ${failure.message}",
            failure is SessionAlreadyInProgress && failure.slotId == slot.slotId
        )
        assertEquals(
            "and the refused attempt left no session, no snapshot, no captured element, no occurrence " +
                "and no set behind — the refusal is inside the transaction that would have written them",
            before,
            sessionTableCounts()
        )
        assertEquals(
            "the attempt that was already in progress is untouched",
            listOf("session-a"),
            rig.database.strings("SELECT `sessionId` FROM `workout_session` WHERE `slotId` = 'slot-a-1'")
        )
        assertEquals(
            "and it is still in progress",
            "IN_PROGRESS",
            rig.database.scalar("SELECT `status` FROM `workout_session` WHERE `sessionId` = 'session-a'")
        )
    }

    /**
     * The rule is about a slot's *unfinished* attempt, so it refuses nothing else: a second slot is
     * startable while the first is being worked out, and a cancelled attempt leaves the opportunity open
     * for a new one.
     */
    @Test
    fun theOccupancyRuleRefusesOnlyASecondUnfinishedAttemptAtTheSameSlot() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(session)

        rig.workoutSessionRepository.startSession(
            ProgramGraphFixture.session("other", rig.graph.slotFor(2))
        )
        assertEquals(
            "another opportunity is being worked out at the same time — the rule is per slot, not global",
            listOf("session-a", "session-other"),
            rig.database.strings("SELECT `sessionId` FROM `workout_session` ORDER BY `startedAt` ASC, `sessionId` ASC")
        )

        rig.workoutSessionRepository.recordSessionOutcome(
            session.copy(status = SessionStatus.CANCELLED, finishedAt = ProgramGraphFixture.FINISHED)
        )
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("b", slot))

        assertEquals(
            "and a cancelled attempt leaves the opportunity open for the next one (§19)",
            listOf("session-a", "session-b"),
            rig.database.strings(
                "SELECT `sessionId` FROM `workout_session` WHERE `slotId` = 'slot-a-1' " +
                    "ORDER BY `startedAt` ASC, `sessionId` ASC"
            )
        )
    }

    /** The row count of every table a start writes: the census a refused start must not move. */
    private fun sessionTableCounts(): List<Int> =
        listOf("workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise", "program_set_log")
            .map { rig.database.count(it) }

    // ---- transactions ----------------------------------------------------------------------------

    @Test
    fun startingASessionIsAtomic() = runBlocking {
        rig.createGraph()
        rig.faults.failSnapshotInsert = true

        val failure = failureOf { rig.workoutSessionRepository.startSession(session) }

        assertTrue(failure.message!!.contains("planted fault"))
        assertEquals(
            "no session, no snapshot, no captured element and no occurrence survived",
            listOf(0, 0, 0, 0),
            listOf("workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise")
                .map { rig.database.count(it) }
        )
    }

    @Test
    fun finishingWritesTheSessionAndItsSlotAsOneFact() = runBlocking {
        startAndLoad()
        val finished = session.copy(status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED)

        rig.workoutSessionRepository.finishSession(finished, ProgramGraphFixture.completedSlot("a", slot))

        val stored = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!
        val storedSlot = rig.programScheduleRepository.slotById(slot.slotId)!!

        assertEquals(SessionStatus.COMPLETED, stored.status)
        assertEquals(ProgramGraphFixture.FINISHED, stored.finishedAt)
        assertEquals(ProgramGraphFixture.CAPTURED, stored.startedAt)
        assertEquals(
            "the planned date and the actual timestamps are both preserved (§19)",
            slot.plannedFor,
            stored.snapshot.workout.plannedFor
        )
        assertEquals(SlotStatus.COMPLETED, storedSlot.status)
        assertEquals(ProgramGraphFixture.FINISHED, storedSlot.completedAt)
        assertEquals(listOf(SessionId("session-a")), storedSlot.attempts)
    }

    @Test
    fun aCancelledSessionIsNotACompletedOneAndKeepsItsPartialWork() = runBlocking {
        startAndLoad()
        val cancelled = session.copy(status = SessionStatus.CANCELLED, finishedAt = ProgramGraphFixture.FINISHED)

        rig.workoutSessionRepository.finishSession(
            cancelled,
            slot.copy(status = SlotStatus.MISSED, attempts = listOf(SessionId("session-a")))
        )

        val stored = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!
        val counts = rig.programProgressRepository.sessionStatusCounts(rig.graph.program.programId)

        assertEquals(SessionStatus.CANCELLED, stored.status)
        assertEquals("the work it did observe stays recorded", 2, stored.exercises.first().results.size)
        assertEquals("cancelled is counted as cancelled", 1, counts[SessionStatus.CANCELLED])
        assertEquals("and never as completed (§19)", 0, counts[SessionStatus.COMPLETED])
        assertEquals(
            "a missed opportunity is a missed opportunity, with no work attached",
            SlotStatus.MISSED,
            rig.programScheduleRepository.slotById(slot.slotId)!!.status
        )
        assertNull(rig.programScheduleRepository.slotById(slot.slotId)!!.completedAt)
    }

    @Test
    fun aFinishThatNamesAnotherSlotIsRefusedBeforeAnyWrite() = runBlocking {
        startAndLoad()
        val otherSlot = rig.graph.slotFor(2)

        val failure = failureOf {
            rig.workoutSessionRepository.finishSession(
                session.copy(status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED),
                ProgramGraphFixture.completedSlot("a", otherSlot)
            )
        }

        assertTrue(failure.message!!.contains("records its own slot"))
        assertEquals(
            "and nothing was written",
            "IN_PROGRESS",
            rig.database.scalar("SELECT `status` FROM `workout_session` WHERE `sessionId` = 'session-a'")
        )
    }

    @Test
    fun aSessionReadByIdentityIsAbsentAndNotAFailure() = runBlocking {
        assertNull(rig.freshSessionRepository().sessionById(SessionId("session-nowhere")))
        assertEquals(emptyList<Any>(), rig.freshSessionRepository().sessionsOfSlot(SlotId("slot-nowhere")))
    }
}
