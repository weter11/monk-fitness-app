package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 14's product decision: **the imported Program's planned start date is the user's choice**, made
 * in the review step and carried by the import request into the one transaction that creates the Program.
 *
 * The rules this suite measures, each through the Program UI's own state holder:
 *
 * ```text
 * default            today, in the composition root's calendar
 * user's choice      held until the confirmation, and it does not touch the reviewed draft
 * the Program        `plannedStartDate` is the chosen date
 * the schedule       the initial opportunities are anchored to the chosen date, not to the import day
 * selection          unchanged when the checkbox is OFF, moved through the lifecycle service when ON
 * atomicity          a failed import leaves no Program, no revision, no opportunity and no selection
 * the format         carries no date: nothing about the choice is serialized
 * ```
 *
 * Every claim is measured on the real engine through the production services (§27's unit is the
 * database's own), so *"the date choice affects the initial schedule created by Import"* is a statement
 * about rows rather than about a parameter being passed.
 */
class ProgramImportDateTest {

    private val rig = ProgramsRig("import-date")

    /** The imported Program's own name, from the fixture's document. */
    private val importedName = "Imported strength"

    /** A Wednesday, so it is a training weekday of the fixture's Monday/Wednesday/Friday schedule. */
    private val chosenDate: LocalDate = LocalDate.parse("2026-10-07")

    @After
    fun tearDown() {
        rig.close()
    }

    // ---------------------------------------------------------------- the review step

    @Test
    fun theReviewDefaultsToTodayAndLeavesTheCheckboxOff() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)

        val review = rig.state.importReview
        assertNotNull("the review must be offered for a valid document", review)
        assertEquals(
            "the default planned start date is today, in the composition root's calendar",
            ProgramTransferFixture.IMPORTED_ON,
            review!!.plannedStartDate
        )
        assertFalse("§5's checkbox defaults OFF", review.makeActive)
        assertEquals(
            "and the review states what the file describes",
            importedName,
            review.name
        )
    }

    @Test
    fun reviewingAFileWritesNothingAtAll() = runBlocking {
        val before = rig.tableCounts()

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "a review is a read: an import that is never confirmed leaves no trace anywhere (§5)",
            before,
            rig.tableCounts()
        )
        assertTrue("and the list is not refreshed by a review", rig.state.rows.isEmpty())
    }

    @Test
    fun theChosenDateIsHeldUntilTheConfirmationAndDoesNotTouchTheDraft() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        val reviewed = rig.state.importDraft

        rig.controller.setImportStartDate(chosenDate)
        rig.controller.setImportMakeActive(true)

        assertEquals(chosenDate, rig.state.importReview?.plannedStartDate)
        assertTrue(rig.state.importReview?.makeActive == true)
        assertSame(
            "the date is the UI's own state until the confirmation; the reviewed draft is the pipeline's " +
                "and the choice is not written into it",
            reviewed,
            rig.state.importDraft
        )
    }

    @Test
    fun discardingTheReviewImportsNothing() = runBlocking {
        val before = rig.tableCounts()

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        rig.controller.discardImport()

        assertNull(rig.state.importReview)
        assertNull(rig.state.importDraft)
        assertEquals(before, rig.tableCounts())
    }

    // ---------------------------------------------------------------- the Program it creates

    @Test
    fun theImportedProgramIsPlannedToStartOnTheChosenDate() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)

        assertTrue(rig.controller.confirmImport())

        val imported = importedProgram()
        assertEquals(
            "the chosen date is the Program's plannedStartDate — not the day it was imported on",
            chosenDate,
            imported.plannedStartDate
        )
        assertNotEquals(ProgramTransferFixture.IMPORTED_ON, imported.plannedStartDate)
        assertNull(
            "and it is a plan, not a start: nothing about the import begins the Program (§3)",
            imported.actualStartDate
        )
    }

    @Test
    fun theDefaultImportIsStillPlannedToStartOnTheDayItArrived() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)

        assertTrue(rig.controller.confirmImport())

        assertEquals(
            "§30 step 13's own default is preserved when the user changes nothing",
            ProgramTransferFixture.IMPORTED_ON,
            importedProgram().plannedStartDate
        )
    }

    @Test
    fun theInitialScheduleIsAnchoredToTheChosenDate() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)

        assertTrue(rig.controller.confirmImport())

        val program = importedProgram()
        val slots = rig.slotsOf(program.programId)
        assertTrue("the import creates the Scheduler's own opportunities (§27)", slots.isNotEmpty())
        assertEquals(
            "§27's creation unit includes the initial slots, and they are planned FROM the chosen date: " +
                "importing first and moving the date afterwards would leave them anchored to a date the " +
                "user never chose",
            chosenDate,
            slots.map { slot -> slot.plannedFor }.min()
        )
    }

    @Test
    fun theDefaultImportSchedulesFromTheDayItArrived() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)

        assertTrue(rig.controller.confirmImport())

        val slots = rig.slotsOf(importedProgram().programId)
        assertEquals(
            ProgramTransferFixture.IMPORTED_ON,
            slots.map { slot -> slot.plannedFor }.min()
        )
    }

    // ---------------------------------------------------------------- the checkbox

    @Test
    fun theSelectionIsNotTouchedWhenTheCheckboxIsOff() = runBlocking {
        val existing = rig.createProgramThroughTheUi("Before the import")
        rig.controller.select(existing!!)
        val selected = rig.selectedProgramId()

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        assertTrue(rig.controller.confirmImport())

        assertEquals(
            "OFF means the selection is not touched at all — not cleared, not compared, not rewritten",
            selected,
            rig.selectedProgramId()
        )
        assertEquals("and the imported Program is not the selected one", selected, rig.selectedProgramId())
    }

    @Test
    fun theImportedProgramBecomesSelectedWhenTheCheckboxIsOn() = runBlocking {
        val existing = rig.createProgramThroughTheUi("Before the import")
        rig.controller.select(existing!!)

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        rig.controller.setImportMakeActive(true)
        assertTrue(rig.controller.confirmImport())

        val imported = importedProgram()
        assertEquals(
            "ON moves the selection to the imported Program, through the layer that owns selection",
            imported.programId,
            rig.selectedProgramId()
        )
        assertTrue(
            "and the list says so, because the controller re-read it from the service",
            rig.state.rows.single { row -> row.programId == imported.programId.value }.isSelected
        )
    }

    // ---------------------------------------------------------------- two imports

    @Test
    fun twoImportsOfOneFileAreTwoIndependentProgramsWithTheirOwnDates() = runBlocking {
        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        assertTrue(rig.controller.confirmImport())

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        assertTrue(rig.controller.confirmImport())

        val imported = rig.storedPrograms().filter { program -> program.name == importedName }
        assertEquals("importing the same file twice creates two programs (§5)", 2, imported.size)
        assertEquals(
            "each one keeps the date it was imported with",
            listOf(ProgramTransferFixture.IMPORTED_ON, chosenDate).sorted(),
            imported.mapNotNull { program -> program.plannedStartDate }.sorted()
        )
        assertEquals(
            "and neither reuses the other's identity",
            2,
            imported.map { program -> program.programId.value }.toSet().size
        )
    }

    // ---------------------------------------------------------------- refusals and failures

    @Test
    fun aFileThatIsNotAProgramIsRefusedAndNothingIsImported() = runBlocking {
        val before = rig.tableCounts()

        rig.controller.reviewImport("this is not a program".toByteArray())

        assertEquals(ProgramNotice.NOT_A_PROGRAM_FILE, rig.state.notice)
        assertNull("no review is offered for a refused file", rig.state.importReview)
        assertEquals(before, rig.tableCounts())
    }

    @Test
    fun aDocumentThatNamesAnUnknownExerciseIsRefusedAsInvalidData() = runBlocking {
        rig.controller.reviewImport(
            ProgramTransferFixture.editedBytes("pushups", "an_exercise_this_app_does_not_have")
        )

        assertEquals(
            "§15's *invalid imported data*: the file is readable and describes something this app cannot hold",
            ProgramNotice.UNKNOWN_EXERCISES,
            rig.state.notice
        )
        assertNull(rig.state.importReview)
    }

    @Test
    fun aFailedImportLeavesNoProgramNoRevisonNoSlotAndNoSelection() = runBlocking {
        val existing = rig.createProgramThroughTheUi("Untouched")
        rig.controller.select(existing!!)
        val before = rig.tableCounts()
        val selectedBefore = rig.selectedProgramId()

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        rig.controller.setImportMakeActive(true)
        rig.transfer.faults.failSlotInsert = true
        val imported = try {
            rig.controller.confirmImport()
        } finally {
            rig.transfer.faults.failSlotInsert = false
        }

        assertFalse("a failed import reports failure rather than a completed one", imported)
        assertEquals(
            "and the whole unit rolled back: no Program, no revision, no opportunity (§27)",
            before,
            rig.tableCounts()
        )
        assertEquals(
            "and a failed import selects nothing, even when the choice was on",
            selectedBefore,
            rig.selectedProgramId()
        )
        assertEquals(ProgramNotice.IMPORT_FAILED, rig.state.notice)
        assertNotNull(
            "and the review stays available, so the user can try again",
            rig.state.importReview
        )
    }

    // ---------------------------------------------------------------- the format

    @Test
    fun theTransferFormatCarriesNoDateAndWasNotChangedByThisStage() = runBlocking {
        assertEquals("the format's version is unchanged by this stage", 1, ProgramTransferFormat.VERSION)
        val document = ProgramTransferFixture.VALID_DOCUMENT
        assertFalse(
            "the chosen date is not a field of the transfer document: it is the import request's fact",
            document.contains("plannedStartDate")
        )
        assertFalse(document.contains(chosenDate.toString()))

        rig.controller.reviewImport(ProgramTransferFixture.VALID_BYTES)
        rig.controller.setImportStartDate(chosenDate)
        assertTrue(rig.controller.confirmImport())

        val program = importedProgram()
        assertFalse(
            "and the stored Program's date is not what the file said — the file says nothing about dates",
            ProgramTransferFixture.VALID_DOCUMENT.contains(program.plannedStartDate.toString())
        )
    }

    /** The Program the last import created, read back from storage through a fresh repository. */
    private suspend fun importedProgram() = rig.storedPrograms()
        .singleOrNull { program ->
            program.name == importedName && program.source == ProgramSource.IMPORTED
        }
        ?: error(
            "no imported Program is stored; the stored ones are " +
                rig.storedPrograms().map { program -> program.name + "/" + program.source }
        )
}
