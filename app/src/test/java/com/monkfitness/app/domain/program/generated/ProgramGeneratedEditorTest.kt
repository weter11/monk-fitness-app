package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDraftEditor
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramEditorRig
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramStructureAspect
import com.monkfitness.app.domain.program.differencesFrom
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.validation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 10's **Editor integration**, decided by the real thing.
 *
 * Everything here runs over the production editor service on the production DAOs against a real
 * SQLite engine, so the claims are about behaviour rather than about shape: `Generate` and
 * `Regenerate` write nothing at all, `Save` remains the only thing that creates a revision, and a
 * generated plan that is saved and reloaded describes exactly what the user was shown.
 *
 * The state that matters most is the one asserted first: **generation is a draft operation**. A
 * generate that persisted, or that moved a revision pointer, would break the whole §7 boundary, and
 * it is measured by comparing the row count of every table — the fifteen target tables and the ten
 * the app already shipped — before and after.
 */
class ProgramGeneratedEditorTest {

    /** The identity source a generated draft is built with, taken from the rig so it is readable. */
    private val ProgramEditorRig.draftIds: DraftIdSource
        get() = DraftIdSource { ids.newId() }

    // ------------------------------------------------------------------ generation persists nothing

    @Test
    fun generateAndRegenerateWriteNothingAtAll() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking { rig.createGraph() }
            val before = rig.tableCounts()
            val draft = draftFor(rig)

            val generated = ProgramGeneratedEditor(draft, rig.draftIds)
                .generate(GeneratedPlannerRig.request())
            val regenerated = ProgramGeneratedEditor(generated.draft, rig.draftIds)
                .regenerate(GeneratedPlannerRig.request())

            assertEquals(
                "a generation alters a draft and nothing else: not one row of any table moved (§7)",
                before,
                rig.tableCounts()
            )
            assertTrue(
                "and it produced a plan",
                generated.draft.days.isNotEmpty() && regenerated.draft.days.isNotEmpty()
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun aGenerateAndARegenerateCreateNoRevision() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val before = rig.revisionCount(programId)

                val draft = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request()).draft
                ProgramGeneratedEditor(draft, rig.draftIds).regenerate(GeneratedPlannerRig.request())

                assertEquals(
                    "neither operation mints a revision: only Save does (§6, §7)",
                    before,
                    rig.revisionCount(programId)
                )
            }
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ Save is what persists

    @Test
    fun aGeneratedPlanSavesAsExactlyOneRevisionAndComesBackWhole() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                // §7's `Build for me` entry path: a Program does not exist yet, so the generated plan
                // is its first revision and nothing of anybody else's is in it.
                val generated = ProgramGeneratedEditor(draftFor(rig), rig.draftIds)
                    .generate(GeneratedPlannerRig.request())

                val outcome = rig.service.save(generated.draft).value()
                val saved = outcome as? ProgramSaveOutcome.RevisionSaved
                    ?: throw AssertionError("a generated draft did not save: $outcome")
                val programId = saved.program.programId

                assertTrue(
                    "a generated draft saves like any other draft: exactly one new revision, and it " +
                        "creates the Program (§27)",
                    outcome.createdProgram
                )
                assertEquals(
                    "one save, one revision (§6: at most one)",
                    1,
                    saved.revisionsCreated
                )
                assertEquals(
                    "and the Program holds that one revision",
                    1,
                    rig.revisionCount(programId)
                )

                val stored = rig.currentRevision(programId)!!
                assertEquals(
                    "the saved revision holds the plan the user was shown, day for day and element " +
                        "for element",
                    generated.draft.days.map { day -> day.exercises.map { it.exerciseId } },
                    stored.days.map { day -> day.exercises.map { it.exerciseId } }
                )
                assertEquals(
                    "with the Goals & Focus configuration it was built for (§6, §8)",
                    generated.draft.focus,
                    stored.focus
                )
                assertEquals(
                    "and in the mode generation planned in (§2)",
                    ProgramMode.GENERATED,
                    stored.mode
                )
                assertEquals(
                    "each occurrence is its own row, and saving re-identified them as it always does",
                    stored.days.sumOf { it.exercises.size },
                    stored.days.flatMap { it.exercises }.map { it.programExerciseId }.distinct().size
                )
                assertEquals(
                    "the plan's elements are the generator's own, so a later regeneration may " +
                        "reconcile them (§7)",
                    listOf(ProgramExerciseOrigin.GENERATED),
                    stored.days.flatMap { it.exercises }.map { it.origin }.distinct()
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun savingTheSameGeneratedDraftTwiceCreatesOneRevision() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val generated = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request())

                rig.service.save(generated.draft).value()
                val afterFirst = rig.revisionCount(programId)
                rig.service.save(generated.draft).value()

                assertEquals(
                    "a save whose plan is already stored is a no-op (§6), generated or not",
                    afterFirst,
                    rig.revisionCount(programId)
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun aSaveAfterARegenerationThatChangedNothingIsStillANoOp() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val generated = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request())
                rig.service.save(generated.draft).value()
                val stored = rig.currentRevision(programId)!!

                // Re-open the saved revision, regenerate the same request, and save: reconciliation
                // must change nothing, so the second save writes nothing at all.
                val reopened = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).regenerate(GeneratedPlannerRig.request())
                val outcome = rig.service.save(reopened.draft).value()

                assertEquals(
                    "regenerating an unchanged plan leaves the structure identical, so §6's no-op " +
                        "save rule applies unchanged",
                    ProgramSaveOutcome.NothingToChange(outcome.program, stored.revisionId),
                    outcome
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun changingOnlyTheGoalCreatesAStructuralRevision() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val generated = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request())
                rig.service.save(generated.draft).value()
                val first = rig.currentRevision(programId)!!

                // The generated plan, with its **goal changed and nothing else** — no regeneration:
                // the same days, the same exercises, the same prescriptions, one configuration fact
                // different. §6 lists goals/focus among the structural changes.
                val refocused = generated.draft.copy(
                    focus = FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL))
                )
                assertEquals(
                    "the plan's structure differs in exactly one dimension — the configuration",
                    listOf(ProgramStructureAspect.FOCUS),
                    refocused.structure.differencesFrom(first.structure)
                )
                assertEquals(
                    "and the days themselves are untouched: this is a goal-only edit",
                    first.days.map { day -> day.exercises.map { it.exerciseId } },
                    refocused.days.map { day -> day.exercises.map { it.exerciseId } }
                )

                val outcome = rig.service.save(refocused).value()
                assertTrue(
                    "so saving it creates a revision, even though not one exercise changed",
                    outcome is ProgramSaveOutcome.RevisionSaved
                )
                assertEquals(
                    "the Program now holds the revision it was created with, the generated one, and " +
                        "this one — one revision per save, no more (§6)",
                    3,
                    rig.revisionCount(programId)
                )
                assertEquals(
                    "the superseded revision still states the goal it was built for, and its plan is " +
                        "untouched (§6: a revision is immutable)",
                    FocusPlan.Balanced,
                    rig.revision(first.revisionId)!!.focus
                )
                assertEquals(first.days, rig.revision(first.revisionId)!!.days)
                assertEquals(
                    "and the new revision states the new configuration",
                    FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL)),
                    rig.currentRevision(programId)!!.focus
                )
                assertEquals(
                    "with the plan it was saved with, day for day",
                    refocused.days.map { day -> day.exercises.map { it.exerciseId } },
                    rig.currentRevision(programId)!!.days.map { day -> day.exercises.map { it.exerciseId } }
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun generatingIntoAnExistingProgramPreservesWhatTheUserOwns() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val stored = rig.currentRevision(programId)!!

                // The Program's own plan is the *user's*: generating into it reconciles rather than
                // replacing, so the plan's days keep their content and the plan's own elements are
                // arranged around it. (An owner decision — see
                // `docs/PROGRAM_GENERATED_PLANNER.md`: `Generate` and `Regenerate` are one
                // reconciliation, so which button the user pressed cannot decide whose content
                // survives.)
                val generated = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request())

                val userOwned = stored.days.flatMap { it.exercises }
                    .filter { it.origin == ProgramExerciseOrigin.USER_AUTHORED || it.isPinned }
                assertTrue(
                    "the rig's plan does hold content the user owns, so the rule is measured",
                    userOwned.isNotEmpty()
                )

                userOwned.forEach { element ->
                    assertTrue(
                        "the user's own element '${element.exerciseId}' is still in the plan, as " +
                            "itself — same identity, same occurrence (§7)",
                        generated.draft.days.flatMap { it.exercises }.any {
                            it.programExerciseId == element.programExerciseId &&
                                it.exerciseId == element.exerciseId
                        }
                    )
                }
                assertTrue(
                    "and the plan the generator produced joined it",
                    generated.draft.days.flatMap { it.exercises }
                        .any { it.origin == ProgramExerciseOrigin.GENERATED }
                )
                assertEquals(
                    "nothing of the user's was dropped: the only elements a reconciliation may drop " +
                        "are the generator's own, and it did not have to drop the user's (§7)",
                    emptyList<String>(),
                    generated.reconciliation.ofKind(ChangeKind.DROPPED)
                        .mapNotNull { it.programExerciseId }
                        .filter { dropped ->
                            userOwned.any { it.programExerciseId == dropped }
                        }
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun aGenerationDoesNotTouchTheSchedulerSlotsItWasPlannedBeside() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId
                val before = rig.slots(programId)

                val generated = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request())
                rig.service.save(generated.draft).value()

                val after = rig.slots(programId)
                assertEquals(
                    "§20 keeps scheduling the Scheduler's decision: a generation plans days, not dates, " +
                        "and creates, moves, re-points and cancels no opportunity",
                    before.map { it.slotId to it.plannedFor to it.status },
                    after.map { it.slotId to it.plannedFor to it.status }
                )
                assertTrue(
                    "and the plan is longer than the slots that exist, because the horizon stays the " +
                        "scheduler's: ${generated.draft.days.size} days against ${after.size} slots",
                    generated.draft.days.size > after.size
                )
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun aStaleGeneratedDraftSavesRelativeToWhatIsStoredAndRaisesNoConflict() {
        val rig = ProgramEditorRig("generated")
        try {
            runBlocking {
                rig.createGraph()
                val programId = rig.graph.program.programId

                // A draft generated from the Program's plan as it was…
                val stale = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(GeneratedPlannerRig.request()).draft

                // …then superseded by a save of its own generation, and a second save of a plan
                // built for a different goal.
                val afterFirstSave = rig.revisionCount(programId) + 1
                rig.service.save(stale).value()
                assertEquals(afterFirstSave, rig.revisionCount(programId))
                val firstSaved = rig.currentRevision(programId)!!

                val focused = ProgramGeneratedEditor(
                    rig.service.editDraft(programId).value(),
                    rig.draftIds
                ).generate(
                    GeneratedPlannerRig.request(
                        focus = FocusPlan.focused(setOf(Focus.POSTURE)),
                        candidates = listOf(
                            GeneratedPlannerRig.candidate(
                                "posture-wall-slide", "posture", setOf(Focus.POSTURE)
                            )
                        )
                    )
                ).draft
                val afterSecondSave = afterFirstSave + 1
                rig.service.save(focused).value()
                assertEquals(afterSecondSave, rig.revisionCount(programId))

                // Saving the stale draft now is measured against what is *stored*, not against the
                // revision it was opened from, and the answer is a revision rather than a refusal:
                // §6 and §7 keep editing a policy about stored state, and no RevisionConflict exists.
                val outcome = rig.service.save(stale).value()
                assertTrue(
                    "a stale generated draft saves relative to the current revision, with no " +
                        "RevisionConflict: ${outcome.revisionsCreated} revision(s) were created",
                    outcome is ProgramSaveOutcome.RevisionSaved
                )
                assertEquals(
                    "so the Program holds the revisions its saves asked for, and nothing was " +
                        "rewritten: ${rig.revisionCount(programId)} revisions",
                    afterSecondSave + 1,
                    rig.revisionCount(programId)
                )
                assertEquals(
                    "and the superseded revision still describes exactly what it described when it " +
                        "was saved — a stale save appends, it never rewrites (§6)",
                    firstSaved,
                    rig.revision(firstSaved.revisionId)
                )
            }
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ the draft, not the plan

    @Test
    fun aGeneratedDraftIsValidAndSaveableWithNothingAddedByHand() {
        val rig = ProgramEditorRig("generated")
        try {
            val generated = ProgramGeneratedEditor(draftFor(rig), rig.draftIds)
                .generate(GeneratedPlannerRig.request())

            assertEquals(
                "a generated draft is a draft a save can hold: no unfinished day, no missing revision " +
                    "base, nothing the user has to repair first",
                emptyList<String>(),
                generated.draft.validation().issues.map { it.message }
            )
            assertEquals(
                "and its days are numbered 1..n as a revision must hold them",
                (1..generated.draft.days.size).toList(),
                generated.draft.days.map { it.position }
            )
            assertEquals(
                "with the name the editor session gave it, so §7's Basics step is not what stands " +
                    "between the user and a save",
                "Generated program",
                generated.draft.name
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun theManualEditorIsStillTheOneThatEditsAGeneratedPlan() {
        val rig = ProgramEditorRig("generated")
        try {
            val generated = ProgramGeneratedEditor(draftFor(rig), rig.draftIds)
                .generate(GeneratedPlannerRig.request())
            val dayId = generated.draft.days.first().programDayId
            val elementId = generated.draft.days.first().exercises.first().programExerciseId

            // The manual editor's own default origin is USER_AUTHORED, which is exactly what
            // reconciliation reads to decide it may not replace an element; pinning is a second,
            // independent fact. Neither operation knows anything about generation.
            val edited = ProgramDraftEditor(generated.draft, rig.draftIds)
                .addingExercise(
                    programDayId = dayId,
                    exerciseId = "mobility-spine-wave",
                    prescription = generated.draft.days.first().exercises.first().prescription
                )
                .settingPinned(dayId, elementId, isPinned = true)
                .draft

            val regenerated = ProgramGeneratedEditor(edited, rig.draftIds)
                .regenerate(GeneratedPlannerRig.request())

            val firstDay = regenerated.draft.days.first().exercises
            assertTrue(
                "the element the user pinned is still there, still pinned (§7)",
                firstDay.any { it.programExerciseId == elementId && it.isPinned }
            )
            assertTrue(
                "and the exercise the user added is still on the day, as their own content",
                firstDay.any {
                    it.exerciseId == "mobility-spine-wave" &&
                        it.origin == ProgramExerciseOrigin.USER_AUTHORED
                }
            )
            assertEquals(
                "the user's own content comes first and the generator's follows around it (§8)",
                listOf(
                    ProgramExerciseOrigin.GENERATED,
                    ProgramExerciseOrigin.USER_AUTHORED
                ),
                firstDay.take(2).map { it.origin }
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A draft a user would generate into: named, generated, and not yet planned. */
    private fun draftFor(@Suppress("UNUSED_PARAMETER") rig: ProgramEditorRig): ProgramEditorDraft = ProgramEditorDraft(
        name = "Generated program",
        description = "built for me",
        mode = ProgramMode.GENERATED,
        schedule = ProgramSchedule.FlexiblePerWeek(3)
    )

    /** The value of a result the editor produced, failing loudly when it refused or failed. */
    private fun <T> ProgramEditorResult<T>.value(): T = when (this) {
        is ProgramEditorResult.Success -> value
        is ProgramEditorResult.Rejected -> throw AssertionError("the editor refused: $rejection")
        is ProgramEditorResult.Failed -> throw AssertionError("the editor failed", cause)
    }
}
