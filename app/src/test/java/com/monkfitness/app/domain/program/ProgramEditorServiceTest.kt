package com.monkfitness.app.domain.program

import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 6 — the Manual Editor, against a real SQLite engine and the production DAOs.
 *
 * The editor's claims are claims about *storage*: that a save creates exactly one revision or none,
 * that a superseded revision is still what it was, that a draft's identities never reach a row, and
 * that a failure leaves nothing behind. Each of them is therefore measured through the engine — row
 * counts of every table, revisions read back by identity, slots compared before and after — rather
 * than argued from the service's shape.
 *
 * The suite is organised the way the flow is: create, edit, copy, review, and the two guarantees that
 * cut across all of them (draft-first editing, immutable revisions).
 */
class ProgramEditorServiceTest {

    private val rig = ProgramEditorRig("a")

    private val programId: ProgramId get() = rig.graph.program.programId

    // ============================================================ create

    @Test
    fun aManualProgramIsCreatedFromADraftAndItsPlanIsStoredWhole() = runBlocking {
        val draft = draftOf(
            "Evening strength",
            "Three short sessions a week"
        )

        val outcome = rig.service.save(draft).savedRevision()

        assertTrue("a draft of a new Program creates one", outcome.createdProgram)
        assertEquals("and exactly one revision", 1, outcome.revisionsCreated)
        assertEquals(ProgramRevision.FIRST_REVISION_NUMBER, outcome.revision.revisionNumber)
        assertEquals("the plan the user reviewed is the plan that was saved", draft.structure, outcome.revision.structure)
        assertEquals(ProgramSource.USER, outcome.program.source)
        assertEquals(LifecycleStatus.NOT_STARTED, outcome.program.lifecycleStatus)
        assertNull("creating a Program is not starting it (§3)", outcome.program.actualStartDate)
        assertNull("and a planned start date is a Program fact this stage does not invent", outcome.program.plannedStartDate)
        assertEquals(rig.clock.instant, outcome.revision.createdAt)

        // Read back through fresh repositories: the row is the truth, not the returned value.
        assertEquals(outcome.program, rig.stored(outcome.program.programId))
        assertEquals(outcome.revision, rig.currentRevision(outcome.program.programId))
        assertEquals(1, rig.revisionCount(outcome.program.programId))
    }

    @Test
    fun aCreatedRevisionHoldsEverySetTheDraftPrescribed() = runBlocking {
        val draft = draftOf("Evening strength")

        val created = rig.service.save(draft).savedRevision()
        val reloaded = rig.revision(created.revision.revisionId)!!

        assertEquals(
            "`12 / 10 / 8 / 6` is one prescription with four sets, not four defaults (§10)",
            listOf(12, 10, 8, 6),
            reloaded.days.first().exercises.first().prescription.perSetTargets
        )
        assertEquals(
            "and `30s / 30s / 45s` is another, in its own dimension",
            listOf(30, 30, 45),
            reloaded.days.first().exercises[1].prescription.perSetTargets
        )
        assertEquals("the day names are the user's", listOf("Push day", "Rest", "Repeat day"), reloaded.days.map { it.name })
        assertEquals(
            "the day types are the user's, including the rest day that prescribes nothing (§20)",
            listOf(ProgramDayType.TRAINING, ProgramDayType.REST, ProgramDayType.TRAINING),
            reloaded.days.map { it.type }
        )
        assertEquals(
            "the last day repeats one exercise as two elements of its own (§9)",
            listOf("pushup", "pushup"),
            reloaded.days.last().plannedExerciseIds
        )
        assertEquals(
            "and `plank` is where the draft put it: on day 1, after the pushups",
            listOf("pushup", "plank"),
            reloaded.days.first().plannedExerciseIds
        )
        assertEquals(
            "with two distinct identities",
            2,
            reloaded.days.last().exercises.filter { it.exerciseId == "pushup" }
                .map { it.programExerciseId }.distinct().size
        )
        assertTrue(
            "and the pinned element is still pinned (§7)",
            reloaded.days.first().exercises[1].isPinned
        )
        assertTrue(
            "the elements the user authored are marked as theirs, not as the generator's",
            reloaded.days.flatMap { it.exercises }.all { it.origin == ProgramExerciseOrigin.USER_AUTHORED }
        )
    }

    @Test
    fun creatingAProgramDoesNotScheduleIt() = runBlocking {
        val before = rig.tableCounts()

        val created = rig.service.save(draftOf("Evening strength")).savedRevision()

        assertTrue(
            "no slot is written: how a plan becomes dates is the Scheduler's (§20, §30 step 7)",
            rig.slots(created.program.programId).isEmpty()
        )
        assertEquals(
            "creation wrote the Program graph and nothing else",
            before.toMutableMap().also { it["program"] = it.getValue("program") + 1 }
                .also { it["program_revision"] = it.getValue("program_revision") + 1 }
                .also { it["program_day"] = it.getValue("program_day") + 3 }
                .also { it["program_exercise"] = it.getValue("program_exercise") + 4 },
            rig.tableCounts()
        )
        assertEquals(0, rig.tableCounts().getValue("program_workout_slot"))
    }

    @Test
    fun anUnfinishedDraftIsRejectedBeforeAnythingIsWritten() = runBlocking {
        val before = rig.tableCounts()
        val empty = rig.service.newDraft(name = "No plan yet")

        val rejected = rig.service.save(empty).rejection()

        assertTrue("the draft is not finished", rejected is ProgramEditorRejection.InvalidDraft)
        val validation = (rejected as ProgramEditorRejection.InvalidDraft).validation
        assertTrue(
            "and the rejection says which rule it broke: ${rejected.message}",
            validation.issues.contains(ProgramDraftIssue.NoPlan)
        )
        assertEquals("nothing was written at all (§7)", before, rig.tableCounts())
        assertEquals(0, rig.tableCounts().getValue("program"))
    }

    @Test
    fun aDayThatPlansNothingIsRejectedBeforeItIsSaved() = runBlocking {
        val before = rig.tableCounts()
        val unfinished = ProgramEditorDraft(
            name = "Half a plan",
            days = listOf(
                ProgramDay(
                    programDayId = ProgramDayId("draft-day-1"),
                    position = 1,
                    type = ProgramDayType.TRAINING,
                    name = "Push day"
                )
            )
        )

        val rejected = rig.service.save(unfinished).rejection()

        assertTrue(
            "an unfinished day is a draft state, and a save is where it stops being acceptable",
            rejected is ProgramEditorRejection.InvalidDraft
        )
        assertTrue(
            rejected.message.contains("pushes at least one exercise") ||
                rejected.message.contains("prescribes at least one exercise")
        )
        assertEquals(before, rig.tableCounts())
    }

    // ============================================================ edit, and the revision rule

    @Test
    fun editingAUserProgramsPlanSavesExactlyOneNewRevision() = runBlocking {
        rig.createGraph()
        val before = rig.currentRevision(programId)!!
        val countsBefore = rig.tableCounts()

        val draft = rig.service.editDraft(programId).saved()
        assertEquals("the draft is the Program as it is stored", before.structure, draft.structure)
        assertEquals(before.revisionId, draft.baseRevisionId)
        assertEquals(rig.stored(programId).name, draft.name)

        val changed = rig.service.editor(draft)
            .settingPrescription(
                programDayId = draft.days.first().programDayId,
                programExerciseId = draft.days.first().exercises.first().programExerciseId,
                prescription = RepPrescription(listOf(20, 20))
            )
            .draft

        val outcome = rig.service.save(changed).savedRevision()

        assertFalse("an edit is not a new Program", outcome.createdProgram)
        assertEquals("one save creates one revision (§6)", 1, outcome.revisionsCreated)
        assertEquals(2, outcome.revision.revisionNumber)
        assertNotEquals("with an identity of its own", before.revisionId, outcome.revision.revisionId)
        assertEquals("and the Program now points at it", outcome.revision.revisionId, rig.stored(programId).currentRevisionId)
        assertEquals(2, rig.revisionCount(programId))

        val countsAfter = rig.tableCounts()
        assertEquals(
            "the new revision is new rows: 1 revision, 3 days, 5 elements",
            countsBefore.getValue("program_revision") + 1,
            countsAfter.getValue("program_revision")
        )
        assertEquals(
            countsBefore.getValue("program_day") + 3,
            countsAfter.getValue("program_day")
        )
        assertEquals(
            countsBefore.getValue("program_exercise") + 5,
            countsAfter.getValue("program_exercise")
        )
        assertEquals(
            "the load line's prescription reached the row",
            listOf(20, 20),
            rig.revision(outcome.revision.revisionId)!!.days.first().exercises.first().prescription.perSetTargets
        )
    }

    @Test
    fun theSupersededRevisionStillDescribesExactlyWhatItDescribed() = runBlocking {
        rig.createGraph()
        val before = rig.currentRevision(programId)!!

        val draft = rig.service.editDraft(programId).saved()
        rig.service.save(
            rig.service.editor(draft)
                .addingExercise(
                    programDayId = draft.days.first().programDayId,
                    exerciseId = "dip",
                    prescription = RepPrescription(listOf(6))
                )
                .draft
        ).savedRevision()

        assertEquals(
            "a revision is immutable history: a session that ran under it stays explained (§6, §19)",
            before,
            rig.revision(before.revisionId)
        )
        assertEquals(1, rig.revision(before.revisionId)!!.revisionNumber)
    }

    @Test
    fun aNewRevisionNeverReusesThePlanRowsItReplaces() = runBlocking {
        rig.createGraph()
        val before = rig.currentRevision(programId)!!

        val draft = rig.service.editDraft(programId).saved()
        val outcome = rig.service.save(
            rig.service.editor(draft)
                .renamingDay(draft.days.first().programDayId, "Heavy push day")
                .draft
        ).savedRevision()

        val savedDays = outcome.revision.days.map { it.programDayId }.toSet()
        val savedElements = outcome.revision.days.flatMap { day -> day.exercises.map { it.programExerciseId } }.toSet()
        val previousDays = before.days.map { it.programDayId }.toSet()
        val previousElements = before.days.flatMap { day -> day.exercises.map { it.programExerciseId } }.toSet()
        val draftHandles = draft.days.map { it.programDayId }.toSet()
        val draftElementHandles = draft.days.flatMap { day -> day.exercises.map { it.programExerciseId } }.toSet()

        assertTrue(
            "a plan day is a row of its own, so a new revision's days cannot be the old ones (§6, §23)",
            savedDays.intersect(previousDays).isEmpty()
        )
        assertTrue(
            "the same for every plan element",
            savedElements.intersect(previousElements).isEmpty()
        )
        assertTrue(
            "and a draft's working handles are never what gets persisted",
            savedDays.intersect(draftHandles).isEmpty()
        )
        assertTrue(savedElements.intersect(draftElementHandles).isEmpty())

        assertEquals(
            "while the rows it replaced are still there, under their own identities",
            previousDays,
            rig.revision(before.revisionId)!!.days.map { it.programDayId }.toSet()
        )
    }

    @Test
    fun aSaveThatChangesNothingCreatesNoRevisionAndWritesNothing() = runBlocking {
        rig.createGraph()
        val stored = rig.stored(programId)
        val countsBefore = rig.tableCounts()

        val draft = rig.service.editDraft(programId).saved()
        // The user typed a name and then typed the same name: nothing structural, and nothing factual.
        val outcome = rig.service.save(rig.service.editor(draft).renamed(stored.name).draft).saved()

        assertTrue("a no-op save is a result, not a failure (§28)", outcome is ProgramSaveOutcome.NothingToChange)
        assertEquals("and it creates no revision (§6)", 0, outcome.revisionsCreated)
        assertEquals(1, rig.revisionCount(programId))
        assertEquals("nothing at all was written", countsBefore, rig.tableCounts())
        assertEquals(
            "not even the updatedAt stamp — a save that changes nothing is not a change",
            stored,
            rig.stored(programId)
        )
    }

    @Test
    fun savingTheSameDraftTwiceCreatesOneRevision() = runBlocking {
        rig.createGraph()
        val draft = rig.service.editDraft(programId).saved()
        val changed = rig.service.editor(draft)
            .addingDay(type = ProgramDayType.REST, name = "Day off")
            .draft

        val first = rig.service.save(changed).savedRevision()
        val second = rig.service.save(changed).saved()

        assertEquals(1, first.revisionsCreated)
        assertTrue(
            "a second save of the same draft finds the plan it just wrote and changes nothing",
            second is ProgramSaveOutcome.NothingToChange
        )
        assertEquals(0, second.revisionsCreated)
        assertEquals("so the Program still has two revisions", 2, rig.revisionCount(programId))
    }

    @Test
    fun renamingCreatesNoRevisionAndSavesTheFact() = runBlocking {
        rig.createGraph()
        val countsBefore = rig.tableCounts()

        val draft = rig.service.editDraft(programId).saved()
        val outcome = rig.service.save(
            rig.service.editor(draft)
                .renamed("Evening strength")
                .described("Three short sessions a week")
                .draft
        ).saved()

        assertTrue("only a Program fact changed (§6)", outcome is ProgramSaveOutcome.FactsSaved)
        assertEquals(0, outcome.revisionsCreated)
        assertEquals(1, rig.revisionCount(programId))
        val stored = rig.stored(programId)
        assertEquals("Evening strength", stored.name)
        assertEquals("Three short sessions a week", stored.description)
        assertEquals("the stamp comes from the injected clock (§26)", rig.clock.instant, stored.updatedAt)
        assertEquals(
            "and the pointer did not move",
            rig.graph.revision.revisionId,
            stored.currentRevisionId
        )
        assertEquals(
            "no plan row was touched: the only table that changed is `program`",
            countsBefore.filterKeys { it != "program" },
            rig.tableCounts().filterKeys { it != "program" }
        )
    }

    @Test
    fun aRenameAndAPlanChangeAreSavedTogetherAndPointAtTheNewRevision() = runBlocking {
        rig.createGraph()
        val before = rig.currentRevision(programId)!!

        val draft = rig.service.editDraft(programId).saved()
        val outcome = rig.service.save(
            rig.service.editor(draft)
                .renamed("Evening strength")
                .described("Three short sessions a week")
                .addingDay(type = ProgramDayType.REST, name = "Day off")
                .draft
        ).savedRevision()

        assertEquals(1, outcome.revisionsCreated)
        val stored = rig.stored(programId)
        assertEquals("the rename landed in the same save as the revision", "Evening strength", stored.name)
        assertEquals("Three short sessions a week", stored.description)
        assertEquals(rig.clock.instant, stored.updatedAt)
        assertEquals(
            "and the pointer names the revision that was just written, not the one it replaced",
            outcome.revision.revisionId,
            stored.currentRevisionId
        )
        assertNotEquals(before.revisionId, stored.currentRevisionId)
        assertEquals(2, rig.revisionCount(programId))
        assertEquals("the superseded revision is still exactly what it was", before, rig.revision(before.revisionId))
    }

    @Test
    fun aFailedSaveLeavesNoRevisionNoMovedPointerAndNoRenamedProgram() = runBlocking {
        rig.createGraph()
        val before = rig.stored(programId)
        val beforeRevision = rig.currentRevision(programId)!!
        val countsBefore = rig.tableCounts()

        val draft = rig.service.editDraft(programId).saved()
        val changed = rig.service.editor(draft)
            .renamed("This rename must not land either")
            .addingDay(type = ProgramDayType.REST)
            .draft

        // The plan's elements fail to insert: the write dies in the middle of the revision.
        rig.faults.failExerciseInsert = true
        val failed = rig.service.save(changed)

        assertTrue(
            "a storage failure surfaces as a failure, never as an empty success (§28, §33)",
            failed is ProgramEditorResult.Failed
        )
        assertTrue((failed as ProgramEditorResult.Failed).cause.message!!.contains("planted fault"))
        assertEquals("no new revision exists", 1, rig.revisionCount(programId))
        assertEquals("no orphan rows were left behind", countsBefore, rig.tableCounts())
        assertEquals("the pointer did not move", beforeRevision.revisionId, rig.stored(programId).currentRevisionId)
        assertEquals(
            "and the rename that rode along with it did not land either — one save is one unit (§27)",
            before,
            rig.stored(programId)
        )
    }

    @Test
    fun aFailedCreateLeavesNoProgramBehind() = runBlocking {
        val countsBefore = rig.tableCounts()

        rig.faults.failExerciseInsert = true
        val failed = rig.service.save(draftOf("Evening strength"))

        assertTrue(failed is ProgramEditorResult.Failed)
        assertEquals("the whole creation is one transaction (§27)", countsBefore, rig.tableCounts())
        assertEquals(0, rig.tableCounts().getValue("program"))
    }

    @Test
    fun savingDoesNotReconcileOrModifySchedulerSlots() = runBlocking {
        rig.createGraph()
        val slotsBefore = rig.slots(programId)
        assertTrue("the fixture Program is scheduled", slotsBefore.size == 3)

        val draft = rig.service.editDraft(programId).saved()
        val outcome = rig.service.save(
            rig.service.editor(draft)
                .addingDay(type = ProgramDayType.REST, name = "Day off")
                .draft
        ).savedRevision()

        assertEquals(
            "future-slot reconciliation is the Scheduler's (§20, §30 step 7): a save moves no date and " +
                "supersedes no opportunity",
            slotsBefore,
            rig.slots(programId)
        )
        assertTrue(
            "the slots still present the revision they were planned from",
            slotsBefore.all { it.revisionId == rig.graph.revision.revisionId }
        )
        assertTrue(
            "and the new revision has no slot of its own yet",
            rig.scheduleRepository.slotsOfRevision(outcome.revision.revisionId).isEmpty()
        )
    }

    @Test
    fun savingLeavesTheProgramsOwnFactsAlone() = runBlocking {
        rig.createGraph()
        val archived = rig.stored(programId).copy(archivedAt = ProgramGraphFixture.CAPTURED)
        rig.programRepository.updateProgram(archived)
        val before = rig.stored(programId)

        val draft = rig.service.editDraft(programId).saved()
        rig.service.save(
            rig.service.editor(draft)
                .settingPinned(
                    programDayId = draft.days.first().programDayId,
                    programExerciseId = draft.days.first().exercises.first().programExerciseId,
                    isPinned = true
                )
                .draft
        ).savedRevision()

        val after = rig.stored(programId)
        assertEquals("the planned start date is a plan, not a consequence of editing (§3, §6)", before.plannedStartDate, after.plannedStartDate)
        assertEquals("the factual start is untouched", before.actualStartDate, after.actualStartDate)
        assertEquals("the lifecycle is untouched", before.lifecycleStatus, after.lifecycleStatus)
        assertEquals("the archive stamp is untouched: archiving is not a lifecycle the editor moves (§29)", before.archivedAt, after.archivedAt)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.source, after.source)
        assertEquals(before.programId, after.programId)
        assertNotEquals("while the plan itself did change", before.currentRevisionId, after.currentRevisionId)
    }

    @Test
    fun aDraftOpenedFromASupersededRevisionSavesRelativeToWhatIsStored() = runBlocking {
        rig.createGraph()
        val staleDraft = rig.service.editDraft(programId).saved()

        // Another editor session saves first.
        val other = rig.service.editDraft(programId).saved()
        val second = rig.service.save(
            rig.service.editor(other).addingDay(type = ProgramDayType.REST, name = "Recovery").draft
        ).savedRevision()

        // The first draft is stale now: its plan differs from the *stored* one, so it is a change.
        val third = rig.service.save(
            rig.service.editor(staleDraft)
                .addingExercise(
                    programDayId = staleDraft.days.first().programDayId,
                    exerciseId = "dip",
                    prescription = RepPrescription(listOf(8))
                )
                .draft
        ).savedRevision()

        assertEquals(3, rig.revisionCount(programId))
        assertEquals(3, third.revision.revisionNumber)
        assertEquals(
            "revisions are immutable and append-only, so a stale draft cannot overwrite anything: the " +
                "revision it was opened from is still exactly what it was (§6)",
            second.revision,
            rig.revision(second.revision.revisionId)
        )
    }

    // ============================================================ copy

    @Test
    fun copyingCreatesANewProgramWhosePlanIsNewRowsWithTheSameStructure() = runBlocking {
        rig.createGraph()
        val source = rig.currentRevision(programId)!!
        val sourceCounts = rig.tableCounts()

        val draft = rig.service.copyDraft(programId, name = "Copy of Program a").saved()
        assertNull("a copy names no Program: it does not exist until it is saved (§4)", draft.programId)
        assertEquals(source.revisionId, draft.baseRevisionId)
        assertEquals("and it starts from the source's plan", source.structure, draft.structure)

        val outcome = rig.service.save(draft).savedRevision()

        assertTrue("the copy is a new Program", outcome.createdProgram)
        assertNotEquals(programId, outcome.program.programId)
        assertEquals("owned by the user whatever the source was (§4)", ProgramSource.USER, outcome.program.source)
        assertEquals(ProgramRevision.FIRST_REVISION_NUMBER, outcome.revision.revisionNumber)
        assertEquals("carrying the structure that was copied", source.structure, outcome.revision.structure)
        assertNotEquals("as its own revision", source.revisionId, outcome.revision.revisionId)
        assertTrue(
            "with day and element identities of its own — two Programs never share plan rows (§6, §23)",
            outcome.revision.days.map { it.programDayId }
                .intersect(source.days.map { it.programDayId }.toSet()).isEmpty() &&
                outcome.revision.days.flatMap { day -> day.exercises.map { it.programExerciseId } }
                    .intersect(source.days.flatMap { day -> day.exercises.map { it.programExerciseId } }.toSet())
                    .isEmpty()
        )

        val sourceCountsAfter = rig.tableCounts()
        assertEquals("the source Program is untouched", 1, rig.revisionCount(programId))
        assertEquals(source, rig.currentRevision(programId))
        assertEquals(
            "and copying wrote exactly one Program, one revision, three days and five elements",
            listOf(
                sourceCounts.getValue("program") + 1,
                sourceCounts.getValue("program_revision") + 1,
                sourceCounts.getValue("program_day") + 3,
                sourceCounts.getValue("program_exercise") + 5
            ),
            listOf(
                sourceCountsAfter.getValue("program"),
                sourceCountsAfter.getValue("program_revision"),
                sourceCountsAfter.getValue("program_day"),
                sourceCountsAfter.getValue("program_exercise")
            )
        )
    }

    @Test
    fun editingTheStandardProgramInPlaceIsRefusedAndCopyingItIsTheWay() = runBlocking {
        rig.createStandardProgram()
        val standard = rig.stored(rig.standardProgramId)
        val standardRevision = rig.currentRevision(rig.standardProgramId)!!

        val refused = rig.service.editDraft(rig.standardProgramId).rejection()

        assertTrue(
            "§4: the built-in Program cannot be edited directly",
            refused is ProgramEditorRejection.Refused
        )
        assertEquals(
            ProgramOperationRefusal.StandardProgramCannotBeEdited,
            (refused as ProgramEditorRejection.Refused).rule
        )
        assertEquals(
            "and the refusal is §4's own sentence, not a paraphrase",
            ProgramOperationRefusal.StandardProgramCannotBeEdited.message,
            refused.message
        )

        val copy = rig.service.copyDraft(rig.standardProgramId, name = "My own standard").saved()
        val created = rig.service.save(copy).savedRevision()
        assertEquals(ProgramSource.USER, created.program.source)
        assertEquals(standardRevision.structure, created.revision.structure)

        assertEquals(
            "while the built-in Program is exactly what it was",
            standard,
            rig.stored(rig.standardProgramId)
        )
        assertEquals(standardRevision, rig.currentRevision(rig.standardProgramId))
        assertEquals(1, rig.revisionCount(rig.standardProgramId))
    }

    @Test
    fun aCopyMayBeEditedBeforeItIsSavedAndTheCopyIsEditableAfterwards() = runBlocking {
        rig.createStandardProgram()

        val draft = rig.service.copyDraft(rig.standardProgramId, name = "My own standard").saved()
        val edited = rig.service.editor(draft)
            .addingExercise(
                programDayId = draft.days.first().programDayId,
                exerciseId = "dip",
                prescription = RepPrescription(listOf(6, 5))
            )
            .draft
        val created = rig.service.save(edited).savedRevision()

        assertEquals(
            "the copy holds what the user made of it, not what the source said",
            listOf("pushup", "pike_pushup", "dip"),
            created.revision.days.first().plannedExerciseIds
        )
        assertNotEquals(
            "and the source's plan is not what it copied",
            rig.graph.revision.days.first().plannedExerciseIds,
            created.revision.days.first().plannedExerciseIds
        )

        val copyDraft = rig.service.editDraft(created.program.programId).saved()
        val revised = rig.service.save(
            rig.service.editor(copyDraft)
                .renamingDay(copyDraft.days.first().programDayId, "My push day")
                .draft
        ).savedRevision()

        assertEquals("a copy is the user's own from then on (§4)", 2, revised.revision.revisionNumber)
        assertEquals(2, rig.revisionCount(created.program.programId))
        assertEquals(
            "and the built-in Program never moved",
            1,
            rig.revisionCount(rig.standardProgramId)
        )
    }

    @Test
    fun aDraftThatEditsTheStandardProgramIsRefusedByTheSaveAsWell() = runBlocking {
        rig.createStandardProgram()
        val standardRevision = rig.currentRevision(rig.standardProgramId)!!
        val standard = rig.stored(rig.standardProgramId)

        // A draft that claims to edit the built-in Program, built by hand: the save must refuse it on
        // its own, not only through the entry point that opened it (§4).
        val forged = ProgramEditorDraft(
            programId = rig.standardProgramId,
            baseRevisionId = standardRevision.revisionId,
            name = StandardProgram.NAME,
            description = standard.description,
            days = standardRevision.days.map { it.copy(name = "Hijacked") }
        )

        val refused = rig.service.save(forged).rejection()

        assertEquals(
            ProgramOperationRefusal.StandardProgramCannotBeEdited,
            (refused as ProgramEditorRejection.Refused).rule
        )
        assertEquals(1, rig.revisionCount(rig.standardProgramId))
        assertEquals(standard, rig.stored(rig.standardProgramId))
        assertEquals(standardRevision, rig.currentRevision(rig.standardProgramId))
    }

    @Test
    fun aCopyIsGivenAName() = runBlocking {
        rig.createGraph()

        val failed = rig.service.copyDraft(programId, name = "   ")

        assertTrue(
            "how a copy is named is the caller's, and naming it nothing is a bug rather than a state",
            failed is ProgramEditorResult.Failed
        )
        assertEquals(1, rig.revisionCount(programId))
    }

    // ============================================================ reload

    @Test
    fun aSavedStructureSurvivesAReloadExactly() = runBlocking {
        val draft = draftOf("Evening strength", "Three short sessions a week")

        val created = rig.service.save(draft).savedRevision()
        val reloaded = rig.revision(created.revision.revisionId)!!

        assertEquals(draft.structure, reloaded.structure)
        assertEquals(draft.name, created.program.name)
        assertEquals(draft.description, created.program.description)
        assertEquals(draft.duration, reloaded.duration)
        assertEquals(draft.schedule, reloaded.schedule)
        assertEquals(draft.mode, reloaded.mode)
        assertEquals(
            "every element, in order, with its per-set targets, its authorship and its pin",
            listOf(
                "pushup 12/10/8/6 user unpinned",
                "plank 30/30/45 user pinned",
                "pushup 5 user unpinned",
                "pushup 5 user unpinned"
            ),
            reloaded.days.flatMap { day -> day.exercises }.map { element ->
                "${element.exerciseId} ${element.prescription.perSetTargets.joinToString("/")} " +
                    "${element.origin.name.substringBefore("_").lowercase()} " +
                    (if (element.isPinned) "pinned" else "unpinned")
            }
        )
    }

    @Test
    fun aReloadedPlanDescribesTheWorkTheUserPrescribed() = runBlocking {
        val created = rig.service.save(draftOf("Evening strength")).savedRevision()

        val reloaded = rig.revision(created.revision.revisionId)!!
        assertEquals(listOf(4, 3, 1, 1), reloaded.days.flatMap { day -> day.exercises }.map { it.prescription.setCount })
        assertEquals(
            "the sets are per set, and the list is the source of truth (§10)",
            listOf(30, 30, 45),
            reloaded.days.flatMap { day -> day.exercises }[1].prescription.let {
                (it as TimePrescription).perSetSeconds
            }
        )
        assertEquals(3, reloaded.days.size)
        assertEquals(listOf(1, 2, 3), reloaded.days.map { it.position })
        assertEquals(
            "the plan prescribes nine sets in total, across both dimensions",
            9,
            reloaded.days.sumOf { day -> day.exercises.sumOf { it.prescription.setCount } }
        )
    }

    // ============================================================ draft-first

    @Test
    fun editingNeverTouchesAnythingThatIsStored() = runBlocking {
        rig.createGraph()
        val programBefore = rig.stored(programId)
        val revisionBefore = rig.currentRevision(programId)!!
        val slotsBefore = rig.slots(programId)
        val countsBefore = rig.tableCounts()

        // A whole editing session: open, then change everything a user can change.
        val draft = rig.service.editDraft(programId).saved()
        val edited = rig.service.editor(draft)
            .renamed("Still not saved")
            .described("Also not saved")
            .withDuration(ProgramDuration.FixedDays(56))
            .withSchedule(ProgramSchedule.FlexiblePerWeek(5))
            .addingDay(type = ProgramDayType.TRAINING, name = "New day")
            .removingDay(draft.days.last().programDayId)
            .settingPinned(
                programDayId = draft.days.first().programDayId,
                programExerciseId = draft.days.first().exercises.first().programExerciseId,
                isPinned = true
            )
            .draft

        assertNotEquals("the draft did change", draft, edited)
        assertEquals("the Program is exactly what it was", programBefore, rig.stored(programId))
        assertEquals("including its current revision", revisionBefore, rig.currentRevision(programId))
        assertEquals("and its slots", slotsBefore, rig.slots(programId))
        assertEquals("and the whole database", countsBefore, rig.tableCounts())
    }

    // ============================================================ review

    @Test
    fun theReviewSaysWhatSavingWouldDo() = runBlocking {
        rig.createGraph()

        val creating = rig.service.review(draftOf("Evening strength")).saved()
        assertEquals(ProgramDraftSaveKind.CREATES_PROGRAM, creating.saveKind)
        assertEquals(1, creating.targetRevisionNumber)
        assertNull(creating.targetProgramId)
        assertTrue("nothing was compared against, so nothing is reported as changed", creating.changes.isEmpty())
        assertTrue(creating.isSavable)
        assertEquals(3, creating.dayCount)
        assertEquals(1, creating.restDayCount)
        assertEquals(4, creating.exerciseCount)

        val copying = rig.service.review(rig.service.copyDraft(programId, name = "Copy").saved()).saved()
        assertEquals(ProgramDraftSaveKind.CREATES_COPY, copying.saveKind)
        assertEquals(1, copying.targetRevisionNumber)
        assertTrue("a copy is a new Program even when nothing was edited", copying.willCreateARevision)

        val unchanged = rig.service.review(rig.service.editDraft(programId).saved()).saved()
        assertEquals(ProgramDraftSaveKind.NO_STRUCTURAL_CHANGE, unchanged.saveKind)
        assertNull(unchanged.targetRevisionNumber)
        assertFalse("so saving it creates no revision (§6)", unchanged.willCreateARevision)
        assertEquals(programId, unchanged.targetProgramId)

        val edited = rig.service.editDraft(programId).saved()
        val changing = rig.service.review(
            rig.service.editor(edited)
                .withMode(ProgramMode.GENERATED)
                .renamingDay(edited.days.first().programDayId, "Heavy push day")
                .draft
        ).saved()
        assertEquals(ProgramDraftSaveKind.CREATES_REVISION, changing.saveKind)
        assertEquals(2, changing.targetRevisionNumber)
        assertEquals(
            "the dimensions that would change, in the declaration's order",
            listOf(ProgramStructureAspect.MODE, ProgramStructureAspect.DAYS),
            changing.changes
        )
    }

    @Test
    fun theReviewOfAnUnfinishedDraftReportsItsFindingsInsteadOfRefusing() = runBlocking {
        val review = rig.service.review(rig.service.newDraft(name = "No plan yet")).saved()

        assertFalse("the review is a successful read that says 'not yet'", review.isSavable)
        assertTrue(review.validation.issues.contains(ProgramDraftIssue.NoPlan))
        assertEquals("and it still describes the draft", 0, review.dayCount)
    }

    @Test
    fun reviewingADraftWhoseProvenanceIsGoneIsRefused() = runBlocking {
        val ghost = ProgramEditorDraft(
            baseRevisionId = RevisionId("revision-ghost"),
            name = "Copy of nothing",
            days = plan()
        )

        val reviewed = rig.service.review(ghost)
        val saved = rig.service.save(ghost)

        assertEquals(
            "a draft opened from a revision that does not exist is a stale draft, not a copy of nothing",
            ProgramEditorRejection.RevisionNotFound(RevisionId("revision-ghost")),
            (reviewed as ProgramEditorResult.Rejected).rejection
        )
        assertEquals(
            "and the save refuses it for the same reason, before writing anything",
            reviewed.rejection,
            (saved as ProgramEditorResult.Rejected).rejection
        )
    }

    // ============================================================ missing rows

    @Test
    fun editingOrCopyingAProgramThatIsNotStoredIsRefused() = runBlocking {
        val ghost = ProgramId("program-ghost")

        listOf(rig.service.editDraft(ghost), rig.service.copyDraft(ghost, name = "Copy")).forEach { result ->
            val rejection = (result as ProgramEditorResult.Rejected).rejection
            assertEquals(
                "a missing Program is a typed refusal (§28), not an empty result (§33): $result",
                ProgramEditorRejection.Refused(ProgramOperationRefusal.ProgramNotFound(ghost)),
                rejection
            )
        }
    }

    @Test
    fun aDraftThatEditsAProgramWithoutNamingItsRevisionIsRejected() = runBlocking {
        rig.createGraph()
        val orphan = ProgramEditorDraft(
            programId = programId,
            name = "Not from a revision",
            days = plan()
        )

        val rejection = rig.service.save(orphan).rejection()

        assertTrue(rejection is ProgramEditorRejection.InvalidDraft)
        assertTrue(
            "an edit names where it started; guessing would silently overwrite a plan (§33)",
            (rejection as ProgramEditorRejection.InvalidDraft).validation.issues
                .any { it is ProgramDraftIssue.ProgramWithoutBaseRevision }
        )
        assertEquals(1, rig.revisionCount(programId))
    }

    // ============================================================ the exercise library

    @Test
    fun theEditorNeverMutatesExerciseLibraryData() = runBlocking {
        rig.createGraph()
        val libraryBefore = rig.library()
        assertTrue("the app's library is what is being compared", libraryBefore.size > 20)

        // A full cycle: create, edit a user Program, copy the built-in one, and edit the copy.
        val created = rig.service.save(draftOf("Evening strength")).savedRevision()
        val edited = rig.service.editDraft(programId).saved()
        rig.service.save(
            rig.service.editor(edited)
                .addingExercise(
                    programDayId = edited.days.first().programDayId,
                    exerciseId = libraryBefore.first().id,
                    prescription = RepPrescription(listOf(3, 3))
                )
                .draft
        ).savedRevision()
        rig.createStandardProgram()
        val copy = rig.service.copyDraft(rig.standardProgramId, name = "My own standard").saved()
        rig.service.save(rig.service.editor(copy).removingDay(copy.days.last().programDayId).draft).savedRevision()

        assertEquals(
            "a prescription belongs to the plan, never to the exercise: the library is byte-for-byte " +
                "what it was (§10)",
            libraryBefore,
            rig.library()
        )
        assertEquals(1, rig.revisionCount(created.program.programId))
    }

    // ============================================================ helpers

    /** The draft the editor produces for a new MANUAL Program, built through the editor's own API. */
    private fun draftOf(name: String, description: String = ""): ProgramEditorDraft {
        val days = plan()
        return ProgramEditorDraft(
            name = name,
            description = description,
            days = days
        ).also { draft -> assertTrue("the fixture draft is valid", draft.validation().isValid) }
    }

    /**
     * A three-day manual plan that exercises every per-set guarantee:
     *
     * ```text
     * day 1  Push day     pushup 12/10/8/6, plank 30/30/45 (pinned)
     * day 2  Rest         nothing — a rest day prescribes no exercises (§20)
     * day 3  Repeat day   pushup 5, pushup 5 — the same exercise as two occurrences (§9)
     * ```
     */
    private fun plan(): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId("draft-day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Push day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("draft-element-1"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(12, 10, 8, 6)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("draft-element-2"),
                    exerciseId = "plank",
                    prescription = TimePrescription(listOf(30, 30, 45)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED,
                    isPinned = true
                )
            )
        ),
        ProgramDay(
            programDayId = ProgramDayId("draft-day-2"),
            position = 2,
            type = ProgramDayType.REST,
            name = "Rest"
        ),
        ProgramDay(
            programDayId = ProgramDayId("draft-day-3"),
            position = 3,
            type = ProgramDayType.TRAINING,
            name = "Repeat day",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("draft-element-3"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(5)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                ),
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("draft-element-4"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(5)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        )
    )

    private fun <T> ProgramEditorResult<T>.saved(): T {
        assertTrue("expected the operation to succeed, got $this", this is ProgramEditorResult.Success)
        return (this as ProgramEditorResult.Success).value
    }

    private fun ProgramEditorResult<ProgramSaveOutcome>.savedRevision(): ProgramSaveOutcome.RevisionSaved {
        val outcome = saved()
        assertTrue("expected a new revision, got $outcome", outcome is ProgramSaveOutcome.RevisionSaved)
        return outcome as ProgramSaveOutcome.RevisionSaved
    }

    private fun <T> ProgramEditorResult<T>.rejection(): ProgramEditorRejection {
        assertTrue("expected a typed refusal, got $this", this is ProgramEditorResult.Rejected)
        return (this as ProgramEditorResult.Rejected).rejection
    }
}
