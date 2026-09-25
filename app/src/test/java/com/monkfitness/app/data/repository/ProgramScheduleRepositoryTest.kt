package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `ProgramScheduleRepository`: the opportunities a Program has, and its pause intervals.
 *
 * The repository stores and reads scheduling rows and makes no scheduling decision, so the assertions
 * are about identity, order and the absence of anything that could slide or measure a schedule.
 */
class ProgramScheduleRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val programId = ProgramId(ProgramGraphFixture.programId("a"))

    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("a"))

    @Test
    fun theSlotsOfAProgramComeBackInPlannedDateOrderWithTheirPlanDay() = runBlocking {
        rig.createGraph()

        val slots = rig.programScheduleRepository.slotsOfProgram(programId)

        assertEquals(
            listOf("2026-09-21", "2026-09-22", "2026-09-23"),
            slots.map { it.plannedFor.toString() }
        )
        assertEquals(
            listOf("day-a-1", "day-a-2", "day-a-3"),
            slots.map { it.programDayId.value }
        )
        assertEquals(
            "and every slot names the Program and the revision it was scheduled from",
            listOf(programId) + List(2) { programId },
            slots.map { it.programId }
        )
        assertEquals(List(3) { revisionId }, slots.map { it.revisionId })
        assertEquals(
            "the same slots are readable from the revision they came from",
            slots.map { it.slotId },
            rig.programScheduleRepository.slotsOfRevision(revisionId).map { it.slotId }
        )
    }

    @Test
    fun aSlotIsReadByIdentityWithTheSessionsAttemptedForIt() = runBlocking {
        rig.createGraph()
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("b", rig.graph.slotFor(1)))

        val slot = rig.programScheduleRepository.slotById(SlotId(ProgramGraphFixture.slotId("a", 1)))!!

        assertEquals(SlotStatus.PLANNED, slot.status)
        assertEquals(listOf(SessionId("session-b")), slot.attempts)
        assertNull("an open opportunity has not happened", slot.completedAt)
        assertNull(rig.programScheduleRepository.slotById(SlotId("slot-nowhere")))
    }

    @Test
    fun slotsAreFilteredByDateAndStatusForTheCallerWhoAsks() = runBlocking {
        rig.createGraph()

        assertEquals(
            listOf("2026-09-22", "2026-09-23"),
            rig.programScheduleRepository
                .slotsFrom(programId, LocalDate.parse("2026-09-22"), SlotStatus.PLANNED)
                .map { it.plannedFor.toString() }
        )
        assertEquals(
            "the filter is the caller's date, not a horizon this layer decided",
            emptyList<String>(),
            rig.programScheduleRepository
                .slotsFrom(programId, LocalDate.parse("2026-09-24"), SlotStatus.PLANNED)
                .map { it.plannedFor.toString() }
        )
        assertEquals(3, rig.programScheduleRepository.countSlots(programId, SlotStatus.PLANNED))
        assertEquals(0, rig.programScheduleRepository.countSlots(programId, SlotStatus.MISSED))
    }

    @Test
    fun storingSlotsKeepsTheDatesAndDaysTheCallerDecidedOn() = runBlocking {
        rig.createGraph()
        val extra = WorkoutSlot(
            slotId = SlotId("slot-a-4"),
            programId = programId,
            revisionId = revisionId,
            programDayId = ProgramDayId(ProgramGraphFixture.dayId("a", 1)),
            plannedFor = LocalDate.parse("2026-09-28"),
            status = SlotStatus.PLANNED
        )

        rig.programScheduleRepository.addSlots(listOf(extra))

        assertEquals(
            "what was stored is the slot that was handed in — no date was generated and none shifted",
            extra,
            rig.programScheduleRepository.slotById(extra.slotId)!!.copy(attempts = emptyList())
        )
        assertEquals(4, rig.programScheduleRepository.countSlots(programId, SlotStatus.PLANNED))
    }

    @Test
    fun targetIdentityLookupUsesOnlyProgramAndOccurrenceKey() = runBlocking {
        rig.createGraph()
        val target = targetSlot("slot-a-4", "2026-10-05", "strength:2026-10-05")
        rig.programScheduleRepository.addSlots(listOf(target))

        val found = rig.programScheduleRepository.slotByTargetOccurrenceKey(
            programId,
            "strength:2026-10-05"
        )

        assertEquals(target, found)
        assertNull(
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "missing:2026-10-05")
        )
        assertNull(
            "the same date is not a fallback identity",
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "mobility:2026-10-05")
        )
        assertEquals(
            ProgramDaoSql.PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_TARGET_OCCURRENCE_KEY,
            "SELECT * FROM `program_workout_slot` WHERE `programId` = :programId AND " +
                "`targetOccurrenceKey` = :targetOccurrenceKey LIMIT 1"
        )
    }

    @Test
    fun oneProgramCanPersistMultipleTargetOccurrencesOnTheSameDate() = runBlocking {
        rig.createGraph()
        val strength = targetSlot("slot-a-4", "2026-10-05", "strength:2026-10-05")
        val mobility = targetSlot("slot-a-5", "2026-10-05", "mobility:2026-10-05")

        rig.programScheduleRepository.addSlots(listOf(strength, mobility))

        val stored = rig.programScheduleRepository.slotsOfProgram(programId)
            .filter { it.plannedFor == LocalDate.parse("2026-10-05") }
        assertEquals(listOf(strength, mobility), stored)
        assertEquals(
            "each semantic key finds its own row",
            strength,
            rig.programScheduleRepository.slotByTargetOccurrenceKey(
                programId,
                "strength:2026-10-05"
            )
        )
        assertEquals(
            mobility,
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "mobility:2026-10-05")
        )
    }

    @Test
    fun oneProgramCannotPersistTheSameTargetOccurrenceTwiceButAnotherProgramMay() = runBlocking {
        rig.createGraph()
        val first = targetSlot("slot-a-4", "2026-10-05", "strength:2026-10-05")
        val duplicate = first.copy(slotId = SlotId("slot-a-duplicate"))
        rig.programScheduleRepository.addSlots(listOf(first))

        val duplicateFailure = runCatching {
            rig.programScheduleRepository.addSlots(listOf(duplicate))
        }.exceptionOrNull()
        assertTrue(
            "the Program/key unique index refuses the duplicate: $duplicateFailure",
            duplicateFailure?.message?.contains("UNIQUE constraint failed") == true
        )
        assertNull(rig.programScheduleRepository.slotById(duplicate.slotId))

        val otherProgram = ProgramId(ProgramGraphFixture.programId("b"))
        val otherGraph = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(otherGraph.program, otherGraph.revision, otherGraph.slots)
        val sameKeyElsewhere = targetSlot(
            "slot-b-4",
            "2026-10-05",
            "strength:2026-10-05",
            otherProgram,
            otherGraph.revision.revisionId,
            otherGraph.revision.days.first().programDayId
        )
        rig.programScheduleRepository.addSlots(listOf(sameKeyElsewhere))
        assertEquals(
            "identity is scoped by Program",
            sameKeyElsewhere,
            rig.programScheduleRepository.slotByTargetOccurrenceKey(
                otherProgram,
                "strength:2026-10-05"
            )
        )
    }

    @Test
    fun multipleLegacyNullTargetKeysCanShareAProgramAndDate() = runBlocking {
        rig.createGraph()
        val first = targetSlot("slot-a-4", "2026-10-05", null)
        val second = targetSlot("slot-a-5", "2026-10-05", null)

        rig.programScheduleRepository.addSlots(listOf(first, second))

        assertEquals(
            listOf(first, second),
            rig.programScheduleRepository.slotsOfProgram(programId)
                .filter { it.plannedFor == LocalDate.parse("2026-10-05") }
        )
    }

    private fun targetSlot(
        slotId: String,
        date: String,
        targetOccurrenceKey: String?,
        owner: ProgramId = programId,
        revision: RevisionId = revisionId,
        day: ProgramDayId = ProgramDayId(ProgramGraphFixture.dayId("a", 1))
    ) = WorkoutSlot(
        slotId = SlotId(slotId),
        programId = owner,
        revisionId = revision,
        programDayId = day,
        plannedFor = LocalDate.parse(date),
        status = SlotStatus.PLANNED,
        targetOccurrenceKey = targetOccurrenceKey
    )

    @Test
    fun recordingAnOutcomeChangesThatSlotAndNothingElse() = runBlocking {
        rig.createGraph()
        val missed = SlotId(ProgramGraphFixture.slotId("a", 1))

        rig.programScheduleRepository.recordSlotOutcome(missed, SlotStatus.MISSED, null)

        val slots = rig.programScheduleRepository.slotsOfProgram(programId)
        assertEquals(SlotStatus.MISSED, slots.first().status)
        assertNull("a missed opportunity is not a workout that scored zero (§12)", slots.first().completedAt)
        assertEquals(
            "no missed workout slides the schedule (§20)",
            listOf("2026-09-21", "2026-09-22", "2026-09-23"),
            slots.map { it.plannedFor.toString() }
        )
        assertEquals(
            "and the other opportunities keep their own status",
            listOf(SlotStatus.PLANNED, SlotStatus.PLANNED),
            slots.drop(1).map { it.status }
        )
        assertEquals(1, rig.programScheduleRepository.countSlots(programId, SlotStatus.MISSED))
    }

    @Test
    fun pauseIntervalsAreStoredAndClosedWithoutRewritingThem() = runBlocking {
        rig.createGraph()
        val pause = ProgramPause(PauseId("pause-a"), programId, ProgramGraphFixture.STARTED)

        rig.programScheduleRepository.addPause(pause)
        val open = rig.programScheduleRepository.pausesOfProgram(programId).single()

        assertEquals("an open interval is open, not zero-length", pause, open)
        assertTrue(open.isOpen)

        rig.programScheduleRepository.closePause(pause.pauseId, ProgramGraphFixture.FINISHED)
        val closed = rig.programScheduleRepository.pausesOfProgram(programId).single()

        assertEquals(ProgramGraphFixture.FINISHED, closed.endedAt)
        assertEquals("the interval's start is not rewritten", ProgramGraphFixture.STARTED, closed.startedAt)
        assertEquals(ProgramGraphFixture.FINISHED, rig.programScheduleRepository.pauseById(pause.pauseId)!!.endedAt)
        assertNull(rig.programScheduleRepository.pauseById(PauseId("pause-nowhere")))
    }

    @Test
    fun theScheduleRepositoryExposesNoSchedulingDecision() {
        val names = ProgramScheduleRepository::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name.substringBefore('-') }

        assertTrue(
            "no date generation, no missed detection, no supersession, no horizon extension (§20): $names",
            names.none { name ->
                listOf("generate", "supersede", "extend", "slide", "reconcile", "plan")
                    .any { name.lowercase().contains(it) }
            }
        )
        assertEquals(
            "and the surface is the slots and the pauses",
            listOf(
                "addPause", "addSlots", "closePause", "countSlots", "pauseById", "pausesOfProgram",
                "recordSlotOutcome", "slotById", "slotByTargetOccurrenceKey", "slotsFrom", "slotsOfProgram", "slotsOfRevision"
            ),
            names.sorted()
        )
    }
}
