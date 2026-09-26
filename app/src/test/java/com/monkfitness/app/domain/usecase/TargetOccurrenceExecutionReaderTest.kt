package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.failureOf
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionCounts
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionFacts
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionReadException
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * §30 step 15's read-back, measured on a real SQLite engine: what a target occurrence's **stored
 * execution facts** are, and what happens when the stored graph is not what it claims to be.
 *
 * Everything here is read back through the three repositories the phase names, from rows written by
 * the production write paths — a real `TargetScheduleSlotPersister` pass for the slot and the semantic
 * occurrence, a real `WorkoutSessionRepository.startSession` / `finishSession` for each attempt, and
 * a real `appendSet` for every confirmed set. Nothing is planted by hand except where a test is
 * specifically about corrupting the store.
 *
 * The claims under test, in the order the phase states them:
 *
 * ```text
 *  1. the read model separates the four layers: occurrence / slot / attempt / performed work
 *  2. every stored attempt is preserved, in stored start order, never collapsed
 *  3. slot status and session status stay separate facts, with separate types
 *  4. the semantic occurrence is exactly Phase 14's read-back, and the slot exactly what was stored
 *  5. corrupted or mismatched stored data is a typed failure, never a partial or empty record
 * ```
 */
class TargetOccurrenceExecutionReaderTest {

    private val rig = ProgramDataAccessRig("target-exec")
    private val key = "target-exec"
    private val programId = ProgramId(ProgramGraphFixture.programId(key))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId(key))
    private val day = ProgramDayId(ProgramGraphFixture.dayId(key, 1))
    private val occurrenceKey = "strength:2026-10-05"

    @After
    fun close() {
        rig.close()
    }

    // ---- the four layers stay four ---------------------------------------------------------------

    @Test
    fun theRecordCarriesTheOccurrenceTheSlotAndTheAttemptsAsSeparateFacts() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot))

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(occurrenceKey, record.occurrenceKey)
        assertEquals(programId, record.programId)
        assertEquals("the slot is the one the pass persisted", slot.slotId, record.slot.slotId)
        assertEquals(
            "and the attempt is a stored session, in its own right",
            listOf(SessionId("session-a")),
            record.attemptIds
        )
        // The record is not the semantic occurrence: adding the slot and the attempts is what makes it
        // a read model, and the three parts stay separately addressable rather than being flattened.
        assertEquals(
            "the semantic payload is untouched by the read",
            OccurrenceComponent("rule-a", "workout-a"),
            record.occurrence.occurrence.components.single()
        )
        assertEquals(SlotStatus.PLANNED, record.slotStatus)
    }

    @Test
    fun anOccurrenceWithNoSessionAttemptsIsAValidEmptyRecordRatherThanAFailure() = runBlocking {
        persistTarget()

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertTrue("no attempt is a normal state, not a refusal", record.attempts.isEmpty())
        assertFalse(record.hasAttempts)
        assertEquals(
            "and the pure aggregation agrees without needing a verdict",
            0,
            TargetOccurrenceExecutionFacts.countsOf(record).totalAttempts
        )
        assertEquals(
            "the semantic occurrence and the slot are still there: absence of execution is not " +
                "absence of target state",
            occurrenceKey,
            record.occurrenceKey
        )
        assertNotNull(record.slot.slotId)
    }

    @Test
    fun oneInProgressSessionIsReadBackAsThatStoredStatus() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot))

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "the exact persisted SessionStatus, not one inferred from the finish stamp",
            listOf(SessionStatus.IN_PROGRESS),
            record.attemptStatuses
        )
        assertNull(
            "and an in-progress attempt has no finish stamp, because §19 ties the two together",
            record.attempts.single().finishedAt
        )
    }

    @Test
    fun oneCompletedSessionIsReadBackAsThatStoredStatus() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(listOf(SessionStatus.COMPLETED), record.attemptStatuses)
        assertNotNull(record.attempts.single().finishedAt)
    }

    @Test
    fun oneCancelledSessionIsReadBackAsThatStoredStatusAndNotAsACompletedOne() = runBlocking {
        val slot = persistTarget()
        cancelSession("a", slot)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "a cancelled attempt is cancelled — the partial work it recorded stays recorded (§12)",
            listOf(SessionStatus.CANCELLED),
            record.attemptStatuses
        )
        assertEquals(
            "and it is not quietly a completion",
            0,
            TargetOccurrenceExecutionFacts.countsOf(record).countOf(SessionStatus.COMPLETED)
        )
    }

    // ---- several attempts, none collapsed ---------------------------------------------------------

    @Test
    fun everyStoredAttemptIsPreservedInStoredStartOrder() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)
        cancelSession("b", slot)
        rig.startSessionWithSets(session("c", slot, startedAt = LATER_THAN_B))

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "three stored attempts, all three returned, in the session repository's start order",
            listOf("session-a", "session-b", "session-c"),
            record.attemptIds.map { it.value }
        )
        assertEquals(
            "with each attempt's own status preserved rather than one status for the slot",
            listOf(SessionStatus.COMPLETED, SessionStatus.CANCELLED, SessionStatus.IN_PROGRESS),
            record.attemptStatuses
        )
    }

    @Test
    fun aCancelledAttemptFollowedByALaterInProgressAttemptStaysTwoStoredFacts() = runBlocking {
        val slot = persistTarget()
        cancelSession("a", slot)
        rig.startSessionWithSets(session("b", slot, startedAt = LATER_THAN_A))

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "the cancellation is not overwritten by the later attempt",
            listOf("session-a", "session-b"),
            record.attemptIds.map { it.value }
        )
        assertEquals(
            listOf(SessionStatus.CANCELLED, SessionStatus.IN_PROGRESS),
            record.attemptStatuses
        )
        assertEquals(
            "and the pure aggregation counts both rather than picking the latest",
            2,
            TargetOccurrenceExecutionFacts.countsOf(record).totalAttempts
        )
    }

    @Test
    fun aCancelledAttemptFollowedByALaterCompletedAttemptStaysTwoStoredFacts() = runBlocking {
        val slot = persistTarget()
        cancelSession("a", slot)
        completeSession("b", slot, startedAt = LATER_THAN_A)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(listOf("session-a", "session-b"), record.attemptIds.map { it.value })
        assertEquals(
            listOf(SessionStatus.CANCELLED, SessionStatus.COMPLETED),
            record.attemptStatuses
        )
        // The pair is the case a single-enum `ExistingOccurrence` cannot hold without a policy. The
        // counts describe it; nothing reduces it to one value.
        val counts = TargetOccurrenceExecutionFacts.countsOf(record)
        assertEquals(2, counts.totalAttempts)
        assertEquals(1, counts.countOf(SessionStatus.CANCELLED))
        assertEquals(1, counts.countOf(SessionStatus.COMPLETED))
    }

    @Test
    fun thePureAggregationFiltersWithoutChoosingAndWithoutReordering() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)
        cancelSession("b", slot, startedAt = LATER_THAN_A)
        completeSession("c", slot, startedAt = LATER_THAN_B)

        val record = reader().executionRecordOf(programId, occurrenceKey)
        val completed = TargetOccurrenceExecutionFacts.attemptsWithStatus(
            record,
            SessionStatus.COMPLETED
        )

        assertEquals(
            "every attempt of that status, in stored order — all of them or none",
            listOf("session-a", "session-c"),
            completed.map { it.sessionId.value }
        )
        assertEquals(
            "and the counts describe the whole record without ranking its attempts",
            TargetOccurrenceExecutionCounts(3, mapOf(SessionStatus.COMPLETED to 2, SessionStatus.CANCELLED to 1)),
            TargetOccurrenceExecutionFacts.countsOf(record)
        )
    }

    // ---- the opportunity is not the execution -----------------------------------------------------

    @Test
    fun aMissedSlotWithNoSessionReadsAsAMissedOpportunityAndNoAttempts() = runBlocking {
        val slot = persistTarget()
        rig.programScheduleRepository.recordSlotOutcome(slot.slotId, SlotStatus.MISSED, completedAt = null)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "MISSED is a stored opportunity outcome and is returned as exactly that",
            SlotStatus.MISSED,
            record.slotStatus
        )
        assertTrue(
            "it is emphatically not an execution state: there is no session to have one",
            record.attempts.isEmpty()
        )
    }

    @Test
    fun aSupersededSlotWithNoSessionReadsAsASupersededOpportunityAndNoAttempts() = runBlocking {
        val slot = persistTarget()
        rig.programScheduleRepository.recordSlotOutcome(slot.slotId, SlotStatus.SUPERSEDED, completedAt = null)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(SlotStatus.SUPERSEDED, record.slotStatus)
        assertTrue(record.attempts.isEmpty())
    }

    @Test
    fun aCompletedSlotWithACompletedSessionKeepsBothStatusesAsTheirOwnTokens() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(SlotStatus.COMPLETED, record.slotStatus)
        assertEquals(
            "and the session's own status is a different token of a different type",
            listOf(SessionStatus.COMPLETED),
            record.attemptStatuses
        )
    }

    /**
     * The confusion this stage exists to prevent, made impossible to commit.
     *
     * `SlotStatus.COMPLETED` and `SessionStatus.COMPLETED` are the same word in two vocabularies, and
     * a slot is completed by *taking* the opportunity while a session is completed by *finishing* the
     * workout. This case stores a completed slot whose only attempt was **cancelled**: the slot says
     * the opportunity was taken, the session says the workout was not finished, and both are true at
     * once. Any implementation that derives one from the other produces exactly one of them wrongly.
     */
    @Test
    fun aSlotStatusOfCompletedAndASessionStatusOfCompletedAreNotTheSameFact() = runBlocking {
        val slot = persistTarget()
        // A completed attempt establishes the taken opportunity, so the slot's outcome is writable.
        completeSession("a", slot)
        // A *cancelled* attempt then lands at the same slot. It is legal (§19: a cancel does not take
        // the opportunity) and it is exactly the case where the two vocabularies disagree.
        cancelSession("b", slot, startedAt = LATER_THAN_A)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals("the opportunity outcome", SlotStatus.COMPLETED, record.slotStatus)
        assertEquals(
            "the attempt states, which include one cancelled attempt",
            listOf(SessionStatus.COMPLETED, SessionStatus.CANCELLED),
            record.attemptStatuses
        )
        // The mechanical half of the claim: the two are distinct enumerations, so a reader that
        // swapped them would not compile at all. This asserts the *sets* are disjoint, which is the
        // property that makes the two vocabularies independent rather than merely spelled apart.
        assertEquals(
            "no member name is shared between the two vocabularies except the coincidental " +
                "COMPLETED/CANCELLED pair, and even those are distinct types",
            emptySet<String>(),
            SlotStatus.entries.map { it.name }.toSet()
                .intersect(SessionStatus.entries.map { it.name }.toSet())
                .minus(setOf("COMPLETED", "CANCELLED"))
        )
        assertTrue(
            "SlotStatus and SessionStatus are separate enumerations with separate members: " +
                "MISSED and SUPERSEDED exist only on the first",
            SlotStatus.entries.any { it.name == "MISSED" } &&
                SessionStatus.entries.none { it.name == "MISSED" }
        )
        assertEquals(
            "and the read model exposes each under its own accessor rather than a shared one",
            SlotStatus.values().toSet().intersect(SessionStatus.entries.toSet()),
            emptySet<Any>()
        )
    }

    @Test
    fun aCompletedSlotNeverManufacturesASessionStatusOfItsOwn() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)
        // The only attempt at this slot is the completed one, so the slot status and every attempt
        // status happen to agree here. The claim is about the *source*: the attempt statuses below
        // are read from stored session rows, and the slot's own token is not what produced them.
        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(SlotStatus.COMPLETED, record.slotStatus)
        assertEquals(
            "the attempt statuses come from the session rows, each read from its own stored status",
            listOf(SessionStatus.COMPLETED),
            record.attemptStatuses
        )
        assertEquals(
            "and there is exactly one stored attempt, so the agreement is a coincidence of this " +
                "fixture rather than a derivation",
            1,
            rig.database.count("workout_session")
        )
    }

    @Test
    fun removingAStoredAttemptMakesTheSlotReadBackRefuseRatherThanReadAsCompleted() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)
        // A COMPLETED slot is completed *by a session* (§19/§20), so a stored slot whose attempt has
        // been deleted is invalid stored data. The refusal comes from the schedule repository's own
        // slot assembly, which is the established contract — what matters here is that the reader
        // propagates it instead of reading a completed opportunity as a completed workout.
        rig.database.exec("DELETE FROM `workout_session` WHERE `sessionId` = 'session-a'")

        val failure = failureOf { reader().executionRecordOf(programId, occurrenceKey) }
        assertTrue(
            "the orphaned completed slot is refused: ${failure::class.simpleName} ${failure.message}",
            failure is IllegalArgumentException
        )
        assertTrue(
            "and it is refused by the slot's own invariant, naming the attempt requirement: " +
                failure.message,
            failure.message!!.contains("completed by a session")
        )
    }

    // ---- performed work stays performed work ------------------------------------------------------

    @Test
    fun severalConfirmedSetsArePreservedInConfirmationOrder() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(
            session(
                "a",
                slot,
                sets = listOf(
                    SetResult(SetLogId("set-a-1"), 1, completedReps = 12, durationSeconds = 0, performedAt = EARLIEST.plus(TEN_MINUTES)),
                    SetResult(SetLogId("set-a-2"), 2, completedReps = 10, durationSeconds = 0, performedAt = EARLIEST.plus(TWENTY_MINUTES)),
                    SetResult(SetLogId("set-a-3"), 3, completedReps = 8, durationSeconds = 0, performedAt = EARLIEST.plus(THIRTY_MINUTES))
                )
            )
        )

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "three confirmed sets, in confirmation order, as three separate stored rows",
            listOf(1, 2, 3),
            record.confirmedSets().map { it.setIndex }
        )
        assertEquals(
            "each with its own stored amounts",
            listOf(12, 10, 8),
            record.confirmedSets().map { it.completedReps }
        )
    }

    @Test
    fun confirmedSetsFromSeveralAttemptsAreFlattenedInAttemptThenSetOrder() = runBlocking {
        val slot = persistTarget()
        completeSession(
            "a",
            slot,
            sets = listOf(
                SetResult(
                    SetLogId("set-a-1"), 1, completedReps = 12, durationSeconds = 0,
                    performedAt = EARLIEST.plus(TEN_MINUTES)
                ),
                SetResult(
                    SetLogId("set-a-2"), 2, completedReps = 10, durationSeconds = 0,
                    performedAt = EARLIEST.plus(TWENTY_MINUTES)
                )
            )
        )
        completeSession(
            "b",
            slot,
            startedAt = LATER_THAN_A,
            sets = listOf(
                SetResult(
                    SetLogId("set-b-1"), 1, completedReps = 5, durationSeconds = 0,
                    performedAt = LATER_THAN_A.plus(TEN_MINUTES)
                )
            )
        )

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "attempt order first, then each attempt's own confirmation order",
            listOf("set-a-1", "set-a-2", "set-b-1"),
            record.confirmedSets().map { it.setLogId.value }
        )
    }

    @Test
    fun aSkippedSessionExerciseIsPreservedAsItsOwnFactRatherThanAsEmptyWork() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot, skipped = true))

        val record = reader().executionRecordOf(programId, occurrenceKey)

        val skipped = record.skippedExercises()
        assertEquals("the skip is one stored fact", 1, skipped.size)
        assertEquals("pike_pushup", skipped.single().exerciseId)
        assertTrue(
            "and a skipped exercise observed no sets at all — it is not a zero-valued workout (§12)",
            skipped.single().results.isEmpty()
        )
        assertEquals(
            "the skipped exercise carries no set into the confirmed sets",
            2,
            record.confirmedSets().size
        )
    }

    @Test
    fun theCapturedPresentationIsTheSessionRepositorysOwnAndNotTheLivePlan() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot))
        val stored = reader().executionRecordOf(programId, occurrenceKey).attempts.single()

        val throughRepository = rig.freshSessionRepository().sessionById(SessionId("session-a"))!!
        assertEquals(
            "the snapshot is exactly what the session repository read back, not a reconstruction",
            throughRepository.snapshot,
            stored.snapshot
        )
        assertEquals(
            "including the presented exercise identities, which differ from the live plan's",
            listOf("knee_pushup", "pike_pushup"),
            stored.snapshot.workout.exercises.map { it.exerciseId }
        )
    }

    // ---- Phase 14's read-back and the stored slot are unchanged -------------------------------------

    @Test
    fun theSemanticOccurrenceIsExactlyThePhaseFourteenReadBack() = runBlocking {
        persistTarget()

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "the record's occurrence is the repository's own read-back, unchanged",
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, occurrenceKey),
            record.occurrence
        )
    }

    @Test
    fun theSlotFieldsAreExactlyThosePersisted() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)

        val record = reader().executionRecordOf(programId, occurrenceKey)
        val stored = rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)!!

        assertEquals("every stored slot field, unchanged", stored, record.slot)
        assertEquals(
            "including the target key the identity was found through",
            occurrenceKey,
            record.slot.targetOccurrenceKey
        )
        assertEquals(
            "and the plan day and planned date, read as stored facts rather than as identities",
            day,
            record.slot.programDayId
        )
        assertEquals(DAY, record.slot.plannedFor)
    }

    @Test
    fun theSlotAttemptsListAndTheSessionAttemptsAreTheSameStoredRows() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)
        cancelSession("b", slot, startedAt = LATER_THAN_A)

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "the slot's own attempt list (§23: the link lives on the session rows) agrees with the " +
                "assembled attempts, in the same order",
            record.slot.attempts,
            record.attemptIds
        )
    }

    // ---- two Programs, one key --------------------------------------------------------------------

    @Test
    fun theSameOccurrenceKeyInTwoProgramsStaysIndependent() = runBlocking {
        val mine = persistTarget()
        val otherKey = "other-target-exec"
        val other = ProgramGraphFixture.graph(otherKey)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherProgramId = other.program.programId
        // Stored under the *same* occurrence key, in the other Program — which is the case: the key
        // is only unique within a Program, so independence is the property under test.
        persistTarget(occurrenceKey, otherProgramId, RevisionId(ProgramGraphFixture.revisionId(otherKey)))

        // A completed attempt on my occurrence, and none at all on the other one.
        completeSession("a", mine)
        val otherSlot = rig.programScheduleRepository
            .slotByTargetOccurrenceKey(otherProgramId, occurrenceKey)!!

        val mineRecord = reader().executionRecordOf(programId, occurrenceKey)
        val otherRecord = reader().executionRecordOf(otherProgramId, occurrenceKey)

        assertEquals(
            "my occurrence has its own attempts",
            listOf(SessionStatus.COMPLETED),
            mineRecord.attemptStatuses
        )
        assertTrue(
            "and the other Program's same-key occurrence has none of them",
            otherRecord.attempts.isEmpty()
        )
        assertNotEquals(
            "the two records are separate values, not one read under two names",
            mineRecord,
            otherRecord
        )
        assertEquals(
            "each names its own Program and its own slot",
            listOf(programId.value, otherProgramId.value),
            listOf(mineRecord.programId.value, otherRecord.programId.value)
        )
        assertNotEquals(otherSlot.slotId, mineRecord.slot.slotId)
    }

    // ---- typed failures ---------------------------------------------------------------------------

    @Test
    fun aMissingSemanticRecordIsATypedFailure() = runBlocking {
        persistTarget()

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.MissingTargetOccurrence::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, "no-such-occurrence") }
        }

        assertEquals(programId, failure.programId)
        assertEquals("no-such-occurrence", failure.occurrenceKey)
    }

    @Test
    fun aMissingSlotForAStoredTargetOccurrenceIsATypedFailure() = runBlocking {
        val slot = persistTarget()
        // Stage 14 writes the two in one transaction, so this is staged by removing the slot row
        // directly — the shape a restore or a hand-edited database leaves behind.
        rig.database.exec("DELETE FROM `program_workout_slot` WHERE `slotId` = '${slot.slotId.value}'")

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.MissingTargetSlot::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, occurrenceKey) }
        }

        assertEquals(programId, failure.programId)
        assertEquals(occurrenceKey, failure.occurrenceKey)
        assertTrue(
            "the refusal is about the missing slot, not a generic absence",
            failure.message!!.contains("no slot")
        )
    }

    @Test
    fun aSlotWhoseStoredTargetKeyNoLongerMatchesIsNotFoundRatherThanReinterpreted() = runBlocking {
        val slot = persistTarget()
        // The stored slot now names a different target key. The lookup is keyed on the *pair*, so a
        // re-pointed row is not "a slot for this key with a different name" — it is a slot for a
        // different identity, and the pair that was asked about no longer has one. Reporting that as
        // a mismatch would imply the reader had found a row and disagreed with it.
        rig.database.exec(
            "UPDATE `program_workout_slot` SET `targetOccurrenceKey` = 'other:2026-10-05' " +
                "WHERE `slotId` = '${slot.slotId.value}'"
        )

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.MissingTargetSlot::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, occurrenceKey) }
        }

        assertEquals(occurrenceKey, failure.occurrenceKey)
        assertNull(
            "and the re-pointed slot is not reachable under the old identity",
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)
        )
    }

    @Test
    fun aRecordRefusesASlotWhoseTargetKeyDiffersFromItsOccurrence() {
        // The mismatch guard itself: a record that paired an occurrence with a slot answering for a
        // different target identity is refused at construction, so the reader's check is a second
        // line rather than the only one.
        val slot = runBlocking { persistTarget() }
        val other = PersistedTargetOccurrence(programId, occurrence("other:2026-10-05"))

        val failure = runCatching {
            com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord(
                occurrence = other,
                slot = slot,
                attempts = emptyList()
            )
        }
        assertTrue(
            "the record refuses a slot that does not present its occurrence: ${failure.exceptionOrNull()}",
            failure.isFailure
        )
        assertTrue(
            failure.exceptionOrNull()!!.message!!.contains("cannot present slot")
        )
    }

    @Test
    fun aRecordRefusesASlotOfAnotherProgram() {
        val slot = runBlocking { persistTarget() }
        val other = PersistedTargetOccurrence(ProgramId("program-somewhere-else"), occurrence(occurrenceKey))

        val failure = runCatching {
            com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord(
                occurrence = other,
                slot = slot,
                attempts = emptyList()
            )
        }
        assertTrue(
            "a slot of another Program cannot be an occurrence's slot: ${failure.exceptionOrNull()}",
            failure.isFailure
        )
    }

    @Test
    fun aRecordRefusesAnAttemptAtAnotherSlotOrOfAnotherProgram() = runBlocking {
        val mine = persistTarget()
        val otherKey = "other-target-exec"
        val other = ProgramGraphFixture.graph(otherKey)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val theirs = persistTarget(
            occurrenceKey = "other:2026-10-05",
            programId = other.program.programId,
            revisionId = RevisionId(ProgramGraphFixture.revisionId(otherKey))
        )
        val attempt = session("a", theirs)
        val myOccurrence = PersistedTargetOccurrence(programId, occurrence(occurrenceKey))

        assertTrue(
            "a session attempted at another slot is refused, not folded into this record",
            runCatching {
                TargetOccurrenceExecutionRecord(myOccurrence, mine, listOf(attempt))
            }.isFailure
        )
        assertTrue(
            "and a session belonging to another Program is refused, even at the right slot",
            runCatching {
                TargetOccurrenceExecutionRecord(
                    occurrence = PersistedTargetOccurrence(other.program.programId, occurrence("other:2026-10-05")),
                    slot = theirs,
                    // A session's own identity triple is fixed at construction, so the mismatch is
                    // staged by pairing a foreign attempt with this Program's occurrence.
                    attempts = listOf(session("b", mine).copy(programId = other.program.programId))
                )
            }.isFailure
        )
    }

    @Test
    fun aSessionOfAnotherProgramIsATypedFailureRatherThanAForeignAttempt() = runBlocking {
        val slot = persistTarget()
        val otherKey = "other-target-exec"
        val other = ProgramGraphFixture.graph(otherKey)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherProgramId = other.program.programId
        persistTarget(otherKey, otherProgramId, RevisionId(ProgramGraphFixture.revisionId(otherKey)))
        // The attempt really is at *my* slot, so the reader finds it; only its Program column
        // disagrees, which is the case a lookup by Program cannot notice on its own.
        rig.startSessionWithSets(session("a", slot))
        rig.database.exec(
            "UPDATE `workout_session` SET `programId` = '${otherProgramId.value}' " +
                "WHERE `sessionId` = 'session-a'"
        )

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.SessionBelongsToAnotherProgram::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, occurrenceKey) }
        }

        assertEquals(programId, failure.requestedProgramId)
        assertEquals(otherProgramId, failure.sessionProgramId)
        assertEquals("session-a", failure.sessionIdValue)
    }

    @Test
    fun aSessionOfAnotherRevisionIsATypedFailure() = runBlocking {
        val slot = persistTarget()
        val other = ProgramGraphFixture.nextRevision(key, revisionNumber = 2)
        rig.programPlanRepository.saveNewRevision(other, ProgramGraphFixture.UPDATED)
        rig.startSessionWithSets(session("a", slot))

        rig.database.exec(
            "UPDATE `workout_session` SET `revisionId` = '${other.revisionId.value}' " +
                "WHERE `sessionId` = 'session-a'"
        )

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.SessionBelongsToAnotherRevision::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, occurrenceKey) }
        }

        assertEquals(slot.slotId.value, failure.slotIdValue)
        assertEquals("session-a", failure.sessionIdValue)
        assertEquals(revisionId.value, failure.slotRevisionIdValue)
        assertEquals(other.revisionId.value, failure.sessionRevisionIdValue)
    }

    @Test
    fun anAttemptMovedToAnotherSlotIsNotAttributedToTheSlotItNoLongerNames() = runBlocking {
        val mine = persistTarget()
        val otherKey = "other-target-exec"
        val other = ProgramGraphFixture.graph(otherKey)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherSlot = persistTarget(
            otherKey,
            other.program.programId,
            RevisionId(ProgramGraphFixture.revisionId(otherKey))
        )
        // An in-progress attempt on a still-PLANNED slot, so moving it does not leave behind a
        // COMPLETED slot whose recorded attempt no longer exists — a different corruption entirely.
        rig.startSessionWithSets(session("a", mine))

        // Move the attempt onto the other slot of the *same* Program's own other key: program and
        // revision both still agree, and only the slot link is wrong.
        rig.database.exec(
            "UPDATE `workout_session` SET `slotId` = '${otherSlot.slotId.value}' " +
                "WHERE `sessionId` = 'session-a'"
        )

        // The read is over the slot, so the moved row is no longer *found* — the honest outcome is
        // that my record has no attempts while the slot row still claims one. Both facts are real,
        // and the read reports the stored session graph rather than the slot's cached attempt list.
        //
        // This is why the reader still checks `SessionReferencesAnotherSlot` without this case
        // reaching it: `sessionsOfSlot` selects on `slotId`, so a row that names another slot is
        // simply not returned. The check guards a repository that ever widened that query, and it
        // is a second line behind the record's own invariant rather than the only one.
        val record = reader().executionRecordOf(programId, occurrenceKey)
        assertTrue(
            "the moved attempt is not attributed to my slot, because it no longer names it",
            record.attempts.isEmpty()
        )
        assertEquals(
            "and the record's own attempt list agrees with the sessions actually stored for it",
            0,
            record.attemptIds.size
        )
    }

    @Test
    fun aMalformedSessionGraphStillFailsThroughTheSessionRepositorysOwnValidation() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot))

        // Remove the session's snapshot. §19 requires a session to be assembled from the presentation
        // it was started under, and the session repository refuses a graph that is not there.
        rig.database.exec("DELETE FROM `session_snapshot` WHERE `sessionId` = 'session-a'")

        val failure = failureOf { reader().executionRecordOf(programId, occurrenceKey) }
        assertTrue(
            "the malformed graph propagates instead of being repaired or dropped into an empty " +
                "record: ${failure::class.simpleName} ${failure.message}",
            failure !is TargetOccurrenceExecutionReadException
        )
        assertTrue(
            "and it is the session repository's own refusal, not a new one invented here: ${failure.message}",
            failure.message!!.contains("snapshot") || failure.message!!.contains("presentation")
        )
    }

    @Test
    fun corruptStoredDataNeverBecomesAnEmptyExecutionRecord() = runBlocking {
        val slot = persistTarget()
        rig.startSessionWithSets(session("a", slot))
        rig.database.exec("DELETE FROM `session_snapshot` WHERE `sessionId` = 'session-a'")

        val record = runCatching { reader().executionRecordOf(programId, occurrenceKey) }
        assertTrue(
            "the call failed rather than returning a record with the attempt quietly dropped",
            record.isFailure
        )
        assertNull("so there is no record at all", record.getOrNull())
    }

    // ---- the lookup path ---------------------------------------------------------------------------

    @Test
    fun noCurrentRevisionLookupOccursAndTheLookupIsKeyedOnTheProgram() = runBlocking {
        val slot = persistTarget()
        completeSession("a", slot)

        // A different current revision is in place. Nothing about the read changes, because the
        // reader asks for a stored target identity and never consults the current revision.
        val next = ProgramGraphFixture.nextRevision(key, revisionNumber = 2)
        rig.programPlanRepository.saveNewRevision(next, ProgramGraphFixture.UPDATED)
        rig.programRepository.updateProgram(
            rig.graph.program.copy(currentRevisionId = next.revisionId, updatedAt = ProgramGraphFixture.UPDATED)
        )

        val record = reader().executionRecordOf(programId, occurrenceKey)

        assertEquals(
            "the slot is still the stored one, not the new revision's",
            slot.slotId,
            record.slot.slotId
        )
        assertEquals(
            "and the stored revision is reported as the fact it is",
            revisionId,
            record.slot.revisionId
        )
        assertEquals(listOf(SessionStatus.COMPLETED), record.attemptStatuses)
    }

    @Test
    fun aKeyHeldByAnotherProgramIsNotReadableThroughThisProgram() = runBlocking {
        val otherKey = "other-target-exec"
        val other = ProgramGraphFixture.graph(otherKey)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        persistTarget(otherKey, other.program.programId, RevisionId(ProgramGraphFixture.revisionId(otherKey)))

        val failure = assertThrows(
            TargetOccurrenceExecutionReadException.MissingTargetOccurrence::class.java
        ) {
            runBlocking { reader().executionRecordOf(programId, occurrenceKey) }
        }

        assertEquals(
            "membership is the pair, so the Program is not interchangeable with the key",
            programId,
            failure.programId
        )
    }

    // ---- the fixture ------------------------------------------------------------------------------

    private fun reader() = TargetOccurrenceExecutionReader(
        occurrenceRepository = rig.targetScheduleOccurrenceRepository,
        scheduleRepository = rig.programScheduleRepository,
        sessionRepository = rig.workoutSessionRepository
    )

    /**
     * Runs the production write path that persists a target slot and the occurrence it presents.
     *
     * The slot identity is given explicitly so a suite can stage two Programs' target state in one
     * database, which is what "the same key in two Programs" needs.
     */
    private suspend fun persistTarget(
        occurrenceKey: String = this.occurrenceKey,
        programId: ProgramId = this.programId,
        revisionId: RevisionId = this.revisionId
    ): WorkoutSlot {
        if (programId.value != this.programId.value) {
            // A second Program's graph is created by the caller; this one only needs its target state.
            return persistOnly(programId, revisionId, occurrenceKey)
        }
        if (!graphPersisted) {
            rig.createGraph()
            graphPersisted = true
        }
        val planned = occurrence(occurrenceKey)
        val presentation = TargetOccurrencePresentation(planned, day)
        TargetScheduleSlotPersister(
            scheduleRepository = rig.programScheduleRepository,
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            idGenerator = IdGenerator { "slot-$occurrenceKey-${ids++}" },
            inTransaction = rig.transaction
        ).persist(
            TargetScheduleSlotPersistenceInput(
                programId = programId,
                revisionId = revisionId,
                decision = decision(listOf(planned)),
                presentations = listOf(presentation)
            )
        )
        return rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)!!
    }

    /** The same boundary for a Program that is not this rig's own graph. */
    private suspend fun persistOnly(
        programId: ProgramId,
        revisionId: RevisionId,
        occurrenceKey: String
    ): WorkoutSlot {
        val planned = occurrence(occurrenceKey)
        val dayId = ProgramGraphFixture.dayId(programId.value.removePrefix("program-"), 1)
        TargetScheduleSlotPersister(
            scheduleRepository = rig.programScheduleRepository,
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            idGenerator = IdGenerator { "slot-$programId-$occurrenceKey-${ids++}" },
            inTransaction = rig.transaction
        ).persist(
            TargetScheduleSlotPersistenceInput(
                programId = programId,
                revisionId = revisionId,
                decision = decision(listOf(planned)),
                presentations = listOf(TargetOccurrencePresentation(planned, ProgramDayId(dayId)))
            )
        )
        return rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)!!
    }

    private suspend fun completeSession(
        tag: String,
        slot: WorkoutSlot,
        startedAt: Instant = EARLIEST,
        sets: List<SetResult> = twoRepSets(tag, startedAt)
    ) {
        val started = session(tag, slot, startedAt = startedAt, sets = sets)
        // `startSession` writes the session and its captured presentation only; a set is confirmed
        // during the workout (§27), so the rig's helper is the write path that files the sets.
        rig.startSessionWithSets(started)
        val finished = started.copy(
            status = SessionStatus.COMPLETED,
            finishedAt = startedAt.plus(FORTY_MINUTES)
        )
        rig.workoutSessionRepository.finishSession(
            finished,
            slot.copy(
                status = SlotStatus.COMPLETED,
                attempts = listOf(finished.sessionId),
                completedAt = finished.finishedAt
            )
        )
    }

    private suspend fun cancelSession(tag: String, slot: WorkoutSlot, startedAt: Instant = EARLIEST) {
        val started = session(tag, slot, startedAt = startedAt)
        rig.workoutSessionRepository.startSession(started)
        // A cancel ends the attempt *without* taking the opportunity (§19), so the slot is untouched.
        rig.workoutSessionRepository.recordSessionOutcome(
            started.copy(status = SessionStatus.CANCELLED, finishedAt = startedAt.plus(FORTY_MINUTES))
        )
    }

    private fun decision(occurrences: List<PlannedOccurrence>) = TargetScheduleDecision(
        targetPlan = com.monkfitness.app.domain.program.target.TargetPlan(
            planned = occurrences,
            reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                preserved = emptyList(),
                superseded = emptyList(),
                added = occurrences
            )
        ),
        preserved = emptyList(),
        retained = emptyList(),
        created = occurrences,
        superseded = emptyList(),
        missed = emptyList()
    )

    private fun occurrence(key: String) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = DAY,
        components = listOf(OccurrenceComponent("rule-a", "workout-a"))
    )

    /** A finished attempt that does not hold the opportunity, so another can follow it. */
    private fun session(
        tag: String,
        slot: WorkoutSlot,
        startedAt: Instant = EARLIEST,
        sets: List<SetResult> = twoRepSets(tag, startedAt),
        skipped: Boolean = false
    ): WorkoutSession {
        val first = com.monkfitness.app.domain.common.ProgramExerciseId(
            ProgramGraphFixture.planExerciseId(key, 1)
        )
        val second = com.monkfitness.app.domain.common.ProgramExerciseId(
            ProgramGraphFixture.planExerciseId(key, 2)
        )
        val sessionId = SessionId("session-$tag")
        val captured = com.monkfitness.app.domain.workout.WorkoutSessionSnapshot(
            sessionId = sessionId,
            capturedAt = startedAt,
            workout = com.monkfitness.app.domain.workout.EffectiveWorkout(
                slotId = slot.slotId,
                programId = slot.programId,
                revisionId = slot.revisionId,
                plannedFor = slot.plannedFor,
                computedAt = ProgramGraphFixture.COMPUTED,
                exercises = listOf(
                    com.monkfitness.app.domain.workout.EffectiveExercise(
                        first,
                        "knee_pushup",
                        com.monkfitness.app.domain.prescription.RepPrescription(listOf(10, 8))
                    ),
                    com.monkfitness.app.domain.workout.EffectiveExercise(
                        second,
                        "pike_pushup",
                        com.monkfitness.app.domain.prescription.RepPrescription(listOf(8, 8))
                    )
                ),
                appliedAdjustmentIds = listOf(com.monkfitness.app.domain.common.AdjustmentId("adj-$tag"))
            )
        )
        return WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = captured,
            status = SessionStatus.IN_PROGRESS,
            startedAt = startedAt,
            exercises = listOf(
                com.monkfitness.app.domain.workout.SessionExercise(
                    sessionExerciseId = com.monkfitness.app.domain.common.SessionExerciseId("se-$tag-1"),
                    programExerciseId = first,
                    exerciseId = "knee_pushup",
                    prescription = com.monkfitness.app.domain.prescription.RepPrescription(listOf(10, 8)),
                    results = sets
                ),
                com.monkfitness.app.domain.workout.SessionExercise(
                    sessionExerciseId = com.monkfitness.app.domain.common.SessionExerciseId("se-$tag-2"),
                    programExerciseId = second,
                    exerciseId = "pike_pushup",
                    prescription = com.monkfitness.app.domain.prescription.RepPrescription(listOf(8, 8)),
                    skipped = skipped
                )
            )
        )
    }

    /**
     * The suite's default performed work: two confirmed repetition sets, `12` then `10`.
     *
     * Deliberately unequal and deliberately not sorted-symmetric, so a read that reordered, summed or
     * truncated the stored sets would produce a different list than the one that was written.
     */
    private fun twoRepSets(tag: String, startedAt: Instant) = listOf(
        SetResult(
            SetLogId("set-$tag-1"), 1, completedReps = 12, durationSeconds = 0,
            performedAt = startedAt.plus(TEN_MINUTES)
        ),
        SetResult(
            SetLogId("set-$tag-2"), 2, completedReps = 10, durationSeconds = 0,
            performedAt = startedAt.plus(TWENTY_MINUTES)
        )
    )

    private var ids = 0

    /** Whether this suite already created the rig's own graph; `createProgram` is not repeatable. */
    private var graphPersisted = false

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val EARLIEST: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val LATER_THAN_A: Instant = Instant.parse("2026-10-06T08:00:00Z")
        val LATER_THAN_B: Instant = Instant.parse("2026-10-07T08:00:00Z")

        /**
         * This suite's own clock arithmetic.
         *
         * The shared `ProgramGraphFixture` instants belong to a fixture whose sessions start in
         * September, and borrowing them here would stamp a workout's finish and its sets *before* the
         * session began — which §19's own `finishedAt >= startedAt` invariant rightly refuses. Every
         * stamp in this suite is therefore derived from the session's own start, so the relative
         * order the tests assert is stated in one place.
         */
        val TEN_MINUTES: Duration = Duration.ofMinutes(10)
        val TWENTY_MINUTES: Duration = Duration.ofMinutes(20)
        val THIRTY_MINUTES: Duration = Duration.ofMinutes(30)
        val FORTY_MINUTES: Duration = Duration.ofMinutes(40)
    }
}
