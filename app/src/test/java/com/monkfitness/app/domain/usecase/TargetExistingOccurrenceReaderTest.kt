package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionReadException
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * §30 step 17's bridge, measured on a real SQLite engine: a stored target occurrence becomes the
 * target scheduling input, and the execution it carries is the Phase 16 policy's verdict.
 *
 * The rows are written by the production write paths — a real `TargetScheduleSlotPersister` pass for
 * the slot and the semantic occurrence, a real `startSession` / `finishSession` / `recordSessionOutcome`
 * for each attempt — because the point of the bridge is that it reads what was actually stored, not
 * that it can be handed a hand-built verdict.
 *
 * The claims under test:
 *
 * ```text
 *  1. an occurrence with no attempt becomes a PLANNED scheduling input
 *  2. a started / completed / cancelled history becomes STARTED / COMPLETED / CANCELLED
 *  3. the execution is the Phase 16 policy's, over the same stored history
 *  4. the payload is forwarded unchanged and no performance data is required or produced
 *  5. a read refusal propagates rather than defaulting to PLANNED
 * ```
 */
class TargetExistingOccurrenceReaderTest {

    private val rig = ProgramDataAccessRig("target-existing")
    private val key = "target-existing"
    private val programId = ProgramId(ProgramGraphFixture.programId(key))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId(key))
    private val day = ProgramDayId(ProgramGraphFixture.dayId(key, 1))

    @After
    fun close() {
        rig.close()
    }

    // ---- 1./2. the stored history becomes the scheduling execution ---------------------------------

    @Test
    fun anOccurrenceWithNoAttemptBecomesAPlannedSchedulingInput() = runBlocking {
        persistTarget("strength", DAY)

        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))

        assertEquals(OCCURRENCE_EXECUTION_PLANNED, existing.execution)
        assertEquals("strength", existing.occurrenceKey)
    }

    @Test
    fun aStartedAttemptBecomesAStartedSchedulingInput() = runBlocking {
        val slot = persistTarget("strength", DAY)
        rig.startSessionWithSets(session("a", slot))

        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))

        assertEquals(OccurrenceExecution.STARTED, existing.execution)
    }

    @Test
    fun aCompletedAttemptBecomesACompletedSchedulingInput() = runBlocking {
        val slot = persistTarget("strength", DAY)
        completeSession("a", slot)

        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))

        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
    }

    @Test
    fun aCancelledAttemptBecomesACancelledSchedulingInput() = runBlocking {
        val slot = persistTarget("strength", DAY)
        cancelSession("a", slot)

        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))

        assertEquals(OccurrenceExecution.CANCELLED, existing.execution)
    }

    // ---- 3. the verdict is the Phase 16 policy's, not the bridge's ----------------------------------

    @Test
    fun theExecutionIsThePhaseSixteenPolicyVerdictOverTheSameStoredHistory() = runBlocking {
        // `CANCELLED` then a later `IN_PROGRESS` is the history that separates "highest state
        // attained" from "state of the last attempt". Both answers are STARTED for a monotonic
        // reading and the *last attempt's* answer is also STARTED, so the discriminating case is the
        // reverse order below.
        val slot = persistTarget("strength", DAY)
        cancelSession("a", slot, EARLIEST)
        completeSession("b", slot, LATER_THAN_A)

        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))
        val record = TargetOccurrenceExecutionReader(
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            scheduleRepository = rig.programScheduleRepository,
            sessionRepository = rig.workoutSessionRepository
        ).executionRecordOf(programId, "strength")
        val decision = com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionPolicy.decide(record)

        assertEquals(decision.execution, existing.execution)
        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
        assertEquals("every stored attempt was read", 2, record.attemptIds.size)
    }

    @Test
    fun aCompletedSlotStatusDoesNotBecomeTheExecution() = runBlocking {
        // The opportunity and the execution are separate facts. A slot the persister left `PLANNED`
        // while an attempt completed must still read as COMPLETED, because the verdict comes from
        // the attempts and not from `slot.status`.
        val slot = persistTarget("strength", DAY)
        completeSession("a", slot)

        val record = TargetOccurrenceExecutionReader(
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            scheduleRepository = rig.programScheduleRepository,
            sessionRepository = rig.workoutSessionRepository
        ).executionRecordOf(programId, "strength")
        val existing = reader().existingOccurrenceOf(programId, occurrence("strength", DAY))

        assertEquals(SlotStatus.COMPLETED, record.slotStatus)
        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
    }

    // ---- 4. the payload and the absence of performance ----------------------------------------------

    @Test
    fun theCallerStatedPayloadIsForwardedUnchangedAndNothingIsAggregatedFromIt() = runBlocking {
        val planned = persistTarget("strength", DAY)
        completeSession("a", planned)
        // A payload that differs from the stored semantic record's components in its own text: the
        // bridge must forward the caller's payload verbatim, because reconciling payloads is the
        // planner's rule and rewriting one here would defeat it.
        val stated = PlannedOccurrence(
            occurrenceKey = "strength",
            plannedFor = DAY,
            components = listOf(OccurrenceComponent("rule-a", "workout-a"))
        )

        val existing = reader().existingOccurrenceOf(programId, stated)

        assertSamePayload(stated, existing.occurrence)
        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
    }

    @Test
    fun aBatchReadsEveryOccurrenceInTheCallersOrder() = runBlocking {
        val first = persistTarget("strength", DAY)
        val second = persistTarget("mobility", DAY)
        completeSession("a", first)
        cancelSession("b", second)

        val existing = reader().existingOccurrencesOf(
            programId,
            listOf(occurrence("mobility", DAY), occurrence("strength", DAY))
        )

        assertEquals(listOf("mobility", "strength"), existing.map { it.occurrenceKey })
        assertEquals(
            listOf(OccurrenceExecution.CANCELLED, OccurrenceExecution.COMPLETED),
            existing.map { it.execution }
        )
    }

    // ---- 5. refusals propagate ----------------------------------------------------------------------

    @Test
    fun aMissingStoredOccurrenceIsRefusedRatherThanDefaultedToPlanned() = runBlocking {
        rig.createGraph()
        graphPersisted = true

        val failure = assertThrows(TargetOccurrenceExecutionReadException.MissingTargetOccurrence::class.java) {
            kotlinx.coroutines.runBlocking {
                reader().existingOccurrenceOf(programId, occurrence("absent", DAY))
            }
        }

        assertEquals("absent", failure.occurrenceKey)
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private fun assertSamePayload(expected: PlannedOccurrence, actual: PlannedOccurrence) {
        assertEquals(expected.occurrenceKey, actual.occurrenceKey)
        assertEquals(expected.plannedFor, actual.plannedFor)
        assertEquals(expected.components, actual.components)
    }

    private fun reader() = TargetExistingOccurrenceReader(
        TargetOccurrenceExecutionReader(
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            scheduleRepository = rig.programScheduleRepository,
            sessionRepository = rig.workoutSessionRepository
        )
    )

    private suspend fun persistTarget(occurrenceKey: String, date: LocalDate): WorkoutSlot {
        if (!graphPersisted) {
            rig.createGraph()
            graphPersisted = true
        }
        val planned = occurrence(occurrenceKey, date)
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
                presentations = listOf(TargetOccurrencePresentation(planned, day))
            )
        )
        return rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)!!
    }

    private suspend fun completeSession(
        tag: String,
        slot: WorkoutSlot,
        startedAt: Instant = EARLIEST
    ) {
        val started = session(tag, slot, startedAt = startedAt)
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

    private fun occurrence(occurrenceKey: String, date: LocalDate) = PlannedOccurrence(
        occurrenceKey = occurrenceKey,
        plannedFor = date,
        components = listOf(OccurrenceComponent("rule-a", "workout-a"))
    )

    private fun session(
        tag: String,
        slot: WorkoutSlot,
        startedAt: Instant = EARLIEST
    ): WorkoutSession {
        val first = com.monkfitness.app.domain.common.ProgramExerciseId(
            ProgramGraphFixture.planExerciseId(key, 1)
        )
        val second = com.monkfitness.app.domain.common.ProgramExerciseId(
            ProgramGraphFixture.planExerciseId(key, 2)
        )
        val sessionId = SessionId("session-$tag")
        val captured = WorkoutSessionSnapshot(
            sessionId = sessionId,
            capturedAt = startedAt,
            workout = EffectiveWorkout(
                slotId = slot.slotId,
                programId = slot.programId,
                revisionId = slot.revisionId,
                plannedFor = slot.plannedFor,
                computedAt = ProgramGraphFixture.COMPUTED,
                exercises = listOf(
                    EffectiveExercise(first, "knee_pushup", RepPrescription(listOf(10, 8))),
                    EffectiveExercise(second, "pike_pushup", RepPrescription(listOf(8, 8)))
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
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-$tag-1"),
                    programExerciseId = first,
                    exerciseId = "knee_pushup",
                    prescription = RepPrescription(listOf(10, 8)),
                    results = listOf(
                        SetResult(
                            SetLogId("set-$tag-1"), 1, completedReps = 12, durationSeconds = 0,
                            performedAt = startedAt.plus(TEN_MINUTES)
                        )
                    )
                ),
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-$tag-2"),
                    programExerciseId = second,
                    exerciseId = "pike_pushup",
                    prescription = RepPrescription(listOf(8, 8)),
                    skipped = true
                )
            )
        )
    }

    private var ids = 0
    private var graphPersisted = false

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val EARLIEST: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val LATER_THAN_A: Instant = Instant.parse("2026-10-06T08:00:00Z")
        val TEN_MINUTES: Duration = Duration.ofMinutes(10)
        val FORTY_MINUTES: Duration = Duration.ofMinutes(40)
        val OCCURRENCE_EXECUTION_PLANNED = OccurrenceExecution.PLANNED
    }
}
