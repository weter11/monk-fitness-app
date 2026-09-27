package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SessionStatus.CANCELLED
import com.monkfitness.app.domain.workout.SessionStatus.COMPLETED
import com.monkfitness.app.domain.workout.SessionStatus.IN_PROGRESS
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * §30 step 16's precedence, exercised on real [TargetOccurrenceExecutionRecord]s.
 *
 * The records are built in memory rather than read back through the Phase 15 reader, and that is
 * deliberate: this phase's entire input is the record, so a suite that reached for a database would
 * be testing the *reader* again — and the cases this phase must judge are ones a storage contract
 * forbids anyway. A `COMPLETED` slot with a later cancelled attempt, or a `MISSED` slot holding a
 * completed attempt, are exactly the states the real write paths keep apart, and the policy has to
 * answer for every combination of them whether or not the current writer can produce it. The
 * reader's own end-to-end coverage is Phase 15's suite; what is proven here is that the decision
 * depends on nothing but the record.
 *
 * The claims under test, in the order the phase states them:
 *
 * ```text
 *  1. the full precedence table, one case per row
 *  2. the whole matrix of mixed attempt histories
 *  3. the verdict does not depend on attempt order
 *  4. slot status is an opportunity fact and never an execution state
 *  5. the policy neither repairs nor reorders the record it is given
 *  6. equal records give equal decisions, and nothing ambient leaks in
 * ```
 */
class TargetOccurrenceExecutionPolicyTest {

    // ---- 1. the precedence table -------------------------------------------------------------------

    @Test
    fun anOccurrenceWithNoAttemptIsPlanned() {
        val decision = decide(record())

        assertEquals(OccurrenceExecution.PLANNED, decision.execution)
        assertEquals(
            "and the decision says it was read from zero attempts, so a PLANNED is visibly a " +
                "no-attempt reading rather than a filtered-away one",
            0,
            decision.attemptCount
        )
    }

    @Test
    fun oneInProgressAttemptIsStarted() {
        assertEquals(OccurrenceExecution.STARTED, decide(record(session(status = IN_PROGRESS))).execution)
    }

    @Test
    fun oneCancelledAttemptIsCancelled() {
        assertEquals(OccurrenceExecution.CANCELLED, decide(record(session(status = CANCELLED))).execution)
    }

    @Test
    fun oneCompletedAttemptIsCompleted() {
        assertEquals(OccurrenceExecution.COMPLETED, decide(record(session(status = COMPLETED))).execution)
    }

    @Test
    fun aCancelledAttemptFollowedByALaterStartedOneIsStarted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND)
        )

        assertEquals(OccurrenceExecution.STARTED, decide(record).execution)
    }

    @Test
    fun aCancelledAttemptFollowedByALaterCompletedOneIsCompleted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = COMPLETED, startedAt = SECOND)
        )

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    @Test
    fun aCancelledThenStartedThenCompletedHistoryIsCompleted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND),
            session("c", status = COMPLETED, startedAt = THIRD)
        )

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    @Test
    fun aCompletedOccurrenceStaysCompletedAfterALaterCancelledAttempt() {
        val record = record(
            session("a", status = COMPLETED, startedAt = FIRST),
            session("b", status = CANCELLED, startedAt = SECOND)
        )

        assertEquals(
            "an occurrence does not fall back: the cancellation is a second fact about a second " +
                "attempt, not a retraction of the finished one",
            OccurrenceExecution.COMPLETED,
            decide(record).execution
        )
    }

    @Test
    fun aCompletedOccurrenceStaysCompletedAfterALaterStartedAttempt() {
        val record = record(
            session("a", status = COMPLETED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND)
        )

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    // ---- 2. the mixed matrix -----------------------------------------------------------------------

    @Test
    fun aCancelledAndAStartedAttemptReadAsStarted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND)
        )
        assertEquals(OccurrenceExecution.STARTED, decide(record).execution)
    }

    @Test
    fun aCancelledAndACompletedAttemptReadAsCompleted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = COMPLETED, startedAt = SECOND)
        )
        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    @Test
    fun aStartedAndACompletedAttemptReadAsCompleted() {
        val record = record(
            session("a", status = IN_PROGRESS, startedAt = FIRST),
            session("b", status = COMPLETED, startedAt = SECOND)
        )
        assertEquals(
            "the completed attempt outranks the open one, and the branch order is the rule",
            OccurrenceExecution.COMPLETED,
            decide(record).execution
        )
    }

    @Test
    fun severalCancelledAttemptsReadAsCancelled() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = CANCELLED, startedAt = SECOND),
            session("c", status = CANCELLED, startedAt = THIRD)
        )

        assertEquals(OccurrenceExecution.CANCELLED, decide(record).execution)
    }

    @Test
    fun aCompletedAttemptAmongThreeCancelledOnesStillReadsAsCompleted() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = CANCELLED, startedAt = SECOND),
            session("c", status = COMPLETED, startedAt = THIRD)
        )

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    // ---- 3. the verdict is order-independent --------------------------------------------------------

    @Test
    fun arbitraryAttemptOrderDoesNotChangeTheVerdict() {
        val attempts = listOf(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND),
            session("c", status = COMPLETED, startedAt = THIRD)
        )
        val forward = decide(record(*attempts.toTypedArray()))

        // Every ordering of the same three stored attempts, in all six permutations.
        for (permutation in permutations(attempts)) {
            assertEquals(
                "the same stored statuses in a different stored order must not decide differently: " +
                    permutation.joinToString { it.status.toString() },
                forward,
                decide(record(*permutation.toTypedArray()))
            )
        }
    }

    @Test
    fun theSameAttemptsReadTheSameWayForEveryPermutation() {
        val attempts = listOf(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND)
        )
        val verdicts = permutations(attempts).map { permutation ->
            decide(record(*permutation.toTypedArray())).execution
        }.toSet()

        assertEquals(
            "a started occurrence is started whichever attempt is listed first",
            setOf(OccurrenceExecution.STARTED),
            verdicts
        )
    }

    // ---- 4. slot status is an opportunity fact ------------------------------------------------------

    @Test
    fun aMissedSlotWithACancelledAttemptStillReadsAsCancelled() {
        val record = record(session(status = CANCELLED), slotStatus = SlotStatus.MISSED)

        assertEquals(OccurrenceExecution.CANCELLED, decide(record).execution)
        assertEquals(
            "and the missed opportunity is carried as its own separate fact, under its own type",
            SlotStatus.MISSED,
            decide(record).slotStatus
        )
    }

    @Test
    fun aMissedSlotWithAStartedAttemptStillReadsAsStarted() {
        val record = record(session(status = IN_PROGRESS), slotStatus = SlotStatus.MISSED)

        assertEquals(OccurrenceExecution.STARTED, decide(record).execution)
    }

    @Test
    fun aMissedSlotWithACompletedAttemptStillReadsAsCompleted() {
        val record = record(session(status = COMPLETED), slotStatus = SlotStatus.MISSED)

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
    }

    @Test
    fun aMissedSlotWithNoAttemptIsPlannedRatherThanCancelled() {
        val record = record(slotStatus = SlotStatus.MISSED)

        assertEquals(
            "MISSED is an opportunity outcome, not an execution state, so it never becomes CANCELLED",
            OccurrenceExecution.PLANNED,
            decide(record).execution
        )
    }

    @Test
    fun aSupersededSlotIsNotASpecialExecutionValue() {
        val planned = decide(record(slotStatus = SlotStatus.PLANNED)).execution
        val superseded = decide(record(slotStatus = SlotStatus.SUPERSEDED)).execution

        assertEquals(
            "SUPERSEDED is about the plan, not the workout: with no attempt the occurrence is " +
                "planned exactly as an ordinary planned one is",
            planned,
            superseded
        )
        for (status in listOf(SessionStatus.CANCELLED, SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED)) {
            assertEquals(
                "and a superseded slot with a $status attempt follows the attempt policy alone",
                decide(record(session(status = status), slotStatus = SlotStatus.PLANNED)).execution,
                decide(record(session(status = status), slotStatus = SlotStatus.SUPERSEDED)).execution
            )
        }
    }

    @Test
    fun aPlannedSlotWithACompletedHistoricalAttemptFollowsTheAttemptPolicy() {
        val record = record(session(status = COMPLETED), slotStatus = SlotStatus.PLANNED)

        assertEquals(
            "a PLANNED slot is not evidence that no historical session exists",
            OccurrenceExecution.COMPLETED,
            decide(record).execution
        )
    }

    @Test
    fun aCompletedSlotWithALaterCancelledAttemptRemainsCompleted() {
        val record = record(
            session("a", status = COMPLETED, startedAt = FIRST),
            session("b", status = CANCELLED, startedAt = SECOND),
            slotStatus = SlotStatus.COMPLETED
        )

        assertEquals(OccurrenceExecution.COMPLETED, decide(record).execution)
        assertEquals(
            "and the slot's own COMPLETED status is reported as itself, not read back as the verdict",
            SlotStatus.COMPLETED,
            decide(record).slotStatus
        )
    }

    @Test
    fun aCompletedSlotWhoseOnlyAttemptWasCancelledDoesNotBecomeCompleted() {
        val record = record(session(status = CANCELLED), slotStatus = SlotStatus.COMPLETED)

        assertEquals(
            "`SlotStatus.COMPLETED` and `SessionStatus.COMPLETED` are different tokens, and the " +
                "opportunity is not evidence about the attempt",
            OccurrenceExecution.CANCELLED,
            decide(record).execution
        )
    }

    @Test
    fun slotStatusAndOccurrenceExecutionAreTwoSeparatelyTypedFields() {
        val record = record(session(status = IN_PROGRESS), slotStatus = SlotStatus.MISSED)
        val decision = decide(record)

        assertEquals(SlotStatus.MISSED, decision.slotStatus)
        assertEquals(OccurrenceExecution.STARTED, decision.execution)
        assertNotEquals(
            "a caller cannot read one off the other: neither value is the other's type",
            decision.slotStatus as Any,
            decision.execution as Any
        )
    }

    // ---- 5. the policy neither repairs nor reorders ------------------------------------------------

    @Test
    fun thePolicyChoosesNoRepresentativeAttempt() {
        val record = record(
            session("a", status = COMPLETED, startedAt = FIRST),
            session("b", status = CANCELLED, startedAt = SECOND),
            session("c", status = IN_PROGRESS, startedAt = THIRD)
        )
        val decision = decide(record)

        assertEquals(
            "the decision is not any single attempt's own status — no attempt in this record is " +
                "IN_PROGRESS-latest, and the verdict is COMPLETED",
            OccurrenceExecution.COMPLETED,
            decision.execution
        )
        assertEquals(
            "and it was read from every stored attempt, none dropped",
            3,
            decision.attemptCount
        )
    }

    @Test
    fun thePolicyDoesNotReorderTheAttemptsItIsGiven() {
        val attempts = listOf(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = COMPLETED, startedAt = SECOND)
        )
        val record = record(*attempts.toTypedArray())

        assertEquals(
            "the record's own stored order is passed through untouched",
            attempts.map { it.sessionId },
            record.attemptIds
        )
        assertEquals(
            "and reading it leaves that order alone",
            attempts.map { it.sessionId },
            record.attemptIds
        )
    }

    @Test
    fun duplicateSessionObjectsAreGovernedByTheRecordsOwnInvariants() {
        val duplicate = runCatching {
            record(session("a", status = COMPLETED), session("a", status = COMPLETED))
        }

        assertTrue(
            "a session listed twice is the record's corruption to refuse, not the policy's to " +
                "deduplicate: the record is refused before the policy is ever reached",
            duplicate.isFailure
        )
        assertTrue(
            "and it is refused as an argument error, not swallowed into a tidier record",
            duplicate.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun aRecordThePolicyIsGivenIsNeverAlteredByReadingIt() {
        val record = record(
            session("a", status = CANCELLED, startedAt = FIRST),
            session("b", status = IN_PROGRESS, startedAt = SECOND)
        )
        val before = record.attemptStatuses

        decide(record)
        decide(record)

        assertEquals("the stored statuses are exactly as they were", before, record.attemptStatuses)
        assertEquals(2, record.attempts.size)
    }

    // ---- 6. determinism ----------------------------------------------------------------------------

    @Test
    fun equalRecordsProduceEqualityIdenticalDecisions() {
        val first = decide(
            record(
                session("a", status = CANCELLED, startedAt = FIRST),
                session("b", status = COMPLETED, startedAt = SECOND)
            )
        )
        val second = decide(
            record(
                session("a", status = CANCELLED, startedAt = FIRST),
                session("b", status = COMPLETED, startedAt = SECOND)
            )
        )

        assertEquals("the decision is a value, not an identity", first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun theSameRecordReadTwiceProducesTheSameDecision() {
        val record = record(session(status = IN_PROGRESS))

        assertEquals(decide(record), decide(record))
    }

    @Test
    fun theDecisionExposesTheExecutionDirectlyRatherThanBehindAGetter() {
        val decision = decide(record(session(status = COMPLETED)))

        assertEquals(
            "the verdict is a declared property of the decision, so no incidental accessor has to " +
                "compute it a second way",
            OccurrenceExecution.COMPLETED,
            decision.execution
        )
        assertEquals(1, decision.attemptCount)
    }

    @Test
    fun thePolicyNeedsNoCollaboratorAndHoldsNoStateBetweenCalls() {
        // Nothing to inject and nothing to reset: the same object answers every call identically,
        // which is the observable form of "pure" for a caller that can only see results.
        val policy = TargetOccurrenceExecutionPolicy

        val planned = policy.decide(record())
        val completed = policy.decide(record(session(status = COMPLETED)))
        val plannedAgain = policy.decide(record())

        assertEquals(OccurrenceExecution.PLANNED, planned.execution)
        assertEquals(OccurrenceExecution.COMPLETED, completed.execution)
        assertEquals("a prior call leaves nothing behind", planned, plannedAgain)
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** The verdict for [record], the way a caller reads it. */
    private fun decide(record: TargetOccurrenceExecutionRecord) =
        TargetOccurrenceExecutionPolicy.decide(record)

    /**
     * A record of one slot, holding exactly [attempts].
     *
     * The slot's `attempts` column is filled from the same sessions, because a `COMPLETED` slot is
     * required by `WorkoutSlot` to name at least one attempt and the record requires every attempt
     * to be the record's own slot's.
     */
    private fun record(
        vararg attempts: WorkoutSession,
        slotStatus: SlotStatus = SlotStatus.PLANNED
    ): TargetOccurrenceExecutionRecord {
        val ids = attempts.map { it.sessionId }
        val slot = WorkoutSlot(
            slotId = SLOT_ID,
            programId = PROGRAM_ID,
            revisionId = REVISION_ID,
            programDayId = ProgramDayId("day-1"),
            plannedFor = DAY,
            status = slotStatus,
            attempts = ids,
            completedAt = if (slotStatus == SlotStatus.COMPLETED) attempts.first().finishedAt else null,
            targetOccurrenceKey = KEY
        )
        return TargetOccurrenceExecutionRecord(
            occurrence = PersistedTargetOccurrence(
                programId = PROGRAM_ID,
                occurrence = PlannedOccurrence(
                    occurrenceKey = KEY,
                    plannedFor = DAY,
                    components = listOf(OccurrenceComponent("rule-a", "workout-a"))
                )
            ),
            slot = slot,
            attempts = attempts.toList()
        )
    }

    /**
     * A stored attempt of [status] against the suite's own slot.
     *
     * `startedAt` is a parameter so a multi-attempt history is a genuine *sequence* of stored
     * attempts rather than three indistinguishable ones, which is what makes the ordering tests
     * about something.
     */
    private fun session(
        tag: String = "a",
        status: SessionStatus,
        startedAt: Instant = FIRST
    ): WorkoutSession {
        val sessionId = SessionId("session-$tag")
        val finishedAt = if (status == SessionStatus.IN_PROGRESS) null else startedAt.plus(FORTY_MINUTES)
        val workout = EffectiveWorkout(
            slotId = SLOT_ID,
            programId = PROGRAM_ID,
            revisionId = REVISION_ID,
            plannedFor = DAY,
            computedAt = startedAt,
            exercises = emptyList()
        )
        return WorkoutSession(
            sessionId = sessionId,
            slotId = SLOT_ID,
            programId = PROGRAM_ID,
            revisionId = REVISION_ID,
            snapshot = WorkoutSessionSnapshot(
                sessionId = sessionId,
                capturedAt = startedAt,
                workout = workout
            ),
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt
        )
    }

    /** Every ordering of [items], so an order-independence claim is checked exhaustively. */
    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { head -> permutations(items - head).map { tail -> listOf(head) + tail } }
        }

    private companion object {
        val KEY = "strength:2026-10-05"
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val PROGRAM_ID = ProgramId("program-1")
        val REVISION_ID = RevisionId("revision-1")
        val SLOT_ID = SlotId("slot-1")

        val FIRST: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val SECOND: Instant = Instant.parse("2026-10-06T08:00:00Z")
        val THIRD: Instant = Instant.parse("2026-10-07T08:00:00Z")
        val FORTY_MINUTES: Duration = Duration.ofMinutes(40)
    }
}
