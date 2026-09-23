package com.monkfitness.app.domain.usecase

import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.SlotStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * §27's production Save, against a real SQLite engine and the production DAOs — the remediation's
 * core claims, each measured on rows rather than argued from the code's shape:
 *
 * ```text
 * Create/Copy → Program + Revision + plan + initial Slots, anchored to the request's date, atomic,
 *               selecting nothing
 * Save Editor → new Revision + future-slot reconciliation — completed history survives, idempotent
 * ```
 *
 * The suite is organised as the brief states the rules: the creation date, the Scheduler's own slots,
 * atomicity under a planted engine failure, and the reconciliation pair that production previously
 * never ran.
 */
class ProgramSaveServiceTest {

    private val rig = ProgramSaveRig("save")

    @After
    fun tearDown() {
        rig.close()
    }

    // ---------------------------------------------------------------- the creation date

    @Test
    fun anExactPlannedStartDateIsStoredVerbatimAndIsNotReplacedByToday() = runBlocking {
        val exact = LocalDate.parse("2026-10-07") // a Wednesday, so the schedule below can anchor on it
        val draft = rig.draftOf("Exact date", ProgramSchedule.FixedWeekdays(setOf(java.time.DayOfWeek.WEDNESDAY)))

        val result = rig.service.save(draft, plannedStartDate = exact)

        val outcome = savedOutcome(result)
        assertTrue("a create draft creates a Program", outcome.createdProgram)
        val stored = rig.storedProgram(outcome.program.programId)
        assertEquals(
            "the user's exact date is the Program's planned start date",
            exact,
            stored.plannedStartDate
        )
        assertNotEquals(
            "and it is not today's date wearing the picker's face",
            rig.today(),
            exact
        )
        assertEquals(LifecycleStatus.NOT_STARTED, stored.lifecycleStatus)
        assertNull("creating is not starting (§3)", stored.actualStartDate)

        val firstSlot = rig.slots(stored.programId).minOf { slot -> slot.plannedFor }
        assertEquals(
            "the initial opportunities are anchored to the chosen date: the first one lands on it",
            exact,
            firstSlot
        )
    }

    @Test
    fun withoutAnExactDateTheCreationTakesTodayFromTheInjectedClockAndZone() = runBlocking {
        val first = savedOutcome(rig.service.save(rig.draftOf("Today one"))).program

        assertEquals(
            "no explicit choice → today, read from the injected clock in the injected calendar",
            rig.today(),
            rig.storedProgram(first.programId).plannedStartDate
        )

        // The clock moves; a later creation reads *its* today — the default is resolved at Save,
        // not captured when the editor was opened.
        rig.clock.instant = java.time.Instant.parse("2026-10-01T23:30:00Z")
        val second = savedOutcome(rig.service.save(rig.draftOf("Today two"))).program

        assertEquals(LocalDate.parse("2026-10-01"), rig.storedProgram(second.programId).plannedStartDate)
    }

    // ---------------------------------------------------------------- the Scheduler's own slots

    @Test
    fun initialSlotsAreTheSchedulersOwnDecisionStoredWhole() = runBlocking {
        val outcome = savedOutcome(rig.service.save(rig.draftOf("Scheduled")))

        val stored = rig.slots(outcome.program.programId)
        assertTrue("§27: a created Program has initial opportunities", stored.isNotEmpty())
        assertTrue(
            "every one presents the revision being created",
            stored.all { slot -> slot.revisionId == outcome.revision.revisionId }
        )
        assertTrue(
            "and every one is planned at or after the anchor",
            stored.all { slot -> !slot.plannedFor.isBefore(outcome.program.plannedStartDate) }
        )

        // The same inputs decide the same opportunities: recomputing through the Scheduler's own
        // entry point must agree date-for-date and day-for-day with what was stored — the stored
        // rows are that decision, not a second construction of it.
        val recomputed = when (
            val again = rig.scheduler.initialSlotsFor(
                rig.storedProgram(outcome.program.programId),
                rig.currentRevision(outcome.program.programId)!!
            )
        ) {
            is ProgramSchedulingResult.Success -> again.value
            else -> error("recomputing the Scheduler's own answer failed: $again")
        }
        assertEquals(
            stored.map { slot -> slot.plannedFor to slot.programDayId }.sortedBy { it.toString() },
            recomputed.map { slot -> slot.plannedFor to slot.programDayId }.sortedBy { it.toString() }
        )
    }

    @Test
    fun aCopySavesThroughTheSameCreationUnit() = runBlocking {
        rig.createGraph()
        val draft = rig.editor.copyDraft(rig.graph.program.programId, name = "Copy of Program save").let {
            (it as ProgramEditorResult.Success).value
        }

        val outcome = savedOutcome(rig.service.save(draft, plannedStartDate = null))

        assertTrue("a copy is a new Program", outcome.createdProgram)
        val stored = rig.storedProgram(outcome.program.programId)
        assertEquals(rig.today(), stored.plannedStartDate)
        assertTrue("with initial opportunities of its own", rig.slots(stored.programId).isNotEmpty())
        assertEquals("one first revision", 1, rig.revisionCount(stored.programId))
    }

    // ---------------------------------------------------------------- atomicity

    @Test
    fun aFailedCreationLegLeavesNoProgramNoRevisionAndNoSlot() = runBlocking {
        val before = rig.tableCounts()
        rig.faults.failExerciseInsert = true

        val result = try {
            rig.service.save(rig.draftOf("Doomed"), plannedStartDate = LocalDate.parse("2026-10-07"))
        } finally {
            rig.faults.failExerciseInsert = false
        }

        assertTrue("a storage failure is §28's SYSTEM_FAILURE, not a success", result is ProgramEditorResult.Failed)
        assertEquals(
            "one transaction: not one row of any table moved — the Program graph and its slots alike",
            before,
            rig.tableCounts()
        )
        assertEquals(0, rig.tableCounts().getValue("program"))
        assertEquals(0, rig.tableCounts().getValue("program_workout_slot"))
    }

    /**
     * A failure out of the Scheduler's own decision, planted in the Scheduler's own clock, leaves
     * nothing behind: the slots are decided before the write, so the write never happens.
     *
     * The Scheduler's *refusal* branch ([com.monkfitness.app.domain.program.ProgramSchedulingRefusal
     * .NoSchedulingAnchor]) is unreachable through this path by construction — every creation carries
     * a non-null planned start date — and is therefore asserted where it is reachable, at the
     * Scheduler's own boundary, rather than fabricated here.
     */
    @Test
    fun aSchedulerFailureLeavesNoPartiallyCreatedProgram() = runBlocking {
        val before = rig.tableCounts()
        // The same graph, one Scheduler whose own clock read fails: the failure is produced by
        // ProgramScheduler itself, on the decision leg, before any write exists to roll back.
        val explodingScheduler = ProgramScheduler(
            programRepository = rig.programRepository,
            planRepository = rig.planRepository,
            scheduleRepository = rig.scheduleRepository,
            clock = Clock { throw IllegalStateException("planted fault: the scheduling clock") },
            idGenerator = rig.ids,
            zone = rig.zone,
            inTransaction = rig.transaction
        )
        val failing = ProgramSaveService(
            editor = rig.editor,
            programRepository = rig.programRepository,
            scheduler = explodingScheduler,
            clock = rig.clock,
            zone = rig.zone,
            inTransaction = rig.transaction
        )

        val result = failing.save(rig.draftOf("Never written"), plannedStartDate = null)

        assertTrue("the failure surfaces as §28's SYSTEM_FAILURE", result is ProgramEditorResult.Failed)
        assertEquals("and the table set is exactly what it was", before, rig.tableCounts())
        assertEquals(0, rig.tableCounts().getValue("program"))
    }

    @Test
    fun creationMovesNoSelection() = runBlocking {
        rig.service.save(rig.draftOf("Unselected"), plannedStartDate = null)

        assertNull("a creation writes no AppState (§9: selection is an explicit opt-in)", rig.selection())

        // …and a later creation does not clear an existing selection either.
        rig.createGraph()
        rig.select(rig.graph.program.programId)
        rig.service.save(rig.draftOf("Second"), plannedStartDate = null)

        assertEquals(
            "the previous selection stands: creating a Program selects nothing and clears nothing",
            rig.graph.program.programId,
            rig.selection()!!.selectedProgramId
        )
    }

    @Test
    fun anUnsavedDraftNeverReachesATable() = runBlocking {
        val before = rig.tableCounts()

        val result = rig.service.save(
            rig.draftOf("").let { draft -> draft.copy(days = emptyList()) },
            plannedStartDate = null
        )

        assertTrue("an unfinished draft is refused", result is ProgramEditorResult.Rejected)
        assertEquals("before any identity reaches storage (§7)", before, rig.tableCounts())
    }

    // ---------------------------------------------------------------- future-slot reconciliation

    @Test
    fun aStructuralSaveReconcilesFutureOpportunities() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId
        val slotsBefore = rig.slots(programId)
        assertEquals("the fixture Program starts with its three stored opportunities", 3, slotsBefore.size)
        val tuesday = slotsBefore.single { slot -> slot.plannedFor == LocalDate.parse("2026-09-22") }
        val monday = slotsBefore.single { slot -> slot.plannedFor == LocalDate.parse("2026-09-21") }
        val wednesday = slotsBefore.single { slot -> slot.plannedFor == LocalDate.parse("2026-09-23") }

        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val changed = rig.editor.editor(draft)
            .addingDay(type = ProgramDayType.REST, name = "Day off")
            .draft
        val result = rig.service.save(changed)

        val outcome = savedOutcome(result)
        assertFalse("the edit created a revision, not a Program", outcome.createdProgram)
        assertEquals(2, rig.revisionCount(programId))

        // The future Tuesday the M/W/F revision no longer presents is superseded — an existing row,
        // its status changed, never deleted or re-pointed (§20).
        assertEquals(SlotStatus.SUPERSEDED, rig.slot(tuesday.slotId)!!.status)

        // The dates the revision does present keep their identity untouched.
        val mondayAfter = rig.slot(monday.slotId)!!
        assertEquals(SlotStatus.PLANNED, mondayAfter.status)
        assertEquals(monday.plannedFor, mondayAfter.plannedFor)
        assertEquals(monday.programDayId, mondayAfter.programDayId)
        assertEquals(SlotStatus.PLANNED, rig.slot(wednesday.slotId)!!.status)

        // …and the new revision is scheduled: opportunities presenting ITS days exist.
        val forNewRevision = rig.slotsOfRevision(outcome.revision.revisionId)
        assertTrue("the saved revision has opportunities of its own", forNewRevision.isNotEmpty())
        assertTrue(
            "every one presents a day of the revision just saved",
            forNewRevision.all { slot ->
                slot.programDayId in outcome.revision.days.map { day -> day.programDayId }
            }
        )
        assertTrue(
            "and every one is at or after the day of the pass",
            forNewRevision.all { slot -> !slot.plannedFor.isBefore(rig.today()) }
        )
    }

    @Test
    fun aCompletedOpportunitySurvivesReconciliationAsHistory() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId
        val trained = rig.completeFirstOpportunity()

        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val changed = rig.editor.editor(draft)
            .addingDay(type = ProgramDayType.REST, name = "Day off")
            .draft
        savedOutcome(rig.service.save(changed))

        val after = rig.slot(trained.slotId)
        assertEquals(
            "a finished attempt is history: §19 — reconciliation never rewrites it",
            SlotStatus.COMPLETED,
            after!!.status
        )
        assertEquals(
            "its date is not re-planned either: one row, one date",
            1,
            rig.slots(programId).count { slot -> slot.plannedFor == trained.plannedFor }
        )
    }

    @Test
    fun repeatedReconciliationIsIdempotent() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId
        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val changed = rig.editor.editor(draft)
            .addingDay(type = ProgramDayType.REST, name = "Day off")
            .draft
        savedOutcome(rig.service.save(changed)) // the Save's own pass has already run once

        val counts = rig.tableCounts()
        val statuses = rig.slots(programId).map { slot -> slot.slotId.value to slot.status }
            .sortedBy { (id, _) -> id }.toMap()

        val second = rig.scheduler.schedule(programId)
        val third = rig.scheduler.schedule(programId)

        assertTrue("the second pass runs", second is ProgramSchedulingResult.Success)
        assertTrue("and decides nothing — after one pass there is nothing left to decide",
            (second as ProgramSchedulingResult.Success).value.isNoOp)
        assertTrue("as does a third", third is ProgramSchedulingResult.Success)
        assertTrue((third as ProgramSchedulingResult.Success).value.isNoOp)
        assertEquals("no row changed identity or status between the passes", counts, rig.tableCounts())
        assertEquals(
            statuses,
            rig.slots(programId).map { slot -> slot.slotId.value to slot.status }
                .sortedBy { (id, _) -> id }.toMap()
        )
    }

    // ---------------------------------------------------------------- the shape of the layer

    @Test
    fun theSaveServiceIsWiredWithExactlyTheOwnersItsTwoLinesNeed() {
        val collaborators = ProgramSaveService::class.java.declaredConstructors
            .single()
            .parameterTypes
            .map { type -> type.simpleName }

        assertEquals(
            "the editor (structure), the repository (the creation primitive), the Scheduler " +
                "(opportunities), the two ports today is read from, and the transaction runner — " +
                "and notably no lifecycle service: a creation cannot move a selection (§9)",
            listOf(
                "ProgramEditorService", "ProgramRepository", "ProgramScheduler",
                "Clock", "ZoneId", "Function2"
            ),
            collaborators
        )
    }

    // ---------------------------------------------------------------- the mechanism

    private fun savedOutcome(result: ProgramEditorResult<ProgramSaveOutcome>): ProgramSaveOutcome.RevisionSaved {
        assertTrue("the save succeeded, got: $result", result is ProgramEditorResult.Success)
        val outcome = (result as ProgramEditorResult.Success).value
        assertTrue("this suite measures creations and revisions, got: $outcome",
            outcome is ProgramSaveOutcome.RevisionSaved)
        return outcome as ProgramSaveOutcome.RevisionSaved
    }

}
