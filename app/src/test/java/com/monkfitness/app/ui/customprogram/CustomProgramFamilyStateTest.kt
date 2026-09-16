package com.monkfitness.app.ui.customprogram

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic family grouping and the tri-state family control.
 *
 * The three properties this suite pins:
 *
 *  * **one catalogue** — the families are the app's declared families in their declared order, and
 *    inside a family the exercises are the library's own order. No second catalogue, no map
 *    iteration order, no `HashSet` traversal decides what the user sees.
 *  * **three states, derived** — `ALL_ENABLED`, `PARTIAL`, `NONE_ENABLED`, computed from the draft
 *    every time it changes. Nothing about a family is stored separately, so a family cannot disagree
 *    with its own exercises.
 *  * **a family toggle touches its own family and nothing else** — `ALL → NONE`, `NONE → ALL`,
 *    `PARTIAL → ALL`, with every other family's selection byte-identical afterwards.
 */
class CustomProgramFamilyStateTest {

    private val rig = CustomProgramEditorRig()

    @After
    fun tearDown() {
        rig.close()
    }

    // ---- grouping -----------------------------------------------------------------------------

    @Test
    fun familiesWithLibraryExercisesAreGroupedInTheDeclaredOrder() = rig.inRig {
        editor.open()

        val expected = families
            .filter { family -> library.any { it.familyId == family.id } }
            .map { it.id }

        assertEquals(expected, state().families.map { it.familyId })
        assertTrue("the real library must produce a real grouping", expected.size > 20)
    }

    @Test
    fun everyLibraryExerciseAppearsExactlyOnceAcrossTheFamilies() = rig.inRig {
        editor.open()

        val grouped = state().families.flatMap { group -> group.exercises.map { it.id } }

        assertEquals(libraryIds.size, grouped.size)
        assertEquals(libraryIds, grouped.toSet())
    }

    @Test
    fun exercisesInsideAFamilyKeepTheLibraryOrder() = rig.inRig {
        editor.open()

        state().families.forEach { group ->
            val expected = library.filter { it.familyId == group.familyId }.map { it.id }
            assertEquals("family ${group.familyId}", expected, group.exercises.map { it.id })
        }
    }

    @Test
    fun noFamilyIsInTheLibraryWithoutADeclaredFamilyEntry() = rig.inRig {
        editor.open()

        assertFalse(
            "every library family id is declared",
            library.any { exercise -> families.none { it.id == exercise.familyId } }
        )
    }

    @Test
    fun theGroupingIsReproducibleAcrossEditorInstances() {
        val first = CustomProgramEditorRig()
        val second = CustomProgramEditorRig()
        try {
            first.inRig { editor.open() }
            second.inRig { editor.open() }

            assertEquals(
                first.state().families.map { it.familyId },
                second.state().families.map { it.familyId }
            )
            assertEquals(
                first.state().families.map { group -> group.exercises.map { it.id } },
                second.state().families.map { group -> group.exercises.map { it.id } }
            )
        } finally {
            first.close()
            second.close()
        }
    }

    // ---- tri-state classification -------------------------------------------------------------

    @Test
    fun aFullyEnabledFamilyIsAllEnabled() = rig.inRig {
        editor.open()

        assertEquals(
            listOf(FamilySelectionState.ALL_ENABLED),
            state().families.map { it.selectionState }.distinct()
        )
        assertEquals(idsInFamily("pushups"), enabledIdsIn("pushups"))
        assertEquals(FamilySelectionState.ALL_ENABLED, group("pushups").selectionState)
    }

    @Test
    fun aFullyDisabledFamilyIsNoneEnabled() = rig.inRig {
        editor.open()

        idsInFamily("pushups").forEach { editor.toggleExercise(it) }

        assertEquals(FamilySelectionState.NONE_ENABLED, group("pushups").selectionState)
        assertTrue(enabledIdsIn("pushups").isEmpty())
        assertEquals(
            "no other family changed",
            listOf(FamilySelectionState.ALL_ENABLED),
            state().families.filterNot { it.familyId == "pushups" }.map { it.selectionState }.distinct()
        )
    }

    @Test
    fun aPartiallyEnabledFamilyIsPartial() = rig.inRig {
        editor.open()

        editor.toggleExercise("pushups_wide")

        assertEquals(FamilySelectionState.PARTIAL, group("pushups").selectionState)
        assertEquals(idsInFamily("pushups") - "pushups_wide", enabledIdsIn("pushups"))
    }

    @Test
    fun togglingAnIndividualExerciseUpdatesTheFamilyState() = rig.inRig {
        editor.open()
        val memberIds = idsInFamily("pushups")

        memberIds.forEach { editor.toggleExercise(it) }
        assertEquals(FamilySelectionState.NONE_ENABLED, group("pushups").selectionState)

        editor.toggleExercise("pushups")
        assertEquals(FamilySelectionState.PARTIAL, group("pushups").selectionState)

        (memberIds - "pushups").forEach { editor.toggleExercise(it) }
        assertEquals(FamilySelectionState.ALL_ENABLED, group("pushups").selectionState)
    }

    @Test
    fun theFamilyStateFollowsTheDraftRatherThanTheStoredConfiguration() = rig.inRig {
        editor.open()

        idsInFamily("pushups").forEach { editor.toggleExercise(it) }
        assertEquals(FamilySelectionState.NONE_ENABLED, group("pushups").selectionState)
        assertTrue(
            "the stored configuration is untouched while the family reads as disabled",
            defaultEnabledExerciseIds.containsAll(idsInFamily("pushups"))
        )
        assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)

        editor.discardDraft()

        assertEquals(FamilySelectionState.ALL_ENABLED, group("pushups").selectionState)
    }

    // ---- the family toggle --------------------------------------------------------------------

    @Test
    fun theFamilyToggleTurnsAnAllEnabledFamilyIntoNoneEnabled() = rig.inRig {
        editor.open()

        editor.toggleFamily("plank")

        assertEquals(FamilySelectionState.NONE_ENABLED, group("plank").selectionState)
        assertEquals(defaultEnabledExerciseIds - idsInFamily("plank"), state().draftEnabledExerciseIds)
    }

    @Test
    fun theFamilyToggleTurnsANoneEnabledFamilyIntoAllEnabled() = rig.inRig {
        editor.open()
        idsInFamily("plank").forEach { editor.toggleExercise(it) }
        assertEquals(FamilySelectionState.NONE_ENABLED, group("plank").selectionState)

        editor.toggleFamily("plank")

        assertEquals(FamilySelectionState.ALL_ENABLED, group("plank").selectionState)
        assertEquals(defaultEnabledExerciseIds, state().draftEnabledExerciseIds)
    }

    @Test
    fun theFamilyToggleTurnsAPartiallyEnabledFamilyIntoAllEnabled() = rig.inRig {
        editor.open()
        editor.toggleExercise("plank")
        assertEquals(FamilySelectionState.PARTIAL, group("plank").selectionState)

        editor.toggleFamily("plank")

        assertEquals(FamilySelectionState.ALL_ENABLED, group("plank").selectionState)
        assertEquals(defaultEnabledExerciseIds, state().draftEnabledExerciseIds)
    }

    @Test
    fun aFamilyOperationAffectsOnlyThatFamily() = rig.inRig {
        editor.open()
        editor.toggleExercise("pushups_wide")
        val draftBefore = state().draftEnabledExerciseIds
        val groupsBefore = state().families
            .filterNot { it.familyId == "pullups" }
            .associate { group -> group.familyId to group.exercises.map { it.id to it.enabled } }

        editor.toggleFamily("pullups")

        assertEquals(
            "the same draft apart from the toggled family",
            draftBefore - idsInFamily("pullups"),
            state().draftEnabledExerciseIds
        )
        state().families.filterNot { it.familyId == "pullups" }.forEach { group ->
            assertEquals(
                "family ${group.familyId} was modified",
                groupsBefore.getValue(group.familyId),
                group.exercises.map { it.id to it.enabled }
            )
        }
    }

    @Test
    fun aFamilyToggleOnAnUnknownFamilyChangesNothing() = rig.inRig {
        editor.open()
        val before = state().draftEnabledExerciseIds
        val groupsBefore = state().families.map { group -> group.familyId to group.selectionState }

        editor.toggleFamily("not_a_family_of_this_library")

        assertEquals(before, state().draftEnabledExerciseIds)
        assertEquals(groupsBefore, state().families.map { group -> group.familyId to group.selectionState })
    }

    @Test
    fun thePartialStateIsNotTheSameAsAllEnabled() = rig.inRig {
        editor.open()
        editor.toggleExercise("pushups_wide")

        assertNotEquals(FamilySelectionState.ALL_ENABLED, group("pushups").selectionState)
        assertNotEquals(FamilySelectionState.NONE_ENABLED, group("pushups").selectionState)
        assertEquals(FamilySelectionState.PARTIAL, group("pushups").selectionState)
    }

    private fun CustomProgramEditorRig.enabledIdsIn(familyId: String): Set<String> =
        group(familyId).exercises.filter { it.enabled }.map { it.id }.toSet()
}
