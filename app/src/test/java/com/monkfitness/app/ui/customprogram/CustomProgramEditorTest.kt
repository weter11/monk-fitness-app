package com.monkfitness.app.ui.customprogram

import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
import com.monkfitness.app.domain.adaptive.ProgramConfigurationWarningCode
import com.monkfitness.app.domain.adaptive.TrainingDomain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft lifecycle of the Custom Program editor: what a checkbox does, what Cancel does, what
 * Apply does, and what none of them may touch.
 *
 * The rules this suite exists to defend:
 *
 *  * **draft-first** — a toggle edits the draft and the draft only. The proof is not a flag on the
 *    editor: it is the stored bytes. Every "did not persist" assertion compares the sha256 of the
 *    configuration store before and after, and the repository is read back for the version.
 *  * **Cancel is not an apply** — it discards the draft and leaves the persisted configuration, its
 *    source and its version exactly as they were, so reopening the editor shows the stored selection.
 *  * **Apply is gated by the domain validator** — hard errors keep the editor open, persist nothing,
 *    and leave the user's rejected selection exactly as they proposed it (nothing is repaired,
 *    nothing is re-enabled, nothing is substituted).
 *  * **warnings never block** — a selection that is valid but unbalanced is applied, with the
 *    warnings presented.
 *  * **reset is a separate, confirmed action** — and it is the repository's reset, not the UI's
 *    arithmetic: an already-default selection stays a no-op.
 */
class CustomProgramEditorTest {

    private val rig = CustomProgramEditorRig()

    @After
    fun tearDown() {
        rig.close()
    }

    /** A valid, non-default selection: strength (full body, legs) plus flexibility (spine). */
    private val customSelection = setOf("pushups", "squats", "cat_cow")

    // ---- opening ------------------------------------------------------------------------------

    @Test
    fun theInitialDraftMirrorsThePersistedConfiguration() = rig.inRig {
        val seeded = seed(customSelection)

        editor.open()

        assertEquals(customSelection, state().draftEnabledExerciseIds)
        assertEquals(customSelection, state().persistedEnabledExerciseIds)
        assertEquals(seeded.configurationVersion, repository.load().configurationVersion)
        assertEquals(ProgramConfigurationSource.CUSTOM, state().configurationSource)
        assertTrue("the editor is open", state().isOpen)
        assertFalse("a freshly opened editor has nothing to apply", state().hasUnsavedChanges)
    }

    @Test
    fun theInitialDraftIsTheAuthoritativeDefaultSetOnFirstUse() = rig.inRig {
        editor.open()

        assertEquals(defaultEnabledExerciseIds, state().draftEnabledExerciseIds)
        assertEquals(ProgramConfigurationSource.DEFAULT, state().configurationSource)
        assertEquals(ProgramConfiguration.INITIAL_VERSION, repository.load().configurationVersion)
    }

    @Test
    fun openingTheEditorPersistsNothing() = rig.inRig {
        editor.open()

        assertEquals("a read must not create the configuration store", CustomProgramEditorRig.ABSENT, storedDigest())
    }

    // ---- toggling -----------------------------------------------------------------------------

    @Test
    fun togglingOneExerciseChangesOnlyTheDraft() = rig.inRig {
        seed(customSelection)
        editor.open()
        val before = storedDigest()
        val storedBefore = repository.load()

        editor.toggleExercise("pushups")

        assertFalse("pushups left the draft", "pushups" in state().draftEnabledExerciseIds)
        assertEquals(customSelection, state().persistedEnabledExerciseIds)
        assertTrue("the effective selection is still the stored one", storedBefore.hasSameEffectiveSelectionAs(repository.load()))
        assertEquals("the version did not move", storedBefore.configurationVersion, repository.load().configurationVersion)
        assertEquals("not one byte was written", before, storedDigest())
    }

    @Test
    fun togglingTheSameExerciseTwiceRestoresThePersistedSelection() = rig.inRig {
        seed(customSelection)
        editor.open()
        val before = storedDigest()

        editor.toggleExercise("pushups")
        assertTrue(state().hasUnsavedChanges)
        editor.toggleExercise("pushups")

        assertEquals(customSelection, state().draftEnabledExerciseIds)
        assertFalse(state().hasUnsavedChanges)
        assertEquals("toggling back and forth never writes", before, storedDigest())
    }

    @Test
    fun togglingAnUnknownExerciseIsIgnored() = rig.inRig {
        editor.open()
        val before = state().draftEnabledExerciseIds

        editor.toggleExercise("not_an_exercise_of_this_library")

        assertEquals(before, state().draftEnabledExerciseIds)
    }

    // ---- cancel -------------------------------------------------------------------------------

    @Test
    fun cancelDiscardsDraftChangesAndLeavesPersistenceUntouched() = rig.inRig {
        seed(customSelection)
        editor.open()
        val before = storedDigest()
        val storedVersion = repository.load().configurationVersion

        editor.toggleExercise("pushups")
        editor.toggleExercise("cat_cow")
        editor.discardDraft()

        assertEquals(customSelection, state().draftEnabledExerciseIds)
        assertEquals(customSelection, repository.load().enabledExerciseIds)
        assertEquals(storedVersion, repository.load().configurationVersion)
        assertEquals(before, storedDigest())
        assertFalse(state().hasUnsavedChanges)
    }

    @Test
    fun reopeningAfterCancelRestoresThePersistedConfiguration() = rig.inRig {
        seed(customSelection)
        editor.open()
        editor.toggleExercise("pushups")
        editor.toggleExercise("squats")
        editor.discardDraft()

        editor.open()

        assertEquals(customSelection, state().draftEnabledExerciseIds)
        assertEquals(customSelection, state().persistedEnabledExerciseIds)
        assertEquals(customSelection, repository.load().enabledExerciseIds)
    }

    // ---- apply --------------------------------------------------------------------------------

    @Test
    fun successfulApplyPersistsTheDraftAsACustomConfiguration() = rig.inRig {
        val seeded = seed(customSelection)
        editor.open()

        editor.toggleExercise("pushups")
        val result = editor.apply()

        val applied = (result as CustomProgramApplyResult.Applied).configuration
        assertEquals(customSelection - "pushups", applied.enabledExerciseIds)
        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(seeded.configurationVersion + 1, applied.configurationVersion)
        assertEquals(customSelection - "pushups", repository.load().enabledExerciseIds)
        assertEquals(ProgramConfigurationSource.CUSTOM, repository.load().source)
        assertEquals(seeded.configurationVersion + 1, repository.load().configurationVersion)
        assertEquals(applied, state().appliedConfiguration)
        assertEquals(customSelection - "pushups", state().draftEnabledExerciseIds)
        assertEquals(customSelection - "pushups", state().persistedEnabledExerciseIds)
        assertFalse("a successful apply leaves nothing unsaved", state().hasUnsavedChanges)
    }

    @Test
    fun applyingTheAuthoritativeDefaultSelectionIsDefaultRatherThanCustom() = rig.inRig {
        seed(customSelection)
        editor.open()

        (defaultEnabledExerciseIds - state().draftEnabledExerciseIds).forEach { editor.toggleExercise(it) }
        val result = editor.apply()

        assertTrue(result is CustomProgramApplyResult.Applied)
        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)
        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
        assertEquals("a real change still advances the version", 2, repository.load().configurationVersion)
    }

    @Test
    fun aNoOpApplyDoesNotBumpTheConfigurationVersion() = rig.inRig {
        val seeded = seed(customSelection)
        editor.open()
        val before = storedDigest()

        val result = editor.apply()

        assertTrue(result is CustomProgramApplyResult.Applied)
        assertEquals(seeded.configurationVersion, repository.load().configurationVersion)
        assertEquals(ProgramConfigurationSource.CUSTOM, repository.load().source)
        assertEquals("a no-op writes nothing at all", before, storedDigest())
    }

    @Test
    fun invalidApplyDoesNotPersistAndKeepsTheEditorOpen() = rig.inRig {
        editor.open()
        idsInDomain(TrainingDomain.FLEXIBILITY).forEach { editor.toggleExercise(it) }

        val result = editor.apply()

        assertTrue("a configuration without a required training domain is rejected", result is CustomProgramApplyResult.Rejected)
        assertTrue("the editor stays open", state().isOpen)
        assertNull("nothing was applied", state().appliedConfiguration)
        assertTrue("the error is presented", state().isApplyBlocked)
        assertEquals(
            "the missing domain is named",
            listOf(TrainingDomain.FLEXIBILITY),
            state().errors.map { it.trainingDomain }
        )
        assertEquals("nothing was persisted", CustomProgramEditorRig.ABSENT, storedDigest())
        assertEquals(ProgramConfiguration.INITIAL_VERSION, repository.load().configurationVersion)
        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)
        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
    }

    @Test
    fun aRejectedApplyLeavesTheProposedSelectionExactlyAsItWas() = rig.inRig {
        editor.open()
        val proposed = defaultEnabledExerciseIds - idsInDomain(TrainingDomain.FLEXIBILITY)
        idsInDomain(TrainingDomain.FLEXIBILITY).forEach { editor.toggleExercise(it) }

        editor.apply()

        assertEquals("no exercise is re-enabled or substituted on the user's behalf", proposed, state().draftEnabledExerciseIds)
        assertEquals("the persisted configuration is still the default set", defaultEnabledExerciseIds, state().persistedEnabledExerciseIds)
        assertFalse(state().errors.isEmpty())
    }

    @Test
    fun softWarningsArePresentedWithoutBlockingApply() = rig.inRig {
        editor.open()
        idsInRegion(BodyRegion.SHOULDERS).forEach { editor.toggleExercise(it) }

        assertFalse("a balance warning is not a hard error", state().isApplyBlocked)
        assertTrue(
            "the uncovered body region is presented as a warning",
            state().warnings.any {
                it.code == ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED &&
                    it.bodyRegion == BodyRegion.SHOULDERS
            }
        )

        val result = editor.apply()

        assertTrue("a warned-about selection may be applied", result is CustomProgramApplyResult.Applied)
        assertEquals(defaultEnabledExerciseIds - idsInRegion(BodyRegion.SHOULDERS), repository.load().enabledExerciseIds)
        assertEquals(ProgramConfigurationSource.CUSTOM, repository.load().source)
    }

    // ---- reset to default ---------------------------------------------------------------------

    @Test
    fun resetRequiresConfirmationBeforeItTouchesPersistence() = rig.inRig {
        seed(customSelection)
        editor.open()
        val before = storedDigest()

        editor.requestResetToDefault()

        assertTrue("the confirmation is visible", state().isResetConfirmationVisible)
        assertEquals("nothing is reset before the user confirms", customSelection, repository.load().enabledExerciseIds)
        assertEquals(before, storedDigest())
    }

    @Test
    fun confirmedResetRestoresTheDefaultSelectionThroughTheRepository() = rig.inRig {
        val seeded = seed(customSelection)
        editor.open()
        editor.toggleExercise("pushups")

        editor.requestResetToDefault()
        editor.confirmResetToDefault()

        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)
        assertEquals("a real selection change advances the version by one", seeded.configurationVersion + 1, repository.load().configurationVersion)
        assertEquals("the draft is reloaded from the resulting configuration", defaultEnabledExerciseIds, state().draftEnabledExerciseIds)
        assertEquals(defaultEnabledExerciseIds, state().persistedEnabledExerciseIds)
        assertFalse("the confirmation is gone", state().isResetConfirmationVisible)
        assertFalse(state().hasUnsavedChanges)
    }

    @Test
    fun cancelledResetChangesNothing() = rig.inRig {
        val seeded = seed(customSelection)
        editor.open()
        editor.toggleExercise("pushups")
        val draftBefore = state().draftEnabledExerciseIds
        val before = storedDigest()

        editor.requestResetToDefault()
        editor.dismissResetConfirmation()

        assertFalse(state().isResetConfirmationVisible)
        assertEquals("the draft is untouched by a cancelled reset", draftBefore, state().draftEnabledExerciseIds)
        assertEquals(customSelection, repository.load().enabledExerciseIds)
        assertEquals(seeded.configurationVersion, repository.load().configurationVersion)
        assertEquals(before, storedDigest())
    }

    @Test
    fun resettingAnAlreadyDefaultConfigurationIsANoOp() = rig.inRig {
        seed(customSelection)
        editor.open()
        editor.requestResetToDefault()
        editor.confirmResetToDefault()
        val afterFirstReset = storedDigest()
        val versionAfterFirstReset = repository.load().configurationVersion

        editor.requestResetToDefault()
        editor.confirmResetToDefault()

        assertEquals(versionAfterFirstReset, repository.load().configurationVersion)
        assertEquals("repeating a reset writes nothing", afterFirstReset, storedDigest())
        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
        assertEquals(ProgramConfigurationSource.DEFAULT, repository.load().source)
    }

    @Test
    fun resetDoesNotDependOnWhatTheDraftCurrentlyHolds() = rig.inRig {
        seed(customSelection)
        editor.open()
        editor.toggleExercise("cat_cow")
        editor.toggleExercise("squats")

        editor.requestResetToDefault()
        editor.confirmResetToDefault()

        assertEquals(defaultEnabledExerciseIds, state().draftEnabledExerciseIds)
        assertEquals(defaultEnabledExerciseIds, state().persistedEnabledExerciseIds)
        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
        assertTrue("the restored selection is recognisable as the default", state().isDefaultSelection)
    }
}
