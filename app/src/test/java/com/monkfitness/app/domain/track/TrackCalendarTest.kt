package com.monkfitness.app.domain.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The retained **daily-track calendar**, and the one thing it must not change: which day a stored date is.
 *
 * ### Why this test exists at all
 *
 * §30 step 15 retired the shipped 56-day program's calendar (`domain/usecase/ProgramCalendar`). Two
 * **retained global features** used to read their position out of it — the posture/mobility track, whose
 * completed sessions are stored one per track day, and the nutrition planner's meal-phase offset — and
 * they keep the rhythm they always had. The arithmetic moved to [TrackCalendar] and its *owner* changed;
 * its *answers* must not, because rows recorded under the old calendar are read back under the new one.
 *
 * ### The oracle is written out, not imported
 *
 * The retired functions cannot be called (they are gone), so the expected values here are derived from
 * the formula the feature has always had — **day 1 is the anchor, day 56 is `anchor + 55`, and the next
 * cycle starts at `anchor + 56`** — computed independently in this file rather than read from the code
 * under test. A test that called `TrackCalendar` to compute its own expectation would prove nothing.
 *
 * ### And the persisted anchor is not a second source of truth
 *
 * The other half of the claim is about storage: an install that predates this stage already has a start
 * date, and it must keep the position it had rather than being silently moved to "today". That is a
 * property of `SettingsManager`'s two keys, and since this project has no Robolectric harness the
 * DataStore object itself cannot be constructed here — so the *source* is asserted, which is the strongest
 * available form of the claim: the legacy key is read, and never written.
 */
class TrackCalendarTest {

    private val anchor: LocalDate = LocalDate.of(2026, 1, 1)

    /** The shipped program's own arithmetic, restated here as an independent oracle. */
    private fun shippedDay(storedStart: LocalDate, date: LocalDate): Int {
        val elapsed = ChronoUnit.DAYS.between(storedStart, date).toInt()
        return min(56, max(1, elapsed + 1))
    }

    private fun shippedCycleAndDay(storedStart: LocalDate, date: LocalDate): Pair<Int, Int> {
        val elapsed = max(0L, ChronoUnit.DAYS.between(storedStart, date)).toInt()
        return (elapsed / 56 + 1) to (elapsed % 56 + 1)
    }

    @Test
    fun oneCycleIsFiftySixDays() {
        assertEquals(56, TrackCalendar.TRACK_DAYS)
    }

    @Test
    fun theCyclesAndDaysAreTheOnesTheRetiredCalendarGave() {
        // Every day of three cycles, plus the boundaries either side: the retained track's position for a
        // stored legacy start date is the position it always was, to the day.
        val offsets = listOf(-400L, -1L, 0L, 1L, 55L, 56L, 57L, 111L, 112L, 113L, 167L, 168L, 2000L)
        for (offset in offsets) {
            val date = anchor.plusDays(offset)
            assertEquals(
                "cycle/day at offset $offset from a stored legacy start date",
                shippedCycleAndDay(anchor, date),
                TrackCalendar.cycleAndDay(anchor, date)
            )
        }
    }

    @Test
    fun theCappedDayIsTheDayTheNutritionPhaseAlwaysUsed() {
        val offsets = listOf(-400L, -1L, 0L, 1L, 55L, 56L, 57L, 111L, 112L, 400L, 3650L)
        for (offset in offsets) {
            val date = anchor.plusDays(offset)
            assertEquals(
                "capped day at offset $offset from a stored legacy start date",
                shippedDay(anchor, date),
                TrackCalendar.cappedDay(anchor, date)
            )
        }
    }

    @Test
    fun theFirstDayIsTheAnchorAndTheCycleWrapsOneDayAfterItsLast() {
        assertEquals(1 to 1, TrackCalendar.cycleAndDay(anchor, anchor))
        assertEquals(1 to 56, TrackCalendar.cycleAndDay(anchor, anchor.plusDays(55)))
        assertEquals(
            "the day after a cycle's last is the next cycle's first: one date is one track day",
            2 to 1,
            TrackCalendar.cycleAndDay(anchor, anchor.plusDays(56))
        )
        assertEquals(
            "and a third cycle is the same arithmetic again",
            3 to 1,
            TrackCalendar.cycleAndDay(anchor, anchor.plusDays(112))
        )
    }

    @Test
    fun aDateBeforeTheAnchorIsTheTracksFirstDayRatherThanACycleZero() {
        // A track that started today has no yesterday. Producing cycle 0 — or a negative day — would let a
        // caller write a row into a cycle that never existed, so both readings clamp instead.
        assertEquals(1 to 1, TrackCalendar.cycleAndDay(anchor, anchor.minusDays(1)))
        assertEquals(1 to 1, TrackCalendar.cycleAndDay(anchor, anchor.minusYears(3)))
        assertEquals(1, TrackCalendar.cappedDay(anchor, anchor.minusDays(1)))
        assertEquals(1, TrackCalendar.cappedDay(anchor, anchor.minusYears(3)))
    }

    @Test
    fun theCappedDayStopsAtTheEndOfTheFirstCycleInsteadOfWrapping() {
        // The nutrition phase's reading: after the track's first 56 days it keeps planning from day 56
        // rather than restarting at 1 — the behaviour the shipped planner already had.
        assertEquals(56, TrackCalendar.cappedDay(anchor, anchor.plusDays(55)))
        assertEquals(56, TrackCalendar.cappedDay(anchor, anchor.plusDays(56)))
        assertEquals(56, TrackCalendar.cappedDay(anchor, anchor.plusDays(500)))
    }

    // ---- the persisted anchor -------------------------------------------------------------------

    private val settingsSource: String = File("src/main/java/com/monkfitness/app/data/local/SettingsManager.kt")
        .let { if (it.isFile) it else File("app/src/main/java/com/monkfitness/app/data/local/SettingsManager.kt") }
        .readText()

    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun theStoredLegacyStartDateIsStillReadAndIsNeverWritten() {
        val code = code(settingsSource)

        assertTrue(
            "the retained track's anchor lives under its own key now",
            code.contains("""stringPreferencesKey("track_start_date")""")
        )
        assertTrue(
            "and the key an older build wrote is still read, so an existing install keeps its position " +
                "instead of being moved to today",
            code.contains("""stringPreferencesKey("program_start_date")""")
        )
        assertTrue(
            "the read falls back to it",
            Regex("""preferences\[TRACK_START_DATE\]\s*[\s\S]{0,200}preferences\[LEGACY_START_DATE_KEY\]""")
                .containsMatchIn(code)
        )
        // The one-write half: the legacy key may be read, and must never be written. A write would make the
        // old key live again and give the anchor two homes — the second source of truth §6 forbids.
        assertFalse(
            "the legacy key is never assigned to",
            Regex("""preferences\[LEGACY_START_DATE_KEY\]\s*=""").containsMatchIn(code)
        )
        assertEquals(
            "and it is named in exactly the two reads the fallback needs — the flow and the stamp",
            2,
            Regex("""preferences\[LEGACY_START_DATE_KEY\]""").findAll(code).count()
        )
    }

    @Test
    fun theAnchorIsStampedOnceUnderTheCurrentKeyOnly() {
        val code = code(settingsSource)

        assertTrue(
            "the stamp writes the current key",
            Regex("""preferences\[TRACK_START_DATE\]\s*=""").containsMatchIn(code)
        )
        assertTrue(
            "and it carries an existing legacy value forward rather than replacing it with today",
            Regex("""preferences\[LEGACY_START_DATE_KEY\]\s*\?:\s*LocalDate\.now\(\)""").containsMatchIn(code)
        )
    }

    @Test
    fun theRetiredProgramPreferencesAreGone() {
        val code = code(settingsSource)

        for (retired in listOf(
            """stringPreferencesKey("program_cycle_number")""",
            """intPreferencesKey("program_cycle_number")""",
            """stringPreferencesKey("program_revision")""",
            """intPreferencesKey("program_revision")""",
            "startRevisedProgram",
            "resetProgramStartDate",
            "programSummaryDismissedFlow",
            "setProgramCycleNumber",
            "setProgramRevision"
        )) {
            assertFalse(
                "$retired belonged to the shipped program's stored cycle/revision state and is retired",
                code.contains(retired)
            )
        }
    }
}
