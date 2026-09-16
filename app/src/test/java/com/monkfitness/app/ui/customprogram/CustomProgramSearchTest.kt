package com.monkfitness.app.ui.customprogram

import com.monkfitness.app.util.matchesQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search/filtering: what it hides, and — more importantly — what it must never change.
 *
 * The load-bearing rule is the last one in this list: the filter is a *view*. A family's tri-state
 * describes the whole family draft, so searching down to the single enabled exercise of an otherwise
 * disabled family must still report `PARTIAL`. A header that flips to `ALL_ENABLED` because the only
 * exercise the search left visible happens to be enabled is the bug this suite exists for.
 *
 * The matching itself is the app's own `matchesQuery` over the app's own localized name and
 * description fields — the same function and the same fields `PostureScreen` searches. Nothing here
 * invents a second naming catalogue or a second search implementation.
 */
class CustomProgramSearchTest {

    private val rig = CustomProgramEditorRig()

    @After
    fun tearDown() {
        rig.close()
    }

    // ---- filtering ----------------------------------------------------------------------------

    @Test
    fun searchShowsOnlyTheExercisesTheAppsOwnMatcherAccepts() = rig.inRig {
        editor.open()

        editor.setSearchQuery("push")

        val expected = library.filter { exercise -> matchesQuery(exercise, "push") }.map { it.id }.toSet()
        val visible = state().families.flatMap { group -> group.visibleExercises.map { it.id } }.toSet()

        assertTrue("the query must match something", expected.isNotEmpty())
        assertEquals(expected, visible)
        assertTrue("pushups_wide matches", "pushups_wide" in visible)
        assertFalse("squats does not match 'push'", "squats" in visible)
    }

    @Test
    fun searchIgnoresCaseAndSurroundingWhitespace() = rig.inRig {
        editor.open()

        editor.setSearchQuery("push")
        val plain = visibleIds()
        editor.setSearchQuery("  PUSH  ")

        assertEquals(plain, visibleIds())
    }

    @Test
    fun searchMatchesTheDescriptionFieldsTheAppEnrichesNotOnlyTheNames() {
        val library = CustomProgramEditorRig.searchableLibrary().map { exercise ->
            if (exercise.id == "squats") exercise.copy(descriptionEn = "kettlebell only marker") else exercise
        }
        val describedRig = CustomProgramEditorRig(library = library)
        try {
            describedRig.inRig {
                editor.open()

                editor.setSearchQuery("kettlebell only marker")

                assertEquals(setOf("squats"), visibleIds())
            }
        } finally {
            describedRig.close()
        }
    }

    @Test
    fun searchResultsAreReproducible() = rig.inRig {
        editor.open()

        editor.setSearchQuery("squat")
        val first = state().families.map { group -> group.familyId to group.visibleExercises.map { it.id } }
        editor.setSearchQuery("squat")
        val second = state().families.map { group -> group.familyId to group.visibleExercises.map { it.id } }

        assertEquals(first, second)
    }

    @Test
    fun aQueryThatMatchesNothingShowsNoFamilyAndKeepsTheDraft() = rig.inRig {
        editor.open()
        val draftBefore = state().draftEnabledExerciseIds

        editor.setSearchQuery("zzzz-no-such-exercise")

        assertTrue(state().families.all { group -> group.visibleExercises.isEmpty() })
        assertTrue(state().visibleFamilies.isEmpty())
        assertEquals(draftBefore, state().draftEnabledExerciseIds)
    }

    // ---- the filter is a view -----------------------------------------------------------------

    @Test
    fun filteringTheViewDoesNotMutateTheDraftOrTheStoredConfiguration() = rig.inRig {
        editor.open()
        editor.toggleExercise("pushups")
        val draftBefore = state().draftEnabledExerciseIds
        val digestBefore = storedDigest()
        val versionBefore = repository.load().configurationVersion
        val selectionBefore = repository.load().enabledExerciseIds

        editor.setSearchQuery("push")
        editor.setSearchQuery("squat")
        editor.setSearchQuery("")

        assertEquals(draftBefore, state().draftEnabledExerciseIds)
        assertEquals(selectionBefore, repository.load().enabledExerciseIds)
        assertEquals(versionBefore, repository.load().configurationVersion)
        assertEquals(digestBefore, storedDigest())
    }

    @Test
    fun aFamilyStateDescribesTheWholeFamilyNotTheVisibleSubset() = rig.inRig {
        editor.open()
        val members = idsInFamily("pushups")
        (members - "pushups_wide").forEach { editor.toggleExercise(it) }
        assertEquals(FamilySelectionState.PARTIAL, group("pushups").selectionState)

        editor.setSearchQuery("wide")

        val pushups = group("pushups")
        assertEquals("only one exercise of the family is visible", 1, pushups.visibleExercises.size)
        assertEquals("pushups_wide", pushups.visibleExercises.single().id)
        assertTrue("the one visible exercise is enabled", pushups.visibleExercises.single().enabled)
        assertEquals("the family still holds its whole selection", 6, pushups.exercises.size)
        assertEquals(
            "a partially enabled family must not read as fully enabled just because the visible exercise is enabled",
            FamilySelectionState.PARTIAL,
            pushups.selectionState
        )
    }

    @Test
    fun aFullyEnabledFamilyStaysAllEnabledWhileFiltered() = rig.inRig {
        editor.open()

        editor.setSearchQuery("wide")

        assertEquals(FamilySelectionState.ALL_ENABLED, group("pushups").selectionState)
        assertTrue(group("pushups").visibleExercises.size < group("pushups").exercises.size)
    }

    @Test
    fun aFullyDisabledFamilyStaysNoneEnabledWhileFiltered() = rig.inRig {
        editor.open()
        idsInFamily("pushups").forEach { editor.toggleExercise(it) }

        editor.setSearchQuery("wide")

        assertEquals(FamilySelectionState.NONE_ENABLED, group("pushups").selectionState)
        assertFalse(group("pushups").visibleExercises.single().enabled)
    }

    @Test
    fun aFamilyWithNoMatchIsHiddenButKeepsItsCompleteSelection() = rig.inRig {
        editor.open()
        val members = idsInFamily("pushups")
        (members - "pushups").forEach { editor.toggleExercise(it) }

        editor.setSearchQuery("hamstring")

        val pushups = group("pushups")
        assertFalse("a family with nothing visible is not shown", pushups.isVisible)
        assertTrue(pushups.visibleExercises.isEmpty())
        assertEquals("its selection is still complete and unchanged", 6, pushups.exercises.size)
        assertEquals(FamilySelectionState.PARTIAL, pushups.selectionState)
        assertEquals("pushups is the only enabled member of its family", setOf("pushups"), state().draftEnabledExerciseIds.intersect(members))
        assertTrue("pushups is still enabled in the draft", "pushups" in state().draftEnabledExerciseIds)
    }

    @Test
    fun clearingSearchRestoresEveryExerciseInTheDeterministicOrder() = rig.inRig {
        editor.open()
        val before = state().families.map { group -> group.familyId to group.exercises.map { it.id } }

        editor.setSearchQuery("push")
        assertTrue(state().families.flatMap { group -> group.visibleExercises }.size < library.size)
        editor.setSearchQuery("")

        assertEquals("", state().searchQuery)
        assertEquals(before, state().families.map { group -> group.familyId to group.exercises.map { it.id } })
        state().families.forEach { group ->
            assertEquals("every exercise of ${group.familyId} is visible again", group.exercises, group.visibleExercises)
        }
    }

    @Test
    fun togglingWhileFilteredEditsTheDraftAndNotTheView() = rig.inRig {
        editor.open()

        editor.setSearchQuery("wide")
        editor.toggleExercise("pullups_wide")

        assertFalse("pullups_wide" in state().draftEnabledExerciseIds)
        assertEquals(FamilySelectionState.PARTIAL, group("pullups").selectionState)
        assertEquals(
            "the visible set is unchanged by the toggle",
            library.filter { matchesQuery(it, "wide") }.map { it.id }.toSet(),
            visibleIds()
        )
    }

    private fun CustomProgramEditorRig.visibleIds(): Set<String> =
        state().families.flatMap { group -> group.visibleExercises.map { it.id } }.toSet()
}
