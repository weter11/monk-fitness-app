package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.data.repository.failureOf
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The editor's `edit` step, as a value: every operation answers with the next draft, and the draft it
 * was given is never touched.
 *
 * That last property is the file's spine. Draft-first means the user's in-progress work lives in a
 * value the editor replaces — if an operation could mutate the draft it was handed, then "the editor
 * never changes a saved Program" would depend on nothing having kept a reference to a shared draft,
 * and a `ProgramRevision` handed to the editor is just such a value.
 */
class ProgramDraftEditorTest {

    // ------------------------------------------------------------------ identity and order

    @Test
    fun addingDaysAndElementsMintsTheDraftsOwnIdentities() {
        val ids = TestIds()
        val editing = editor(ProgramEditorDraft(name = "My program"), ids)

        val withDay = editing.addingDay(type = ProgramDayType.TRAINING, name = "Push day")
        val withExercise = withDay
            .addingExercise(
                programDayId = withDay.draft.days.single().programDayId,
                exerciseId = "pushup",
                prescription = RepPrescription(listOf(12, 10, 8, 6))
            )
            .addingExercise(
                programDayId = withDay.draft.days.single().programDayId,
                exerciseId = "plank",
                prescription = TimePrescription(listOf(30, 30, 45))
            )
            .addingDay(type = ProgramDayType.REST)

        val plan = withExercise.draft.days
        assertEquals("two days, numbered in order", listOf(1, 2), plan.map { it.position })
        assertEquals(
            "every added thing drew one identity: day, two elements, day",
            4,
            ids.count
        )
        assertEquals(
            "and the draft's own ids come from the injected source, never from a clock or a hash (§26)",
            listOf("draft-1", "draft-2", "draft-3"),
            listOf(plan[0].programDayId.value) + plan[0].exercises.map { it.programExerciseId.value }
        )
    }

    @Test
    fun theEditorNeverMutatesTheDraftItWasGiven() {
        val untouched = ProgramEditorDraft(name = "My program", days = plan())
        val before = untouched.copy()
        val ids = TestIds()

        val edited = editor(untouched, ids)
            .renamed("Renamed")
            .described("A description")
            .withSchedule(ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY)))
            .addingExercise(
                programDayId = untouched.days.first().programDayId,
                exerciseId = "dip",
                prescription = RepPrescription(listOf(5))
            )
            .removingDay(untouched.days.last().programDayId)
            .draft

        assertEquals("the draft the editor was given is exactly as it was", before, untouched)
        assertNotEquals("and the next draft is a different value", untouched, edited)
        assertEquals("plank", untouched.days.last().plannedExerciseIds.single())
        assertEquals(2, untouched.days.size)
    }

    @Test
    fun removingADayRenumbersWhatIsLeft() {
        val plan = plan()
        val ids = TestIds()

        val trimmed = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .removingDay(plan.first().programDayId)
            .draft

        assertEquals(1, trimmed.days.size)
        assertEquals("the remaining day is the last one, renumbered", plan.last().copy(position = 1), trimmed.days.single())
        assertEquals(1, trimmed.days.single().position)
    }

    @Test
    fun movingADayReordersThePlanAndRenumbersIt() {
        val plan = plan()
        val ids = TestIds()

        val reordered = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .movingDay(plan.last().programDayId, toPosition = 1)
            .draft

        assertEquals(
            "the moved day is first, and the positions follow the order",
            listOf(plan.last().programDayId, plan.first().programDayId),
            reordered.days.map { it.programDayId }
        )
        assertEquals(listOf(1, 2), reordered.days.map { it.position })
    }

    @Test
    fun movingAnElementReordersItsDayOnly() {
        val plan = planWithTwoOccurrences()
        val ids = TestIds()
        val day = plan.first()
        val second = day.exercises[1]

        val reordered = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .movingExercise(day.programDayId, second.programExerciseId, toPosition = 1)
            .draft

        assertEquals(
            "the plan order is the user's (§2)",
            listOf(second.programExerciseId, day.exercises[0].programExerciseId),
            reordered.days.first().exercises.map { it.programExerciseId }
        )
        assertEquals("the rest day is untouched", plan.last(), reordered.days.last())
    }

    // ------------------------------------------------------------------ repeats and prescriptions

    @Test
    fun oneOccurrenceCanBeRemovedWithoutTouchingItsTwin() {
        val plan = planWithTwoOccurrences()
        val day = plan.first()
        val ids = TestIds()

        val trimmed = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .removingExercise(day.programDayId, day.exercises[0].programExerciseId)
            .draft

        assertEquals(
            "the same exercise used twice is two elements, and one of them is what was removed (§9)",
            1,
            trimmed.days.first().exercises.size
        )
        assertEquals(
            day.exercises[1].programExerciseId,
            trimmed.days.first().exercises.single().programExerciseId
        )
    }

    @Test
    fun duplicatingAnExerciseRepeatsItAsItsOwnOccurrence() {
        val plan = planWithTwoOccurrences()
        val day = plan.first()
        val original = day.exercises[0]
        val ids = TestIds()

        val duplicated = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .duplicatingExercise(day.programDayId, original.programExerciseId)
            .draft

        val elements = duplicated.days.first().exercises
        assertEquals("a copy sits directly after its original", 3, elements.size)
        assertEquals(original.programExerciseId, elements[0].programExerciseId)
        assertEquals(
            "with the same exercise, prescription and pin — and an identity of its own",
            original.copy(programExerciseId = elements[1].programExerciseId),
            elements[1]
        )
        assertNotEquals(elements[0].programExerciseId, elements[1].programExerciseId)
    }

    @Test
    fun changingOneOccurrencesPrescriptionLeavesTheOtherAlone() {
        val plan = planWithTwoOccurrences()
        val day = plan.first()
        val first = day.exercises[0]
        val second = day.exercises[1]
        val ids = TestIds()

        val retargeted = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .settingPrescription(day.programDayId, first.programExerciseId, RepPrescription(listOf(3)))
            .draft

        assertEquals(
            "the two occurrences are separate plan elements (§9)",
            RepPrescription(listOf(3)),
            retargeted.days.first().exercises[0].prescription
        )
        assertEquals(
            second.prescription,
            retargeted.days.first().exercises[1].prescription
        )
    }

    @Test
    fun pinningIsAPlanEditAndUnpinningDoesNotChangeTheValue() {
        val plan = plan()
        val day = plan.first()
        val element = day.exercises[0]
        val ids = TestIds()

        val pinned = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .settingPinned(day.programDayId, element.programExerciseId, isPinned = true)
            .draft
        val unpinned = editor(pinned, ids)
            .settingPinned(day.programDayId, element.programExerciseId, isPinned = false)
            .draft

        assertTrue(pinned.days.first().exercises[0].isPinned)
        assertFalse(
            "unpinning keeps the value and only makes the element eligible again (§7)",
            unpinned.days.first().exercises[0].isPinned
        )
        assertEquals(
            "the prescription survives both",
            element.prescription,
            unpinned.days.first().exercises[0].prescription
        )
    }

    // ------------------------------------------------------------------ the rest-day rule

    @Test
    fun aRestDayCannotBeMadeOutOfADayThatStillPlansWork() {
        val plan = planWithTwoOccurrences()
        val day = plan.first()
        val ids = TestIds()
        val before = ProgramEditorDraft(name = "My program", days = plan)

        val refused = try {
            editor(before, ids).retypingDay(day.programDayId, ProgramDayType.REST)
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertTrue(
            "a REST day prescribes nothing, and the editor will not drop the user's elements to make " +
                "that true (§20, §33)",
            refused != null
        )
        assertTrue(
            "the refusal says what has to happen first: ${refused?.message}",
            refused?.message?.contains("remove them first") == true
        )
        assertEquals("and the draft it was given still holds both elements", 2, before.days.first().exercises.size)

        val emptied = editor(before, ids)
            .removingExercise(day.programDayId, day.exercises[0].programExerciseId)
            .removingExercise(day.programDayId, day.exercises[1].programExerciseId)
            .retypingDay(day.programDayId, ProgramDayType.REST)
            .draft
        assertEquals(ProgramDayType.REST, emptied.days.first().type)
        assertTrue(emptied.days.first().exercises.isEmpty())
    }

    @Test
    fun aRestDayMayNotBeGivenAnExerciseInTheFirstPlace() {
        val ids = TestIds()
        val editing = editor(ProgramEditorDraft(name = "My program"), ids)
        val withRest = editing.addingDay(type = ProgramDayType.REST).draft

        val refused = try {
            editor(withRest, ids).addingExercise(
                programDayId = withRest.days.single().programDayId,
                exerciseId = "pushup",
                prescription = RepPrescription(listOf(5))
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertTrue(
            "the domain refuses the state before the editor can produce it: a REST day prescribes " +
                "no exercises (§20)",
            refused != null
        )
    }

    // ------------------------------------------------------------------ the Program's own facts

    @Test
    fun theProgramsOwnFactsAreEditedWithoutTouchingThePlan() {
        val plan = plan()
        val ids = TestIds()

        val facts = editor(ProgramEditorDraft(name = "My program", days = plan), ids)
            .renamed("Evening strength")
            .described("Three short sessions a week")
            .withMode(ProgramMode.GENERATED)
            .withDuration(ProgramDuration.FixedDays(56))
            .withSchedule(ProgramSchedule.FlexiblePerWeek(4))
            .draft

        assertEquals("Evening strength", facts.name)
        assertEquals("Three short sessions a week", facts.description)
        assertEquals(ProgramMode.GENERATED, facts.mode)
        assertEquals(ProgramDuration.FixedDays(56), facts.duration)
        assertEquals(ProgramSchedule.FlexiblePerWeek(4), facts.schedule)
        assertEquals("and the plan is exactly what it was", plan, facts.days)
    }

    // ------------------------------------------------------------------ loud failures

    @Test
    fun addressingADayOrElementTheDraftDoesNotHoldFailsLoudly() {
        val ids = TestIds()
        val draft = ProgramEditorDraft(name = "My program", days = plan())

        val missingDay = failureOf {
            editor(draft, ids).removingDay(ProgramDayId("day-ghost"))
        }
        assertTrue(
            "a draft cannot remove a day it does not hold: ${missingDay.message}",
            missingDay.message?.contains("day-ghost") == true
        )

        val missingElement = failureOf {
            editor(draft, ids).removingExercise(
                programDayId = draft.days.first().programDayId,
                programExerciseId = ProgramExerciseId("element-ghost")
            )
        }
        assertTrue(
            "nor an element it does not hold: ${missingElement.message}",
            missingElement.message?.contains("element-ghost") == true
        )
    }

    @Test
    fun insertingOutsideThePlanFailsLoudlyInsteadOfBeingClamped() {
        val ids = TestIds()
        val draft = ProgramEditorDraft(name = "My program", days = plan())

        val tooFar = failureOf { editor(draft, ids).addingDay(ProgramDayType.REST, at = 4) }
        assertTrue(
            "one past the last day is an insert, further is a bug: ${tooFar.message}",
            tooFar.message?.contains("4") == true
        )

        val appendedAtTheEnd = editor(draft, ids).addingDay(ProgramDayType.REST, at = 3).draft
        assertEquals("appending is expressed as position 3 of 2", 3, appendedAtTheEnd.days.size)
    }

    // ------------------------------------------------------------------ helpers

    private class TestIds(private val tag: String = "draft") : DraftIdSource {

        var count: Int = 0
            private set

        override fun newId(): String {
            count += 1
            return "$tag-$count"
        }
    }

    private fun editor(draft: ProgramEditorDraft, ids: DraftIdSource) = ProgramDraftEditor(draft, ids)

    private fun plan(): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId("day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Push day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("element-1"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(12, 10, 8, 6)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-2"),
            position = 2,
            type = ProgramDayType.TRAINING,
            name = "Plank day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("element-2"),
                    exerciseId = "plank",
                    prescription = TimePrescription(listOf(30, 30, 45)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        )
    )

    private fun planWithTwoOccurrences(): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId("day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Push day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("element-1"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(12, 10, 8, 6)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED,
                    isPinned = true
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("element-2"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(5)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-2"),
            position = 2,
            type = ProgramDayType.REST
        )
    )

    private fun failureOf(block: () -> Unit): IllegalArgumentException = try {
        block()
        throw AssertionError("expected the editor to refuse this operation, and it did not")
    } catch (expected: IllegalArgumentException) {
        expected
    }
}
