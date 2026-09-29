package com.monkfitness.app.domain.usecase

import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.common.ProgramExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * §30 step 22 on a real SQLite engine: **target schedule is revisioned Program behaviour**.
 *
 * ```text
 * no structural change + no target change   → no Revision
 * structural change + target unchanged      → new Revision, source carried forward
 * structural change + target replaced       → new Revision, new source on it
 * target-only change                        → new Revision, same Program, same structure
 * target explicitly cleared                 → new Revision with no target source
 * previous Revision                         → immutable, its source untouched
 * ```
 *
 * Everything runs over the data-access rig's real engine with production repositories, the production
 * editor, the production Scheduler and the production save boundary. Only the clock, the identity
 * generator and the calendar are stated values, because a test cannot ask the device for them. So
 * "the new revision is current", "the previous revision's source is byte-identical", "the new revision
 * got fresh plan-day identities" and "a failed source write rolled the revision back" are statements
 * about storage, not about a mock's call log.
 *
 * ```text
 *  A. a target-only replacement creates a new revision with the new source, leaving the old one intact
 *  B. a target-only clear creates a new revision stating no source, leaving the old one intact
 *  C. a structural edit with Keep carries the source forward, re-identified
 *  D. a structural edit with Replace attaches the new source to the new revision only
 *  E. a structural edit with no explicit target change does not erase the source
 *  F. a no-op edit with Keep creates no revision
 *  G. a target-only Replace that states the same semantics creates no revision
 *  H. a failing source write rolls the new revision back
 *  I. a new revision gets fresh plan-day identities
 *  J. Start after a target-only revision reads the new current revision
 *  K. a target revision change leaves the legacy slots alone
 *  L. two Programs own structurally identical target sources independently
 * ```
 */
class TargetScheduleRevisionSemanticsIntegrationTest {

    private val rig = ProgramSaveRig(KEY)
    private val clock = MovableClock(STARTED_AT)
    private val ids = SequentialIds("s22")
    private var minted = 0

    @After
    fun close() {
        rig.close()
    }

    // ---------------------------------------------------------------- A. target-only replacement

    @Test
    fun aTargetOnlyReplacementMintsANewRevisionAndLeavesThePreviousSourceIntact() = runBlocking {
        val programId = createdProgramWithSource("A")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstSource = rig.storedTargetSource(firstRevision).source()
        val planBefore = rig.currentRevision(programId)!!.structure
        val factsBefore = rig.storedProgram(programId)
        val countsBefore = rig.tableCounts()

        val outcome = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-two", "workout-two", cadence = ScheduleCadence.Daily, anchor = ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-two", currentDayOne(programId)))
                )
            )
        ).saved()

        val secondRevision = outcome.revision.revisionId
        assertNotEquals("a target-only change is a revision-producing change", firstRevision, secondRevision)
        assertEquals(
            "and the new revision is the Program's current plan",
            secondRevision,
            rig.currentRevision(programId)!!.revisionId
        )
        assertEquals("the new revision states the new source", "rule-two", rig.storedTargetSource(secondRevision).source().rules.single().ruleId)
        assertEquals(
            "and the previous revision still states its own source, byte for byte",
            firstSource,
            rig.storedTargetSource(firstRevision).source()
        )
        assertEquals(
            "its stored source still names the revision it was written against",
            firstRevision,
            rig.storedTargetSource(firstRevision).source().revisionId
        )
        assertEquals(
            "the structure is the Program's own, unchanged — this was a target-only change",
            planBefore,
            rig.currentRevision(programId)!!.structure
        )
        assertEquals(
            "the Program identity, its name and its lifecycle are untouched",
            factsBefore.copy(currentRevisionId = secondRevision, updatedAt = factsBefore.updatedAt),
            rig.storedProgram(programId)
        )
        assertEquals(
            "and exactly the rows one new revision needs were added — a revision, its plan day and " +
                "its plan element, and not one legacy opportunity",
            countsBefore + mapOf("program_revision" to 1, "program_day" to 1, "program_exercise" to 1).mapValues { (table, added) -> countsBefore.getValue(table) + added },
            rig.tableCounts()
        )
    }

    // ---------------------------------------------------------------- B. target-only clear

    @Test
    fun aTargetOnlyClearMintsARevisionThatStatesNoSourceAndLeavesTheOldOneIntact() = runBlocking {
        val programId = createdProgramWithSource("B")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstSource = rig.storedTargetSource(firstRevision).source()

        val outcome = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Clear
        ).saved()

        val secondRevision = outcome.revision.revisionId
        assertNotEquals("clearing is a revision-producing change", firstRevision, secondRevision)
        assertEquals(
            "the new revision states no target source at all — the typed absence, not an empty source",
            TargetScheduleSourceRead.Missing(secondRevision),
            rig.storedTargetSource(secondRevision)
        )
        assertEquals(
            "and the previous revision's source survives untouched: clearing deleted nothing",
            firstSource,
            rig.storedTargetSource(firstRevision).source()
        )
        assertEquals(
            "the target rule rows are still on disk, owned by the revision that stated them",
            1,
            rig.database.count("program_target_schedule_rule")
        )
    }

    // ---------------------------------------------------------------- C. structural edit + Keep

    @Test
    fun aStructuralEditWithKeepCarriesTheSourceForwardReIdentifiedOntoTheNewDays() = runBlocking {
        val programId = createdProgramWithSource("C")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstSource = rig.storedTargetSource(firstRevision).source()
        val firstDay = firstSource.programDayBindings.single().programDayId

        val outcome = rig.service.save(
            editedDraft(programId),
            targetChange = TargetScheduleRevisionChange.Keep
        ).saved()

        val secondRevision = outcome.revision.revisionId
        assertNotEquals("the structural change minted a new revision", firstRevision, secondRevision)
        val carried = rig.storedTargetSource(secondRevision).source()
        assertEquals(
            "the new revision states the same rules, verbatim",
            firstSource.rules,
            carried.rules
        )
        assertEquals(
            "and the same workout presents a plan day",
            firstSource.programDayBindings.single().workoutId,
            carried.programDayBindings.single().workoutId
        )
        assertNotEquals(
            "…but through the editor-minted identity for that day, not the old one copied over",
            firstDay,
            carried.programDayBindings.single().programDayId
        )
        assertEquals(
            "which is the correspondence the save itself reported",
            carried.programDayBindings.single().programDayId,
            outcome.mintedProgramDays[firstDay]
        )
        assertEquals(
            "and the previous revision's source is byte-identical",
            firstSource,
            rig.storedTargetSource(firstRevision).source()
        )
    }

    // ---------------------------------------------------------------- D. structural edit + Replace

    @Test
    fun aStructuralEditWithReplaceAttachesTheNewSourceToTheNewRevisionOnly() = runBlocking {
        val programId = createdProgramWithSource("D")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstSource = rig.storedTargetSource(firstRevision).source()
        val draft = editedDraft(programId)

        val outcome = rig.service.save(
            draft,
            targetChange = TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-d", "workout-d", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(
                        TargetScheduleAuthoringBinding("workout-d", draft.days.last().programDayId)
                    )
                )
            )
        ).saved()

        val secondRevision = outcome.revision.revisionId
        assertEquals(
            "the new revision states the newly supplied source",
            "rule-d",
            rig.storedTargetSource(secondRevision).source().rules.single().ruleId
        )
        assertEquals(
            "and the source is written against the new revision, never the one it replaced",
            secondRevision,
            rig.storedTargetSource(secondRevision).source().revisionId
        )
        assertEquals(
            "the previous revision's source is unchanged, byte for byte",
            firstSource,
            rig.storedTargetSource(firstRevision).source()
        )
    }

    // ---------------------------------------------------------------- E. omission on an edit

    @Test
    fun aStructuralEditThatSaysNothingAboutTargetSchedulingDoesNotEraseIt() = runBlocking {
        val programId = createdProgramWithSource("E")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstSource = rig.storedTargetSource(firstRevision).source()

        // No `targetChange` at all: this is the defect Stage 22 fixes. Under Stage 19 the absent
        // authoring meant "no source", and the Program silently lost its target scheduling here.
        val outcome = rig.service.save(editedDraft(programId)).saved()

        val secondRevision = outcome.revision.revisionId
        assertNotEquals("a structural change still mints its revision", firstRevision, secondRevision)
        val carried = rig.storedTargetSource(secondRevision).source()
        assertEquals(
            "the omission meant Keep, not Clear: the rules are carried forward",
            firstSource.rules,
            carried.rules
        )
        assertEquals(
            "with the same workout presenting a plan day of the new revision",
            firstSource.programDayBindings.single().workoutId,
            carried.programDayBindings.single().workoutId
        )
    }

    // ---------------------------------------------------------------- F. no-op + Keep

    @Test
    fun aNoOpEditWithKeepCreatesNoRevisionAndTouchesNothing() = runBlocking {
        val programId = createdProgramWithSource("F")
        val revisionBefore = rig.currentRevision(programId)!!.revisionId
        val countsBefore = rig.tableCounts()

        val result = rig.service.save(unchangedDraft(programId), targetChange = TargetScheduleRevisionChange.Keep)

        assertTrue(
            "an unchanged structure is §6's no-op save, whatever the target statement says: $result",
            (result as ProgramEditorResult.Success).value is ProgramSaveOutcome.NothingToChange
        )
        assertEquals("the Program still points at the revision it had", revisionBefore, rig.currentRevision(programId)!!.revisionId)
        assertEquals("and not one row was written", countsBefore, rig.tableCounts())
    }

    @Test
    fun aFactsOnlyEditWithKeepCreatesNoRevisionAndLeavesTheSourceWhereItIs() = runBlocking {
        val programId = createdProgramWithSource("F2")
        val revisionBefore = rig.currentRevision(programId)!!.revisionId
        val sourceBefore = rig.storedTargetSource(revisionBefore).source()

        val result = rig.service.save(
            renamedDraft(programId, "Renamed"),
            targetChange = TargetScheduleRevisionChange.Keep
        )

        assertTrue(
            "a rename is §6's facts-only save, which creates no revision: $result",
            (result as ProgramEditorResult.Success).value is ProgramSaveOutcome.FactsSaved
        )
        assertEquals("the revision is unchanged", revisionBefore, rig.currentRevision(programId)!!.revisionId)
        assertEquals("and its target source is where it was", sourceBefore, rig.storedTargetSource(revisionBefore).source())
    }

    // ---------------------------------------------------------------- G. an identical Replace

    @Test
    fun aTargetOnlyReplacementThatStatesTheSameSemanticsCreatesNoRevision() = runBlocking {
        val programId = createdProgramWithSource("G")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val stored = rig.storedTargetSource(firstRevision).source()
        val countsBefore = rig.tableCounts()

        // The identical case, stated against the **current** revision's day identity — the handle a
        // caller can honestly hold. A new revision would give that day a fresh identity, so this is
        // exactly the case an identity comparison gets wrong and a semantic one gets right.
        val result = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = stored.rules,
                    bindings = stored.programDayBindings.map { binding ->
                        TargetScheduleAuthoringBinding(binding.workoutId, binding.programDayId)
                    }
                )
            )
        )

        assertTrue(
            "stating what is already stored changes nothing: $result",
            (result as ProgramEditorResult.Success).value is ProgramSaveOutcome.NothingToChange
        )
        assertEquals("the Program still points at the revision it had", firstRevision, rig.currentRevision(programId)!!.revisionId)
        assertEquals("and not one row was written", countsBefore, rig.tableCounts())
    }

    @Test
    fun aTargetOnlyReplacementThatReordersTheSameRulesCreatesNoRevision() = runBlocking {
        val programId = createdProgramWithSource("G2")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val day = rig.currentRevision(programId)!!.days.first().programDayId

        // Two rules, stored in one order and stated back in the other: the same *semantics*, and the
        // comparison is order-insensitive by rule identity rather than an accident of a DAO's ORDER BY.
        rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(
                        rule("rule-g2-a", "workout-a", ScheduleCadence.Daily, ANCHOR),
                        rule("rule-g2-b", "workout-b", ScheduleCadence.EveryNDays(2), ANCHOR)
                    )
                )
            )
        ).saved()
        val secondRevision = rig.currentRevision(programId)!!.revisionId
        val stored = rig.storedTargetSource(secondRevision).source()

        val result = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = stored.rules.reversed(),
                    bindings = stored.programDayBindings.map {
                        TargetScheduleAuthoringBinding(it.workoutId, it.programDayId)
                    }
                )
            )
        )

        assertTrue(
            "the same rules in another order state the same semantics: $result",
            (result as ProgramEditorResult.Success).value is ProgramSaveOutcome.NothingToChange
        )
        assertEquals("no revision was minted", secondRevision, rig.currentRevision(programId)!!.revisionId)
        assertTrue("and the day really was re-identified by the previous change", day != stored.programDayBindings.firstOrNull()?.programDayId)
    }

    // ---------------------------------------------------------------- H. atomicity

    @Test
    fun aFailingSourceWriteRollsTheTargetOnlyRevisionBackWithIt() = runBlocking {
        val programId = createdProgramWithSource("H")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val sourceBefore = rig.storedTargetSource(firstRevision).source()
        val countsBefore = rig.tableCounts()

        // The fault goes on the *binding* insert, which follows the rule insert, so at the moment it
        // throws the new revision and its rule rows genuinely exist on disk. A fault on the first
        // statement would prove only that the unit never started.
        rig.faults.failTargetBindingInsert = true
        val result = try {
            rig.service.saveTargetScheduleChange(
                programId,
                TargetScheduleRevisionChange.Replace(
                    authoring(
                        rules = listOf(rule("rule-h", "workout-h", ScheduleCadence.Daily, ANCHOR)),
                        bindings = listOf(
                            TargetScheduleAuthoringBinding("workout-h", currentDayOne(programId))
                        )
                    )
                )
            )
        } finally {
            rig.faults.failTargetBindingInsert = false
        }

        assertTrue("the failure surfaces as §28's SYSTEM_FAILURE", result is ProgramEditorResult.Failed)
        assertEquals(
            "the whole unit rolled back — the table set is exactly what it was, rule rows included",
            countsBefore,
            rig.tableCounts()
        )
        assertEquals(
            "so the Program still points at the revision it had",
            firstRevision,
            rig.currentRevision(programId)!!.revisionId
        )
        assertEquals(
            "and that revision's target source is still readable, unchanged",
            sourceBefore,
            rig.storedTargetSource(firstRevision).source()
        )

        // …and clearing the fault lets the same change land, so the assertion above is a rollback and
        // not a code path that never writes.
        val landed = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-h", "workout-h", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-h", currentDayOne(programId)))
                )
            )
        ).saved()
        assertEquals("rule-h", rig.storedTargetSource(landed.revision.revisionId).source().rules.single().ruleId)
    }

    // ---------------------------------------------------------------- I. fresh identities

    @Test
    fun everyNewRevisionGetsFreshPlanDayIdentitiesAndNoOldBindingIdentityIsReused() = runBlocking {
        val programId = createdProgramWithSource("I")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        val firstDay = rig.storedTargetSource(firstRevision).source().programDayBindings.single().programDayId

        val second = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-i", "workout-i", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-i", firstDay))
                )
            )
        ).saved()
        val secondDay = rig.storedTargetSource(second.revision.revisionId).source()
            .programDayBindings.single().programDayId

        assertNotEquals("§6 re-identifies a plan day on every revision", firstDay, secondDay)
        assertEquals(
            "and the re-identification goes through the correspondence the editor reported",
            secondDay,
            second.mintedProgramDays[firstDay]
        )
        val daysOfNewRevision = rig.currentRevision(programId)!!.days.map { it.programDayId }
        assertTrue(
            "the new revision's own plan days are the ones the binding names",
            secondDay in daysOfNewRevision
        )
        assertTrue(
            "and the old identity is not among them: a revision never reuses a row it replaces",
            firstDay !in daysOfNewRevision
        )
    }

    // ---------------------------------------------------------------- J. Start sees the new revision

    @Test
    fun aStartAfterATargetOnlyRevisionReadsTheNewCurrentRevisionSource() = runBlocking {
        val programId = createdProgramWithSource("J")
        val firstRevision = rig.currentRevision(programId)!!.revisionId
        assertEquals("the first revision's source is what a start would read", "rule-J", rig.storedTargetSource(firstRevision).source().rules.single().ruleId)

        val second = rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-j-b", "workout-j-b", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(
                        TargetScheduleAuthoringBinding("workout-j-b", currentDayOne(programId))
                    )
                )
            )
        ).saved()
        assertEquals(
            "the second revision is the one the Program points at",
            second.revision.revisionId,
            rig.currentRevision(programId)!!.revisionId
        )

        // Stage 21's path, unchanged: ProgramStartService → TargetScheduleProductionConsumer. It
        // must now see the *new* revision's source, and the new revision's plan days, because that is
        // what the Program's current revision names.
        val started = startService().start(programId).started()
        assertEquals(
            "the pass planned against the revision the target-only change created",
            second.revision.revisionId,
            started.targetScheduling.input.revisionId
        )
        assertEquals(
            "and read that revision's source — the new rule, not the superseded one",
            "rule-j-b",
            started.targetScheduling.input.scheduleDefinitions.single().ruleId
        )
        assertEquals(
            "whose workout is the new one, so the produced target slots present the new workout's plan day",
            30,
            started.targetScheduling.result.applicationResult.persistenceResult.created.size
        )
        assertEquals(
            "…and every one of them is a target slot of the new revision",
            listOf(second.revision.revisionId),
            started.targetScheduling.result.applicationResult.persistenceResult.created
                .map { it.revisionId }.distinct()
        )
    }

    // ---------------------------------------------------------------- K. legacy isolation

    @Test
    fun aTargetRevisionChangeLeavesTheLegacySlotsExactlyAsTheyWere() = runBlocking {
        val programId = createdProgramWithSource("K")
        val slotsBefore = rig.slots(programId)

        rig.service.saveTargetScheduleChange(
            programId,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-k", "workout-k", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-k", currentDayOne(programId)))
                )
            )
        ).saved()

        assertEquals(
            "a target revision change is not a legacy schedule change: not one opportunity is added, " +
                "removed or rewritten",
            slotsBefore,
            rig.slots(programId)
        )
        assertEquals(
            "and the new revision presents no legacy opportunity of its own yet",
            0,
            rig.slotsOfRevision(rig.currentRevision(programId)!!.revisionId).size
        )
    }

    // ---------------------------------------------------------------- L. cross-Program isolation

    @Test
    fun twoProgramsOwnStructurallyIdenticalTargetSourcesIndependently() = runBlocking {
        val first = createdProgramWithSource("L1", programName = "First")
        val second = createdProgramWithSource("L1", programName = "Second", dayId = "draft-day-L2")
        assertNotEquals("two distinct Programs", first, second)

        // Byte-identical rules and a binding on each Program's *own* first day: the only difference is
        // which revision each names.
        rig.service.saveTargetScheduleChange(
            second,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-shared", "workout-shared", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-shared", currentDayOne(second)))
                )
            )
        ).saved()

        // …and the first Program states the very same rule on its own current revision.
        rig.service.saveTargetScheduleChange(
            first,
            TargetScheduleRevisionChange.Replace(
                authoring(
                    rules = listOf(rule("rule-shared", "workout-shared", ScheduleCadence.Daily, ANCHOR)),
                    bindings = listOf(TargetScheduleAuthoringBinding("workout-shared", currentDayOne(first)))
                )
            )
        ).saved()

        val firstSource = rig.storedTargetSource(rig.currentRevision(first)!!.revisionId).source()
        val secondSource = rig.storedTargetSource(rig.currentRevision(second)!!.revisionId).source()
        assertEquals("both state the same rules", firstSource.rules, secondSource.rules)
        assertNotEquals(
            "and each binds its own Program's plan day: no cross-Program consumption",
            firstSource.programDayBindings.single().programDayId,
            secondSource.programDayBindings.single().programDayId
        )
        assertEquals(
            "the first Program's revision count is its own",
            2,
            rig.revisionCount(first)
        )
        assertEquals("and so is the second's", 2, rig.revisionCount(second))
    }

    // ---------------------------------------------------------------- Create and Copy are untouched

    @Test
    fun aCreationThatStatesNoAuthoringStillMeansNoTargetSource() = runBlocking {
        val outcome = rig.service.save(rig.draftOf("No target")).saved()
        assertEquals(
            "§30 step 22 gave edits a Keep default; a creation's absence still means Missing",
            TargetScheduleSourceRead.Missing(outcome.revision.revisionId),
            rig.storedTargetSource(outcome.revision.revisionId)
        )
    }

    @Test
    fun aCreationRefusesAnEditStatementAndAnEditRefusesACreationAuthoring() = runBlocking {
        val creationCounts = rig.tableCounts()
        val onCreation = rig.service.save(
            rig.draftOf("Wrong leg"),
            targetChange = TargetScheduleRevisionChange.Clear
        )
        assertTrue(
            "Keep/Clear is an edit statement: a Program that does not exist yet has no source to clear",
            onCreation is ProgramEditorResult.Failed
        )
        assertTrue(
            "…and it is the typed refusal, not a message",
            (onCreation as ProgramEditorResult.Failed).cause
                is TargetScheduleRevisionChangeException.EditChangeOnACreation
        )
        assertEquals("and the creation wrote nothing at all", creationCounts, rig.tableCounts())

        val programId = createdProgramWithSource("legs")
        val countsBefore = rig.tableCounts()
        val onEdit = rig.service.save(
            unchangedDraft(programId),
            targetSchedule = authoring(rules = listOf(rule("x", "y", ScheduleCadence.Daily, ANCHOR)))
        )
        assertTrue(
            "an edit states its target scheduling with Keep/Replace/Clear, not with a creation authoring",
            onEdit is ProgramEditorResult.Failed
        )
        assertTrue(
            "…and that too is the typed refusal",
            (onEdit as ProgramEditorResult.Failed).cause
                is TargetScheduleRevisionChangeException.CreationAuthoringOnAnEdit
        )
        assertEquals("and the edit wrote nothing at all", countsBefore, rig.tableCounts())
    }

    @Test
    fun aCopyWithoutAStatedAuthoringHasNoTargetSourceAndGuessesNoBinding() = runBlocking {
        val source = createdProgramWithSource("copy-source", programName = "Source")
        val copyDraft = (rig.editor.copyDraft(source, "The copy") as ProgramEditorResult.Success).value
        val copyDraftedDays = copyDraft.days.map { it.programDayId }

        val outcome = rig.service.save(copyDraft).saved()

        assertEquals(
            "a copy states no target source unless its caller states one: there is no honest " +
                "correspondence from the source Program's bindings to the copy's freshly minted days, " +
                "so nothing is inferred",
            TargetScheduleSourceRead.Missing(outcome.revision.revisionId),
            rig.storedTargetSource(outcome.revision.revisionId)
        )
        assertEquals(
            "and not one target row was written for the copy",
            0,
            rig.database.count("program_target_schedule_rule") -
                rig.storedTargetSource(rig.currentRevision(source)!!.revisionId).source().rules.size
        )
        assertTrue(
            "the source Program's days are not the copy's — they were re-identified at save",
            copyDraftedDays.none { it in rig.currentRevision(outcome.program.programId)!!.days.map { day -> day.programDayId } }
        )
        assertEquals(
            "and the source Program's own source is untouched by the copy",
            "rule-copy-source",
            rig.storedTargetSource(rig.currentRevision(source)!!.revisionId).source().rules.single().ruleId
        )
    }

    @Test
    fun aKeepWhoseStoredBindingNamesAPlanDayTheDraftNoLongerCarriesIsRefused() = runBlocking {
        val programId = createdProgramWithSource("removed")
        val revisionBefore = rig.currentRevision(programId)!!.revisionId
        val sourceBefore = rig.storedTargetSource(revisionBefore).source()
        val countsBefore = rig.tableCounts()

        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        val boundDay = sourceBefore.programDayBindings.single().programDayId
        val removing = rig.editor.editor(draft).removingDay(boundDay).draft

        val result = rig.service.save(removing, targetChange = TargetScheduleRevisionChange.Keep)

        assertTrue(
            "a stored binding whose plan day the draft no longer carries cannot be carried forward: " +
                "which day that workout presents now is the caller's statement to make",
            result is ProgramEditorResult.Failed
        )
        assertTrue(
            "…and the refusal is typed and names the workout and the day",
            (result as ProgramEditorResult.Failed).cause
                is TargetScheduleAuthoringException.StoredProgramDayNotInTheDraft
        )
        assertEquals("nothing was written", countsBefore, rig.tableCounts())
        assertEquals("the Program still points at the revision it had", revisionBefore, rig.currentRevision(programId)!!.revisionId)
        assertEquals("and its source is still readable", sourceBefore, rig.storedTargetSource(revisionBefore).source())
    }

    // ---------------------------------------------------------------- the fixtures

    /**
     * A Program created through the save boundary that states a source on its first revision — the
     * precondition every case above starts from, so "the previous revision's source is unchanged" is
     * always a comparison against a real stored value.
     */
    private suspend fun createdProgramWithSource(
        key: String,
        programName: String = "Program $key",
        dayId: String = "draft-day-$key"
    ): ProgramId {
        val outcome = rig.service.save(
            rig.draftOf(programName, draftDayId = ProgramDayId(dayId)),
            plannedStartDate = LocalDate.parse("2026-10-05"),
            targetSchedule = authoring(
                rules = listOf(rule("rule-$key", "workout-$key", ScheduleCadence.Daily, ANCHOR)),
                bindings = listOf(
                    TargetScheduleAuthoringBinding("workout-$key", ProgramDayId(dayId))
                )
            )
        ).saved()
        return outcome.program.programId
    }

    /** The first plan day of the Program's **current** revision — the handle a caller can hold. */
    private suspend fun currentDayOne(programId: ProgramId): ProgramDayId =
        rig.currentRevision(programId)!!.days.first().programDayId

    /** A draft that changes the structure: one more plan day than the current revision carries. */
    private suspend fun editedDraft(programId: ProgramId): ProgramEditorDraft {
        val draft = rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }
        return rig.editor.editor(draft)
            .addingDay(type = ProgramDayType.REST, name = "An extra day off")
            .draft
    }

    /** A draft whose structure is the current revision's, unchanged. */
    private suspend fun unchangedDraft(programId: ProgramId): ProgramEditorDraft =
        rig.editor.editDraft(programId).let { (it as ProgramEditorResult.Success).value }

    /** A draft whose only change is the Program's name — §6's facts-only save. */
    private suspend fun renamedDraft(programId: ProgramId, name: String): ProgramEditorDraft =
        unchangedDraft(programId).copy(name = name)

    // ---- the Stage 21 Start path, composed exactly as the composition root composes it -----------

    private fun startService() = ProgramStartService(
        lifecycle = ProgramLifecycleService(
            programRepository = rig.programRepository,
            scheduleRepository = rig.scheduleRepository,
            sessionRepository = rig.sessionRepository,
            appStateRepository = rig.appStateRepository,
            planRepository = rig.planRepository,
            clock = clock,
            idGenerator = ids,
            standardProgramId = StandardProgram.programId,
            inTransaction = rig.transaction
        ),
        consumer = TargetScheduleProductionConsumer(
            programRepository = rig.programRepository,
            planRepository = rig.planRepository,
            sourceBridge = TargetScheduleSourceBridge(rig.targetSourceRepository),
            occurrenceRepository = rig.targetOccurrenceRepository,
            existingOccurrenceReader = TargetExistingOccurrenceReader(
                TargetOccurrenceExecutionReader(
                    occurrenceRepository = rig.targetOccurrenceRepository,
                    scheduleRepository = rig.scheduleRepository,
                    sessionRepository = rig.sessionRepository
                )
            ),
            inputAdapter = TargetScheduleInputAdapter(),
            orchestrator = TargetScheduleOrchestrator(
                TargetScheduleApplicationService(
                    TargetScheduleSlotPersister(
                        scheduleRepository = rig.scheduleRepository,
                        occurrenceRepository = rig.targetOccurrenceRepository,
                        idGenerator = IdGenerator { "s22-slot-${minted++}" },
                        inTransaction = rig.transaction
                    )
                )
            )
        ),
        scheduleRepository = rig.scheduleRepository,
        zone = ZONE
    )

    // ---- narrowings, so a refusal names itself ---------------------------------------------------

    private fun ProgramEditorResult<ProgramSaveOutcome>.saved(): ProgramSaveOutcome.RevisionSaved {
        val value = (this as? ProgramEditorResult.Success)?.value
            ?: throw AssertionError("expected a saved revision, got $this")
        return value as? ProgramSaveOutcome.RevisionSaved
            ?: throw AssertionError("expected a new revision, got $value")
    }

    private fun TargetScheduleSourceRead.source(): TargetScheduleSource =
        (this as? TargetScheduleSourceRead.Source)?.source
            ?: throw AssertionError("expected a stored target source, got $this")

    private fun ProgramStartResult.started(): ProgramStartResult.Started =
        this as? ProgramStartResult.Started
            ?: throw AssertionError("expected a started Program, got $this")

    // ---- the caller's own statements -------------------------------------------------------------

    private fun authoring(
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

    private companion object {
        const val KEY = "s22"
        val ZONE: ZoneId = ZoneId.of("UTC")
        val STARTED_AT = java.time.Instant.parse("2026-10-05T08:00:00Z")
        val ANCHOR: LocalDate = LocalDate.parse("2026-10-05")
    }
}
