package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facts the Progress suites are computed over, written out by hand.
 *
 * Every expectation in `ProgressCalculatorTest` is derived from **these** literals, not from the
 * calculator: the plan of Program A is five opportunities in five different states, its attempts include a
 * completed workout, a cancelled one and one still running, and Program B contributes a completed workout
 * of the **same exercise** as A (which is what makes an aggregate comparison meaningful) plus a missed
 * opportunity of its own.
 *
 * ```text
 * Program A                                     Program B
 *   a1  09-14 COMPLETED  session-a1              b1  09-14 COMPLETED  session-b1
 *   a2  09-15 MISSED     (attempt session-a3)    b2  09-16 MISSED     (no attempt)
 *   a3  09-16 PLANNED    (attempt session-a6)
 *   a4  09-17 SUPERSEDED
 *   a5  09-18 COMPLETED  session-a5
 *
 * session-a1  start 09-14 07:30Z  finish 07:30→08:00Z  1800 s  COMPLETED   pushup 12,10  plank 30,45
 * session-a3  start 09-15 07:00Z  finish 07:00→07:10Z   600 s  CANCELLED   pushup 5
 * session-a5  start 09-18 23:30Z  finish →09-19 00:15Z 2700 s  COMPLETED   pushup 8,6    plank 30
 * session-a6  start 09-20 07:00Z  (no finish)                IN_PROGRESS pushup 3
 * session-b1  start 09-14 09:00Z  finish 09:00→09:12Z   720 s  COMPLETED   pushup 20
 * ```
 */
internal object ProgressFixture {

    val PROGRAM_A = ProgramId("program-a")
    val PROGRAM_B = ProgramId("program-b")

    val SET_ONE: Instant = Instant.parse("2026-09-14T07:40:00Z")
    val SET_TWO: Instant = Instant.parse("2026-09-14T07:45:00Z")
    val SET_THREE: Instant = Instant.parse("2026-09-14T07:50:00Z")
    val SET_FOUR: Instant = Instant.parse("2026-09-14T07:55:00Z")

    fun slot(
        id: String,
        plannedFor: String,
        status: SlotStatus = SlotStatus.PLANNED,
        programId: ProgramId = PROGRAM_A,
        attempts: List<SessionId> = emptyList(),
        completedAt: Instant? = null
    ) = WorkoutSlot(
        slotId = SlotId(id),
        programId = programId,
        revisionId = RevisionId("revision-${programId.value}"),
        programDayId = ProgramDayId("day-$id"),
        plannedFor = LocalDate.parse(plannedFor),
        status = status,
        attempts = attempts,
        completedAt = completedAt
    )

    fun slotsOf(programId: ProgramId): List<WorkoutSlot> = if (programId == PROGRAM_A) {
        listOf(
            slot("slot-a-1", "2026-09-14", SlotStatus.COMPLETED, attempts = listOf(SessionId("session-a1")), completedAt = Instant.parse("2026-09-14T08:00:00Z")),
            slot("slot-a-2", "2026-09-15", SlotStatus.MISSED, attempts = listOf(SessionId("session-a3"))),
            slot("slot-a-3", "2026-09-16", SlotStatus.PLANNED, attempts = listOf(SessionId("session-a6"))),
            slot("slot-a-4", "2026-09-17", SlotStatus.SUPERSEDED),
            slot("slot-a-5", "2026-09-18", SlotStatus.COMPLETED, attempts = listOf(SessionId("session-a5")), completedAt = Instant.parse("2026-09-19T00:15:00Z"))
        )
    } else {
        listOf(
            slot("slot-b-1", "2026-09-14", SlotStatus.COMPLETED, programId, listOf(SessionId("session-b1")), Instant.parse("2026-09-14T09:12:00Z")),
            slot("slot-b-2", "2026-09-16", SlotStatus.MISSED, programId)
        )
    }

    /** One occurrence as it ran: the exercise, the prescription presented for it, and the sets confirmed. */
    data class Performed(
        val exerciseId: String,
        val prescription: Prescription,
        val sets: List<SetResult> = emptyList(),
        val skipped: Boolean = false
    )

    fun reps(id: String, index: Int, reps: Int, at: Instant) =
        SetResult(SetLogId(id), index, completedReps = reps, durationSeconds = 0, performedAt = at)

    fun seconds(id: String, index: Int, seconds: Int, at: Instant) =
        SetResult(SetLogId(id), index, completedReps = 0, durationSeconds = seconds, performedAt = at)

    fun session(
        key: String,
        slot: WorkoutSlot,
        startedAt: Instant,
        finishedAt: Instant? = null,
        status: SessionStatus = if (finishedAt == null) SessionStatus.IN_PROGRESS else SessionStatus.COMPLETED,
        performed: List<Performed> = emptyList()
    ): WorkoutSession {
        val sessionId = SessionId("session-$key")
        val elements = performed.mapIndexed { index, occurrence ->
            EffectiveExercise(
                ProgramExerciseId("plan-ex-$key-${index + 1}"),
                occurrence.exerciseId,
                occurrence.prescription
            )
        }
        return WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = WorkoutSessionSnapshot(
                sessionId = sessionId,
                capturedAt = startedAt,
                workout = EffectiveWorkout(
                    slotId = slot.slotId,
                    programId = slot.programId,
                    revisionId = slot.revisionId,
                    plannedFor = slot.plannedFor,
                    computedAt = startedAt,
                    exercises = elements
                )
            ),
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exercises = performed.mapIndexed { index, occurrence ->
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("session-ex-$key-${index + 1}"),
                    programExerciseId = ProgramExerciseId("plan-ex-$key-${index + 1}"),
                    exerciseId = occurrence.exerciseId,
                    prescription = occurrence.prescription,
                    results = occurrence.sets,
                    skipped = occurrence.skipped
                )
            }
        )
    }

    fun sessionsOf(programId: ProgramId): List<WorkoutSession> {
        val a = slotsOf(PROGRAM_A)
        val b = slotsOf(PROGRAM_B)
        return if (programId == PROGRAM_A) {
            listOf(
                session(
                    "a1", a[0], Instant.parse("2026-09-14T07:30:00Z"), Instant.parse("2026-09-14T08:00:00Z"),
                    performed = listOf(
                        Performed("pushup", RepPrescription(listOf(10)), listOf(reps("set-a1-1", 1, 12, SET_ONE), reps("set-a1-2", 2, 10, SET_TWO))),
                        Performed("plank", TimePrescription(listOf(30)), listOf(seconds("set-a1-3", 1, 30, SET_THREE), seconds("set-a1-4", 2, 45, SET_FOUR)))
                    )
                ),
                session(
                    "a3", a[1], Instant.parse("2026-09-15T07:00:00Z"), Instant.parse("2026-09-15T07:10:00Z"),
                    status = SessionStatus.CANCELLED,
                    performed = listOf(
                        Performed("pushup", RepPrescription(listOf(10)), listOf(reps("set-a3-1", 1, 5, Instant.parse("2026-09-15T07:05:00Z"))))
                    )
                ),
                session(
                    "a5", a[4], Instant.parse("2026-09-18T23:30:00Z"), Instant.parse("2026-09-19T00:15:00Z"),
                    performed = listOf(
                        Performed("pushup", RepPrescription(listOf(8)), listOf(reps("set-a5-1", 1, 8, Instant.parse("2026-09-18T23:40:00Z")), reps("set-a5-2", 2, 6, Instant.parse("2026-09-18T23:50:00Z")))),
                        Performed("plank", TimePrescription(listOf(30)), listOf(seconds("set-a5-3", 1, 30, Instant.parse("2026-09-19T00:05:00Z"))))
                    )
                ),
                session(
                    "a6", a[2], Instant.parse("2026-09-20T07:00:00Z"),
                    performed = listOf(
                        Performed("pushup", RepPrescription(listOf(10)), listOf(reps("set-a6-1", 1, 3, Instant.parse("2026-09-20T07:05:00Z"))))
                    )
                )
            )
        } else {
            listOf(
                session(
                    "b1", b[0], Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T09:12:00Z"),
                    performed = listOf(
                        Performed("pushup", RepPrescription(listOf(10)), listOf(reps("set-b1-1", 1, 20, Instant.parse("2026-09-14T09:05:00Z"))))
                    )
                )
            )
        }
    }

    fun factsOf(programId: ProgramId) = ProgressFacts(
        scope = ProgressScope.OfProgram(programId),
        slots = slotsOf(programId),
        sessions = sessionsOf(programId)
    )

    fun allProgramsFacts() = ProgressFacts(
        scope = ProgressScope.AllPrograms,
        slots = slotsOf(PROGRAM_A) + slotsOf(PROGRAM_B),
        sessions = sessionsOf(PROGRAM_A) + sessionsOf(PROGRAM_B)
    )
}

/**
 * `ProgressCalculator`: the §21 measures of §30 step 9, and the four prohibitions that shape them — a
 * missed opportunity is not a zero, a cancelled attempt is not a completed workout, no measure mixes two
 * exercises or two units, and no measure reads "today" unless it is a rate.
 *
 * Every number asserted here is derivable by hand from `ProgressFixture`'s literals, and the comments say
 * how.
 */
class ProgressCalculatorTest {

    private val utc = ProgressCalculator(ZoneId.of("UTC"))

    /** The seven calendar dates ending on `2026-09-20`: `2026-09-14 .. 2026-09-20`. */
    private val week = ProgressWindow.ofLastDays(7, LocalDate.parse("2026-09-20"))

    private val programA = ProgressFixture.factsOf(ProgressFixture.PROGRAM_A)
    private val programB = ProgressFixture.factsOf(ProgressFixture.PROGRAM_B)
    private val allPrograms = ProgressFixture.allProgramsFacts()

    // ------------------------------------------------------------------ calendar

    @Test
    fun theCalendarIsChronologicalAndCountsEachStatusAsItsOwnFact() {
        val calendar = utc.calendar(programA)

        assertEquals(
            "the opportunities, in the order they were planned for",
            listOf("slot-a-1", "slot-a-2", "slot-a-3", "slot-a-4", "slot-a-5"),
            calendar.entries.map { it.slotId.value }
        )
        assertEquals(listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18"),
            calendar.entries.map { it.plannedFor.toString() })
        assertEquals("two opportunities were taken", 2, calendar.completed)
        assertEquals("one passed", 1, calendar.missed)
        assertEquals("one is still ahead", 1, calendar.upcoming)
        assertEquals(
            "and one was replaced before it was trained — reported rather than folded into missed, " +
                "because the user was never expected to train it (§20)",
            1,
            calendar.superseded
        )
        assertEquals(5, calendar.opportunityCount)
        assertEquals(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), calendar.scope)
    }

    @Test
    fun aMissedOpportunityCarriesNoWorkAndNoAttemptIsInventedForIt() {
        val missedOnly = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_B),
            slots = listOf(ProgressFixture.slotsOf(ProgressFixture.PROGRAM_B)[1]),
            sessions = emptyList()
        )

        val calendar = utc.calendar(missedOnly)
        assertEquals(1, calendar.missed)
        assertEquals(0, calendar.completed)
        assertEquals(
            "the opportunity records that it passed and nothing else: no attempt, no set, no amount of " +
                "work that could be read as zero (§12)",
            emptyList<SessionId>(),
            calendar.entries.single().attempts
        )
    }

    @Test
    fun anOpenOpportunityStaysOpenEvenWhenItsDateHasPassed() {
        val stale = ProgressFixture.slot("slot-a-1", "2026-01-01", SlotStatus.PLANNED)
        val calendar = utc.calendar(
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), listOf(stale), emptyList())
        )

        assertEquals(
            "upcoming is the PLANNED status §20 owns, never a date comparison made here: deciding from " +
                "'its date has passed' would be a second missed-ness rule (§33)",
            1,
            calendar.upcoming
        )
        assertEquals(0, calendar.missed)
        assertEquals("2026-01-01", calendar.entries.single().plannedFor.toString())
    }

    // ------------------------------------------------------------------ history

    @Test
    fun historyIsOneEntryPerAttemptInStartOrderWithItsOwnExposure() {
        val history = utc.history(programA)

        assertEquals(
            "every attempt, oldest first — and no entry for an opportunity nobody attempted",
            listOf("session-a1", "session-a3", "session-a5", "session-a6"),
            history.map { it.sessionId.value }
        )
        val completed = history.first()
        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertEquals("2026-09-14", completed.plannedFor.toString())
        assertEquals(1800L, completed.duration?.seconds)
        assertEquals("four sets were confirmed, across two occurrences", 4, completed.performedSets)
        assertEquals(2, completed.exposedExercises)

        val cancelled = history[1]
        assertEquals(
            "a cancelled attempt stays cancelled and keeps the work it recorded (§12, §19)",
            SessionStatus.CANCELLED,
            cancelled.status
        )
        assertEquals(1, cancelled.performedSets)
        assertTrue("the cancelled attempt has exposure", cancelled.hasExposure)
        assertEquals("and its own actual duration", 600L, cancelled.duration?.seconds)

        val running = history.last()
        assertEquals(SessionStatus.IN_PROGRESS, running.status)
        assertNull(
            "an attempt that is still running has no duration — not a zero and not 'so far'",
            running.duration
        )
        assertEquals(
            "and the sets it has confirmed so far are already exposure",
            1,
            running.performedSets
        )
    }

    @Test
    fun aSkippedOccurrenceIsCountedAsSkippedAndObservesNothing() {
        val slot = ProgressFixture.slot("slot-a-1", "2026-09-14", SlotStatus.PLANNED)
        val facts = ProgressFacts(
            ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            listOf(slot),
            listOf(
                ProgressFixture.session(
                    "skip", slot, Instant.parse("2026-09-14T07:30:00Z"),
                    performed = listOf(
                        ProgressFixture.Performed("pushup", RepPrescription(listOf(10)), skipped = true),
                        ProgressFixture.Performed("plank", TimePrescription(listOf(30)), listOf(ProgressFixture.seconds("set-skip-1", 1, 30, ProgressFixture.SET_ONE)))
                    )
                )
            )
        )

        val item = utc.history(facts).single()
        assertEquals(1, item.skippedExercises)
        assertEquals(1, item.performedSets)
        assertEquals("a skipped occurrence observed nothing, and is not a zero-valued one", 1, item.exposedExercises)
    }

    // ------------------------------------------------------------------ frequency, duration

    @Test
    fun frequencyCountsCompletedWorkoutsOnTheDatesTheyActuallyStarted() {
        val frequency = utc.training(programA, week).frequency

        assertEquals(
            "the completed workouts inside 2026-09-14..2026-09-20: session-a1 and session-a5",
            2,
            frequency.completedSessions
        )
        assertEquals("on two distinct dates", 2, frequency.trainingDays)
        assertEquals(7, frequency.calendarDays)
        assertEquals(2.0, frequency.sessionsPerSevenDays, 1e-12)
        assertEquals(2.0 / 7.0, frequency.trainingDayRatio, 1e-12)
    }

    @Test
    fun averageDurationComesFromTheActualStartAndFinishStamps() {
        val average = utc.training(programA, week).averageSessionDuration

        assertEquals("two completed workouts were measured", 2, average.measuredSessions)
        assertEquals("1800 s plus 2700 s", 4500L, average.totalSeconds)
        assertEquals(
            "the second one began at 23:30 on one date and ended at 00:15 the next: it lasted 45 minutes, " +
                "and it lasted 45 minutes whatever dates the two instants fall on",
            2250.0,
            average.averageSeconds!!,
            1e-12
        )
    }

    @Test
    fun aCancelledAttemptIsNotAWorkoutAndIsNotCountedAsOne() {
        val cancelledOnly = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(ProgressFixture.slotsOf(ProgressFixture.PROGRAM_A)[1]),
            sessions = listOf(ProgressFixture.sessionsOf(ProgressFixture.PROGRAM_A)[1])
        )
        val training = utc.training(cancelledOnly, week)

        assertEquals("cancelled is not a completion (§19)", 0, training.frequency.completedSessions)
        assertEquals(0, training.frequency.trainingDays)
        assertEquals("no workout was measured, so there is no average", 0, training.averageSessionDuration.measuredSessions)
        assertNull(
            "an average of nothing is not zero seconds",
            training.averageSessionDuration.averageSeconds
        )
        assertEquals(
            "and the opportunity is still the missed one it was — a cancellation does not take it",
            1,
            utc.calendar(cancelledOnly).missed
        )
    }

    @Test
    fun aMissedOpportunityProducesNoPerformanceAndNoVolumeOfItsOwn() {
        val training = utc.training(programB, week)
        val missed = ProgressFixture.slotsOf(ProgressFixture.PROGRAM_B)[1]

        assertEquals(1, utc.calendar(programB).missed)
        assertEquals(
            "the missed opportunity's own facts are: it passed. It contributes no observation, no volume " +
                "and no workout — the only set in this scope was performed by a different opportunity",
            1,
            training.series.single().performedSets
        )
        assertEquals(
            "and the single observation is the completed workout's, not the missed opportunity's",
            listOf("session-b1"),
            training.series.single().observations.map { it.sessionId.value }
        )
        assertTrue(
            "nothing at all is recorded about ${missed.slotId.value} beyond the fact that it passed",
            missed.attempts.isEmpty()
        )
    }

    @Test
    fun aPartiallyCancelledAttemptKeepsTheExposureItRecorded() {
        val slot = ProgressFixture.slotsOf(ProgressFixture.PROGRAM_A)[1]
        val cancelled = ProgressFixture.sessionsOf(ProgressFixture.PROGRAM_A)[1]
        val facts = ProgressFacts(
            scope = ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            slots = listOf(slot),
            sessions = listOf(cancelled)
        )
        val series = utc.training(facts, week).series.single()

        assertEquals("the five repetitions it confirmed are observations", 1, series.performedSets)
        assertEquals(5, series.observations.single().repetitions)
        assertEquals(
            "reported with the status that attempt ended with, rather than promoted to a completed " +
                "workout's set or dropped as if it had not happened",
            SessionStatus.CANCELLED,
            series.observations.single().sessionStatus
        )
        assertEquals(
            "and the opportunity it was made on is still the missed one: partial exposure does not turn " +
                "a passed opportunity into a taken one",
            1,
            utc.calendar(facts).missed
        )
    }

    // ------------------------------------------------------------------ comparability

    @Test
    fun performanceIsPerExerciseAndUnitAndTheTwoAreNeverOneSeries() {
        val series = utc.training(programA, week).series

        assertEquals(
            "one series per exercise-and-unit context, one exercise first and then the other",
            listOf(ComparableContext("plank", PrescriptionDimension.TIME_BASED), ComparableContext("pushup", PrescriptionDimension.REP_BASED)),
            series.map { it.context }
        )
        val plank = series.first { it.context.exerciseId == "plank" }
        val pushup = series.last()
        assertEquals(3, plank.performedSets)
        assertEquals(
            "a timed context observes seconds and cannot observe repetitions",
            0,
            plank.observations.count { it.repetitions > 0 }
        )
        assertEquals(null, plank.bestRepetitions)
        assertEquals(45, plank.bestSeconds)
        assertEquals(6, pushup.performedSets)
        assertEquals(0, pushup.observations.count { it.seconds > 0 })
        assertEquals(12, pushup.bestRepetitions)
        assertEquals(null, pushup.bestSeconds)
    }

    @Test
    fun aSeriesIsInTheOrderItHappenedEvenWhenTheFactsArriveShuffled() {
        val observations = utc.training(programA, week).series
            .first { it.context.exerciseId == "pushup" }
            .observations

        assertEquals(
            "the pushup sets in the order they were confirmed: a1's two, then the cancelled attempt's, " +
                "then a5's two, then the running one's",
            listOf(
                "session-a1#1", "session-a1#2", "session-a3#1", "session-a5#1", "session-a5#2", "session-a6#1"
            ),
            observations.map { "${it.sessionId.value}#${it.setIndex}" }
        )
        assertEquals(
            "and the sequence is a function of the facts, not of the order they were read in",
            observations,
            utc.training(
                ProgressFacts(
                    ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
                    programA.slots.reversed(),
                    programA.sessions.reversed()
                ),
                week
            ).series.first { it.context.exerciseId == "pushup" }.observations
        )
    }

    @Test
    fun volumeIsPerContextAndThereIsNoTotalAnywhere() {
        val training = utc.training(programA, week)

        assertEquals(
            listOf(ComparableContext("plank", PrescriptionDimension.TIME_BASED), ComparableContext("pushup", PrescriptionDimension.REP_BASED)),
            training.volumes.map { it.context }
        )
        val plank = training.volumeOf("plank", PrescriptionDimension.TIME_BASED)!!
        val pushup = training.volumeOf("pushup", PrescriptionDimension.REP_BASED)!!

        assertEquals(3, plank.performedSets)
        assertEquals(105, plank.seconds)
        assertEquals("a timed context carries no repetitions at all", null, plank.repetitions)
        assertEquals(6, pushup.performedSets)
        assertEquals(44, pushup.repetitions)
        assertEquals("a repetition context carries no seconds at all", null, pushup.seconds)
        assertEquals(
            "each volume carries its own unit and nothing of the other, so there is no field in which " +
                "105 seconds and 44 repetitions could become one number (§17)",
            listOf(null to 105, 44 to null),
            training.volumes.map { it.repetitions to it.seconds }
        )
    }

    @Test
    fun anExerciseWithNoObservedSetsHasNoSeriesAndNoVolume() {
        val training = utc.training(programB, week)

        assertEquals(1, training.series.size)
        assertNull(
            "an exercise nothing was performed for is absent rather than present-and-zero",
            training.seriesOf("plank", PrescriptionDimension.TIME_BASED)
        )
        assertNull(training.volumeOf("plank", PrescriptionDimension.TIME_BASED))
        assertEquals(1, training.volumeOf("pushup", PrescriptionDimension.REP_BASED)?.performedSets)
    }

    @Test
    fun theReservedDimensionsHaveNoComparableContextAtAll() {
        assertThrows(IllegalArgumentException::class.java) {
            ComparableContext("pushup", PrescriptionDimension.SET_BASED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComparableContext("pushup", PrescriptionDimension.DIFFICULTY_BASED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComparableContext("pushup", PrescriptionDimension.REST_BASED)
        }
        assertEquals(
            "exactly the two dimensions §10 implements carry a unit contract",
            listOf(PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED),
            PrescriptionDimension.entries.filter { it.hasComparableUnit }
        )
    }

    @Test
    fun aVolumeCannotMixTheTwoUnits() {
        val context = ComparableContext("pushup", PrescriptionDimension.REP_BASED)
        assertThrows(IllegalArgumentException::class.java) {
            ContextVolume(context, performedSets = 2, repetitions = 20, seconds = 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextVolume(context, performedSets = 2, repetitions = 20, seconds = null).copy(seconds = 30)
        }
    }

    // ------------------------------------------------------------------ streak

    @Test
    fun theStreakRunsOverTakenOpportunitiesAndTreatsTheTwoUndecidedStatesAsTransparent() {
        val streak = utc.training(programA, week).streakOf(ProgressFixture.PROGRAM_A)!!

        assertEquals(
            "a1 (taken), a2 (missed — the run ends), a3 (still open) and a4 (replaced) are transparent, " +
                "a5 (taken) — so one run is running at the end",
            1,
            streak.current
        )
        assertEquals("and the longest run this Program has had is also one", 1, streak.longest)
        assertEquals(2, streak.takenOpportunities)
        assertEquals("five opportunities, three of them decided", 3, streak.decidedOpportunities)
    }

    @Test
    fun aMissedOpportunityEndsTheRunAndASupersededOneDoesNot() {
        fun taken(id: String, date: String) = ProgressFixture.slot(
            id, date, SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-$id")),
            completedAt = Instant.parse("${date}T08:00:00Z")
        )

        fun missed(id: String, date: String) = ProgressFixture.slot(id, date, SlotStatus.MISSED)

        fun replaced(id: String, date: String) = ProgressFixture.slot(id, date, SlotStatus.SUPERSEDED)

        fun streakOf(vararg slots: WorkoutSlot) = utc.training(
            ProgressFacts(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A), slots.toList(), emptyList()),
            week
        ).streakOf(ProgressFixture.PROGRAM_A)!!

        assertEquals(
            "a replaced opportunity is transparent: the two taken ones it sits between are one run",
            2,
            streakOf(taken("t1", "2026-09-14"), replaced("t2", "2026-09-15"), taken("t3", "2026-09-16")).current
        )
        assertEquals(
            "a missed one ends the run, so the taken opportunity after it starts a new one",
            1,
            streakOf(taken("t1", "2026-09-14"), missed("t2", "2026-09-15"), taken("t3", "2026-09-16")).current
        )
        assertEquals(
            "and a Program whose last decided opportunity was missed has no streak running",
            0,
            streakOf(taken("t1", "2026-09-14"), taken("t2", "2026-09-15"), missed("t3", "2026-09-16")).current
        )
        assertEquals(
            "while the longest run it has had is still reported",
            2,
            streakOf(taken("t1", "2026-09-14"), taken("t2", "2026-09-15"), missed("t3", "2026-09-16")).longest
        )
        assertEquals(
            "an opportunity that is still open neither extends nor ends a run",
            2,
            streakOf(
                taken("t1", "2026-09-14"),
                taken("t2", "2026-09-15"),
                ProgressFixture.slot("t3", "2026-09-16", SlotStatus.PLANNED)
            ).current
        )
        assertEquals(
            "a streak about nothing is zero, of the right type",
            0,
            streakOf().current
        )
    }

    // ------------------------------------------------------------------ aggregate

    @Test
    fun theAggregateIsTheSumOfProgramScopedFactsAndHoldsNoIdentityOfItsOwn() {
        val aggregate = utc.calendar(allPrograms)
        val a = utc.calendar(programA)
        val b = utc.calendar(programB)

        assertEquals("the aggregate's completed count is A's plus B's", a.completed + b.completed, aggregate.completed)
        assertEquals(a.missed + b.missed, aggregate.missed)
        assertEquals(a.upcoming + b.upcoming, aggregate.upcoming)
        assertEquals(a.superseded + b.superseded, aggregate.superseded)
        assertEquals(7, aggregate.opportunityCount)
        assertEquals(
            "every entry keeps the Program it belongs to: nothing is merged on the way in",
            listOf(ProgressFixture.PROGRAM_A, ProgressFixture.PROGRAM_B),
            aggregate.programIds
        )
        assertEquals(ProgressScope.AllPrograms, aggregate.scope)
    }

    @Test
    fun theAggregateComparesAcrossProgramsOnlyWithinAComparableContext() {
        val training = utc.training(allPrograms, week)
        val pushup = training.seriesOf("pushup", PrescriptionDimension.REP_BASED)!!

        assertEquals(
            "the same exercise in the same unit is one comparable context whatever Program it was " +
                "performed in, which is what makes the aggregate readable at all",
            7,
            pushup.performedSets
        )
        assertEquals("A's best set was 12 repetitions, B's was 20", 20, pushup.bestRepetitions)
        assertEquals(
            setOf(ProgressFixture.PROGRAM_A, ProgressFixture.PROGRAM_B),
            pushup.observations.map { it.programId }.toSet()
        )
        assertEquals(
            "and the two Programs' opportunity runs are reported separately rather than merged into a " +
                "run no Program ever had",
            listOf("program-a", "program-b"),
            training.streaks.map { it.programId.value }
        )
        assertEquals(1, training.streakOf(ProgressFixture.PROGRAM_A)!!.longest)
        assertEquals(
            "B took its first opportunity and then missed one, so its run is over",
            0,
            training.streakOf(ProgressFixture.PROGRAM_B)!!.current
        )
    }

    @Test
    fun theAggregateCountsEveryProgramsCompletedWorkoutsInItsFrequency() {
        val frequency = utc.training(allPrograms, week).frequency

        assertEquals(
            "A's two completed workouts and B's one; the cancelled attempt and the running one are not " +
                "workouts",
            3,
            frequency.completedSessions
        )
        assertEquals("on two distinct dates, because two of them happened on 2026-09-14", 2, frequency.trainingDays)
        assertEquals(3.0, frequency.sessionsPerSevenDays, 1e-12)
        assertEquals(
            "1800 + 2700 + 720 seconds over three measured workouts",
            1740.0,
            utc.training(allPrograms, week).averageSessionDuration.averageSeconds!!,
            1e-12
        )
    }

    // ------------------------------------------------------------------ empty, time, deferred

    @Test
    fun anEmptyHistoryYieldsZeroesAndEmptyCollectionsOfTheRightType() {
        val empty = ProgressFacts.empty(ProgressScope.OfProgram(ProgressFixture.PROGRAM_A))

        val calendar = utc.calendar(empty)
        assertEquals(0, calendar.completed)
        assertEquals(0, calendar.missed)
        assertEquals(0, calendar.upcoming)
        assertEquals(0, calendar.superseded)
        assertEquals(emptyList<CalendarSlotEntry>(), calendar.entries)
        assertEquals(emptyList<HistoryItem>(), utc.history(empty))

        val training = utc.training(empty, week)
        assertEquals(0, training.frequency.completedSessions)
        assertEquals(0, training.frequency.trainingDays)
        assertEquals(0, training.averageSessionDuration.measuredSessions)
        assertNull(training.averageSessionDuration.averageSeconds)
        assertEquals(emptyList<ExercisePerformanceSeries>(), training.series)
        assertEquals(emptyList<ContextVolume>(), training.volumes)
        assertEquals(
            "a Program scope knows which Program it is about even with nothing recorded",
            listOf(ProgressFixture.PROGRAM_A),
            training.streaks.map { it.programId }
        )
        assertEquals(0, training.streaks.single().current)

        val aggregate = utc.training(ProgressFacts.empty(ProgressScope.AllPrograms), week)
        assertEquals(
            "an aggregate over nothing has no Program to report a run for, and invents none",
            emptyList<ProgramStreak>(),
            aggregate.streaks
        )
    }

    @Test
    fun aWorkoutIsCountedOnTheDateItsStartFallsOnInTheCalculatorsOwnZone() {
        val lateEvening = ProgressFixture.slot("slot-z-1", "2026-09-14", SlotStatus.COMPLETED, attempts = listOf(SessionId("session-z1")), completedAt = Instant.parse("2026-09-15T00:10:00Z"))
        val facts = ProgressFacts(
            ProgressScope.OfProgram(ProgressFixture.PROGRAM_A),
            listOf(lateEvening),
            listOf(
                ProgressFixture.session(
                    "z1", lateEvening, Instant.parse("2026-09-14T23:30:00Z"), Instant.parse("2026-09-15T00:10:00Z"),
                    performed = listOf(ProgressFixture.Performed("pushup", RepPrescription(listOf(10)), listOf(ProgressFixture.reps("set-z1-1", 1, 10, Instant.parse("2026-09-14T23:40:00Z")))))
                )
            )
        )

        val utcDay = ProgressWindow(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"))
        val tokyoDay = ProgressWindow(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-15"))

        assertEquals(
            "23:30 UTC on 2026-09-14 is a workout on 2026-09-14 in UTC",
            1,
            utc.training(facts, utcDay).frequency.completedSessions
        )
        assertEquals(0, utc.training(facts, tokyoDay).frequency.completedSessions)
        val tokyo = ProgressCalculator(ZoneId.of("Asia/Tokyo"))
        assertEquals(0, tokyo.training(facts, utcDay).frequency.completedSessions)
        assertEquals(
            "and the same instant is 08:30 on 2026-09-15 in Tokyo — the zone is explicit, so the answer " +
                "is a decision rather than an accident of the process's default",
            1,
            tokyo.training(facts, tokyoDay).frequency.completedSessions
        )
        assertEquals("the duration is the same fact in both zones", 2400L, utc.training(facts, utcDay).averageSessionDuration.totalSeconds)
    }

    @Test
    fun theMeasuresTheFactsCannotAnswerAreReportedAsDeferredRatherThanAsZero() {
        val training = utc.training(programA, week)

        assertEquals(
            listOf(ProgressMeasure.FOCUS_DISTRIBUTION, ProgressMeasure.FAMILY_DISTRIBUTION, ProgressMeasure.PROGRAM_PR),
            training.deferred.map { it.measure }
        )
        assertTrue(training.isDeferred(ProgressMeasure.FOCUS_DISTRIBUTION))
        assertTrue(
            "every deferred measure says why, so a screen shows nothing rather than a zero and the owner " +
                "sees which dependency unblocks it",
            training.deferred.all { it.reason.isNotBlank() }
        )
        assertEquals(
            "and the same list is reported for an empty history: a measure is deferred because the model " +
                "cannot express it, not because the facts are unlucky",
            training.deferred.map { it.measure },
            utc.training(ProgressFacts.empty(ProgressScope.AllPrograms), week).deferred.map { it.measure }
        )
    }

    @Test
    fun theWindowIsPartOfTheAnswerSoARateCannotBeReadWithoutItsSpan() {
        val narrow = utc.training(programA, ProgressWindow(LocalDate.parse("2026-09-18"), LocalDate.parse("2026-09-18")))
        val wide = utc.training(programA, ProgressWindow(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18")))

        assertEquals("only the workout that began on 2026-09-18 is inside a one-date window", 1, narrow.frequency.completedSessions)
        assertEquals(1, narrow.frequency.calendarDays)
        assertEquals(7.0, narrow.frequency.sessionsPerSevenDays, 1e-12)
        assertEquals(2, wide.frequency.completedSessions)
        assertEquals(5, wide.frequency.calendarDays)
        assertEquals(
            "the window that produced the rate travels with it, so 'recently' is never implicit",
            narrow.window,
            narrow.frequency.window
        )
        assertEquals(
            "and the two windows really are different spans over the same history",
            listOf(1, 5),
            listOf(narrow.window.calendarDays, wide.window.calendarDays)
        )
        assertEquals(
            "the history-derived measures are cumulative and ignore the window (§21)",
            wide.series.map { it.performedSets },
            narrow.series.map { it.performedSets }
        )
    }
}
