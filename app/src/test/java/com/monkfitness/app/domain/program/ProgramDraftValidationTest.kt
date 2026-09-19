package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.RepPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * §7's `validate` step, rule by rule.
 *
 * Every rule is stated twice: a draft that breaks it is refused with the issue that names it, and a
 * draft that does not is saved-able. The second half matters as much as the first — a validator that
 * refuses everything protects nothing, and the rules here are deliberately few because a manual
 * program is free-form (§2, §10) and the only thing standing between the editor and a bad revision is
 * what a revision can actually hold.
 */
class ProgramDraftValidationTest {

    // ------------------------------------------------------------------ the three entry points

    @Test
    fun aFinishedDraftOfEachEntryPointIsValid() {
        val create = draft(name = "My program", days = listOf(trainingDay(), restDay()))
        assertTrue("create: a named plan with work in it", create.validation().isValid)

        val copy = create.copy(baseRevisionId = RevisionId("revision-1"))
        assertTrue("copy: the same plan, opened from another revision", copy.validation().isValid)

        val edit = create.copy(
            programId = ProgramId("program-1"),
            baseRevisionId = RevisionId("revision-1")
        )
        assertTrue("edit: an existing Program, opened from its current revision", edit.validation().isValid)

        assertTrue(
            "and a valid draft carries no findings at all",
            ProgramDraftValidation.VALID.issues.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the rules

    @Test
    fun aProgramNeedsAName() {
        val blank = draft(name = "", days = listOf(trainingDay()))
        assertEquals(
            "an unnamed Program is a draft, not an error — until it is saved",
            listOf(ProgramDraftIssue.BlankName),
            blank.validation().issues
        )

        val whitespace = draft(name = "   ", days = listOf(trainingDay()))
        assertTrue(
            "a name made of spaces does not name a Program (§1)",
            whitespace.validation().issues.contains(ProgramDraftIssue.BlankName)
        )
        assertFalse(blank.validation().isValid)
        assertTrue(ProgramDraftIssue.BlankName.message.isNotBlank())
    }

    @Test
    fun aSavedRevisionCarriesAPlanSoADraftWithoutOneIsNotSavable() {
        val noPlan = draft(name = "My program", days = emptyList())

        assertEquals(listOf(ProgramDraftIssue.NoPlan), noPlan.validation().issues)
        assertEquals(
            "the same emptiness is what a revision refuses at construction (§6)",
            "a Program needs at least one day before it can be saved",
            ProgramDraftIssue.NoPlan.message
        )
    }

    @Test
    fun everyDayOfAPlanHasItsOwnIdentity() {
        val shared = ProgramDayId("day-1")
        val duplicated = draft(
            name = "My program",
            days = listOf(
                trainingDay(dayId = shared),
                trainingDay(dayId = shared, position = 2)
            )
        )

        assertEquals(
            listOf(ProgramDraftIssue.DuplicateDayIdentity(shared)),
            duplicated.validation().issues
        )
    }

    @Test
    fun theDaysAreNumberedInOrder() {
        val outOfOrder = draft(
            name = "My program",
            days = listOf(
                trainingDay(position = 2, dayId = ProgramDayId("day-2")),
                restDay(position = 5, dayId = ProgramDayId("day-5"))
            )
        )

        assertEquals(
            "a position that skips is not an ordering (§6)",
            listOf(ProgramDraftIssue.DaysOutOfOrder(listOf(2, 5))),
            outOfOrder.validation().issues
        )
    }

    @Test
    fun aDayThatIsNotARestDayPrescribesWork() {
        ProgramDayType.entries.filter { it != ProgramDayType.REST }.forEach { type ->
            val empty = draft(
                name = "My program",
                days = listOf(trainingDay(type = type, elements = emptyList()))
            )
            assertEquals(
                "a $type day with nothing in it is an unfinished draft (§20)",
                listOf(
                    ProgramDraftIssue.DayWithoutWork(
                        programDayId = ProgramDayId("day-1"),
                        position = 1,
                        type = type
                    )
                ),
                empty.validation().issues
            )
        }

        val rest = draft(name = "My program", days = listOf(restDay(position = 1)))
        assertTrue(
            "and a REST day is the one type that prescribes nothing, so it is valid as it is (§20)",
            rest.validation().isValid
        )
    }

    @Test
    fun aDraftThatEditsAProgramMustNameTheRevisionItCameFrom() {
        val orphan = draft(name = "My program", days = listOf(trainingDay()))
            .copy(programId = ProgramId("program-1"))

        assertEquals(
            listOf(ProgramDraftIssue.ProgramWithoutBaseRevision(ProgramId("program-1"))),
            orphan.validation().issues
        )

        val copy = draft(name = "My program", days = listOf(trainingDay()))
            .copy(baseRevisionId = RevisionId("revision-1"))
        assertTrue(
            "a draft that names a revision but no Program is a copy, and that is an entry point of " +
                "its own (§7)",
            copy.validation().isValid
        )
    }

    // ------------------------------------------------------------------ findings, never repairs

    @Test
    fun theValidationCarriesFindingsAndIsStructurallyIncapableOfCarryingARepair() {
        val fields = ProgramDraftValidation::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") || Modifier.isStatic(it.modifiers) }

        assertEquals(
            "the only thing a validation holds is what is wrong with the draft: there is no repaired " +
                "plan, no re-enabled exercise and no corrected prescription (§33)",
            listOf("issues"),
            fields.map { it.name }
        )
        assertEquals(
            "and the findings are a read-only list",
            java.util.List::class.java,
            fields.single().type
        )
    }

    @Test
    fun everyFindingExplainsItselfAndAValidationIsDeterministic() {
        val broken = draft(name = "", days = listOf(trainingDay(dayId = ProgramDayId("day-1"), position = 3)))
            .copy(programId = ProgramId("program-1"))

        val issues = broken.validation().issues
        assertTrue("the draft breaks several rules at once", issues.size >= 3)
        issues.forEach { issue ->
            assertTrue("'${issue::class.simpleName}' explains itself", issue.message.isNotBlank())
        }
        assertEquals("two validations of one draft agree", issues, broken.validation().issues)
        assertEquals("and repeated findings are reported once", issues.distinct(), issues)
    }

    // ------------------------------------------------------------------ helpers

    private fun draft(name: String, days: List<ProgramDay>) =
        ProgramEditorDraft(name = name, days = days)

    private fun trainingDay(
        position: Int = 1,
        dayId: ProgramDayId = ProgramDayId("day-1"),
        type: ProgramDayType = ProgramDayType.TRAINING,
        elements: List<ProgramExercise> = listOf(
            ProgramExercise(
                programExerciseId = ProgramExerciseId("element-1"),
                exerciseId = "pushup",
                prescription = RepPrescription(listOf(10, 8)),
                origin = ProgramExerciseOrigin.USER_AUTHORED
            )
        )
    ): ProgramDay = ProgramDay(
        programDayId = dayId,
        position = position,
        type = type,
        name = "Day $position",
        exercises = elements
    )

    private fun restDay(
        position: Int = 2,
        dayId: ProgramDayId = ProgramDayId("day-rest")
    ): ProgramDay = ProgramDay(
        programDayId = dayId,
        position = position,
        type = ProgramDayType.REST
    )
}
