package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.SetLogId
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a load on a performed set is allowed to be.
 *
 * The bound exists because a stored weight multiplies every volume and record derived from it: a
 * mistyped `6000` would not be one wrong set, it would be a kilogram total that dwarfs the real one. The
 * absence is checked just as hard — `null` means *no load was recorded* and must never be read as zero.
 */
class PerformedLoadTest {

    private fun set(loadKg: Double?) = SetResult(
        setLogId = SetLogId("set-1"),
        setIndex = 1,
        completedReps = 10,
        durationSeconds = 0,
        performedAt = Instant.parse("2026-11-10T07:00:00Z"),
        loadKg = loadKg
    )

    @Test
    fun anAbsentLoadIsValidBecauseItIsNotALoad() {
        assertTrue("no load recorded is a complete value", isPerformedLoad(null))
        assertTrue("and the set accepts it", set(null).loadKg == null)
    }

    @Test
    fun aLoadIsPositiveFiniteAndNoHeavierThanTheCeiling() {
        assertTrue("a light load", isPerformedLoad(0.5))
        assertTrue("a two-decimal load", isPerformedLoad(62.25))
        assertTrue("the ceiling itself is allowed", isPerformedLoad(MAX_PERFORMED_LOAD_KG))
        assertFalse("zero kilograms is not a load", isPerformedLoad(0.0))
        assertFalse("a negative load is not a load", isPerformedLoad(-5.0))
        assertFalse("above the ceiling is a typo", isPerformedLoad(MAX_PERFORMED_LOAD_KG + 0.01))
        assertFalse("NaN is not a measurement", isPerformedLoad(Double.NaN))
        assertFalse("infinity is not a measurement", isPerformedLoad(Double.POSITIVE_INFINITY))
        assertFalse("nor is negative infinity", isPerformedLoad(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun theSetRefusesALoadTheBoundDoesNotAllow() {
        for (rejected in listOf(0.0, -1.0, 500.5, Double.NaN)) {
            assertThrows(
                "a set with loadKg=$rejected is unrepresentable rather than merely discouraged",
                IllegalArgumentException::class.java
            ) { set(rejected) }
        }
    }

    @Test
    fun aLoadIsIndependentOfTheSetsUnit() {
        val timed = SetResult(
            setLogId = SetLogId("set-2"),
            setIndex = 1,
            completedReps = 0,
            durationSeconds = 40,
            performedAt = Instant.parse("2026-11-10T07:05:00Z"),
            loadKg = 10.0
        )
        assertTrue("a weighted hold carries a load and no repetitions", timed.loadKg == 10.0)
        assertTrue(timed.isRepetitionSet.not())
    }

    @Test
    fun theRepetitionBoundIsStatedAndAboveAnySingleSet() {
        assertTrue("a set of one repetition is measured", isPerformedAmount(1))
        assertTrue(
            "the typed bound is a positive count a person can perform",
            MAX_PERFORMED_REPS in 1..1000
        )
        assertTrue(
            "and it is far below the runtime's placeholder, which bounds a stored amount rather than a " +
                "typed one — the two rules are separate and both still hold",
            MAX_PERFORMED_REPS < PLACEHOLDER_PERFORMED_AMOUNT && isPerformedAmount(MAX_PERFORMED_REPS)
        )
    }
}
