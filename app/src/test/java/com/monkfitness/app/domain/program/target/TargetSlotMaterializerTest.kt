package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TargetSlotMaterializerTest {
    @Test
    fun oneOccurrenceMaterializesOneWorkoutSlot() {
        val input = input(
            slotId = "slot-strength",
            presentation = presentation("strength:2026-10-05", DAY, "day-strength")
        )

        val result = TargetSlotMaterializer.materialize(input)

        assertEquals(
            WorkoutSlot(
                slotId = SlotId("slot-strength"),
                programId = PROGRAM_ID,
                revisionId = REVISION_ID,
                programDayId = ProgramDayId("day-strength"),
                plannedFor = DAY,
                status = SlotStatus.PLANNED,
                attempts = emptyList(),
                completedAt = null,
                targetOccurrenceKey = "strength:2026-10-05"
            ),
            result
        )
    }

    @Test
    fun multipleOccurrencesMaterializeAsIndependentSlots() {
        val first = input(
            slotId = "slot-strength",
            presentation = presentation("strength:2026-10-05", DAY, "day-strength")
        )
        val second = input(
            slotId = "slot-mobility",
            presentation = presentation("mobility:2026-10-06", DAY.plusDays(1), "day-mobility")
        )

        val results = listOf(first, second).map(TargetSlotMaterializer::materialize)

        assertEquals(listOf(SlotId("slot-strength"), SlotId("slot-mobility")), results.map { it.slotId })
        assertEquals(
            listOf("strength:2026-10-05", "mobility:2026-10-06"),
            results.map { it.targetOccurrenceKey }
        )
        assertNotEquals(results[0], results[1])
    }

    @Test
    fun sameDateOccurrencesRemainIndependentTargetSlots() {
        val strength = input(
            slotId = "slot-strength",
            presentation = presentation("strength:2026-10-05", DAY, "day-strength")
        )
        val mobility = input(
            slotId = "slot-mobility",
            presentation = presentation("mobility:2026-10-05", DAY, "day-mobility")
        )

        val strengthSlot = TargetSlotMaterializer.materialize(strength)
        val mobilitySlot = TargetSlotMaterializer.materialize(mobility)

        assertEquals(DAY, strengthSlot.plannedFor)
        assertEquals(DAY, mobilitySlot.plannedFor)
        assertEquals(SlotId("slot-strength"), strengthSlot.slotId)
        assertEquals(SlotId("slot-mobility"), mobilitySlot.slotId)
        assertEquals("strength:2026-10-05", strengthSlot.targetOccurrenceKey)
        assertEquals("mobility:2026-10-05", mobilitySlot.targetOccurrenceKey)
        assertEquals(ProgramDayId("day-strength"), strengthSlot.programDayId)
        assertEquals(ProgramDayId("day-mobility"), mobilitySlot.programDayId)
        assertNotEquals(strengthSlot, mobilitySlot)
    }

    @Test
    fun targetKeyDateAndProgramDayArePreservedByteForByte() {
        val targetKey = "  target:opaque/key:v1  "
        val input = input(
            slotId = "opaque-slot",
            programId = ProgramId("opaque-program"),
            revisionId = RevisionId("opaque-revision"),
            presentation = presentation(targetKey, DAY, "opaque-day")
        )

        val result = TargetSlotMaterializer.materialize(input)

        assertEquals(targetKey, result.targetOccurrenceKey)
        assertEquals(DAY, result.plannedFor)
        assertEquals(ProgramDayId("opaque-day"), result.programDayId)
    }

    @Test
    fun providedStorageIdentitiesArePreservedExactly() {
        val input = input(
            slotId = "caller-slot",
            programId = ProgramId("caller-program"),
            revisionId = RevisionId("caller-revision"),
            presentation = presentation("target:key", DAY, "day")
        )

        val result = TargetSlotMaterializer.materialize(input)

        assertEquals(SlotId("caller-slot"), result.slotId)
        assertEquals(ProgramId("caller-program"), result.programId)
        assertEquals(RevisionId("caller-revision"), result.revisionId)
    }

    @Test
    fun freshSlotHasNoExecutionState() {
        val result = TargetSlotMaterializer.materialize(
            input(presentation = presentation("target:key", DAY, "day"))
        )

        assertEquals(SlotStatus.PLANNED, result.status)
        assertTrue(result.attempts.isEmpty())
        assertNull(result.completedAt)
    }

    @Test
    fun blankOccurrenceIdentityIsRejected() {
        val occurrence = PlannedOccurrence(
            occurrenceKey = "  ",
            plannedFor = DAY,
            components = listOf(OccurrenceComponent("strength", "workout"))
        )
        val input = TargetSlotMaterializationInput(
            slotId = SlotId("slot"),
            programId = PROGRAM_ID,
            revisionId = REVISION_ID,
            presentation = TargetOccurrencePresentation(occurrence, ProgramDayId("day"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            TargetSlotMaterializer.materialize(input)
        }
    }

    @Test
    fun blankComponentIdentitiesAreRejectedAsInvalidOccurrenceIdentity() {
        val occurrence = PlannedOccurrence(
            occurrenceKey = "target:key",
            plannedFor = DAY,
            components = listOf(OccurrenceComponent("  ", "workout"))
        )
        val input = TargetSlotMaterializationInput(
            SlotId("slot"),
            PROGRAM_ID,
            REVISION_ID,
            TargetOccurrencePresentation(occurrence, ProgramDayId("day"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            TargetSlotMaterializer.materialize(input)
        }
    }

    @Test
    fun blankTypedIdentitiesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { SlotId("  ") }
        assertThrows(IllegalArgumentException::class.java) { ProgramId("  ") }
        assertThrows(IllegalArgumentException::class.java) { RevisionId("  ") }
        assertThrows(IllegalArgumentException::class.java) { ProgramDayId("  ") }
    }

    @Test
    fun repeatedExecutionProducesEqualityIdenticalSlots() {
        val input = input(presentation = presentation("target:key", DAY, "day"))

        val first = TargetSlotMaterializer.materialize(input)
        val second = TargetSlotMaterializer.materialize(input)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun originalPresentationAndOccurrenceRemainUnchanged() {
        val components = listOf(
            OccurrenceComponent("rule-b", "workout-b"),
            OccurrenceComponent("rule-a", "workout-a")
        )
        val occurrence = PlannedOccurrence("target:opaque:key", DAY, components)
        val presentation = TargetOccurrencePresentation(occurrence, ProgramDayId("day"))
        val presentationSnapshot = presentation.copy(occurrence = occurrence.copy(components = components.toList()))
        val occurrenceSnapshot = occurrence.copy(components = occurrence.components.toList())
        val input = TargetSlotMaterializationInput(SlotId("slot"), PROGRAM_ID, REVISION_ID, presentation)

        TargetSlotMaterializer.materialize(input)

        assertEquals(presentationSnapshot, presentation)
        assertEquals(occurrenceSnapshot, occurrence)
        assertEquals(listOf("rule-b", "rule-a"), occurrence.components.map { it.ruleId })
        assertEquals("target:opaque:key", occurrence.occurrenceKey)
        assertEquals(DAY, occurrence.plannedFor)
    }

    @Test
    fun targetIdentityIsNeitherStorageIdentityDateNorProgramDay() {
        val input = input(
            slotId = "slot-2026-10-05-day-strength",
            presentation = presentation("opaque-target-key", DAY, "day-strength")
        )

        val result = TargetSlotMaterializer.materialize(input)

        assertTrue(result.slotId.value != result.targetOccurrenceKey)
        assertTrue(result.plannedFor.toString() != result.targetOccurrenceKey)
        assertTrue(result.programDayId.value != result.targetOccurrenceKey)
    }

    private fun input(
        slotId: String = "slot",
        programId: ProgramId = PROGRAM_ID,
        revisionId: RevisionId = REVISION_ID,
        presentation: TargetOccurrencePresentation
    ) = TargetSlotMaterializationInput(
        slotId = SlotId(slotId),
        programId = programId,
        revisionId = revisionId,
        presentation = presentation
    )

    private fun presentation(
        occurrenceKey: String,
        plannedFor: LocalDate,
        programDayId: String
    ) = TargetOccurrencePresentation(
        occurrence = PlannedOccurrence(
            occurrenceKey = occurrenceKey,
            plannedFor = plannedFor,
            components = listOf(OccurrenceComponent("strength", "workout"))
        ),
        programDayId = ProgramDayId(programDayId)
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val PROGRAM_ID = ProgramId("program-1")
        val REVISION_ID = RevisionId("revision-1")
    }
}
