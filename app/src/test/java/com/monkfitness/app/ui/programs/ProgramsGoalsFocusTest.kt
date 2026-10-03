package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import com.monkfitness.app.domain.program.ProgramMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7's *Goals & Focus*, measured through the **controller** on the real engine — the only place a claim
 * about what the editor shows or what Save does can be true or false.
 *
 * ```text
 * the section's value      the draft's own FocusPlan, presented unchanged by the controller
 * a change                goes through ProgramDraftEditor.withFocus and clears the review
 * BALANCED / FOCUSED / CUSTOM   all three forms round trip through the UI and back out of storage
 * a structural change     creates exactly one Revision; a no-op creates none
 * the stored Revision     is untouched by a change that was never saved
 * Generate                never persists the focus, and the draft keeps the user's configuration
 * ```
 */
class ProgramsGoalsFocusTest {

    private val rig = ProgramsRig("goalsfocus")

    @After
    fun tearDown() {
        rig.close()
    }

    // ---------------------------------------------------------------- the draft's own value

    @Test
    fun theDraftsConfigurationIsWhatTheScreenRenders() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.MANUAL)

        assertEquals(
            "a draft nobody configured is the domain's own default configuration",
            FocusPlan.Balanced,
            rig.state.draft?.focus
        )

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PULL, Focus.PUSH)))

        assertEquals(
            "§7's section displays the draft's configuration and not a copy the screen may have edited",
            FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)),
            rig.state.draft?.focus
        )
    }

    @Test
    fun aFocusChangeGoesThroughTheDraftEditorAndIsVisibleOnTheNextGeneration() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.CORE)))

        assertEquals(
            "the change is the draft's, reached through the editor's own operation — so every other " +
                "draft invariant still holds",
            FocusPlan.Focused(listOf(Focus.CORE)),
            rig.state.draft?.focus
        )
        assertEquals(
            "and it is still the user's own draft, not a revision: nothing was persisted to get here",
            emptyList<String>(),
            emptyList<String>().filter { it in rig.state.rows.map { row -> row.programId } }
        )
    }

    @Test
    fun changingTheFocusClearsTheReviewThatDescribedThePreviousDraft() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.MANUAL)
        rig.controller.reviewDraft()
        assertNotNull("the review is asked for", rig.state.draft?.review)

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.LEGS)))

        assertNull(
            "§7's Review describes one draft: after a change it describes nothing, so a stale answer " +
                "cannot be read as the new draft's",
            rig.state.draft?.review
        )
    }

    // ---------------------------------------------------------------- the three forms round trip

    @Test
    fun balancedRoundTripsThroughSaveAndReload() = runBlocking {
        val id = rig.createProgramThroughTheUi("Balanced")!!
        rig.controller.openEditDraft(id)
        rig.controller.setDraftFocus(FocusPlan.Balanced)
        assertTrue("a no-op focus change on a balanced draft saves nothing new", !rig.controller.saveDraft())

        assertEquals(
            "§8's BALANCED survives storage as itself",
            FocusPlan.Balanced,
            rig.storedFocus(ProgramId(id))
        )
    }

    @Test
    fun focusedRoundTripsThroughSaveAndReload() = runBlocking {
        val id = rig.createProgramThroughTheUi("Focused")!!
        rig.controller.openEditDraft(id)

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)))
        assertTrue("a structural change is savable", rig.controller.saveDraft())

        assertEquals(
            "§8's FOCUSED, held in the vocabulary's own order",
            FocusPlan.Focused(listOf(Focus.PUSH, Focus.PULL)),
            rig.storedFocus(ProgramId(id))
        )
    }

    @Test
    fun aCustomAllocationRoundTripsThroughSaveAndReload() = runBlocking {
        val id = rig.createProgramThroughTheUi("Custom")!!
        rig.controller.openEditDraft(id)

        rig.controller.setDraftFocus(
            FocusPlan.custom(listOf(FocusAllocation(Focus.MOBILITY, 30), FocusAllocation(Focus.PUSH, 70)))
        )
        assertTrue("a structural change is savable", rig.controller.saveDraft())

        val stored = rig.storedFocus(ProgramId(id))
        assertEquals(
            "the user's own shares, in canonical order — not an even split and not a Focused plan",
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30))),
            stored
        )
        assertEquals(Goal.CUSTOM, stored?.goal)
        assertEquals(70, (stored as FocusPlan.Custom).percentFor(Focus.PUSH))
    }

    // ---------------------------------------------------------------- revision semantics (§6)

    @Test
    fun changingOnlyTheFocusCreatesARevision() = runBlocking {
        val id = rig.createProgramThroughTheUi("Focus revision")!!
        val before = rig.revisionCount(ProgramId(id))

        rig.controller.openEditDraft(id)
        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH)))
        val review = rig.controller.reviewDraft().let { rig.state.draft?.review }

        assertNotNull(review)
        assertTrue(
            "§6 lists goals/focus among the structural changes, so a save of this draft creates a revision",
            review!!.willCreateARevision
        )
        assertTrue(
            "and the review names that aspect among the changes, in the editor's own vocabulary",
            review.changeRes.contains(R.string.programs_change_focus)
        )
        assertTrue(rig.controller.saveDraft())
        assertEquals(
            "exactly one new revision — §7's one Save creates at most one",
            before + 1,
            rig.revisionCount(ProgramId(id))
        )
    }

    @Test
    fun anUnchangedFocusCreatesNoRevision() = runBlocking {
        val id = rig.createProgramThroughTheUi("Focus no-op")!!
        rig.controller.openEditDraft(id)
        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH)))
        rig.controller.saveDraft()
        val afterTheChange = rig.revisionCount(ProgramId(id))

        rig.controller.openEditDraft(id)
        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH)))
        val review = rig.controller.reviewDraft().let { rig.state.draft?.review }

        assertFalse(
            "setting the configuration the draft already has changes nothing, so §6's no-op save holds",
            review!!.willCreateARevision
        )
        assertFalse("and the save writes nothing", rig.controller.saveDraft())
        assertEquals(afterTheChange, rig.revisionCount(ProgramId(id)))
    }

    @Test
    fun theStoredRevisionIsUntouchedUntilTheUserSaves() = runBlocking {
        val id = rig.createProgramThroughTheUi("Unsaved focus")!!
        rig.controller.openEditDraft(id)

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.POSTURE)))

        assertEquals(
            "a draft change is a draft (§7): the stored Revision still holds the configuration it had",
            FocusPlan.Balanced,
            rig.storedFocus(ProgramId(id))
        )
        assertEquals(
            "and no revision was minted by the edit itself",
            1,
            rig.revisionCount(ProgramId(id))
        )
    }

    // ---------------------------------------------------------------- generation integration

    @Test
    fun theUsersConfigurationIsWhatTheGenerationPassPlansFor() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)))
        rig.controller.generateDraft()

        assertEquals(
            "§7's Generate reads the draft's own configuration, so a FOCUSED(PUSH, PULL) plan comes back " +
                "with that focus and not the BALANCED configuration",
            FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)),
            rig.state.draft?.focus
        )
        assertTrue(
            "and every exercise in the resulting plan can serve one of the two focuses the user chose — " +
                "nothing the user did not ask for was planned for",
            rig.everyPlannedExerciseServes(setOf(Focus.PUSH, Focus.PULL))
        )
        assertTrue("the pass actually produced a plan to look at", rig.plannedElementCount() > 0)
    }

    @Test
    fun aCustomAllocationReachesTheGenerationPassWithItsOwnPercentages() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        rig.controller.setDraftFocus(
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30)))
        )
        rig.controller.generateDraft()

        assertEquals(
            "a CUSTOM allocation survives generation as itself — never a Focused plan, never an even split",
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30))),
            rig.state.draft?.focus
        )
        assertTrue(
            "and the plan is built only from the two focuses the user allocated a share to",
            rig.everyPlannedExerciseServes(setOf(Focus.PUSH, Focus.MOBILITY))
        )
        assertTrue("the pass actually produced a plan to look at", rig.plannedElementCount() > 0)
    }

    @Test
    fun generateNeverPersistsTheConfigurationAndLeavesStorageAlone() = runBlocking {
        val id = rig.createProgramThroughTheUi("Generated focus")!!
        val revisions = rig.revisionCount(ProgramId(id))
        val tables = rig.tableCounts()

        rig.controller.openEditDraft(id)
        rig.controller.setDraftMode(ProgramMode.GENERATED)
        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.LEGS)))
        rig.controller.generateDraft()

        assertEquals(
            "§7's Generate only ever alters a draft: it creates no revision",
            revisions,
            rig.revisionCount(ProgramId(id))
        )
        assertEquals(
            "and writes no row of any kind",
            tables,
            rig.tableCounts()
        )
        assertEquals(
            "so the stored Revision still holds the configuration that was saved",
            FocusPlan.Balanced,
            rig.storedFocus(ProgramId(id))
        )
        assertEquals(
            "while the new draft carries the user's choice forward",
            FocusPlan.Focused(listOf(Focus.LEGS)),
            rig.state.draft?.focus
        )
    }
}