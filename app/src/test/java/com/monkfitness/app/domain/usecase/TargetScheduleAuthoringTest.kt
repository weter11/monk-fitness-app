package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.prescription.RepPrescription
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Stage 19 — the explicit target schedule authoring path, measured on rows.
 *
 * The stage's whole claim is that a caller can now **state** the target scheduling semantics of the
 * revision a save creates, and that the only thing the application layer does with that statement is
 * attach it to the revision it just created:
 *
 * ```text
 * caller-owned authoring values
 *         ↓  ProgramSaveService.save(draft, plannedStartDate, targetSchedule)
 * creation / revision-save application boundary
 *         ↓  TargetScheduleSourceRepository.store(...)
 * revision-owned explicit target source
 * ```
 *
 * Every rule here is therefore a *read-back* claim — the stored rows are the assertion, and the
 * authoring is a value the test wrote out rather than one a fixture happened to produce. A test whose
 * subject is only "the save returned Success" would pass against code that stored nothing, so the
 * twelve rules below each read the source back through a **fresh** repository.
 */
class TargetScheduleAuthoringTest {

    private val rig = ProgramSaveRig("authoring")

    @After
    fun tearDown() {
        rig.close()
    }

    // ---------------------------------------------------------------- 1. round-trip unchanged

    @Test
    fun everyStatedRuleReachesTheStoredSourceExactlyAsWritten() = runBlocking {
        val anchor = LocalDate.parse("2026-10-05")
        val authoring = authoringOf(
            rules = listOf(
                rule("rule-alpha", "workout-alpha", ScheduleCadence.EveryNDays(3), anchor),
                rule("rule-beta", "workout-beta", ScheduleCadence.Daily, anchor.plusDays(7))
            )
        )

        val outcome = savedOutcome(
            rig.service.save(rig.draftOf("Authored"), plannedStartDate = null, targetSchedule = authoring)
        )

        assertEquals(
            "each rule keeps its own identity, its own workout, its own cadence and its own anchor — " +
                "compared by rule id, because the read is ordered by stored identity",
            authoring.rules.associate { it.ruleId to it },
            rig.storedTargetRules(outcome.revision.revisionId).associate { it.ruleId to it }
        )
    }

    // ---------------------------------------------------------------- 2. all five cadence forms

    @Test
    fun allFiveCadenceFormsSurviveTheAuthoringPathUnchanged() = runBlocking {
        val anchor = LocalDate.parse("2026-10-05")
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        // Five rules, five forms, each with the payload its own form requires. If any form were
        // normalized into another on the way through, this list would not come back equal.
        val stated = listOf(
            rule("rule-daily", "w-daily", ScheduleCadence.Daily, anchor),
            rule("rule-every", "w-every", ScheduleCadence.EveryNDays(4), anchor),
            rule("rule-sessions", "w-sessions", ScheduleCadence.SessionsPerWeek(3), anchor),
            rule("rule-fixed", "w-fixed", ScheduleCadence.FixedWeekdays(weekdays), anchor),
            rule("rule-derived", "w-derived", ScheduleCadence.DerivedExcluding("rule-daily"), anchor)
        )

        val outcome = savedOutcome(
            rig.service.save(
                rig.draftOf("Five forms"),
                plannedStartDate = null,
                targetSchedule = authoringOf(rules = stated)
            )
        )

        val read = rig.storedTargetRules(outcome.revision.revisionId).associateBy { it.ruleId }
        assertEquals("all five rules are stored", stated.size, read.size)
        for (definition in stated) {
            val stored = read[definition.ruleId]
            assertTrue("rule '${definition.ruleId}' is stored", stored != null)
            assertEquals(
                "the cadence of '${definition.ruleId}' is byte-for-byte what the caller stated",
                definition.cadence,
                stored!!.cadence
            )
        }
        // Spelled out, because the point of the stage is that `SessionsPerWeek(3)` stays a frequency
        // of three and does not quietly become a three-day weekday set on the way through storage.
        assertEquals(ScheduleCadence.Daily, read["rule-daily"]!!.cadence)
        assertEquals(ScheduleCadence.EveryNDays(4), read["rule-every"]!!.cadence)
        assertEquals(ScheduleCadence.SessionsPerWeek(3), read["rule-sessions"]!!.cadence)
        assertEquals(ScheduleCadence.FixedWeekdays(weekdays), read["rule-fixed"]!!.cadence)
        assertEquals(
            "and a derived rule keeps the identity of the rule it derives from",
            ScheduleCadence.DerivedExcluding("rule-daily"),
            read["rule-derived"]!!.cadence
        )
    }

    // ---------------------------------------------------------------- 3. bindings

    @Test
    fun anExplicitBindingReachesTheStoredSourceAgainstTheRevisionOwnDay() = runBlocking {
        val draft = rig.draftOf("Bound")
        val draftedDay = draft.days.single().programDayId
        val authoring = authoringOf(
            rules = listOf(rule("rule-1", "workout-1", ScheduleCadence.Daily, LocalDate.parse("2026-10-05"))),
            bindings = listOf(TargetScheduleAuthoringBinding("workout-1", draftedDay))
        )

        val outcome = savedOutcome(
            rig.service.save(draft, plannedStartDate = null, targetSchedule = authoring)
        )

        val bindings = rig.storedTargetBindings(outcome.revision.revisionId)
        assertEquals(1, bindings.size)
        assertEquals("the workout identity is the caller's", "workout-1", bindings.single().workoutId)
        // The *only* thing re-pointed is the drafted handle, and it lands on a day of the revision the
        // save created — which is the claim a caller cannot make without the editor's correspondence.
        assertEquals(
            "the binding names the identity the drafted day became in the saved revision",
            outcome.revision.days.single().programDayId,
            bindings.single().programDayId
        )
        assertNotEquals(
            "and never the draft's own handle, which is never persisted (§6)",
            draftedDay,
            bindings.single().programDayId
        )
    }

    // ---------------------------------------------------------------- 4. create persists it
    @Test
    fun aCreateStoresTheStatedSourceAgainstTheRevisionItJustCreated() = runBlocking {
        val authoring = authoringOf(
            rules = listOf(rule("rule-create", "workout-create", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")))
        )

        val outcome = savedOutcome(
            rig.service.save(rig.draftOf("Created with target"), plannedStartDate = LocalDate.parse("2026-10-07"), targetSchedule = authoring)
        )

        assertTrue("a create created the Program", outcome.createdProgram)
        assertEquals(
            "the source belongs to the revision this save created",
            authoring.rules,
            rig.storedTargetRules(outcome.revision.revisionId)
        )
        assertEquals(
            "the stored source names that same revision and no other",
            outcome.revision.revisionId,
            (rig.storedTargetSource(outcome.revision.revisionId) as TargetScheduleSourceRead.Source)
                .source.revisionId
        )
    }

    // ---------------------------------------------------------------- 5. copy

    @Test
    fun aCopyStoresANewSourceAgainstTheCopiedRevisionAndLeavesTheSourceProgramAlone() = runBlocking {
        rig.createGraph()
        val source = rig.graph.program
        val sourceRevision = rig.currentRevision(source.programId)!!
        // The source revision has no stated source yet, so "unchanged" below is a typed absence rather
        // than a value that could accidentally compare equal.
        assertTrue(
            "the fixture Program states no target source of its own",
            rig.storedTargetSource(sourceRevision.revisionId) is TargetScheduleSourceRead.Missing
        )
        val draft = rig.editor.copyDraft(source.programId, name = "Copy of graph").let {
            (it as ProgramEditorResult.Success).value
        }
        val firstDraftedDay = draft.days.first().programDayId
        val authoring = authoringOf(
            rules = listOf(rule("rule-copy", "workout-copy", ScheduleCadence.EveryNDays(2), LocalDate.parse("2026-10-06"))),
            bindings = listOf(TargetScheduleAuthoringBinding("workout-copy", firstDraftedDay))
        )

        val outcome = savedOutcome(
            rig.service.save(draft, plannedStartDate = null, targetSchedule = authoring)
        )

        assertTrue("a copy is a new Program", outcome.createdProgram)
        assertNotEquals("with its own first revision", sourceRevision.revisionId, outcome.revision.revisionId)
        assertEquals(
            "the copy's own source is stored against the copy's own revision",
            authoring.rules,
            rig.storedTargetRules(outcome.revision.revisionId)
        )
        assertEquals(
            "and the copy's binding names the identity that drafted day became in the copy's revision",
            outcome.mintedProgramDays[firstDraftedDay],
            rig.storedTargetBindings(outcome.revision.revisionId).single().programDayId
        )
        assertTrue(
            "the copied-from revision is untouched: it still states no source at all",
            rig.storedTargetSource(sourceRevision.revisionId) is TargetScheduleSourceRead.Missing
        )
    }

    // ---------------------------------------------------------------- 6./7. edit

    @Test
    fun aStructuralEditStoresTheNewlySuppliedSourceOnTheNewRevision() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId

        // The first structural edit states a source, so "the previous revision's source is unchanged"
        // is a comparison against a real stored value rather than against an absence.
        val firstAuthoring = authoringOf(
            rules = listOf(rule("rule-first", "workout-first", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")))
        )
        val firstDraft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val firstEdit = rig.editor.editor(firstDraft)
            .addingDay(type = ProgramDayType.REST, name = "Day off")
            .draft
        val firstOutcome = savedOutcome(rig.service.save(firstEdit, targetSchedule = firstAuthoring))
        val firstRevision = firstOutcome.revision.revisionId
        val storedFirst = rig.storedTargetRules(firstRevision)
        assertEquals("the first revision states its own source", firstAuthoring.rules, storedFirst)

        // …and now a second structural edit with a *different* stated source.
        val secondDraft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val secondEdit = rig.editor.editor(secondDraft)
            .addingDay(type = ProgramDayType.REST, name = "Another day off")
            .draft
        val secondAuthoring = authoringOf(
            rules = listOf(
                rule("rule-second-a", "workout-a", ScheduleCadence.SessionsPerWeek(2), LocalDate.parse("2026-10-12")),
                rule("rule-second-b", "workout-b", ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.TUESDAY)), LocalDate.parse("2026-10-13"))
            )
        )

        val secondOutcome = savedOutcome(rig.service.save(secondEdit, targetSchedule = secondAuthoring))

        assertNotEquals(
            "a structural edit created a *new* revision, not a second claim on the first",
            firstRevision,
            secondOutcome.revision.revisionId
        )
        assertEquals(
            "the new revision states the newly supplied source",
            secondAuthoring.rules,
            rig.storedTargetRules(secondOutcome.revision.revisionId)
        )
        assertEquals(
            "and the previous revision's source is unchanged, byte for byte",
            storedFirst,
            rig.storedTargetRules(firstRevision)
        )
        assertEquals(
            "its stored source still names the revision it was written against, not the new one",
            firstRevision,
            (rig.storedTargetSource(firstRevision) as TargetScheduleSourceRead.Source).source.revisionId
        )
    }

    // ---------------------------------------------------------------- 8. omitted authoring

    @Test
    fun omittingTheAuthoringLeavesTheRevisionWithoutASourceRatherThanAnEmptyOne() = runBlocking {
        val outcome = savedOutcome(
            rig.service.save(rig.draftOf("No target"), plannedStartDate = null, targetSchedule = null)
        )

        val read = rig.storedTargetSource(outcome.revision.revisionId)
        assertTrue(
            "a revision whose author stated no target semantics reads as the typed absence, not as an " +
                "empty source that claims it has none — got: $read",
            read is TargetScheduleSourceRead.Missing
        )
        assertEquals(
            "and not one target row was written for it",
            0,
            rig.database.count("program_target_schedule_rule")
        )
    }

    @Test
    fun aNoOpEditThatStatesAnAuthoringStoresNoSourceBecauseItCreatesNoRevision() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId
        val revisionBefore = rig.currentRevision(programId)!!
        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }

        val result = rig.service.save(
            draft,
            targetSchedule = authoringOf(
                rules = listOf(rule("rule-noop", "workout-noop", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")))
            )
        )

        assertTrue(
            "saving an unchanged draft creates no revision (§6), so there is no revision to own a source",
            (result as ProgramEditorResult.Success).value is ProgramSaveOutcome.NothingToChange
        )
        assertEquals(
            "and the revision it already had states no source",
            TargetScheduleSourceRead.Missing(revisionBefore.revisionId),
            rig.storedTargetSource(revisionBefore.revisionId)
        )
    }

    // ---------------------------------------------------------------- 9. no legacy schedule is consulted

    @Test
    fun noTargetFactIsManufacturedFromTheDraftsLegacySchedule() = runBlocking {
        // The draft states a rich legacy `FixedWeekdays` schedule. If any of it leaked into the target
        // source, the stored rules would carry a weekday-derived cadence or an invented identity; the
        // assertion is that the source is exactly the two stated rules and nothing else.
        val draft = rig.draftOf(
            "Legacy schedule, explicit target",
            schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        )
        val authoring = authoringOf(
            rules = listOf(rule("rule-only", "workout-only", ScheduleCadence.EveryNDays(5), LocalDate.parse("2026-11-11")))
        )

        val outcome = savedOutcome(
            rig.service.save(draft, plannedStartDate = LocalDate.parse("2026-10-07"), targetSchedule = authoring)
        )

        assertEquals(
            "the stored source is the stated rules and nothing the legacy schedule could have supplied",
            authoring.rules,
            rig.storedTargetRules(outcome.revision.revisionId)
        )
        assertEquals(
            "and no binding was manufactured from the plan's days either",
            emptyList<TargetProgramDayBinding>(),
            rig.storedTargetBindings(outcome.revision.revisionId)
        )
    }

    @Test
    fun aCreateWithNoAuthoringStoresNoSourceEvenThoughItsLegacyScheduleIsRich() = runBlocking {
        val outcome = savedOutcome(
            rig.service.save(
                rig.draftOf(
                    "Rich schedule, no target",
                    schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY))
                ),
                plannedStartDate = null,
                targetSchedule = null
            )
        )

        assertEquals(
            "a legacy schedule is never a source: the revision states nothing and reads as absent",
            TargetScheduleSourceRead.Missing(outcome.revision.revisionId),
            rig.storedTargetSource(outcome.revision.revisionId)
        )
    }

    // ---------------------------------------------------------------- 10. atomicity

    @Test
    fun aRefusedSourceRollsTheWholeCreationUnitBack() = runBlocking {
        val before = rig.tableCounts()
        val targetBefore = targetTableCounts()
        // A duplicate rule identity is one of Stage 18's typed refusals, raised by the repository from
        // inside the save's transaction — so at the moment it fires the Program and its first revision
        // genuinely exist and must be rolled back with it.
        val authoring = authoringOf(
            rules = listOf(
                rule("rule-dup", "workout-a", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")),
                rule("rule-dup", "workout-b", ScheduleCadence.Daily, LocalDate.parse("2026-10-06"))
            )
        )

        val result = try {
            rig.service.save(rig.draftOf("Refused"), plannedStartDate = null, targetSchedule = authoring)
        } finally {
            rig.faults.clear()
        }

        assertTrue("a refused source is a failure, never a silent success", result is ProgramEditorResult.Failed)
        assertEquals(
            "and the refusal is Stage 18's own typed one, not a repair",
            "duplicate target schedule source rule identity 'rule-dup' at rule 1",
            (result as ProgramEditorResult.Failed).cause?.message
        )
        assertEquals("not one row of any Program table moved", before, rig.tableCounts())
        assertEquals("and no target row was written either", targetBefore, targetTableCounts())
        assertEquals(0, rig.tableCounts().getValue("program"))
    }

    @Test
    fun aFailedSourceWriteRollsTheCreationUnitBackAndLeavesNoOrphanedSource() = runBlocking {
        val before = rig.tableCounts()
        val targetBefore = targetTableCounts()
        val draft = rig.draftOf("Faulted source")
        val authoring = authoringOf(
            rules = listOf(rule("rule-fault", "workout-fault", ScheduleCadence.Daily, LocalDate.parse("2026-10-05"))),
            bindings = listOf(
                TargetScheduleAuthoringBinding("workout-fault", draft.days.single().programDayId)
            )
        )
        // The fault lands on the *source's* second write, which is the save unit's last leg: the
        // Program, its revision, its plan, its initial slots and the source's own rule rows have all
        // been written by then, so a rollback here is a real claim rather than "nothing ran".
        rig.faults.failTargetBindingInsert = true

        val result = try {
            rig.service.save(draft, plannedStartDate = null, targetSchedule = authoring)
        } finally {
            rig.faults.failTargetBindingInsert = false
        }

        assertTrue("the planted failure propagates", result is ProgramEditorResult.Failed)
        assertEquals(
            "the whole creation unit is rolled back: no Program, no revision and no slot",
            before,
            rig.tableCounts()
        )
        assertEquals(
            "and no orphaned source survives it — not even the rule rows the fault came after",
            targetBefore,
            targetTableCounts()
        )

        // …and clearing the fault lets the same save land, so the assertion is a rollback and not a
        // code path that never writes.
        val outcome = savedOutcome(
            rig.service.save(draft, plannedStartDate = null, targetSchedule = authoring)
        )
        assertEquals(1, rig.storedTargetRules(outcome.revision.revisionId).size)
    }

    @Test
    fun aFailedSourceWriteRollsAStructuralEditBackWithItsRevision() = runBlocking {
        rig.createGraph()
        val programId = rig.graph.program.programId
        val revisionBefore = rig.currentRevision(programId)!!
        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val edited = rig.editor.editor(draft).addingDay(type = ProgramDayType.REST, name = "Day off").draft
        val countsBefore = rig.tableCounts()
        val targetBefore = targetTableCounts()
        val authoring = authoringOf(
            rules = listOf(rule("rule-edit-fault", "w", ScheduleCadence.Daily, LocalDate.parse("2026-10-05"))),
            bindings = listOf(TargetScheduleAuthoringBinding("w", edited.days.first().programDayId))
        )
        rig.faults.failTargetBindingInsert = true

        val result = try {
            rig.service.save(edited, targetSchedule = authoring)
        } finally {
            rig.faults.failTargetBindingInsert = false
        }

        assertTrue("the failure surfaces as §28's SYSTEM_FAILURE", result is ProgramEditorResult.Failed)
        assertEquals(
            "the revision and the source roll back together — the table set is exactly what it was",
            countsBefore,
            rig.tableCounts()
        )
        assertEquals("and no source row survives", targetBefore, targetTableCounts())
        assertEquals(
            "so the Program still points at the revision it had",
            revisionBefore.revisionId,
            rig.currentRevision(programId)!!.revisionId
        )
    }

    // ---------------------------------------------------------------- 11. Stage 18's refusals

    @Test
    fun anInvalidSourceClaimStillFailsThroughStageEighteensTypedRefusals() = runBlocking {
        val draft = rig.draftOf("Invalid claims")
        val cases = listOf<Pair<String, TargetScheduleAuthoring>>(
            "a blank rule identity" to authoringOf(
                rules = listOf(rule("  ", "workout", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")))
            ),
            "a blank workout identity" to authoringOf(
                rules = listOf(rule("rule", "  ", ScheduleCadence.Daily, LocalDate.parse("2026-10-05")))
            ),
            "a duplicate workout binding" to authoringOf(
                rules = listOf(rule("rule", "workout", ScheduleCadence.Daily, LocalDate.parse("2026-10-05"))),
                bindings = listOf(
                    TargetScheduleAuthoringBinding("workout", draft.days.single().programDayId),
                    TargetScheduleAuthoringBinding("workout", draft.days.single().programDayId)
                )
            ),
            "an authoring that states no rule at all" to authoringOf(rules = emptyList())
        )

        for ((claim, authoring) in cases) {
            val before = rig.tableCounts()
            val result = rig.service.save(draft, plannedStartDate = null, targetSchedule = authoring)

            assertTrue("$claim is refused", result is ProgramEditorResult.Failed)
            assertEquals("$claim wrote nothing at all", before, rig.tableCounts())
        }
    }

    @Test
    fun aBindingToAPlanDayTheSavedRevisionDoesNotCarryIsRefusedRatherThanGuessed() = runBlocking {
        val before = rig.tableCounts()
        val authoring = authoringOf(
            rules = listOf(rule("rule", "workout", ScheduleCadence.Daily, LocalDate.parse("2026-10-05"))),
            // A drafted handle that is not one of this draft's days.
            bindings = listOf(TargetScheduleAuthoringBinding("workout", ProgramDayId("a-day-this-draft-never-had")))
        )

        val result = rig.service.save(rig.draftOf("Bad binding"), plannedStartDate = null, targetSchedule = authoring)

        assertTrue("a binding to a day the saved revision does not carry is refused", result is ProgramEditorResult.Failed)
        assertTrue(
            "and it is the authoring's own typed refusal, naming the workout and the day",
            (result as ProgramEditorResult.Failed).cause is TargetScheduleAuthoringException.ProgramDayNotInTheSavedRevision
        )
        assertEquals("and nothing was written", before, rig.tableCounts())
    }

    // ---------------------------------------------------------------- the mechanism

    /**
     * Every target-source table's row count, so "the unit left nothing behind" is asserted over the
     * target tables as well as the legacy ones the rig's [ProgramSaveRig.tableCounts] already covers.
     */
    private fun targetTableCounts(): Map<String, Int> = listOf(
        "program_target_schedule_rule",
        "program_target_program_day_binding"
    ).associateWith { table -> rig.database.count(table) }

    private fun authoringOf(
        rules: List<TargetScheduleDefinition>,
        bindings: List<TargetScheduleAuthoringBinding> = emptyList()
    ) = TargetScheduleAuthoring(rules = rules, programDayBindings = bindings)

    private fun rule(
        ruleId: String,
        workoutId: String,
        cadence: ScheduleCadence,
        anchor: LocalDate
    ) = TargetScheduleDefinition(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchor
    )

    private fun savedOutcome(
        result: ProgramEditorResult<ProgramSaveOutcome>
    ): ProgramSaveOutcome.RevisionSaved {
        assertTrue("the save succeeded, got: $result", result is ProgramEditorResult.Success)
        val outcome = (result as ProgramEditorResult.Success).value
        assertTrue("this suite measures created revisions, got: $outcome",
            outcome is ProgramSaveOutcome.RevisionSaved)
        return outcome as ProgramSaveOutcome.RevisionSaved
    }
}
