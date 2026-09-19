package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Progress vocabulary as *values*: what each type refuses to be, and what each derives rather than
 * stores.
 *
 * §21 and its constraints are mostly prohibitions — a missed opportunity that is not a zero, a volume that
 * cannot be summed across contexts, an average of nothing that is not zero seconds, a deferred measure that
 * is not a `0` — and a prohibition is only real if the model makes the forbidden state unrepresentable.
 * These tests are that, one `assertThrows` per shape the rest of the layer would otherwise have to remember
 * not to build.
 */
class ProgressFoundationTest {

    private val calculator = ProgressCalculator(ZoneId.of("UTC"))

    // ------------------------------------------------------------------ scope

    @Test
    fun aScopeIsEitherOneProgramOrTheAggregateAndTheAggregateHasNothingToIdentify() {
        val program = ProgressScope.OfProgram(ProgramId("program-a"))

        assertEquals(ProgramId("program-a"), program.programId)
        assertSame(
            "the aggregate is a single value with no members: there is nothing to identify, name, save or " +
                "select, which is what 'an aggregation view, not an entity' means mechanically (§21)",
            ProgressScope.AllPrograms,
            ProgressScope.AllPrograms
        )
        assertEquals(ProgressScope.AllPrograms, ProgressScope.AllPrograms)
        assertTrue("the aggregate is an object, not a case carrying state", ProgressScope.AllPrograms.toString().contains("AllPrograms"))
    }

    @Test
    fun aProgramScopedFactSetRefusesAnotherProgramsRows() {
        val a = ProgressFixture.factsOf(ProgressFixture.PROGRAM_A)
        val b = ProgressFixture.factsOf(ProgressFixture.PROGRAM_B)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), a.slots + b.slots, a.sessions)
        }
        assertTrue(
            "the refusal names what it saw, so a leak is diagnosable rather than mysterious: " +
                failure.message,
            failure.message!!.contains("program-a") && failure.message!!.contains("program-b")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), a.slots, a.sessions + b.sessions)
        }
        assertEquals(
            "and the aggregate is the case that legitimately holds several Programs' facts",
            listOf(ProgressFixture.PROGRAM_A, ProgressFixture.PROGRAM_B),
            ProgressFixture.allProgramsFacts().programIds
        )
    }

    @Test
    fun aFactIsReadIntoAnAggregationAtMostOnce() {
        val a = ProgressFixture.factsOf(ProgressFixture.PROGRAM_A)

        assertThrows(IllegalArgumentException::class.java) {
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), a.slots + a.slots, a.sessions)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), a.slots, a.sessions + a.sessions)
        }
        assertTrue(ProgressFacts.empty(ProgressScope.AllPrograms).isEmpty)
    }

    // ------------------------------------------------------------------ window

    @Test
    fun aWindowIsAnInclusiveRangeOfCalendarDates() {
        val window = ProgressWindow.ofLastDays(7, LocalDate.parse("2026-09-20"))

        assertEquals("2026-09-14", window.from.toString())
        assertEquals("2026-09-20", window.to.toString())
        assertEquals("both ends included, so seven dates", 7, window.calendarDays)
        assertTrue(window.contains(LocalDate.parse("2026-09-14")))
        assertTrue(window.contains(LocalDate.parse("2026-09-20")))
        assertTrue(!window.contains(LocalDate.parse("2026-09-13")))
        assertTrue(!window.contains(LocalDate.parse("2026-09-21")))
        assertEquals(
            "a one-date window is one date, not zero",
            1,
            ProgressWindow.ofLastDays(1, LocalDate.parse("2026-09-20")).calendarDays
        )
        assertEquals(
            "a 28-date window is four whole weeks",
            LocalDate.parse("2026-08-24"),
            ProgressWindow.ofLastDays(28, LocalDate.parse("2026-09-20")).from
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProgressWindow(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-14"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProgressWindow.ofLastDays(0, LocalDate.parse("2026-09-20"))
        }
    }

    // ------------------------------------------------------------------ duration

    @Test
    fun aDurationIsActualElapsedTimeAndARunningAttemptHasNone() {
        val sessions = ProgressFixture.sessionsOf(ProgressFixture.PROGRAM_A)
        val completed = sessions[0]
        val cancelled = sessions[1]
        val running = sessions[3]

        assertEquals(SessionDuration(SessionId("session-a1"), 1800L), durationOf(completed))
        assertEquals(
            "a cancelled attempt has a duration too — it is a fact about the attempt, and history reports it",
            SessionDuration(SessionId("session-a3"), 600L),
            durationOf(cancelled)
        )
        assertNull("an attempt still running has no duration, not a zero", durationOf(running))
        assertThrows(IllegalArgumentException::class.java) { SessionDuration(SessionId("s"), -1L) }
        assertEquals(
            "the duration comes from the two stored stamps and from nothing else",
            SessionStatus.COMPLETED,
            completed.status
        )
        assertEquals(
            "and the attempt that began at 23:30 and ended at 00:15 lasted 45 minutes, not a day",
            2700L,
            durationOf(sessions[2])!!.seconds
        )
    }

    @Test
    fun anAverageOfNothingIsNoAverageRatherThanZeroSeconds() {
        val none = AverageSessionDuration(measuredSessions = 0, totalSeconds = 0)
        val two = AverageSessionDuration(measuredSessions = 2, totalSeconds = 4500)

        assertNull(none.averageSeconds)
        assertTrue(!none.isMeasured)
        assertEquals(2250.0, two.averageSeconds!!, 1e-12)
        assertTrue(two.isMeasured)
        assertThrows(IllegalArgumentException::class.java) { AverageSessionDuration(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { AverageSessionDuration(1, -1) }
    }

    @Test
    fun frequencyCannotClaimMoreTrainingDaysThanWorkouts() {
        val frequency = TrainingFrequency(ProgressWindow.ofLastDays(7, LocalDate.parse("2026-09-20")), 3, 2)

        assertEquals(7, frequency.calendarDays)
        assertEquals(3.0, frequency.sessionsPerSevenDays, 1e-12)
        assertEquals(2.0 / 7.0, frequency.trainingDayRatio, 1e-12)
        assertThrows(IllegalArgumentException::class.java) { frequency.copy(trainingDays = 4) }
        assertThrows(IllegalArgumentException::class.java) { frequency.copy(completedSessions = -1) }
    }

    // ------------------------------------------------------------------ calendar

    @Test
    fun theCalendarCountsItsOwnEntriesSoItCannotContradictTheHistoryItDescribes() {
        val progress = calculator.calendar(ProgressFixture.factsOf(ProgressFixture.PROGRAM_A))

        assertEquals(progress.entries.count { it.status == SlotStatus.COMPLETED }, progress.completed)
        assertEquals(progress.entries.count { it.status == SlotStatus.MISSED }, progress.missed)
        assertEquals(progress.entries.count { it.status == SlotStatus.PLANNED }, progress.upcoming)
        assertEquals(progress.entries.count { it.status == SlotStatus.SUPERSEDED }, progress.superseded)
        assertEquals(
            "every opportunity is in exactly one of the four buckets",
            progress.opportunityCount,
            progress.completed + progress.missed + progress.upcoming + progress.superseded
        )
        assertThrows("the calendar is chronological", IllegalArgumentException::class.java) {
            CalendarProgress(progress.scope, progress.entries.reversed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalendarProgress(progress.scope, progress.entries + progress.entries.first())
        }
    }

    // ------------------------------------------------------------------ history

    @Test
    fun aHistoryEntryCannotClaimADurationItDoesNotHave() {
        val running = calculator.history(ProgressFixture.factsOf(ProgressFixture.PROGRAM_A)).last()
        val completed = calculator.history(ProgressFixture.factsOf(ProgressFixture.PROGRAM_A)).first()

        assertEquals(SessionStatus.IN_PROGRESS, running.status)
        assertNull(running.duration)
        assertThrows("an attempt still running has no duration", IllegalArgumentException::class.java) {
            running.copy(duration = SessionDuration(running.sessionId, 60))
        }
        assertThrows("a duration belongs to the entry's own attempt", IllegalArgumentException::class.java) {
            completed.copy(duration = SessionDuration(SessionId("somebody-else"), 60))
        }
        assertThrows(IllegalArgumentException::class.java) { completed.copy(performedSets = -1) }
        assertTrue(completed.isCompleted)
        assertTrue(!running.isCompleted)
    }

    // ------------------------------------------------------------------ comparability

    @Test
    fun aComparableContextNamesAnExerciseAndAUnitAndNothingElse() {
        assertThrows(IllegalArgumentException::class.java) { ComparableContext("", PrescriptionDimension.REP_BASED) }
        assertThrows(IllegalArgumentException::class.java) {
            ComparableContext("pushup", PrescriptionDimension.SET_BASED)
        }
        assertEquals(
            "two exercises are two contexts, never one",
            false,
            ComparableContext("pushup", PrescriptionDimension.REP_BASED) ==
                ComparableContext("pike_pushup", PrescriptionDimension.REP_BASED)
        )
        assertEquals(
            "and the same exercise in two units is two contexts as well",
            false,
            ComparableContext("pushup", PrescriptionDimension.REP_BASED) ==
                ComparableContext("pushup", PrescriptionDimension.TIME_BASED)
        )
    }

    @Test
    fun anObservationIsMeasuredInExactlyOneUnit() {
        val base = PerformanceObservation(
            ProgressFixture.PROGRAM_A, SessionId("s"), SessionStatus.COMPLETED, 1,
            ProgressFixture.SET_ONE, repetitions = 10, seconds = 0
        )

        assertThrows(IllegalArgumentException::class.java) { base.copy(seconds = 30) }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(repetitions = 0, seconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { base.copy(setIndex = 0) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(repetitions = -1) }
        assertTrue(base.isRepetitionObservation)
    }

    @Test
    fun aSeriesIsOrderedAndMeasuredInOneUnit() {
        val context = ComparableContext("pushup", PrescriptionDimension.REP_BASED)
        val first = observation("session-1", 1, ProgressFixture.SET_ONE, reps = 10)
        val second = observation("session-2", 1, ProgressFixture.SET_TWO, reps = 12)
        val series = ExercisePerformanceSeries(context, listOf(first, second))

        assertEquals(2, series.performedSets)
        assertEquals(12, series.bestRepetitions)
        assertThrows("a series exists because something was observed", IllegalArgumentException::class.java) {
            ExercisePerformanceSeries(context, emptyList())
        }
        assertThrows("the sequence is the order it happened in", IllegalArgumentException::class.java) {
            ExercisePerformanceSeries(context, listOf(second, first))
        }
        assertThrows("every observation is in the context's own unit", IllegalArgumentException::class.java) {
            ExercisePerformanceSeries(
                context,
                listOf(first, second.copy(repetitions = 0, seconds = 30))
            )
        }
        assertEquals(
            "the most recent observation is the last one of the series",
            second,
            series.latest
        )
        assertNull(
            "a repetition context has no best duration to report",
            series.bestSeconds
        )
    }

    @Test
    fun aVolumeIsAlwaysInAContextAndNeverInTwoUnits() {
        val reps = ComparableContext("pushup", PrescriptionDimension.REP_BASED)
        val seconds = ComparableContext("plank", PrescriptionDimension.TIME_BASED)

        assertEquals(ContextVolume(reps, 3, 44, null), ContextVolume(reps, 3, 44, null))
        assertEquals(ContextVolume(seconds, 3, null, 105), ContextVolume(seconds, 3, null, 105))
        assertThrows("a volume exists because sets were performed", IllegalArgumentException::class.java) {
            ContextVolume(reps, 0, 0, null)
        }
        assertThrows(IllegalArgumentException::class.java) { ContextVolume(reps, 3, null, 105) }
        assertThrows(IllegalArgumentException::class.java) { ContextVolume(reps, 3, 44, 105) }
        assertThrows(IllegalArgumentException::class.java) { ContextVolume(seconds, 3, 44, null) }
    }

    // ------------------------------------------------------------------ streak

    @Test
    fun aStreakCannotClaimARunLongerThanItsLongest() {
        val streak = ProgramStreak(ProgressFixture.PROGRAM_A, current = 2, longest = 3, takenOpportunities = 5, decidedOpportunities = 7)

        assertEquals(2, streak.current)
        assertThrows(IllegalArgumentException::class.java) { streak.copy(current = 4) }
        assertThrows(IllegalArgumentException::class.java) { streak.copy(takenOpportunities = 8) }
        assertThrows(IllegalArgumentException::class.java) { streak.copy(longest = -1) }
    }

    // ------------------------------------------------------------------ deferred

    @Test
    fun aDeferredMeasureSaysWhyItIsDeferredAndThereAreThreeOfThem() {
        assertEquals(
            "the §21 measures the target facts cannot answer, and nothing else: focus distribution, " +
                "family distribution and the Program PR view",
            listOf(ProgressMeasure.FOCUS_DISTRIBUTION, ProgressMeasure.FAMILY_DISTRIBUTION, ProgressMeasure.PROGRAM_PR),
            ProgressMeasure.entries.toList()
        )
        assertThrows(IllegalArgumentException::class.java) {
            DeferredMeasure(ProgressMeasure.PROGRAM_PR, "  ")
        }
        assertTrue(ProgressCalculator.DEFERRED_MEASURES.all { it.reason.contains("§") })
    }

    // ------------------------------------------------------------------ composed value

    @Test
    fun trainingProgressRefusesToContradictItsOwnParts() {
        val training = calculator.training(ProgressFixture.factsOf(ProgressFixture.PROGRAM_A), ProgressWindow.ofLastDays(7, LocalDate.parse("2026-09-20")))

        assertThrows("the rate-like measures share one window", IllegalArgumentException::class.java) {
            training.copy(frequency = training.frequency.copy(window = ProgressWindow.ofLastDays(14, LocalDate.parse("2026-09-20"))))
        }
        assertThrows("a Program has one streak", IllegalArgumentException::class.java) {
            training.copy(streaks = training.streaks + training.streaks.first())
        }
        assertThrows("one series per comparable context", IllegalArgumentException::class.java) {
            training.copy(series = training.series + training.series.first())
        }
        assertThrows("one volume per comparable context", IllegalArgumentException::class.java) {
            training.copy(volumes = training.volumes + training.volumes.first())
        }
        assertThrows("a measure is reported as deferred once", IllegalArgumentException::class.java) {
            training.copy(deferred = training.deferred + training.deferred.first())
        }
        assertEquals(
            "and the composition derives nothing from the calendar: it holds what it was computed from",
            ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            training.scope
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun observation(
        sessionId: String,
        setIndex: Int,
        performedAt: Instant,
        reps: Int
    ) = PerformanceObservation(
        programId = ProgressFixture.PROGRAM_A,
        sessionId = SessionId(sessionId),
        sessionStatus = SessionStatus.COMPLETED,
        setIndex = setIndex,
        performedAt = performedAt,
        repetitions = reps,
        seconds = 0
    )

    /** A prescription in a dimension with no unit contract cannot be built: the model has no subtype. */
    @Test
    fun theReservedDimensionsHaveNoRepresentablePrescription() {
        assertEquals(
            "the three dimensions without a unit contract are named by the model and have no subtype, " +
                "which is why nothing comparable can be built in them (§10, §17)",
            listOf(
                PrescriptionDimension.SET_BASED,
                PrescriptionDimension.DIFFICULTY_BASED,
                PrescriptionDimension.REST_BASED
            ),
            PrescriptionDimension.entries.filterNot { it.hasComparableUnit }
        )
        assertTrue(RepPrescription(listOf(5)).dimension.hasComparableUnit)
    }
}
