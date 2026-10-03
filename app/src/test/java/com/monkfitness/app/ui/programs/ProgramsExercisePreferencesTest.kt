package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 28's **exercise preference**, measured through the **controller** on the real engine — the only
 * place a claim about what the editor shows, what `Save` persists, or what `Generate` reads can be true or
 * false.
 *
 * ```text
 * the four operations   add, remove, move up, move down — each on the draft's own value
 * the rendered order    the draft's order, not a screen's copy of it
 * unknown ids           refused by the layer holding the catalogue, before the draft changes
 * Save                  persists the order; a preference-only change mints exactly one Revision
 * Generate              reads the draft's preference and never persists one
 * ```
 *
 * The exercise ids used here are read from the **real catalogue** the controller publishes, never
 * restated: an id invented by a test would fail for the wrong reason — the refusal under test is "this
 * exercise does not exist", which an invented id satisfies trivially.
 */
class ProgramsExercisePreferencesTest {

    private val rig = ProgramsRig("prefs")

    @After
    fun tearDown() {
        rig.close()
    }

    /** Real catalogue ids, in the order the controller published them. */
    private fun catalogueIds(): List<String> = rig.state.exerciseOptions.map { it.exerciseId }

    // ---------------------------------------------------------------- the four operations

    @Test
    fun theDraftStartsWithNoPreferenceAndNothingInventsOne() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        assertEquals(
            "a draft nobody configured states that the user named no preferred exercise — empty, which is " +
                "not the same claim as any ranking of the catalogue (§9)",
            ExercisePreference.NONE,
            rig.state.draft?.preferredExercises
        )
        assertTrue(
            "and it really is empty rather than populated with a default",
            rig.state.draft?.preferredExercises?.isEmpty == true
        )
    }

    @Test
    fun addingAnExerciseAppendsItAndNeverRanksItAboveWhatTheUserChose() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val (first, second) = catalogueIds().let { list -> list[0] to list[1] }

        rig.controller.preferDraftExercise(first)
        rig.controller.preferDraftExercise(second)

        assertEquals(
            "each add appends as the LEAST preferred: the screen may not invent a position the user did " +
                "not state, and the user then moves it deliberately",
            listOf(first, second),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun removingAnExerciseRemovesOnlyThatOne() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        val (first, second, third) = Triple(ids[0], ids[1], ids[2])
        ids.take(3).forEach { exerciseId -> rig.controller.preferDraftExercise(exerciseId) }

        rig.controller.unpreferDraftExercise(second)

        assertEquals(
            "remove the middle entry and the other two keep their positions — removing a preference is not " +
                "a re-ranking of the ones that remain",
            listOf(first, third),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun movingAnEntryChangesTheExactOrder() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        val (first, second, third) = Triple(ids[0], ids[1], ids[2])
        ids.take(3).forEach { exerciseId -> rig.controller.preferDraftExercise(exerciseId) }

        rig.controller.promoteDraftPreferredExercise(third)

        assertEquals(
            "move up moves exactly one place towards the front, and the other two keep their relative order",
            listOf(first, third, second),
            rig.state.draft?.preferredExercises?.exerciseIds
        )

        rig.controller.demoteDraftPreferredExercise(first)

        assertEquals(
            "and move down is the same operation the other way, which is what makes the two buttons one rule",
            listOf(third, first, second),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun movingAnEntryThatIsAlreadyAtItsEndChangesNothing() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        val (first, second) = ids[0] to ids[1]
        rig.controller.preferDraftExercise(first)
        rig.controller.preferDraftExercise(second)

        rig.controller.promoteDraftPreferredExercise(first)
        rig.controller.demoteDraftPreferredExercise(second)

        assertEquals(
            "the first entry has nowhere to move up to and the last none to move down to, and both are " +
                "no-ops rather than clamped rewrites — which is why the buttons are disabled at the ends " +
                "instead of swallowing the tap",
            listOf(first, second),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun preferringTheSameExerciseTwiceNeverProducesADuplicate() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val first = catalogueIds()[0]

        rig.controller.preferDraftExercise(first)
        rig.controller.preferDraftExercise(first)

        assertEquals(
            "the picker filters out what is already preferred, and the operation refuses a second entry — " +
                "so a duplicate would give 'most preferred' two answers at one position",
            listOf(first),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun anExerciseTheCatalogueDoesNotOfferIsRefusedBeforeTheDraftChanges() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        rig.controller.preferDraftExercise(catalogueIds()[0])

        val before = rig.state.draft?.preferredExercises

        rig.controller.preferDraftExercise("an-exercise-that-does-not-exist")

        assertEquals(
            "§5's exerciseId validation, applied to authoring: an id no shipped exercise answers to never " +
                "reaches the draft, because the controller is the layer that holds the catalogue",
            before,
            rig.state.draft?.preferredExercises
        )
    }

    @Test
    fun thePickerOffersExactlyWhatIsNotAlreadyPreferredAndRanksNothing() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        rig.controller.preferDraftExercise(ids[0])

        val offered = addablePreferenceOptions(
            preferred = rig.state.draft!!.preferredExercises,
            options = rig.state.exerciseOptions
        )

        assertEquals(
            "an exercise that is already preferred has nothing left to be added, so it is filtered out of " +
                "the picker rather than offered as an action that cannot happen",
            ids.drop(1),
            offered.map { option -> option.exerciseId }
        )
        assertEquals(
            "and the offered list keeps the catalogue's order, which is NOT a ranking — the order an " +
                "exercise appears in a picker says nothing about how much the user would rather train it",
            ids.size - 1,
            offered.size
        )
    }

    @Test
    fun theMoveButtonsAreDisabledAtTheEndsRatherThanSwallowingTheTap() {
        // Pure authoring rule, decided without a Compose harness: the two ends of the list.
        assertTrue(
            "the first entry can move down but has nowhere to move up to",
            !movementEnabled(1, 3).canMoveUp && movementEnabled(1, 3).canMoveDown
        )
        assertTrue(
            "a middle entry can move either way",
            movementEnabled(2, 3).canMoveUp && movementEnabled(2, 3).canMoveDown
        )
        assertTrue(
            "the last entry can move up but has nowhere to move down to",
            movementEnabled(3, 3).canMoveUp && !movementEnabled(3, 3).canMoveDown
        )
        assertTrue(
            "a single entry can move nowhere at all, in either direction",
            !movementEnabled(1, 1).canMoveUp && !movementEnabled(1, 1).canMoveDown
        )
    }

    // ---------------------------------------------------------------- Save, and revision semantics

    @Test
    fun savePersistsTheExactOrderAndReloadReturnsIt() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.MANUAL)
        val ids = catalogueIds()
        rig.controller.setDraftName("Preferred program")
        rig.controller.addDraftDay(ProgramDayType.TRAINING)
        // §20: a work day must prescribe something to be savable, so the fixture plants one element.
        // Without it the Save would be refused for an unrelated reason and this test would prove nothing
        // about the preference.
        rig.controller.addDraftElement(rig.state.draft!!.days[0].programDayId, ids[0])
        // Added in a deliberately non-alphabetical order so "persisted in the user's order" cannot pass
        // by accident: the stored list must be this exact sequence, not a sorted one.
        rig.controller.preferDraftExercise(ids[2])
        rig.controller.preferDraftExercise(ids[0])
        rig.controller.preferDraftExercise(ids[1])
        // `ids[2]` is already first after the three appends, so promoting it would be a correct no-op;
        // the last one added is the one with somewhere to move to.
        rig.controller.promoteDraftPreferredExercise(ids[1])

        rig.controller.saveDraft()
        // The UI row carries the id as text and `openEditDraft` takes text; the repository helpers take
        // the typed id. Both spellings are stated here rather than converted ad hoc at each call site.
        val programId = rig.state.rows.first { row -> row.name == "Preferred program" }.programId

        assertEquals(
            "Save persists the user's own order exactly — not sorted, not canonicalised, not filtered " +
                "against the plan",
            ExercisePreference.of(ids[2], ids[1], ids[0]),
            rig.storedPreference(ProgramId(programId))
        )

        rig.controller.openEditDraft(programId)

        assertEquals(
            "and reopening the Program returns the same ordering to the editor",
            listOf(ids[2], ids[1], ids[0]),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
    }

    @Test
    fun aPreferenceOnlyChangeCreatesARevisionAndANoOpChangeCreatesNone() = runBlocking {
        val programId = rig.createProgramThroughTheUi("Revision semantics")!!
        val revisionsBefore = rig.revisionCount(ProgramId(programId))

        rig.controller.openEditDraft(programId)
        rig.controller.preferDraftExercise(catalogueIds().first())
        rig.controller.saveDraft()

        assertEquals(
            "§6 makes a program-behavior change revision-creating, and a stated generation input is program " +
                "behaviour even when no exercise moved — so a preference-only Save mints exactly one",
            revisionsBefore + 1,
            rig.revisionCount(ProgramId(programId))
        )

        val revisionsAfterTheSave = rig.revisionCount(ProgramId(programId))

        rig.controller.openEditDraft(programId)
        rig.controller.preferDraftExercise(catalogueIds().first())
        rig.controller.saveDraft()

        assertEquals(
            "while re-saving the identical preference is a no-op and creates none (§6's no-op rule)",
            revisionsAfterTheSave,
            rig.revisionCount(ProgramId(programId))
        )
    }

    // ---------------------------------------------------------------- generation

    @Test
    fun generateReadsTheDraftsPreferenceAndNeverPersistsOne() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        rig.controller.preferDraftExercise(ids[1])
        rig.controller.preferDraftExercise(ids[0])

        rig.controller.generateDraft()

        assertEquals(
            "Generate alters only a draft (§7), and the preference survives the pass untouched — it is " +
                "the same value the next pass will be planned against",
            listOf(ids[1], ids[0]),
            rig.state.draft?.preferredExercises?.exerciseIds
        )
        assertEquals(
            "and nothing was persisted at all: Generate writes no Program and no Revision (§7 — it alters " +
                "only a draft), so the Programs in storage are exactly the ones that were already there",
            0,
            rig.storedPrograms().size
        )
    }

    @Test
    fun generateAndPreviewOverTheSameDraftSeeTheSamePreferenceSnapshot() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        val ids = catalogueIds()
        rig.controller.preferDraftExercise(ids[1])
        rig.controller.preferDraftExercise(ids[0])

        rig.controller.generateDraft()
        val afterGenerate = rig.state.draft?.preferredExercises

        rig.controller.previewDraft()
        val previewed = rig.state.generationPreview
        rig.controller.dismissGenerationPreview()

        assertEquals(
            "a preview reads the draft's own preference and adopting it is the caller's decision, so the " +
                "draft still carries the same snapshot afterwards (§33: no silent substitution)",
            afterGenerate,
            rig.state.draft?.preferredExercises
        )
        assertTrue(
            "and the preview itself was produced at all, which is what makes the comparison above about " +
                "the values rather than about a preview that never ran",
            previewed != null
        )
    }
}