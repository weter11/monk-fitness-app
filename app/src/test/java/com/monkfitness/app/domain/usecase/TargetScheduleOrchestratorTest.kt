package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.TargetPlan
import com.monkfitness.app.domain.program.target.TargetPlanner
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetSchedulePolicy
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import com.monkfitness.app.domain.program.target.TargetSupersessionReason
import com.monkfitness.app.domain.workout.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 12 behaviour: the orchestrator composes the three existing stages and adds no
 * scheduling semantics of its own.
 */
class TargetScheduleOrchestratorTest {
    private val rig = ProgramDataAccessRig("target-orchestration")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-orchestration"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-orchestration"))
    private val graph = ProgramGraphFixture.graph("target-orchestration")
    private val programDayId = graph.revision.days.first().programDayId
    private val otherProgramDayId = graph.dayId(3)
    private val generatedIds = mutableListOf<String>()

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun theOnlyExecutionPathIsPlannerThenPolicyThenApplicationService() = runBlocking {
        rig.createGraph()
        val request = request(asOf = DAY)

        val result = orchestrator().apply(request)

        val expectedPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )
        assertEquals(expectedPlan, result.targetPlan)
        val expectedDecision = TargetSchedulePolicy.decide(
            targetPlan = expectedPlan,
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        assertEquals(expectedDecision, result.decision)
        assertSame("the policy's own decision instance must reach application", result.decision, result.applicationResult.decision)
        assertSame("the planner's own plan must reach the policy and the result", result.targetPlan, result.decision.targetPlan)
        assertSame(request, result.request)
        assertEquals(
            listOf("strength:2026-10-05", "strength:2026-10-06", "strength:2026-10-07"),
            result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey }
        )
    }

    @Test
    fun onlyPolicyCreatedReachesApplicationEvenWhenPlannedIsLarger() = runBlocking {
        rig.createGraph()
        val request = request(asOf = DAY.plusDays(1))

        val result = orchestrator().apply(request)

        val plannedKeys = result.targetPlan.planned.map { it.occurrenceKey }
        val createdKeys = result.decision.created.map { it.occurrenceKey }
        assertEquals(3, plannedKeys.size)
        assertEquals(listOf("strength:2026-10-06", "strength:2026-10-07"), createdKeys)
        assertFalse(plannedKeys == createdKeys)
        assertEquals(createdKeys, result.applicationResult.presentations.map { it.occurrence.occurrenceKey })
        assertEquals(createdKeys, result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey })
        assertTrue(
            rig.programScheduleRepository.slotsOfProgram(programId)
                .none { it.targetOccurrenceKey == "strength:2026-10-05" }
        )
    }

    @Test
    fun sameDateTargetOccurrenceKeysRemainIndependent() = runBlocking {
        rig.createGraph()
        val request = request(
            asOf = DAY,
            schedules = listOf(
                TargetSchedule.daily("strength", "strength-workout", DAY),
                TargetSchedule.daily("mobility", "mobility-workout", DAY)
            ),
            window = TargetScheduleWindow(DAY, DAY),
            bindings = listOf(binding("strength-workout"), binding("mobility-workout"))
        )

        val result = orchestrator().apply(request)

        assertEquals(
            listOf("mobility:2026-10-05", "strength:2026-10-05"),
            result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey }
        )
        assertEquals(2, result.applicationResult.persistenceResult.created.size)
        assertEquals(
            2,
            rig.programScheduleRepository.slotsOfProgram(programId).count { it.plannedFor == DAY }
        )
    }

    @Test
    fun anExistingTargetRetainedByThePolicyIsNotRecreated() = runBlocking {
        rig.createGraph()
        val retained = strengthOn(DAY.plusDays(1))
        rig.programScheduleRepository.addSlots(listOf(targetSlot("retained-slot", retained, programDayId)))
        val request = request(asOf = DAY, existing = listOf(existing(retained, OccurrenceExecution.PLANNED)))

        val result = orchestrator().apply(request)

        assertEquals(listOf("strength:2026-10-06"), result.decision.retained.map { it.occurrence.occurrenceKey })
        assertFalse(result.decision.created.any { it.occurrenceKey == "strength:2026-10-06" })
        assertEquals(
            listOf("strength:2026-10-05", "strength:2026-10-07"),
            result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey }
        )
        val stored = rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-06")
        assertEquals(SlotId("retained-slot"), stored?.slotId)
        assertEquals(
            1,
            rig.programScheduleRepository.slotsOfProgram(programId)
                .count { it.targetOccurrenceKey == "strength:2026-10-06" }
        )
    }

    @Test
    fun anExistingStartedOccurrenceAndSlotRemainUntouched() = runBlocking {
        rig.createGraph()
        val started = strengthOn(DAY.plusDays(1))
        rig.programScheduleRepository.addSlots(listOf(targetSlot("started-slot", started, programDayId)))
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("orch-started", rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-06")!!))
        val request = request(asOf = DAY, existing = listOf(existing(started, OccurrenceExecution.STARTED)))

        val result = orchestrator().apply(request)

        assertEquals(
            listOf("strength:2026-10-06"),
            result.decision.preserved.map { it.occurrence.occurrenceKey }
        )
        assertTrue(result.decision.retained.isEmpty())
        assertFalse(result.decision.created.any { it.occurrenceKey == "strength:2026-10-06" })
        val stored = rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-06")!!
        assertEquals(SlotId("started-slot"), stored.slotId)
        assertEquals(SlotStatus.PLANNED, stored.status)
        assertEquals(listOf(SessionId("session-orch-started")), stored.attempts)
    }

    @Test
    fun anExistingCompletedOccurrenceAndSlotRemainUntouched() = runBlocking {
        rig.createGraph()
        val completed = strengthOn(DAY.plusDays(1))
        val planted = targetSlot("completed-slot", completed, programDayId)
        rig.programScheduleRepository.addSlots(listOf(planted))
        val session = ProgramGraphFixture.session("orch-completed", planted)
        rig.workoutSessionRepository.startSession(session)
        rig.workoutSessionRepository.finishSession(
            session.copy(status = SessionStatus.COMPLETED, finishedAt = FINISHED),
            ProgramGraphFixture.completedSlot("orch-completed", planted)
        )
        val request = request(asOf = DAY, existing = listOf(existing(completed, OccurrenceExecution.COMPLETED)))

        val result = orchestrator().apply(request)

        assertEquals(
            listOf("strength:2026-10-06"),
            result.decision.preserved.map { it.occurrence.occurrenceKey }
        )
        val stored = rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-06")!!
        assertEquals(SlotId("completed-slot"), stored.slotId)
        assertEquals(SlotStatus.COMPLETED, stored.status)
        assertEquals(
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-06")!!.completedAt,
            stored.completedAt
        )
        assertTrue(generatedIds.none { it == "completed-slot" })
    }

    @Test
    fun thePersistedSetIsExactlyThePolicyCreatedSetUnderAPause() = runBlocking {
        rig.createGraph()
        val request = request(
            asOf = DAY,
            pauses = listOf(ProgramPauseWindow(DAY.plusDays(1), DAY.plusDays(1)))
        )

        val result = orchestrator().apply(request)

        val createdKeys = result.decision.created.map { it.occurrenceKey }
        assertEquals(listOf("strength:2026-10-05", "strength:2026-10-07"), createdKeys)
        assertEquals(createdKeys, result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey })
        assertEquals(
            createdKeys,
            rig.programScheduleRepository.slotsOfProgram(programId)
                .mapNotNull { it.targetOccurrenceKey }
        )
    }

    @Test
    fun aPastUnpausedOccurrenceStaysMissedAndIsNeverPersisted() = runBlocking {
        rig.createGraph()
        val past = strengthOn(DAY)
        val request = request(
            asOf = DAY.plusDays(2),
            window = TargetScheduleWindow(DAY, DAY.plusDays(2)),
            existing = listOf(existing(past, OccurrenceExecution.PLANNED))
        )

        val result = orchestrator().apply(request)

        assertEquals(listOf("strength:2026-10-05"), result.decision.missed.map { it.occurrence.occurrenceKey })
        assertFalse(result.decision.created.any { it.occurrenceKey == "strength:2026-10-05" })
        assertFalse(result.decision.superseded.any { it.occurrence.occurrence.occurrenceKey == "strength:2026-10-05" })
        assertTrue(
            rig.programScheduleRepository.slotsOfProgram(programId)
                .none { it.targetOccurrenceKey == "strength:2026-10-05" }
        )
    }

    @Test
    fun aRemovedFutureTargetStaysSupersededAndIsNotRecreated() = runBlocking {
        rig.createGraph()
        val removed = planned("legacy:2026-10-09", DAY.plusDays(4), "legacy", "legacy-workout")
        val request = request(
            asOf = DAY,
            window = TargetScheduleWindow(DAY, DAY.plusDays(2)),
            existing = listOf(existing(removed, OccurrenceExecution.PLANNED))
        )

        val result = orchestrator().apply(request)

        val superseded = result.decision.superseded.single()
        assertEquals("legacy:2026-10-09", superseded.occurrence.occurrence.occurrenceKey)
        assertEquals(TargetSupersessionReason.TARGET_NO_LONGER_PRESENTS_OCCURRENCE, superseded.reason)
        assertFalse(result.decision.created.any { it.occurrenceKey == "legacy:2026-10-09" })
        assertTrue(
            rig.programScheduleRepository.slotsOfProgram(programId)
                .none { it.targetOccurrenceKey == "legacy:2026-10-09" }
        )
    }

    @Test
    fun aMissingProgramDayBindingPropagatesUnchanged() {
        runBlocking { rig.createGraph() }
        val request = request(asOf = DAY, bindings = emptyList())

        val failure = assertThrows(
            com.monkfitness.app.domain.program.target.TargetOccurrencePresentationException.MissingProgramDayBinding::class.java
        ) {
            runBlocking { orchestrator().apply(request) }
        }

        assertEquals("strength-workout", failure.workoutId)
        assertEquals("strength:2026-10-05", failure.occurrenceKey)
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun aPersistenceConflictPropagatesUnchanged() {
        runBlocking { rig.createGraph() }
        val planted = strengthOn(DAY)
        runBlocking {
            rig.programScheduleRepository.addSlots(
                listOf(targetSlot("conflicting-slot", planted, otherProgramDayId))
            )
        }

        val failure = assertThrows(TargetSlotPersistenceException::class.java) {
            runBlocking { orchestrator().apply(request(asOf = DAY)) }
        }

        assertEquals("strength:2026-10-05", failure.targetOccurrenceKey)
        assertEquals(programDayId, failure.expectedProgramDayId)
        assertEquals(otherProgramDayId, failure.actualProgramDayId)
    }

    @Test
    fun anEmptyCreatedSetPersistsNothingAndStillReturnsEveryBoundary() = runBlocking {
        rig.createGraph()
        val request = request(asOf = DAY.plusDays(3))

        val result = orchestrator().apply(request)

        assertEquals(3, result.targetPlan.planned.size)
        assertTrue(result.decision.created.isEmpty())
        assertTrue(result.applicationResult.presentations.isEmpty())
        assertTrue(result.applicationResult.persistenceResult.created.isEmpty())
        assertTrue(result.applicationResult.persistenceResult.retained.isEmpty())
        assertTrue(generatedIds.isEmpty())
        assertEquals(request, result.request)
        assertTrue(result.decision.targetPlan === result.targetPlan)
    }

    @Test
    fun theRequestInputsAreNeverMutated() = runBlocking {
        rig.createGraph()
        val schedules = mutableListOf(TargetSchedule.daily("strength", "strength-workout", DAY))
        val existing = mutableListOf(existing(planned("legacy:2026-10-09", DAY.plusDays(4), "legacy", "legacy-workout"), OccurrenceExecution.PLANNED))
        val bindings = mutableListOf(binding("strength-workout"))
        val pauses = mutableListOf(ProgramPauseWindow(DAY.plusDays(1), DAY.plusDays(1)))
        val selection = CompositionSelection.combine("strength")
        val sources = mutableMapOf<String, com.monkfitness.app.domain.program.target.ResolvedScheduleSource>()
        val request = TargetScheduleOrchestrationRequest(
            programId = programId,
            revisionId = revisionId,
            schedules = schedules,
            window = TargetScheduleWindow(DAY, DAY.plusDays(2)),
            selection = selection,
            existing = existing,
            sources = sources,
            asOf = DAY,
            pauses = pauses,
            programDayBindings = bindings
        )
        val snapshot = request.copy(
            schedules = schedules.toList(),
            existing = existing.toList(),
            programDayBindings = bindings.toList(),
            pauses = pauses.toList(),
            sources = sources.toMap()
        )

        orchestrator().apply(request)

        assertEquals(snapshot, request)
        assertEquals(listOf("strength"), schedules.map { it.ruleId })
        assertEquals(listOf("legacy:2026-10-09"), existing.map { it.occurrence.occurrenceKey })
        assertEquals(listOf("strength-workout"), bindings.map { it.workoutId })
        assertEquals(1, pauses.size)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun equivalentRequestsProduceEqualityIdenticalSemanticResults() = runBlocking {
        val firstRig = ProgramDataAccessRig("target-orchestration")
        val secondRig = ProgramDataAccessRig("target-orchestration")
        try {
            firstRig.createGraph()
            secondRig.createGraph()
            val request = request(asOf = DAY)

            val first = orchestrator(firstRig, mutableListOf()).apply(request)
            val second = orchestrator(secondRig, mutableListOf()).apply(request)

            assertEquals(first.targetPlan, second.targetPlan)
            assertEquals(first.decision, second.decision)
            assertEquals(first.applicationResult, second.applicationResult)
            assertEquals(first, second)
        } finally {
            firstRig.close()
            secondRig.close()
        }
    }

    @Test
    fun theLegacyContourIsNeitherCalledNorDisturbed() = runBlocking {
        rig.createGraph()
        val legacyBefore = rig.programScheduleRepository.slotsOfProgram(programId)
            .filter { it.targetOccurrenceKey == null }
        assertTrue(legacyBefore.isNotEmpty())

        val result = orchestrator().apply(request(asOf = DAY))

        val legacyAfter = rig.programScheduleRepository.slotsOfProgram(programId)
            .filter { it.targetOccurrenceKey == null }
        assertEquals(legacyBefore, legacyAfter)
        assertTrue(
            result.applicationResult.persistenceResult.created.all { it.targetOccurrenceKey != null }
        )
    }

    private fun orchestrator(
        targetRig: ProgramDataAccessRig = rig,
        ids: MutableList<String> = generatedIds
    ): TargetScheduleOrchestrator = TargetScheduleOrchestrator(
        applicationService = TargetScheduleApplicationService(
            slotPersister = TargetScheduleSlotPersister(
                scheduleRepository = targetRig.programScheduleRepository,
                idGenerator = IdGenerator { "orch-slot-${ids.size + 1}".also(ids::add) }
            )
        )
    )

    private fun request(
        asOf: LocalDate,
        schedules: List<TargetSchedule> = listOf(TargetSchedule.daily("strength", "strength-workout", DAY)),
        window: TargetScheduleWindow = TargetScheduleWindow(DAY, DAY.plusDays(2)),
        existing: List<ExistingOccurrence> = emptyList(),
        pauses: List<ProgramPauseWindow> = emptyList(),
        bindings: List<TargetProgramDayBinding> = listOf(binding("strength-workout"))
    ) = TargetScheduleOrchestrationRequest(
        programId = programId,
        revisionId = revisionId,
        schedules = schedules,
        window = window,
        selection = CompositionSelection(),
        existing = existing,
        sources = emptyMap(),
        asOf = asOf,
        pauses = pauses,
        programDayBindings = bindings
    )

    private fun planned(key: String, date: LocalDate, ruleId: String, workoutId: String) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = listOf(OccurrenceComponent(ruleId, workoutId))
    )

    private fun strengthOn(date: LocalDate) = planned("strength:$date", date, "strength", "strength-workout")

    private fun existing(
        occurrence: PlannedOccurrence,
        execution: OccurrenceExecution
    ) = ExistingOccurrence(
        occurrence = occurrence,
        execution = execution,
        actuals = if (execution == OccurrenceExecution.PLANNED) {
            emptyList()
        } else {
            listOf(ActualResult("work-1", PerformedWork.reps(12)))
        }
    )

    private fun binding(workoutId: String) =
        TargetProgramDayBinding(workoutId, programDayId)

    private fun targetSlot(
        id: String,
        target: PlannedOccurrence,
        day: ProgramDayId
    ) = WorkoutSlot(
        slotId = SlotId(id),
        programId = programId,
        revisionId = revisionId,
        programDayId = day,
        plannedFor = target.plannedFor,
        status = SlotStatus.PLANNED,
        targetOccurrenceKey = target.occurrenceKey
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val FINISHED: Instant = Instant.parse("2026-10-05T10:15:00Z")
    }
}
