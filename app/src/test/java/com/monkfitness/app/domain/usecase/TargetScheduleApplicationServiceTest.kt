package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
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
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentationException
import com.monkfitness.app.domain.program.target.TargetPlan
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
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

class TargetScheduleApplicationServiceTest {
    private val rig = ProgramDataAccessRig("target-application")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-application"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-application"))
    private val graph = ProgramGraphFixture.graph("target-application")
    private val programDayId = graph.revision.days.first().programDayId
    private val generatedIds = mutableListOf<String>()

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun onlyCreatedOccurrencesArePresented() = runBlocking {
        rig.createGraph()
        val created = listOf(occurrence("strength:2026-10-05", DAY, "strength"))
        val decision = decision(created, planned = created + occurrence("mobility:2026-10-06", DAY.plusDays(1), "mobility"))

        val result = service().apply(programId, revisionId, decision, listOf(binding("strength")))

        assertEquals(created, result.presentations.map { it.occurrence })
        assertEquals(listOf("strength:2026-10-05"), result.persistenceResult.created.map { it.targetOccurrenceKey })
    }

    @Test
    fun explicitProgramDayBindingIsUsedWithoutConversion() = runBlocking {
        rig.createGraph()
        val created = listOf(occurrence("strength:2026-10-05", DAY, "strength"))

        val result = service().apply(
            programId,
            revisionId,
            decision(created),
            listOf(TargetProgramDayBinding("strength", programDayId))
        )

        assertEquals(programDayId, result.presentations.single().programDayId)
        assertEquals(programDayId, result.persistenceResult.created.single().programDayId)
    }

    @Test
    fun sameDateDifferentOccurrenceKeysRemainIndependent() = runBlocking {
        rig.createGraph()
        val created = listOf(
            occurrence("mobility:2026-10-05", DAY, "mobility"),
            occurrence("strength:2026-10-05", DAY, "strength")
        )

        val result = service().apply(
            programId,
            revisionId,
            decision(created),
            listOf(binding("mobility"), binding("strength"))
        )

        assertEquals(listOf("mobility:2026-10-05", "strength:2026-10-05"), result.persistenceResult.created.map { it.targetOccurrenceKey })
        assertEquals(listOf(SlotId("slot-1"), SlotId("slot-2")), result.persistenceResult.created.map { it.slotId })
    }

    @Test
    fun emptyCreatedSetProducesNoPresentationAndNoSlot() = runBlocking {
        rig.createGraph()
        val decision = decision(emptyList(), planned = listOf(occurrence("strength:2026-10-05", DAY, "strength")))

        val result = service().apply(programId, revisionId, decision, listOf(binding("strength")))

        assertTrue(result.presentations.isEmpty())
        assertTrue(result.persistenceResult.created.isEmpty())
        assertTrue(result.persistenceResult.retained.isEmpty())
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun missingBindingPropagatesUnchanged() = runBlocking {
        rig.createGraph()
        val created = listOf(occurrence("strength:2026-10-05", DAY, "strength"))

        val failure = assertThrows(TargetOccurrencePresentationException.MissingProgramDayBinding::class.java) {
            runBlocking { service().apply(programId, revisionId, decision(created), emptyList()) }
        }

        assertEquals("strength", failure.workoutId)
        assertEquals("strength:2026-10-05", failure.occurrenceKey)
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun multiDayOccurrencePropagatesWithoutCollapse() = runBlocking {
        rig.createGraph()
        val otherDay = graph.revision.days.drop(1).first().programDayId
        val created = listOf(
            occurrence(
                "combined:2026-10-05",
                DAY,
                "strength-workout",
                "mobility-workout"
            )
        )

        val failure = assertThrows(TargetOccurrencePresentationException.MultiDayProgramOccurrence::class.java) {
            runBlocking {
                service().apply(
                    programId,
                    revisionId,
                    decision(created),
                    listOf(
                        TargetProgramDayBinding("strength-workout", programDayId),
                        TargetProgramDayBinding("mobility-workout", otherDay)
                    )
                )
            }
        }

        assertEquals("combined:2026-10-05", failure.occurrenceKey)
        assertEquals(setOf(programDayId, otherDay), failure.programDayIds)
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun persistenceResultAndOriginalDecisionArePreserved() = runBlocking {
        rig.createGraph()
        val decision = decision(listOf(occurrence("strength:2026-10-05", DAY, "strength")))

        val result = service().apply(programId, revisionId, decision, listOf(binding("strength")))

        assertSame(decision, result.decision)
        assertEquals(SlotStatus.PLANNED, result.persistenceResult.created.single().status)
        assertTrue(result.persistenceResult.created.single().attempts.isEmpty())
        assertEquals(null, result.persistenceResult.created.single().completedAt)
    }

    @Test
    fun sameDecisionCanBeReappliedWithoutCreatingAnotherSlot() = runBlocking {
        rig.createGraph()
        val decision = decision(listOf(occurrence("strength:2026-10-05", DAY, "strength")))
        val application = service()

        val first = application.apply(programId, revisionId, decision, listOf(binding("strength")))
        val second = application.apply(programId, revisionId, decision, listOf(binding("strength")))

        assertEquals(first.persistenceResult.created, second.persistenceResult.retained)
        assertTrue(second.persistenceResult.created.isEmpty())
        assertEquals(listOf("slot-1"), generatedIds)
        assertEquals(1, rig.programScheduleRepository.slotsOfProgram(programId).count { it.targetOccurrenceKey == "strength:2026-10-05" })
    }

    @Test
    fun startedExecutionStateRemainsIntactOnReapplication() = runBlocking {
        rig.createGraph()
        val decision = decision(listOf(occurrence("strength:2026-10-05", DAY, "strength")))
        val existing = targetSlot("existing-slot", decision.created.single())
        rig.programScheduleRepository.addSlots(listOf(existing))
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("started", existing))

        val result = service().apply(programId, revisionId, decision, listOf(binding("strength")))

        assertTrue(result.persistenceResult.created.isEmpty())
        assertEquals(SlotId("existing-slot"), result.persistenceResult.retained.single().slotId)
        assertEquals(listOf(SessionId("session-started")), result.persistenceResult.retained.single().attempts)
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun completedExecutionStateRemainsIntactOnReapplication() = runBlocking {
        rig.createGraph()
        val decision = decision(listOf(occurrence("strength:2026-10-05", DAY, "strength")))
        val existing = targetSlot("completed-slot", decision.created.single())
        rig.programScheduleRepository.addSlots(listOf(existing))
        val session = ProgramGraphFixture.session("completed", existing)
        rig.workoutSessionRepository.startSession(session)
        rig.workoutSessionRepository.finishSession(
            session.copy(status = SessionStatus.COMPLETED, finishedAt = FINISHED),
            ProgramGraphFixture.completedSlot("completed", existing)
        )

        val result = service().apply(programId, revisionId, decision, listOf(binding("strength")))

        assertTrue(result.persistenceResult.created.isEmpty())
        assertEquals(SlotStatus.COMPLETED, result.persistenceResult.retained.single().status)
        assertEquals(
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-05")!!.completedAt,
            result.persistenceResult.retained.single().completedAt
        )
        assertTrue(generatedIds.isEmpty())
    }

    @Test
    fun decisionBindingsAndCreatedInputRemainUnchanged() = runBlocking {
        rig.createGraph()
        val created = mutableListOf(occurrence("strength:2026-10-05", DAY, "strength"))
        val bindings = mutableListOf(binding("strength"))
        val decision = decision(created)
        val decisionSnapshot = decision.copy(
            targetPlan = decision.targetPlan.copy(
                planned = decision.targetPlan.planned.toList(),
                reconciliation = decision.targetPlan.reconciliation.copy(
                    added = decision.targetPlan.reconciliation.added.toList()
                )
            ),
            created = decision.created.toList()
        )

        service().apply(programId, revisionId, decision, bindings)

        assertEquals(decisionSnapshot, decision)
        assertEquals(listOf("strength:2026-10-05"), created.map { it.occurrenceKey })
        assertEquals(listOf("strength"), bindings.map { it.workoutId })
    }

    @Test
    fun applicationServiceDelegatesEveryNewIdentityAndMaterializationToThePersister() = runBlocking {
        rig.createGraph()
        val created = listOf(occurrence("strength:2026-10-05", DAY, "strength"))

        val result = service().apply(programId, revisionId, decision(created), listOf(binding("strength")))

        assertEquals(SlotId("slot-1"), result.persistenceResult.created.single().slotId)
        assertEquals("strength:2026-10-05", result.persistenceResult.created.single().targetOccurrenceKey)
        assertEquals(programDayId, result.persistenceResult.created.single().programDayId)
        assertEquals(DAY, result.persistenceResult.created.single().plannedFor)
        assertFalse(generatedIds.isEmpty())
    }

    private fun service(): TargetScheduleApplicationService = TargetScheduleApplicationService(
        slotPersister = TargetScheduleSlotPersister(
            scheduleRepository = rig.programScheduleRepository,
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            idGenerator = IdGenerator { "slot-${generatedIds.size + 1}".also(generatedIds::add) },
            inTransaction = rig.transaction
        )
    )

    private fun decision(
        created: List<PlannedOccurrence>,
        planned: List<PlannedOccurrence> = created
    ) = TargetScheduleDecision(
        targetPlan = TargetPlan(
            planned = planned,
            reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                preserved = emptyList(),
                superseded = emptyList(),
                added = created
            )
        ),
        preserved = emptyList(),
        retained = emptyList(),
        created = created,
        superseded = emptyList(),
        missed = emptyList()
    )

    private fun occurrence(
        key: String,
        date: LocalDate,
        vararg workouts: String
    ) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = workouts.mapIndexed { index, workout -> component("rule-$index-$workout", workout) }
    )

    private fun component(ruleId: String, workoutId: String) = OccurrenceComponent(ruleId, workoutId)

    private fun binding(workoutId: String) = TargetProgramDayBinding(workoutId, programDayId)

    private fun targetSlot(id: String, target: PlannedOccurrence) = WorkoutSlot(
        slotId = SlotId(id),
        programId = programId,
        revisionId = revisionId,
        programDayId = programDayId,
        plannedFor = target.plannedFor,
        status = SlotStatus.PLANNED,
        targetOccurrenceKey = target.occurrenceKey
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val FINISHED: Instant = Instant.parse("2026-10-05T10:15:00Z")
    }
}
