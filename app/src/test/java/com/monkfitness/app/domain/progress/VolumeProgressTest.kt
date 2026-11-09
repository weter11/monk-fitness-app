package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.isPerformedAmount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volume as the Progress layer computes it: a windowed measure in the unit each set was performed in.
 *
 * Every expectation is derived by hand from [ProgressFixture]'s literals. Program A holds four
 * attempts inside the four-week window — one completed with a repetition and a timed exercise, one
 * cancelled with repetitions, one completed with both, one still running with repetitions — so the
 * mixed, bodyweight-only, cancelled and unfinished cases are all the same run.
 */
class VolumeProgressTest {

    private val utc = ProgressCalculator(ZoneId.of("UTC"))
    private val fourWeeks = ProgressWindow.ofLastDays(28, LocalDate.parse("2026-09-20"))

    @Test
    fun volumeIsWindowedAndKeepsRepetitionsAndSecondsApart() {
        val training = utc.training(ProgressFixture.factsOf(ProgressFixture.PROGRAM_A), fourWeeks)

        assertTrue(training.hasVolume)
        assertEquals(
            "newest attempt first; a cancelled attempt's confirmed work is still work",
            listOf("session-a6", "session-a5", "session-a3", "session-a1"),
            training.sessionVolumes.map { it.sessionId.value }
        )
        assertEquals(listOf(3, 14, 5, 22), training.sessionVolumes.map { it.repetitions })
        assertEquals(
            "seconds are a separate quantity, never added to repetitions",
            listOf(0, 30, 0, 75),
            training.sessionVolumes.map { it.seconds }
        )

        val week = training.weekVolumes.single()
        assertEquals("2026-09-14", week.weekStart.toString())
        assertEquals(44, week.repetitions)
        assertEquals(105, week.seconds)
        assertEquals(9, week.performedSets)

        assertEquals(listOf("plank", "pushup"), training.exerciseVolumes.map { it.exerciseId })
        val plank = training.exerciseVolumes.first { it.exerciseId == "plank" }
        assertEquals(PrescriptionDimension.TIME_BASED, plank.dimension)
        assertNull("a timed exercise carries no repetition total", plank.repetitions)
        assertEquals(105, plank.seconds)
        assertEquals(3, plank.performedSets)

        val pushup = training.exerciseVolumes.first { it.exerciseId == "pushup" }
        assertEquals(PrescriptionDimension.REP_BASED, pushup.dimension)
        assertEquals(44, pushup.repetitions)
        assertNull("a repetition exercise carries no duration total", pushup.seconds)
        assertEquals(6, pushup.performedSets)
    }

    @Test
    fun anAggregateAddsOnlyTheSameUnitAndOnlyInsideTheWindow() {
        val all = utc.training(ProgressFixture.allProgramsFacts(), fourWeeks)

        assertEquals(64, all.exerciseVolumes.first { it.exerciseId == "pushup" }.repetitions)
        assertEquals(105, all.exerciseVolumes.first { it.exerciseId == "plank" }.seconds)
        assertEquals(5, all.sessionVolumes.size)
    }

    @Test
    fun aSetOutsideTheWindowDoesNotContribute() {
        val slot = ProgressFixture.slot(
            "slot-old",
            "2026-08-01",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-old")),
            completedAt = Instant.parse("2026-08-01T07:30:00Z")
        )
        val old = ProgressFixture.session(
            "old",
            slot,
            Instant.parse("2026-08-01T07:00:00Z"),
            Instant.parse("2026-08-01T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "pushup",
                    RepPrescription(listOf(10)),
                    listOf(ProgressFixture.reps("set-old-1", 1, 100, Instant.parse("2026-08-01T07:10:00Z")))
                )
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(old)
        )

        val training = utc.training(facts, fourWeeks)
        assertFalse("a set before the window is not a volume in it", training.hasVolume)
        assertTrue(training.sessionVolumes.isEmpty())
        assertTrue(training.exerciseVolumes.isEmpty())
    }

    @Test
    fun weeksAreCalendarWeeksAndAWeekWithNoSetIsAbsent() {
        val firstSlot = ProgressFixture.slot(
            "slot-w1",
            "2026-08-31",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-w1")),
            completedAt = Instant.parse("2026-08-31T07:30:00Z")
        )
        val secondSlot = ProgressFixture.slot(
            "slot-w2",
            "2026-09-14",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-w2")),
            completedAt = Instant.parse("2026-09-14T07:30:00Z")
        )
        val first = ProgressFixture.session(
            "w1",
            firstSlot,
            Instant.parse("2026-08-31T07:00:00Z"),
            Instant.parse("2026-08-31T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "pushup",
                    RepPrescription(listOf(10)),
                    listOf(ProgressFixture.reps("set-w1-1", 1, 10, Instant.parse("2026-08-31T07:10:00Z")))
                )
            )
        )
        val later = ProgressFixture.session(
            "w2",
            secondSlot,
            Instant.parse("2026-09-14T07:00:00Z"),
            Instant.parse("2026-09-14T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "pushup",
                    RepPrescription(listOf(10)),
                    listOf(ProgressFixture.reps("set-w2-1", 1, 20, Instant.parse("2026-09-14T07:10:00Z")))
                )
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(firstSlot, secondSlot),
            sessions = listOf(first, later)
        )

        val training = utc.training(facts, fourWeeks)
        assertEquals(
            "two Mondays, and no row for the empty week between them",
            listOf("2026-08-31", "2026-09-14"),
            training.weekVolumes.map { it.weekStart.toString() }
        )
        assertEquals(listOf(10, 20), training.weekVolumes.map { it.repetitions })
    }

    @Test
    fun aPlaceholderAmountIsNotAVolumeAndNotARecord() {
        val placeholder = 99_999
        val slot = ProgressFixture.slot(
            "slot-sentinel",
            "2026-09-14",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-sentinel")),
            completedAt = Instant.parse("2026-09-14T07:30:00Z")
        )
        val session = ProgressFixture.session(
            "sentinel",
            slot,
            Instant.parse("2026-09-14T07:00:00Z"),
            Instant.parse("2026-09-14T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "pushup",
                    RepPrescription(listOf(8)),
                    listOf(
                        ProgressFixture.reps("set-s1", 1, 8, Instant.parse("2026-09-14T07:05:00Z")),
                        ProgressFixture.reps("set-s2", 2, placeholder, Instant.parse("2026-09-14T07:10:00Z")),
                        ProgressFixture.reps("set-s3", 3, 5, Instant.parse("2026-09-14T07:15:00Z"))
                    )
                )
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(session)
        )

        val training = utc.training(facts, fourWeeks)
        val pushup = training.series.single()
        assertEquals("the placeholder is not an observation", 2, pushup.performedSets)
        assertEquals("the record is the best real set", 8, pushup.bestRepetitions)
        assertEquals(13, training.exerciseVolumes.single().repetitions)
        assertEquals(13, training.sessionVolumes.single().repetitions)

        assertFalse(isPerformedAmount(placeholder))
        assertTrue(isPerformedAmount(8))
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceObservation(
                programId = ProgressFixture.PROGRAM_A,
                sessionId = SessionId("session-sentinel"),
                sessionStatus = SessionStatus.COMPLETED,
                setIndex = 1,
                performedAt = Instant.parse("2026-09-14T07:05:00Z"),
                repetitions = placeholder,
                seconds = 0
            )
        }
    }

    @Test
    fun aSessionWithOnlyAPlaceholderHasNoRecordAndNoVolume() {
        val slot = ProgressFixture.slot(
            "slot-placeholder-only",
            "2026-09-14",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-placeholder-only")),
            completedAt = Instant.parse("2026-09-14T07:30:00Z")
        )
        val session = ProgressFixture.session(
            "placeholder-only",
            slot,
            Instant.parse("2026-09-14T07:00:00Z"),
            Instant.parse("2026-09-14T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "pushup",
                    RepPrescription(listOf(8)),
                    listOf(ProgressFixture.reps("set-p1", 1, 99_999, Instant.parse("2026-09-14T07:05:00Z")))
                )
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(session)
        )

        val training = utc.training(facts, fourWeeks)
        assertTrue("nothing measured means no series, not a series with no record", training.series.isEmpty())
        assertFalse("and no volume, not 0 repetitions", training.hasVolume)
        assertTrue(training.sessionVolumes.isEmpty())
        assertTrue(training.exerciseVolumes.isEmpty())
    }

    @Test
    fun aTimedOnlySessionReportsSecondsAndNoRepetitionRow() {
        val slot = ProgressFixture.slot(
            "slot-timed",
            "2026-09-14",
            SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-timed")),
            completedAt = Instant.parse("2026-09-14T07:30:00Z")
        )
        val session = ProgressFixture.session(
            "timed",
            slot,
            Instant.parse("2026-09-14T07:00:00Z"),
            Instant.parse("2026-09-14T07:30:00Z"),
            performed = listOf(
                ProgressFixture.Performed(
                    "plank",
                    TimePrescription(listOf(30)),
                    listOf(ProgressFixture.seconds("set-t1", 1, 30, Instant.parse("2026-09-14T07:05:00Z")))
                )
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(session)
        )

        val training = utc.training(facts, fourWeeks)
        assertEquals(0, training.sessionVolumes.single().repetitions)
        assertEquals(30, training.sessionVolumes.single().seconds)
        assertEquals(30, training.exerciseVolumes.single().seconds)
        assertNull(training.exerciseVolumes.single().repetitions)
    }

    @Test
    fun noSessionsMeansNoVolumeAndNoZeroRows() {
        val training = utc.training(ProgressFacts.empty(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A)), fourWeeks)

        assertFalse(training.hasVolume)
        assertTrue(training.sessionVolumes.isEmpty())
        assertTrue(training.weekVolumes.isEmpty())
        assertTrue(training.exerciseVolumes.isEmpty())
        assertTrue(training.series.isEmpty())
        assertNull("an average of no measured attempt is not 0 seconds", training.averageSessionDuration.averageSeconds)
        assertEquals(0, training.frequency.completedSessions)
    }

    @Test
    fun anAttemptWithOnlyASkippedOccurrenceHasNoVolume() {
        val slot = ProgressFixture.slot("slot-skipped", "2026-09-14", SlotStatus.MISSED)
        val session = ProgressFixture.session(
            "skipped",
            slot,
            Instant.parse("2026-09-14T07:00:00Z"),
            Instant.parse("2026-09-14T07:30:00Z"),
            status = SessionStatus.CANCELLED,
            performed = listOf(
                ProgressFixture.Performed("pushup", RepPrescription(listOf(8)), emptyList(), skipped = true)
            )
        )
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(session)
        )

        val training = utc.training(facts, fourWeeks)
        assertFalse("a skipped occurrence observed nothing", training.hasVolume)
        assertTrue(training.sessionVolumes.isEmpty())
    }
}
