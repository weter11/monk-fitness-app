package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.TargetSlotMaterializer
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetSlotMaterializationInput
import com.monkfitness.app.domain.program.target.TargetPlan
import com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation
import com.monkfitness.app.di.IdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TargetScheduleSlotPersisterTest {
    private val rig = ProgramDataAccessRig("target-persistence")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-persistence"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-persistence"))
    private val day = ProgramDayId(ProgramGraphFixture.dayId("target-persistence", 1))
    private val nextDay = ProgramDayId(ProgramGraphFixture.dayId("target-persistence", 3))

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun anAbsentTargetKeyCreatesExactlyOneFreshSlot() = runBlocking {
        rig.createGraph()
        val persister = persister("generated-slot-1")

        val result = persister.persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertEquals(1, result.created.size)
        assertEquals(SlotId("generated-slot-1"), result.created.single().slotId)
        assertEquals(SlotStatus.PLANNED, result.created.single().status)
        assertTrue(result.created.single().attempts.isEmpty())
        assertNull(result.created.single().completedAt)
        assertEquals("strength:2026-10-05", result.created.single().targetOccurrenceKey)
    }

    @Test
    fun repeatedPersistenceOfTheSameTargetKeyIsIdempotent() = runBlocking {
        rig.createGraph()
        val persister = persister("generated-slot-1", "must-not-be-used")
        val request = input(presentation("strength:2026-10-05", DAY, day))

        val first = persister.persist(request)
        val second = persister.persist(request)

        assertEquals(first.created, second.retained)
        assertTrue(second.created.isEmpty())
        assertEquals(1, rig.programScheduleRepository.slotsOfProgram(programId).count { it.targetOccurrenceKey == request.presentations.single().occurrence.occurrenceKey })
    }

    @Test
    fun sameDateDifferentTargetKeysRemainIndependent() = runBlocking {
        rig.createGraph()
        val persister = persister("slot-strength", "slot-mobility")

        val result = persister.persist(
            input(
                presentation("strength:2026-10-05", DAY, day),
                presentation("mobility:2026-10-05", DAY, nextDay)
            )
        )

        assertEquals(listOf("strength:2026-10-05", "mobility:2026-10-05"), result.created.map { it.targetOccurrenceKey })
        assertEquals(listOf(SlotId("slot-strength"), SlotId("slot-mobility")), result.created.map { it.slotId })
        assertNotEquals(result.created[0], result.created[1])
    }

    @Test
    fun oneTargetKeyInTwoProgramsIsIndependent() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("other-target-persistence")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherDay = other.revision.days.first().programDayId
        val persister = persister("slot-a", "slot-b")

        persister.persist(input(presentation("strength:2026-10-05", DAY, day)))
        val otherResult = persister.persist(
            TargetScheduleSlotPersistenceInput(
                programId = other.program.programId,
                revisionId = other.revision.revisionId,
                decision = decision("strength:2026-10-05" to DAY),
                presentations = listOf(presentation("strength:2026-10-05", DAY, otherDay))
            )
        )

        assertEquals("slot-b", otherResult.created.single().slotId.value)
        assertEquals("slot-a", rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-05")!!.slotId.value)
    }

    @Test
    fun existingStartedAttemptStateIsPreserved() = runBlocking {
        rig.createGraph()
        val key = "strength:2026-10-05"
        val existing = targetSlot("existing-slot", key, DAY, day)
        rig.programScheduleRepository.addSlots(listOf(existing))
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("started", existing))

        val result = persister("must-not-be-used").persist(input(presentation(key, DAY, day)))

        assertEquals(
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, key),
            result.retained.single()
        )
        assertEquals(SlotStatus.PLANNED, result.retained.single().status)
        assertEquals(listOf(SessionId("session-started")), result.retained.single().attempts)
    }

    @Test
    fun existingCompletedStateIsPreserved() = runBlocking {
        rig.createGraph()
        val key = "strength:2026-10-05"
        val existing = targetSlot("existing-slot", key, DAY, day)
        rig.programScheduleRepository.addSlots(listOf(existing))
        val session = ProgramGraphFixture.session("completed", existing)
        rig.workoutSessionRepository.startSession(session)
        rig.workoutSessionRepository.finishSession(
            session.copy(
                status = com.monkfitness.app.domain.workout.SessionStatus.COMPLETED,
                finishedAt = ProgramGraphFixture.FINISHED
            ),
            ProgramGraphFixture.completedSlot("completed", existing)
        )

        val result = persister("must-not-be-used").persist(input(presentation(key, DAY, day)))

        assertEquals(
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, key),
            result.retained.single()
        )
        assertEquals(SlotStatus.COMPLETED, result.retained.single().status)
    }

    @Test
    fun existingSlotIdIsNeverReplaced() = runBlocking {
        rig.createGraph()
        val key = "strength:2026-10-05"
        val existing = targetSlot("storage-slot", key, DAY, day)
        rig.programScheduleRepository.addSlots(listOf(existing))

        val result = persister("new-generated-slot").persist(input(presentation(key, DAY, day)))

        assertEquals(SlotId("storage-slot"), result.retained.single().slotId)
        assertEquals(SlotId("storage-slot"), rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, key)!!.slotId)
    }

    @Test
    fun lookupHasNoDateFallback() = runBlocking {
        rig.createGraph()
        val otherKey = targetSlot("same-date-other-key", "mobility:2026-10-05", DAY, day)
        rig.programScheduleRepository.addSlots(listOf(otherKey))

        val result = persister("strength-slot").persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertEquals("strength-slot", result.created.single().slotId.value)
        assertEquals(1, result.created.size)
    }

    @Test
    fun aSemanticPayloadConflictIsExplicitlyRejected() = runBlocking {
        rig.createGraph()
        val key = "strength:2026-10-05"
        rig.programScheduleRepository.addSlots(listOf(targetSlot("existing-slot", key, DAY, day)))

        val failure = assertThrows(TargetSlotPersistenceException::class.java) {
            runBlocking {
                persister("must-not-be-used").persist(input(presentation(key, DAY.plusDays(1), day)))
            }
        }

        assertEquals(key, failure.targetOccurrenceKey)
        assertEquals(DAY.plusDays(1), failure.expectedPlannedFor)
        assertEquals(DAY, failure.actualPlannedFor)
    }

    @Test
    fun materializerMappingIsTheOnlyNewSlotMapping() = runBlocking {
        rig.createGraph()
        val result = persister("mapped-slot").persist(input(presentation("strength:2026-10-05", DAY, day)))

        val expected = TargetSlotMaterializer.materialize(
            TargetSlotMaterializationInput(
                SlotId("mapped-slot"),
                programId,
                revisionId,
                presentation("strength:2026-10-05", DAY, day)
            )
        )

        assertEquals(expected, result.created.single())
    }

    @Test
    fun repeatedInputsDoNotMutateAndDoNotDrift() = runBlocking {
        rig.createGraph()
        val first = presentation("strength:2026-10-05", DAY, day)
        val presentations = listOf(first)
        val request = TargetScheduleSlotPersistenceInput(programId, revisionId, decision("strength:2026-10-05" to DAY), presentations)
        val snapshot = presentations.toList()
        val persister = persister("stable-slot")

        val one = persister.persist(request)
        val two = persister.persist(request)

        assertEquals(snapshot, presentations)
        assertEquals(one.created, two.retained)
        assertTrue(two.created.isEmpty())
    }

    private fun persister(vararg ids: String): TargetScheduleSlotPersister {
        val next = ids.iterator()
        return TargetScheduleSlotPersister(
            scheduleRepository = rig.programScheduleRepository,
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            idGenerator = IdGenerator { if (next.hasNext()) next.next() else error("unexpected id generation") },
            inTransaction = rig.transaction
        )
    }

    private fun input(vararg presentations: TargetOccurrencePresentation) = TargetScheduleSlotPersistenceInput(
        programId = programId,
        revisionId = revisionId,
        decision = decision(*presentations.map { it.occurrence.occurrenceKey to it.occurrence.plannedFor }.toTypedArray()),
        presentations = presentations.toList()
    )

    private fun decision(vararg occurrences: Pair<String, LocalDate>): TargetScheduleDecision {
        val planned = occurrences.map { (key, date) ->
            PlannedOccurrence(key, date, listOf(OccurrenceComponent("rule-$key", "workout")))
        }
        return TargetScheduleDecision(
            targetPlan = TargetPlan(planned, TargetOccurrenceReconciliation(emptyList(), emptyList(), planned)),
            preserved = emptyList(),
            retained = emptyList(),
            created = planned,
            superseded = emptyList(),
            missed = emptyList()
        )
    }

    private fun presentation(key: String, date: LocalDate, programDayId: ProgramDayId) = TargetOccurrencePresentation(
        PlannedOccurrence(key, date, listOf(OccurrenceComponent("rule-$key", "workout"))),
        programDayId
    )

    private fun targetSlot(
        slotId: String,
        key: String,
        date: LocalDate,
        programDayId: ProgramDayId
    ) = WorkoutSlot(
        slotId = SlotId(slotId),
        programId = programId,
        revisionId = revisionId,
        programDayId = programDayId,
        plannedFor = date,
        status = SlotStatus.PLANNED,
        targetOccurrenceKey = key
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
    }
}
