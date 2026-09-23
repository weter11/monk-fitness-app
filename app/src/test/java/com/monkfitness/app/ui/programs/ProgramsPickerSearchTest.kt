package com.monkfitness.app.ui.programs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's search rule (P3 `UI-04`): what one option must contain to answer a query.
 *
 * The defect was that the filter ran on the identifier alone while the row displayed the localized
 * label — so in `es`, `Flexion` matched nothing a Spanish reader could see. The rule now takes both
 * names of the same option and matches either, case-insensitively; this suite states the contract,
 * and `ProgramsArchitectureTest.thePickerSearchesLocalizedNameAndIdAndExplainsAnEmptyResult` pins
 * that the screen actually feeds it both.
 */
class ProgramsPickerSearchTest {

    @Test
    fun theLocalizedDisplayNameMatches() {
        assertTrue(
            "the label the user is reading is the primary thing to search",
            matchesExerciseQuery(query = "Flexion", exerciseId = "hip_flexion", displayName = "Flexión de cadera")
        )
        assertTrue("and the match ignores case", matchesExerciseQuery("flexión", "hip_flexion", "Flexión de cadera"))
        assertTrue("and surrounding whitespace the keyboard leaves behind",
            matchesExerciseQuery("  Flexión  ", "hip_flexion", "Flexión de cadera"))
    }

    @Test
    fun theStableExerciseIdStillMatches() {
        assertTrue(
            "an id is a legitimate thing to paste, and §10 keeps ids opaque but real",
            matchesExerciseQuery("hip_flex", "hip_flexion", "Flexión de cadera")
        )
        assertTrue("also case-insensitively", matchesExerciseQuery("HIP_FLEXION", "hip_flexion", "Cadera"))
    }

    @Test
    fun bothNamesOfTheSameOptionAreOffered() {
        val byName = matchesExerciseQuery("Press", "overhead_press", " Shoulder Press ")
        val byId = matchesExerciseQuery("overhead", "overhead_press", "Shoulder Press")
        assertTrue(byName)
        assertTrue(byId)
        assertFalse(
            "a query matching neither name is a real miss — the empty state's trigger",
            matchesExerciseQuery("benches", "overhead_press", "Shoulder Press")
        )
    }

    @Test
    fun aBlankQueryIsTheUnfilteredListNotAWhim() {
        assertTrue(matchesExerciseQuery("", "pushups", "Push-ups"))
        assertTrue(matchesExerciseQuery("   ", "pushups", "Push-ups"))
    }

    @Test
    fun theNameAndTheIdAreIndependentWaysToFindOneOption() {
        // The defect scenario, stated exactly: the localized name shares nothing with the id, so a
        // name-only filter finds it and an id-only filter misses it — the rule must do both.
        assertTrue(matchesExerciseQuery("Flexion", "sit_up", "Flexión"))
        assertTrue(matchesExerciseQuery("sit_up", "sit_up", "Flexión"))
        assertFalse(matchesExerciseQuery("benches", "sit_up", "Flexión"))
    }
}
