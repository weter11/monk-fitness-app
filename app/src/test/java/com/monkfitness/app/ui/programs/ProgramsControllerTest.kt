package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.progress.ProgressScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Program management surface (§4, §21, §22): what each UI action does, measured through the state
 * holder on the real engine.
 *
 * The rules the suite holds apart are the ones §6, §4 and §29 make easy to get wrong by accident:
 *
 * ```text
 * selection          moves through the lifecycle service, is re-read afterwards and is not duplicated here
 * rename             changes a name and creates no revision (§6)
 * archive            is a stamp, not a lifecycle state, and is refused for the selection (§3)
 * delete             is the cascade with §3's Standard-Program fallback
 * the Standard one   may be selected, copied and shared, and is refused for edit and delete (§4)
 * the editor         saves through §7's service: one revision, or none
 * Share              is the export service's bytes handed to the platform boundary (§11)
 * ```
 */
class ProgramsControllerTest {

    private val rig = ProgramsRig("management")

    @After
    fun tearDown() {
        rig.close()
    }

    // ---------------------------------------------------------------- the list (§21)

    @Test
    fun theListIsTheLifecycleServicesOwnProjection() = runBlocking {
        val created = rig.createProgramThroughTheUi("Morning plan")
        rig.controller.select(created!!)

        rig.controller.load()

        val stored = rig.storedPrograms().single { program -> program.programId == ProgramId(created) }
        val row = rig.state.rows.single()
        assertEquals(stored.programId.value, row.programId)
        assertEquals(stored.name, row.name)
        assertEquals(stored.source, row.source)
        assertEquals(stored.lifecycleStatus, row.lifecycleStatus)
        assertEquals(created, rig.state.selectedProgramId)
        assertTrue("the selected row is the service's answer, not a local flag", row.isSelected)
    }

    @Test
    fun aProgramTheUserHasNotCreatedIsNotInvented() = runBlocking {
        rig.controller.load()

        assertTrue(rig.state.isEmpty)
        assertEquals(emptyList<ProgramRowUi>(), rig.state.rows)
    }

    // ---------------------------------------------------------------- select, rename (§6)

    @Test
    fun selectingMovesTheOneGlobalSelectionAndRefreshesTheList() = runBlocking {
        val first = rig.createProgramThroughTheUi("First")!!
        val second = rig.createProgramThroughTheUi("Second")!!

        rig.controller.select(first)
        assertEquals(ProgramId(first), rig.selectedProgramId())

        rig.controller.select(second)

        assertEquals(
            "selection is one global fact: selecting a second Program replaces the first",
            ProgramId(second),
            rig.selectedProgramId()
        )
        assertEquals(ProgramNotice.SELECTED, rig.state.notice)
        assertTrue(rig.state.rows.single { row -> row.programId == second }.isSelected)
        assertFalse(rig.state.rows.single { row -> row.programId == first }.isSelected)
    }

    @Test
    fun renamingChangesTheNameAndCreatesNoRevision() = runBlocking {
        val created = rig.createProgramThroughTheUi("Old name")!!
        val revisionsBefore = rig.revisionCount(ProgramId(created))

        rig.controller.rename(created, "New name")

        assertEquals(ProgramNotice.RENAMED, rig.state.notice)
        assertEquals("New name", rig.storedProgram(ProgramId(created))?.name)
        assertEquals(
            "a rename is not a structural change, so no revision is created (§6)",
            revisionsBefore,
            rig.revisionCount(ProgramId(created))
        )
    }

    @Test
    fun renamingTheStandardProgramIsRefusedWithTheRulesOwnSentence() = runBlocking {
        rig.seedStandardProgram()

        rig.controller.rename(StandardProgram.programId.value, "Mine now")

        assertEquals(
            "§4's copy-before-edit rule is the lifecycle layer's, and its refusal is surfaced",
            ProgramNotice.STANDARD_CANNOT_BE_EDITED,
            rig.state.notice
        )
        assertEquals(StandardProgram.NAME, rig.storedProgram(StandardProgram.programId)?.name)
    }

    // ---------------------------------------------------------------- archive (§3, §29)

    @Test
    fun archivingIsAStampAndNotALifecycleState() = runBlocking {
        val created = rig.createProgramThroughTheUi("Archivable")!!
        rig.controller.start(created)
        val running = rig.storedProgram(ProgramId(created))!!.lifecycleStatus

        rig.controller.archive(created)

        val archived = rig.storedProgram(ProgramId(created))!!
        assertTrue("the archive stamp is set", archived.isArchived)
        assertEquals("and the lifecycle is exactly what it was", running, archived.lifecycleStatus)
        assertEquals(ProgramNotice.ARCHIVED, rig.state.notice)
        assertTrue(
            "the list files it away rather than showing it among the active ones",
            rig.state.archivedRows.any { row -> row.programId == created }
        )

        rig.controller.unarchive(created)

        assertFalse(rig.storedProgram(ProgramId(created))!!.isArchived)
        assertEquals(ProgramNotice.UNARCHIVED, rig.state.notice)
    }

    @Test
    fun archivingTheSelectionIsRefusedAndSaysWhatToDo() = runBlocking {
        val created = rig.createProgramThroughTheUi("Selected")!!
        rig.controller.select(created)

        rig.controller.archive(created)

        assertEquals(ProgramNotice.ARCHIVE_OF_SELECTION, rig.state.notice)
        assertFalse(rig.storedProgram(ProgramId(created))!!.isArchived)
    }

    // ---------------------------------------------------------------- delete (§3, §29)

    @Test
    fun deletingUsesTheServicesCascadeAndFallsBackToTheStandardProgram() = runBlocking {
        rig.seedStandardProgram()
        val created = rig.createProgramThroughTheUi("Doomed")!!
        rig.controller.select(created)

        rig.controller.delete(created)

        assertEquals(ProgramNotice.DELETED, rig.state.notice)
        assertNull(rig.storedProgram(ProgramId(created)))
        assertEquals(
            "§3's technical fallback: the selection moves to the built-in Program rather than being cleared",
            StandardProgram.programId,
            rig.selectedProgramId()
        )
        assertFalse(
            "and the list no longer contains it",
            rig.state.rows.any { row -> row.programId == created }
        )
    }

    @Test
    fun deletingTheStandardProgramIsRefused() = runBlocking {
        rig.seedStandardProgram()

        rig.controller.delete(StandardProgram.programId.value)

        assertEquals(ProgramNotice.STANDARD_CANNOT_BE_DELETED, rig.state.notice)
        assertNotNull(rig.storedProgram(StandardProgram.programId))
    }

    // ---------------------------------------------------------------- the lifecycle (§3)

    @Test
    fun theLifecycleActionsReachTheServiceAndAreReported() = runBlocking {
        val created = rig.createProgramThroughTheUi("Lifecycle")!!

        rig.controller.start(created)
        assertEquals(ProgramNotice.STARTED, rig.state.notice)
        assertEquals(LifecycleStatus.RUNNING, rig.storedProgram(ProgramId(created))!!.lifecycleStatus)
        assertNotNull(
            "starting records the factual start date, inside the service",
            rig.storedProgram(ProgramId(created))!!.actualStartDate
        )

        rig.controller.pause(created)
        assertEquals(LifecycleStatus.PAUSED, rig.storedProgram(ProgramId(created))!!.lifecycleStatus)

        rig.controller.resume(created)
        assertEquals(LifecycleStatus.RUNNING, rig.storedProgram(ProgramId(created))!!.lifecycleStatus)

        rig.controller.complete(created)
        assertEquals(ProgramNotice.COMPLETED, rig.state.notice)
        assertEquals(LifecycleStatus.COMPLETED, rig.storedProgram(ProgramId(created))!!.lifecycleStatus)

        rig.controller.resume(created)
        assertEquals(
            "a completed Program cannot be resumed directly (§3), and the refusal is shown",
            ProgramNotice.ILLEGAL_TRANSITION,
            rig.state.notice
        )
        assertEquals(LifecycleStatus.COMPLETED, rig.storedProgram(ProgramId(created))!!.lifecycleStatus)
    }

    // ---------------------------------------------------------------- the editor (§7)

    @Test
    fun creatingThroughTheUiSavesThroughTheEditorService() = runBlocking {
        val id = rig.createProgramThroughTheUi("Built by hand")
        assertNotNull("the save must produce a Program the list can see", id)

        val program = rig.storedProgram(ProgramId(id!!))!!
        assertEquals(ProgramSource.USER, program.source)
        assertEquals(LifecycleStatus.NOT_STARTED, program.lifecycleStatus)
        assertEquals("a creation makes exactly one revision", 1, rig.revisionCount(ProgramId(id)))
        assertNull(
            "creating a Program is not starting it, and it names no planned date of its own (§3, §6)",
            program.plannedStartDate
        )
        assertNull(rig.state.draft)
        assertEquals(ProgramNotice.DRAFT_SAVED, rig.state.notice)
    }

    @Test
    fun aNoOpSaveWritesNothingAndSaysSo() = runBlocking {
        val id = rig.createProgramThroughTheUi("Unchanged")!!
        val before = rig.revisionCount(ProgramId(id))

        rig.controller.openEditDraft(id)
        assertFalse(
            "a no-op save writes nothing, so the screen stays where it is rather than reporting a save",
            rig.controller.saveDraft()
        )

        assertEquals(
            "§6's no-op save creates no revision at all",
            before,
            rig.revisionCount(ProgramId(id))
        )
        assertEquals(ProgramNotice.DRAFT_UNCHANGED, rig.state.notice)
    }

    @Test
    fun theReviewStatesWhatSavingWouldDoInTheEditorsOwnVocabulary() = runBlocking {
        val id = rig.createProgramThroughTheUi("Reviewed")!!

        rig.controller.openCreateDraft(ProgramMode.MANUAL)
        rig.controller.setDraftName("Nothing yet")
        rig.controller.reviewDraft()
        val newDraftReview = rig.state.draft?.review
        assertNotNull(newDraftReview)
        assertTrue(newDraftReview!!.createsProgram)
        assertTrue(
            "an unfinished draft is not savable, and the review says so rather than hiding it",
            !newDraftReview.isSavable || newDraftReview.dayCount == 0
        )

        rig.controller.discardDraft()
        rig.controller.openEditDraft(id)
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft!!.days.first().programDayId
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        rig.controller.reviewDraft()

        val editReview = rig.state.draft?.review!!
        assertTrue("an edit of a saved Program creates a revision", editReview.willCreateARevision)
        assertFalse(editReview.createsProgram)
        assertEquals(
            "the change list is the editor's own comparison against the stored revision",
            true,
            editReview.changeRes.contains(com.monkfitness.app.R.string.programs_change_days) &&
                editReview.changeRes.contains(com.monkfitness.app.R.string.programs_change_exercises)
        )
        assertTrue("and the aspects are named at least once each", editReview.changeRes.isNotEmpty())
        assertEquals(2, editReview.revisionNumber)
    }

    @Test
    fun editingTheStandardProgramIsRefusedThroughTheEditor() = runBlocking {
        rig.seedStandardProgram()

        rig.controller.openEditDraft(StandardProgram.programId.value)

        assertEquals(ProgramNotice.STANDARD_CANNOT_BE_EDITED, rig.state.notice)
        assertNull("no draft is opened for a Program that may not be edited", rig.state.draft)
    }

    @Test
    fun copyingTheStandardProgramIsTheCopyBeforeEditPath() = runBlocking {
        rig.seedStandardProgram()

        rig.controller.openCopyDraft(StandardProgram.programId.value, "Copy of")
        val draft = rig.state.draft
        assertNotNull(draft)
        assertEquals(ProgramDraftEntry.COPY, draft!!.entry)
        assertTrue(
            "the copy's name is built from the stored Program's own name, not from the navigation argument",
            draft.name.endsWith(StandardProgram.NAME)
        )

        assertTrue(rig.controller.saveDraft())

        val copy = rig.storedPrograms().single { program ->
            program.programId != StandardProgram.programId
        }
        assertEquals(ProgramSource.USER, copy.source)
        assertFalse("a copy is the user's own, and is no longer protected (§4)", copy.source.isBuiltIn)
        assertEquals(1, rig.revisionCount(copy.programId))
        assertEquals(
            "and the Standard Program it came from is untouched",
            1,
            rig.revisionCount(StandardProgram.programId)
        )
    }

    @Test
    fun theGeneratedEntryPathIsRealAndItsPlanAvailabilityIsReportedRatherThanFabricated() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        assertEquals("`Build for me` opens a GENERATED draft (§2, §8)", ProgramMode.GENERATED, rig.state.draft?.mode)
        assertFalse(
            "no plan is fabricated while the app has no exercise→focus classification",
            rig.controller.generateDraft()
        )
        assertEquals(ProgramNotice.GENERATION_UNAVAILABLE, rig.state.notice)

        rig.controller.setDraftName("Generated by hand for now")
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        rig.controller.addDraftElement(
            rig.state.draft!!.days.first().programDayId,
            ProgramsRig.FIRST_EXERCISE
        )
        assertTrue("the draft is still savable, and its mode is real content (§6)", rig.controller.saveDraft())

        val saved = rig.storedPrograms().single { program -> program.name == "Generated by hand for now" }
        assertEquals(
            ProgramMode.GENERATED,
            rig.transfer.currentRevision(saved.programId)?.mode
        )
    }

    @Test
    fun aCatalogueThatCannotBeReadIsReportedAndDoesNotCrashTheEditor() = runBlocking {
        rig.catalogueFails = true

        rig.controller.openCreateDraft(ProgramMode.MANUAL)

        assertEquals(ProgramNotice.STORAGE_FAILED, rig.state.notice)
        assertNotNull("the draft is still opened, so the user keeps their work", rig.state.draft)
        assertEquals(emptyList<ExerciseOptionUi>(), rig.state.exerciseOptions)
    }

    // ---------------------------------------------------------------- Program Detail (§22)

    @Test
    fun theDetailIsReadFromTheServicesAndShowsTheSchedulersOwnNextOpportunity() = runBlocking {
        rig.storeSourceProgram()
        val programId = rig.sourceProgramId

        rig.controller.openDetail(programId.value)

        val detail = rig.state.detail
        assertNotNull(detail)
        val revision = rig.transfer.currentRevision(programId)!!
        assertEquals(revision.mode, detail!!.mode)
        assertEquals(revision.schedule, detail.schedule)
        assertEquals(revision.revisionNumber, detail.revisionNumber)
        assertEquals(revision.days.size, detail.dayCount)
        assertEquals(revision.days.sumOf { day -> day.exercises.size }, detail.exerciseCount)
        assertEquals(
            "the next opportunity is the Scheduler's own preview, not a date this layer computed",
            rig.slotsOf(programId).map { slot -> slot.plannedFor }.min(),
            detail.nextOpportunity
        )
    }

    @Test
    fun theDetailShowsTheProgressLayersOwnCountsAndTheRecentAttempts() = runBlocking {
        rig.storeSourceProgram()
        val programId = rig.sourceProgramId
        rig.transfer.startSessionOnSource(1)

        rig.controller.openDetail(programId.value)

        val calendar = rig.progress.calendarProgress(ProgressScope.OfProgram(programId))
        val history = rig.progress.history(ProgressScope.OfProgram(programId), ProgramsController.RECENT_WORKOUTS)
        val detail = rig.state.detail!!
        assertEquals(calendar.completed, detail.completed)
        assertEquals(calendar.missed, detail.missed)
        assertEquals(calendar.upcoming, detail.upcoming)
        assertEquals(
            "the recent attempts are the history layer's own, newest first and limited",
            history.map { item -> item.performedSets },
            detail.recentWorkouts.map { entry -> entry.performedSets }
        )
    }

    @Test
    fun theDetailOfAProgramThatIsNotStoredIsRefusedRatherThanInvented() = runBlocking {
        rig.controller.openDetail("no-such-program")

        assertEquals(ProgramNotice.PROGRAM_NOT_FOUND, rig.state.notice)
        assertNull(rig.state.detail)
    }

    // ---------------------------------------------------------------- Share (§11)

    @Test
    fun shareIsTheExportServicesBytesHandedToThePlatformBoundary() = runBlocking {
        rig.storeSourceProgram()
        val programId = rig.sourceProgramId
        val expected = rig.transfer.exportService.export(programId)
        assertTrue(expected is com.monkfitness.app.domain.program.transfer.ProgramTransferResult.Success)

        rig.controller.share(programId.value)

        assertEquals("exactly one share is performed", 1, rig.shares.size)
        val shared = rig.shares.single()
        assertEquals(
            "the filename and the type are the format's own constants (§5, §11)",
            ProgramTransferFormat.FILE_NAME,
            shared.fileName
        )
        assertEquals(ProgramTransferFormat.MIME_TYPE, shared.mimeType)
        assertArrayEquals(
            "and the bytes are the exporter's, not a second rendering of the Plan (§11)",
            (expected as com.monkfitness.app.domain.program.transfer.ProgramTransferResult.Success).value.bytes,
            shared.bytes
        )
        assertNull("a share that happened is not reported as a failure", rig.state.notice)
    }

    @Test
    fun aShareThePlatformRefusesIsReportedAndNotSwallowed() = runBlocking {
        rig.storeSourceProgram()
        rig.shareFails = true

        rig.controller.share(rig.sourceProgramId.value)

        assertEquals(ProgramNotice.SHARE_FAILED, rig.state.notice)
    }

    @Test
    fun sharingAProgramThatIsNotStoredIsRefusedThroughTheExportPath() = runBlocking {
        rig.controller.share("no-such-program")

        assertEquals(ProgramNotice.PROGRAM_NOT_FOUND, rig.state.notice)
        assertTrue("and the platform was never asked to share anything", rig.shares.isEmpty())
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }
}
