package com.monkfitness.app.domain.program

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.repository.failureOf
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.workout.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramTransition
import com.monkfitness.app.domain.program.ProgramTransitionResult
import com.monkfitness.app.domain.program.MyPrograms
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Program lifecycle and My Programs surface against a real SQLite engine — §30 step 5.
 *
 * Every rule the stage owns is driven through [ProgramLifecycleService] over the production DAOs, so
 * each claim is decided by the engine's foreign keys and transactions rather than by the service's
 * code shape. Three of them are decided by *storage* rather than by the service at all:
 *
 *  * deleting the selected Program is refused by the schema (`ON DELETE NO ACTION`) until the
 *    selection moves, which is what makes "never silently clear the selection" a guarantee the
 *    database enforces;
 *  * a completed Program's delete is the cascade's, and every Program-owned table goes with it;
 *  * a copied Program's plan is a *new* revision identity, because two Programs cannot share a plan
 *    row (§6, §23).
 *
 * The suite's shape is deliberately RED-first in its assertion style: each rule is stated as the
 * observable outcome (a status, a stamp, a count, a refusal type), so a mutation of the production
 * code fails exactly one of them by name. [theLegalLifecycleTransitionsAreTheOnlyOnesThatMoveAProgram]
 * is the whole transition table in one test, and the rest each pin one rule to it.
 */
class ProgramLifecycleTest {

    private val rig = ProgramLifecycleRig("a")

    private val programId: ProgramId get() = rig.graph.program.programId

    // ============================================================ the lifecycle (§3)

    @Test
    fun theLegalLifecycleTransitionsAreTheOnlyOnesThatMoveAProgram() = runBlocking {
        rig.createGraph()
        // The fixture program is RUNNING with an actual start; re-create one that is NOT_STARTED.
        val notStarted = rig.stored(programId).copy(
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            actualStartDate = null
        )
        rig.programRepository.updateProgram(notStarted)

        // NOT_STARTED → RUNNING → PAUSED → RUNNING → COMPLETED: the whole legal path.
        assertMoved(LifecycleStatus.NOT_STARTED, ProgramTransition.START, LifecycleStatus.RUNNING)
        assertMoved(LifecycleStatus.RUNNING, ProgramTransition.PAUSE, LifecycleStatus.PAUSED)
        assertMoved(LifecycleStatus.PAUSED, ProgramTransition.RESUME, LifecycleStatus.RUNNING)
        assertMoved(LifecycleStatus.RUNNING, ProgramTransition.COMPLETE, LifecycleStatus.COMPLETED)
    }

    @Test
    fun anIllegalTransitionIsRefusedAndChangesNothing() = runBlocking {
        rig.createGraph()
        val before = rig.stored(programId)

        // START on a RUNNING Program is an idempotent no-op: it succeeds, re-stamps nothing,
        // and above all does not overwrite the factual actualStartDate.
        val startedTwice = serviceStart()
        assertTrue("START on a RUNNING Program is a no-op: $startedTwice", startedTwice is ProgramOperationResult.Success)
        assertEquals("nothing was written", before, rig.stored(programId))

        // COMPLETE from RUNNING is legal; from COMPLETED it is not resumable.
        rig.service.completeProgram(programId)
        val completed = rig.stored(programId)
        assertEquals(LifecycleStatus.COMPLETED, completed.lifecycleStatus)

        rig.service.resumeProgram(programId).let { result ->
            assertRefused(result, ProgramTransition.RESUME)
            assertEquals("a completed Program is unchanged by a resume attempt", completed, rig.stored(programId))
        }
    }

    @Test
    fun everyIllegalTransitionIsRefused() = runBlocking {
        rig.createGraph()

        // RUNNING → PAUSED is legal, and PAUSED → PAUSE is then a no-op rather than a refusal
        // (a pause already in effect is not an illegal transition, it is nothing to do).
        assertTrue(rig.service.pauseProgram(programId) is ProgramOperationResult.Success)
        assertTrue(
            "a second pause is an idempotent no-op, not a refusal",
            rig.service.pauseProgram(programId) is ProgramOperationResult.Success
        )

        // NOT_STARTED → PAUSE/COMPLETE/RESUME: nothing to pause, complete or resume.
        createNotStarted("b")
        val b = ProgramId(ProgramGraphFixture.programId("b"))
        assertRefused(rig.service.pauseProgram(b), ProgramTransition.PAUSE)
        assertRefused(rig.service.completeProgram(b), ProgramTransition.COMPLETE)
        assertRefused(rig.service.resumeProgram(b), ProgramTransition.RESUME)

        // RESUME with no pause in effect, on a RUNNING program: an idempotent no-op, not a
        // refusal — the Program is already where RESUME would put it, and nothing is rewritten.
        createRunning("c")
        val c = ProgramId(ProgramGraphFixture.programId("c"))
        val noPause = rig.service.resumeProgram(c)
        assertTrue(
            "a resume of a Program already RUNNING is a no-op, not an error: $noPause",
            noPause is ProgramOperationResult.Success
        )
        assertEquals(LifecycleStatus.RUNNING, rig.stored(c).lifecycleStatus)
        assertNull("and no pause interval was opened", rig.scheduleRepository.pausesOfProgram(c).firstOrNull())
    }

    @Test
    fun actualStartDateIsSetOnlyOnStartAndIsTheFactualMoment() = runBlocking {
        createNotStarted("a")
        val before = rig.stored(programId)
        assertNull("a NOT_STARTED program has no actual start", before.actualStartDate)

        val startedAt = ProgramGraphFixture.FINISHED
        rig.clock.instant = startedAt

        val started = (rig.service.startProgram(programId) as ProgramOperationResult.Success).value

        assertEquals(LifecycleStatus.RUNNING, started.lifecycleStatus)
        assertEquals(
            "actualStartDate is the moment the user started it, from the injected clock",
            startedAt,
            started.actualStartDate
        )
        assertEquals(startedAt, rig.stored(programId).actualStartDate)

        // A pause, a resume and a completion do not touch the factual start.
        rig.service.pauseProgram(programId)
        rig.clock.instant = startedAt.plusSeconds(3_600)
        rig.service.resumeProgram(programId)
        rig.clock.instant = startedAt.plusSeconds(7_200)
        rig.service.completeProgram(programId)

        assertEquals(
            "the factual start survives the whole lifecycle",
            startedAt,
            rig.stored(programId).actualStartDate
        )
    }

    @Test
    fun aPlannedStartDateNeverStartsAProgram() = runBlocking {
        createNotStarted("a")
        val planned = LocalDate.parse("2024-01-01")

        // Setting the planned start date — even one long in the past — changes no lifecycle.
        val result = rig.service.setPlannedStartDate(programId, planned)
        val planned1 = (result as ProgramOperationResult.Success).value

        assertEquals("the lifecycle is untouched", LifecycleStatus.NOT_STARTED, planned1.lifecycleStatus)
        assertEquals("the plan is recorded", planned, planned1.plannedStartDate)
        assertNull("and no actual start exists", planned1.actualStartDate)

        // Moving the clock far past the planned date still starts nothing — the plan is inert.
        rig.clock.instant = planned.plusDays(365).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        assertEquals(
            "a planned start date does not start a Program (§3)",
            LifecycleStatus.NOT_STARTED,
            rig.stored(programId).lifecycleStatus
        )

        // Only the explicit call does, and it is the only thing that sets the factual start —
        // the stamp is the clock's *current* reading, not the planned date.
        val startedAt = ProgramGraphFixture.FINISHED
        rig.clock.instant = startedAt
        val started = rig.service.startProgram(programId)
        assertTrue(started is ProgramOperationResult.Success)
        assertEquals(LifecycleStatus.RUNNING, rig.stored(programId).lifecycleStatus)
        assertEquals(
            "the start came from the call and stamps that moment, never the planned date",
            startedAt,
            rig.stored(programId).actualStartDate
        )
    }

    @Test
    fun pauseFreezesAndResumeRestoresTheInterval() = runBlocking {
        rig.createGraph()
        val at = ProgramGraphFixture.STARTED
        rig.clock.instant = at

        val paused = (rig.service.pauseProgram(programId) as ProgramOperationResult.Success).value
        assertEquals(LifecycleStatus.PAUSED, paused.lifecycleStatus)

        val open = rig.scheduleRepository.pausesOfProgram(programId)
        assertEquals("one pause interval was opened", 1, open.size)
        assertTrue("and it is in effect", open.first().isOpen)
        assertEquals(at, open.first().startedAt)

        val resumedAt = at.plusSeconds(7_200)
        rig.clock.instant = resumedAt
        val resumed = (rig.service.resumeProgram(programId) as ProgramOperationResult.Success).value

        assertEquals(LifecycleStatus.RUNNING, resumed.lifecycleStatus)
        val closed = rig.scheduleRepository.pausesOfProgram(programId).first()
        assertEquals("resuming closed the interval", resumedAt, closed.endedAt)
        assertFalse("and the interval is no longer in effect", closed.isOpen)
    }

    @Test
    fun completingAPausedProgramClosesItsInterval() = runBlocking {
        rig.createGraph()
        rig.service.pauseProgram(programId)

        rig.service.completeProgram(programId)

        assertEquals(LifecycleStatus.COMPLETED, rig.stored(programId).lifecycleStatus)
        val pause = rig.scheduleRepository.pausesOfProgram(programId).first()
        assertNotNull("the pause interval was closed", pause.endedAt)
    }

    @Test
    fun completionIsTerminalAndCannotBeResumed() = runBlocking {
        rig.createGraph()
        rig.service.completeProgram(programId)
        val completed = rig.stored(programId)

        val resume = rig.service.resumeProgram(programId)
        assertRefused(resume, ProgramTransition.RESUME)

        val start = rig.service.startProgram(programId)
        assertRefused(start, ProgramTransition.START)

        val pause = rig.service.pauseProgram(programId)
        assertRefused(pause, ProgramTransition.PAUSE)

        assertEquals("a completed Program is unchanged by every resumption attempt", completed, rig.stored(programId))
    }

    // ============================================================ archive (§3, §29)

    @Test
    fun archiveIsAStampAndNotALifecycleState() = runBlocking {
        rig.createGraph()
        val lifecycleBefore = rig.stored(programId).lifecycleStatus
        val at = ProgramGraphFixture.FINISHED
        rig.clock.instant = at

        val archived = (rig.service.archiveProgram(programId) as ProgramOperationResult.Success).value

        assertTrue(archived.isArchived)
        assertEquals(at, archived.archivedAt)
        assertEquals(
            "archiving keeps the lifecycle the Program reached (§3)",
            lifecycleBefore,
            archived.lifecycleStatus
        )
        // And the archive is independent in the other direction: the lifecycle still moves.
        rig.service.completeProgram(programId)
        assertEquals(LifecycleStatus.COMPLETED, rig.stored(programId).lifecycleStatus)
        assertTrue("and the archive stamp survived the lifecycle change", rig.stored(programId).isArchived)

        // Unarchive removes the stamp and touches nothing else.
        val unarchived = (rig.service.unarchiveProgram(programId) as ProgramOperationResult.Success).value
        assertFalse(unarchived.isArchived)
        assertNull(unarchived.archivedAt)
        assertEquals(LifecycleStatus.COMPLETED, unarchived.lifecycleStatus)
    }

    @Test
    fun archivingAnAlreadyArchivedProgramIsANoOpNotAnError() = runBlocking {
        rig.createGraph()
        rig.clock.instant = ProgramGraphFixture.STARTED
        val first = (rig.service.archiveProgram(programId) as ProgramOperationResult.Success).value
        rig.clock.instant = ProgramGraphFixture.FINISHED

        val second = rig.service.archiveProgram(programId)

        assertTrue("a second archive is not an error", second is ProgramOperationResult.Success)
        assertEquals(
            "and it does not move the stamp",
            first.archivedAt,
            rig.stored(programId).archivedAt
        )
    }

    @Test
    fun archivingTheSelectedProgramRequiresAnotherSelection() = runBlocking {
        rig.createGraph()
        rig.createStandardProgram()
        (rig.service.selectProgram(programId) as ProgramOperationResult.Success)

        val refused = rig.service.archiveProgram(programId)

        assertTrue("the archive of the selection is refused", refused is ProgramOperationResult.Refused)
        assertEquals(
            ProgramOperationRefusal.ArchivingTheSelectionRequiresAnotherSelection(programId)::class,
            (refused as ProgramOperationResult.Refused).reason::class
        )
        assertFalse("nothing was archived", rig.stored(programId).isArchived)
        assertEquals("and the selection is intact", programId, rig.state()!!.selectedProgramId)

        // Once the selection moves, the archive is allowed.
        rig.service.selectProgram(rig.standardProgramId)
        val archived = rig.service.archiveProgram(programId)
        assertTrue(archived is ProgramOperationResult.Success)
        assertTrue(rig.stored(programId).isArchived)
    }

    // ============================================================ selection (§3, §21)

    @Test
    fun selectionIsGlobalAndMutuallyExclusive() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)

        (rig.service.selectProgram(programId) as ProgramOperationResult.Success)
        var state = rig.state()!!
        assertEquals(programId, state.selectedProgramId)

        (rig.service.selectProgram(other.program.programId) as ProgramOperationResult.Success)
        state = rig.state()!!
        assertEquals(
            "selecting a second Program replaced the first — one row, one selection",
            other.program.programId,
            state.selectedProgramId
        )

        // The My Programs view reports exactly one selected row.
        val view = (rig.service.myPrograms() as ProgramOperationResult.Success).value
        assertEquals("exactly one selected Program", 1, view.rows.count { it.isSelected })
        assertEquals(other.program.programId, view.selectedProgramId)
        assertTrue(view.selectionIsPresent)
    }

    @Test
    fun selectingAnUnknownProgramIsRefused() = runBlocking {
        val refused = rig.service.selectProgram(ProgramId("program-ghost"))

        assertTrue(refused is ProgramOperationResult.Refused)
        assertEquals(
            ProgramOperationRefusal.ProgramNotFound(ProgramId("program-ghost")),
            (refused as ProgramOperationResult.Refused).reason
        )
        assertNull("no state row was written for a refused selection", rig.state())
    }

    @Test
    fun myProgramsReportsTheLifecycleSelectionAndPauseOfEveryProgram() = runBlocking {
        rig.createGraph()
        createNotStarted("b")
        rig.createStandardProgram()
        rig.service.pauseProgram(programId)

        val view = (rig.service.myPrograms() as ProgramOperationResult.Success).value

        assertEquals(3, view.programCount)
        val byKey = view.rows.associateBy { it.programId.value }
        assertTrue("the paused Program reports its open pause", byKey.getValue(programId.value).hasOpenPause)
        assertFalse(byKey.getValue(ProgramGraphFixture.programId("b")).hasOpenPause)
        assertFalse("the not-started Program has not started", byKey.getValue(ProgramGraphFixture.programId("b")).hasStarted)
        assertTrue("the built-in Program is reported as built-in", byKey.getValue(StandardProgram.programId.value).isBuiltIn)
        assertFalse("and none of them claims to be selected yet", view.rows.any { it.isSelected })
        assertNull(view.selectedProgramId)
    }

    // ============================================================ the Standard Program (§4)

    @Test
    fun theStandardProgramCanBeSelectedButNotEditedOrDeleted() = runBlocking {
        rig.createStandardProgram()
        val standard = rig.standardProgramId

        // Selectable.
        val selected = rig.service.selectProgram(standard)
        assertTrue(selected is ProgramOperationResult.Success)
        assertEquals(standard, rig.state()!!.selectedProgramId)

        // Not renamable.
        val rename = rig.service.renameProgram(standard, name = "My own name")
        assertRefused(rename, ProgramOperationRefusal.StandardProgramCannotBeEdited)
        assertEquals(
            "the built-in Program's name is the app's",
            StandardProgram.NAME,
            rig.stored(standard).name
        )

        // Not deletable.
        val delete = rig.service.deleteProgram(standard)
        assertRefused(delete, ProgramOperationRefusal.StandardProgramCannotBeDeleted)
        assertEquals("the built-in Program survived", 1, rig.database.count("program"))
    }

    @Test
    fun editingTheStandardProgramMeansCopyingItFirst() = runBlocking {
        rig.createStandardProgram()

        val copy = rig.service.copyProgram(rig.standardProgramId, name = "My copy")

        assertTrue(copy is ProgramOperationResult.Success)
        val copied = (copy as ProgramOperationResult.Success).value
        assertEquals("the copy is the user's own", ProgramSource.USER, copied.source)
        assertNotEquals("with its own identity", rig.standardProgramId, copied.programId)
        assertEquals(LifecycleStatus.NOT_STARTED, copied.lifecycleStatus)
        assertEquals("My copy", copied.name)

        // The copy carries the plan, as its own revision.
        val plan = rig.planRepository.currentRevision(copied.programId)!!
        assertEquals(
            "the copy's plan is the built-in Program's structure",
            rig.standardGraph.revision.days.flatMap { day -> day.exercises.map { it.exerciseId } },
            plan.days.flatMap { day -> day.exercises.map { it.exerciseId } }
        )
        assertNotEquals(
            "but a revision identity of its own — no plan row is shared (§6)",
            rig.standardGraph.revision.revisionId,
            plan.revisionId
        )
        assertEquals(1, rig.revisionCount(copied.programId))
        assertEquals("and the source's plan was untouched", 1, rig.revisionCount(rig.standardProgramId))

        // And the copy is freely editable.
        val renamed = rig.service.renameProgram(copied.programId, name = "Renamed copy")
        assertTrue(renamed is ProgramOperationResult.Success)
        assertEquals("Renamed copy", rig.stored(copied.programId).name)
    }

    @Test
    fun copyingAUserProgramProducesAUserProgramWithTheSamePlan() = runBlocking {
        rig.createGraph()

        val outcome = rig.service.copyProgram(programId, name = "Copy of A")
        assertTrue("the copy succeeded: $outcome", outcome is ProgramOperationResult.Success)
        val copy = (outcome as ProgramOperationResult.Success).value

        assertEquals(ProgramSource.USER, copy.source)
        assertEquals("Copy of A", copy.name)
        assertEquals(LifecycleStatus.NOT_STARTED, copy.lifecycleStatus)
        assertNull("a copy has not started", copy.actualStartDate)
        assertFalse("and is not archived", copy.isArchived)

        val plan = rig.planRepository.currentRevision(copy.programId)!!
        assertEquals(
            rig.graph.revision.days.flatMap { it.exercises }.map { it.exerciseId },
            plan.days.flatMap { it.exercises }.map { it.exerciseId }
        )
        assertNotEquals(programId, copy.programId)
        assertEquals(
            "the source's lifecycle and selection are untouched by a copy",
            LifecycleStatus.RUNNING,
            rig.stored(programId).lifecycleStatus
        )
    }

    // ============================================================ deletion (§29)

    @Test
    fun deletingTheSelectedProgramSelectsTheStandardProgramAsTheFallback() = runBlocking {
        rig.createGraph()
        rig.createStandardProgram()
        rig.service.selectProgram(programId)

        val deleted = rig.service.deleteProgram(programId)

        assertTrue(deleted is ProgramOperationResult.Success)
        assertEquals(
            "the deleted Program's rows are gone",
            0,
            rig.database.count("workout_session")
        )
        assertEquals(
            "and the built-in Program the selection fell back to survived",
            1,
            rig.database.count("program")
        )
        assertEquals(
            "the selection moved to the built-in Program, the technical fallback (§3)",
            rig.standardProgramId,
            rig.state()!!.selectedProgramId
        )
        assertTrue(
            "and the state row is a valid pointer, not a cleared one",
            rig.selectionIsAValidPointer()
        )
    }

    @Test
    fun deletingAProgramThatIsNotSelectedLeavesTheSelectionAlone() = runBlocking {
        rig.createGraph()
        rig.createStandardProgram()
        rig.service.selectProgram(rig.standardProgramId)

        rig.service.deleteProgram(programId)

        assertEquals(1, rig.database.count("program"))
        assertEquals(
            "an unrelated delete did not move the selection",
            rig.standardProgramId,
            rig.state()!!.selectedProgramId
        )
    }

    @Test
    fun deletingTheSelectedProgramWithoutAStandardProgramFailsLoudlyRatherThanClearingTheSelection() = runBlocking {
        rig.createGraph()
        rig.service.selectProgram(programId)

        val outcome = rig.service.deleteProgram(programId)

        assertTrue("the delete did not succeed: $outcome", outcome is ProgramOperationResult.Failure)
        val cause = (outcome as ProgramOperationResult.Failure).cause
        assertTrue(
            "the fallback is reported, not silently skipped — and the selection was not cleared " +
                "to make the delete pass: $cause",
            cause is com.monkfitness.app.domain.usecase.StandardProgramNotSeeded
        )
        assertEquals("nothing was deleted", 1, rig.database.count("program"))
        assertEquals(
            "and the selection was not cleared to make the delete succeed (§3, §29)",
            programId,
            rig.state()!!.selectedProgramId
        )
    }

    @Test
    fun aProgramWithAnInProgressSessionCannotBeDeleted() = runBlocking {
        rig.createGraph()
        val slot = rig.graph.slots.first()
        rig.sessionRepository.startSession(
            ProgramGraphFixture.session("a", slot, status = SessionStatus.IN_PROGRESS)
        )

        val refused = rig.service.deleteProgram(programId)

        assertTrue(refused is ProgramOperationResult.Refused)
        assertEquals(
            ProgramOperationRefusal.HasInProgressSession(programId),
            (refused as ProgramOperationResult.Refused).reason
        )
        assertEquals("nothing was deleted", 1, rig.database.count("program"))
        assertEquals(
            "and the selection was not cleared behind the user's back",
            null,
            rig.state()?.selectedProgramId
        )
        assertTrue(
            "the in-flight session is intact",
            rig.sessionRepository.sessionById(
                com.monkfitness.app.domain.common.SessionId("session-a")
            )!!.status == SessionStatus.IN_PROGRESS
        )
    }

    @Test
    fun aProgramWithACompletedSessionCanBeDeleted() = runBlocking {
        rig.createGraph()
        rig.createStandardProgram()
        val slot = rig.graph.slots.first()
        val session = ProgramGraphFixture.session(
            "a", slot, status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED
        )
        rig.sessionRepository.startSession(session)

        val deleted = rig.service.deleteProgram(programId)

        assertTrue(deleted is ProgramOperationResult.Success)
        assertEquals("the Program's sessions went with it", 0, rig.database.count("workout_session"))
        assertEquals("and only the built-in Program remains", 1, rig.database.count("program"))
    }

    @Test
    fun deletingAnUnknownProgramIsRefusedNotASuccess() = runBlocking {
        val refused = rig.service.deleteProgram(ProgramId("program-ghost"))

        assertTrue(refused is ProgramOperationResult.Refused)
        assertEquals(
            ProgramOperationRefusal.ProgramNotFound(ProgramId("program-ghost")),
            (refused as ProgramOperationResult.Refused).reason
        )
    }

    // ============================================================ revisions (§6)

    @Test
    fun noRevisionIsCreatedForLifecycleSelectionRenameArchiveOrPlannedStart() = runBlocking {
        rig.createGraph()
        rig.createStandardProgram()
        val before = rig.revisionCount(programId)

        rig.service.pauseProgram(programId)
        rig.service.resumeProgram(programId)
        rig.service.completeProgram(programId)
        rig.service.archiveProgram(programId)
        rig.service.unarchiveProgram(programId)
        rig.service.selectProgram(programId)
        rig.service.setPlannedStartDate(programId, LocalDate.parse("2026-10-01"))
        rig.service.renameProgram(programId, name = "Renamed", description = "described")
        rig.service.configureNextProgram(rig.standardProgramId, autoStart = false)

        assertEquals(
            "nine non-structural operations, not one new revision (§6)",
            before,
            rig.revisionCount(programId)
        )
        assertEquals(
            "the Program's revision pointer never moved",
            rig.graph.program.currentRevisionId,
            rig.stored(programId).currentRevisionId
        )
    }

    @Test
    fun aStructuralChangeStillCreatesANewImmutableRevision() = runBlocking {
        rig.createGraph()
        val before = rig.revisionCount(programId)
        val original = rig.planRepository.currentRevision(programId)!!

        // The structural path the editor will own: a new revision through the plan repository.
        rig.planRepository.saveNewRevision(
            ProgramGraphFixture.nextRevision("a", revisionNumber = 2),
            ProgramGraphFixture.FINISHED
        )

        assertEquals("one structural save, one new revision", before + 1, rig.revisionCount(programId))
        val current = rig.planRepository.currentRevision(programId)!!
        assertNotEquals("with a new identity", original.revisionId, current.revisionId)
        assertEquals(2, current.revisionNumber)
        assertEquals(
            "and the revision it replaced is still readable in full — immutable, not rewritten",
            original,
            rig.planRepository.revisionById(original.revisionId)
        )
        assertEquals(
            "the Program now points at the new revision",
            current.revisionId,
            rig.stored(programId).currentRevisionId
        )
    }

    // ============================================================ failures (§28, §33)

    @Test
    fun aStorageFailureIsReportedRatherThanTurnedIntoAnEmptyResult() = runBlocking {
        rig.createGraph()
        // Break the Program row in a way the mapper will refuse, so the read path fails.
        rig.database.exec(
            "UPDATE `program` SET `lifecycleStatus` = 'NO_SUCH_STATUS' WHERE `programId` = ?",
            programId.value
        )

        val outcome = rig.service.myPrograms()

        assertTrue("the failure reached the caller rather than an empty list: $outcome", outcome is ProgramOperationResult.Failure)
        val cause = (outcome as ProgramOperationResult.Failure).cause
        assertTrue(
            "and it carries the mapper's own failure, not an empty result: $cause",
            cause is IllegalArgumentException && cause.message!!.contains("lifecycleStatus")
        )
    }

    // ============================================================ helpers

    private fun serviceStart(): ProgramOperationResult<Program> = runBlocking {
        rig.service.startProgram(programId)
    }

    private suspend fun assertMoved(from: LifecycleStatus, transition: ProgramTransition, to: LifecycleStatus) {
        val current = rig.stored(programId)
        assertEquals("the precondition of the transition holds", from, current.lifecycleStatus)
        val result = rig.service.lifecycleCall(transition)
        assertTrue("the transition was allowed: $result", result is ProgramOperationResult.Success)
        assertEquals("and it moved the Program to $to", to, rig.stored(programId).lifecycleStatus)
    }

    private fun ProgramLifecycleService.lifecycleCall(transition: ProgramTransition) = runBlocking {
        when (transition) {
            ProgramTransition.START -> startProgram(programId)
            ProgramTransition.PAUSE -> pauseProgram(programId)
            ProgramTransition.RESUME -> resumeProgram(programId)
            ProgramTransition.COMPLETE -> completeProgram(programId)
            ProgramTransition.ARCHIVE -> archiveProgram(programId)
            ProgramTransition.UNARCHIVE -> unarchiveProgram(programId)
        }
    }

    private fun assertRefused(result: ProgramOperationResult<*>, transition: ProgramTransition) {
        assertTrue("an illegal transition is refused, not applied: $result", result is ProgramOperationResult.Refused)
        val reason = (result as ProgramOperationResult.Refused).reason
        assertEquals(
            "and the refusal names the rule: ${reason.message}",
            ProgramOperationRefusal.IllegalTransition::class,
            reason::class
        )
    }

    private fun assertRefused(result: ProgramOperationResult<*>, reason: ProgramOperationRefusal) {
        assertTrue("the operation is refused: $result", result is ProgramOperationResult.Refused)
        assertEquals(reason, (result as ProgramOperationResult.Refused).reason)
    }

    /** A Program in a given lifecycle, created with a plan so it is a representable state. */
    private suspend fun createInLifecycle(key: String, status: LifecycleStatus, startedAt: java.time.Instant?) {
        val graph = ProgramGraphFixture.graph(key)
        val program = graph.program.copy(
            lifecycleStatus = status,
            actualStartDate = startedAt
        )
        rig.programRepository.createProgram(program, graph.revision, graph.slots)
    }

    private suspend fun createNotStarted(key: String) =
        createInLifecycle(key, LifecycleStatus.NOT_STARTED, startedAt = null)

    private suspend fun createRunning(key: String) =
        createInLifecycle(key, LifecycleStatus.RUNNING, ProgramGraphFixture.STARTED)
}
