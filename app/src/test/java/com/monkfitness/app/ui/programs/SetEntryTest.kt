package com.monkfitness.app.ui.programs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the set-confirmation fields accept, as the one rule the field's error and the write both read.
 *
 * The bounds are the brief's own: a load is greater than zero and no heavier than 500 kg with at most two
 * decimals, and a repetition count is a whole number from 1 to 500. Anything else is refused before a
 * write, so a rejected entry cannot become a stored set.
 */
class SetEntryTest {

    @Test
    fun aLoadIsParsedWithUpToTwoDecimals() {
        assertEquals(60.0, requireNotNull(SetEntry.loadOf("60")), 0.0)
        assertEquals(62.5, requireNotNull(SetEntry.loadOf("62.5")), 0.0)
        assertEquals(62.25, requireNotNull(SetEntry.loadOf("62.25")), 0.0)
        assertEquals("the locale's comma is the same value", 62.5, requireNotNull(SetEntry.loadOf("62,5")), 0.0)
        assertEquals("and the ceiling is accepted", 500.0, requireNotNull(SetEntry.loadOf("500")), 0.0)
    }

    @Test
    fun aLoadOutsideTheBoundIsRefused() {
        for (rejected in listOf("0", "0.0", "-5", "500.01", "501", "9999", "62.555", "NaN", "Infinity", "1e3", "abc", ".", "62.")) {
            assertNull("`$rejected` is not a load the app accepts", SetEntry.loadOf(rejected))
        }
    }

    @Test
    fun aBlankLoadIsAnAbsenceRatherThanAnError() {
        assertNull("nothing typed is no load recorded", SetEntry.loadOf(""))
        assertNull("and whitespace is nothing typed", SetEntry.loadOf("   "))
    }

    @Test
    fun aRepetitionCountIsAWholeNumberWithinTheBound() {
        assertEquals(1, SetEntry.repsOf("1"))
        assertEquals(500, SetEntry.repsOf(" 500 "))
        for (rejected in listOf("0", "-1", "501", "", "  ", "10.5", "abc", "999999")) {
            assertNull("`$rejected` is not a repetition count the app accepts", SetEntry.repsOf(rejected))
        }
    }

    @Test
    fun theBoundsTheFieldChecksAreTheBoundsTheDomainStores() {
        assertEquals(
            com.monkfitness.app.domain.workout.MAX_PERFORMED_LOAD_KG,
            SetEntry.MAX_LOAD_KG,
            0.0
        )
        assertEquals(com.monkfitness.app.domain.workout.MAX_PERFORMED_REPS, SetEntry.MAX_REPS)
    }

    @Test
    fun aKilogramValueIsPrintedWithoutATrailingZeroScale() {
        assertEquals("60", formatKg(60.0, java.util.Locale.US))
        assertEquals("62.5", formatKg(62.5, java.util.Locale.US))
        assertEquals("62.25", formatKg(62.25, java.util.Locale.US))
        assertEquals("a comma in a comma locale", "62,5", formatKg(62.5, java.util.Locale.GERMANY))
        assertEquals("480", formatKg(480.0, java.util.Locale.US))
    }
}
