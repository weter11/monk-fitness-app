package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.progress.ProgressWindow
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProgramProgressService` over the real data-access rig: rows written through the Program System's own
 * repositories, read back as §21's aggregations.
 *
 * The calculator's own suite proves the arithmetic over hand-built facts. This one proves the other half of
 * the contract, and it is the half that only a database can settle:
 *
 *  * the facts are the ones that were **stored** — the numbers here come from rows written through
 *    `ProgramRepository`, `WorkoutSessionRepository` and the schedule repository, exactly as §27's
 *    transactions write them;
 *  * a Program scope cannot see another Program's history, even when both are in the same database and the
 *    other Program demonstrably has rows;
 *  * "All Programs" is the aggregate: it is a sum, it keeps every entry's own program identity, and it is
 *    not an entity — there is no id, no row and nothing to select;
 *  * "today" comes from the injected clock, and the calendar a workout is counted on comes from the
 *    service's own zone, so a window is a decision rather than an accident;
 *  * and the session's status semantics of §19 survive into progress: a cancelled attempt is not a workout,
 *    an attempt still running is not one either, and both keep the exposure they recorded.
 */
class ProgramProgressServiceTest {

    private val utc = ZoneId.of("UTC")

    /**
     * A clock that is not the test's "now" by accident: the completed workouts of the fixture happen on
     * 2026-09-21, and this reads 2026-09-25, so the default 28-date window (`2026-08-29 .. 2026-09-25`)
     * contains them and a shifted clock does not.
     */
    private val clock = Clock { Instant.parse("2026-09-25T10:00:00Z") }

    private fun serviceOver(rig: ProgramDataAccessRig, zone: ZoneId = utc, at: Clock = clock) =
        ProgramProgressService(
            programRepository = rig.programRepository,
            scheduleRepository = rig.programScheduleRepository,
            sessionRepository = rig.workoutSessionRepository,
            clock = at,
            zone = zone
        )

    private val programA = ProgramId(ProgramGraphFixture.programId("a"))
    private val programB = ProgramId(ProgramGraphFixture.programId("b"))

    /**
     * Two Programs in one database, with everything a Program System can record between them:
     *
     * ```text
     * Program a  slot 1  COMPLETED  attempt session-a  (12 and 10 repetitions, two sets, 50 minutes)
     *            slot 2  MISSED     attempt session-a2 (5 repetitions, then cancelled)
     *            slot 3  PLANNED    untouched
     * Program b  slot 1  PLANNED    attempt session-b  (12 and 10 repetitions, still running)
     *            slot 2  MISSED     nothing
     *            slot 3  PLANNED    untouched
     * ```
     */
    private suspend fun twoProgramsWithHistory(rig: ProgramDataAccessRig) {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)

        val slotOne = rig.graph.slotFor(1)
        val slotTwo = rig.graph.slotFor(2)
        val slotThree = rig.graph.slotFor(3)

        val completed = ProgramGraphFixture.session("a", slotOne)
        rig.startSessionWithSets(completed)
        rig.workoutSessionRepository.finishSession(
            completed.copy(status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED),
            ProgramGraphFixture.completedSlot("a", slotOne)
        )

        val abandoned = ProgramGraphFixture.session(
            "a2", slotTwo,
            sets = listOf(
                SetResult(
                    SetLogId("set-a2-1"), 1,
                    completedReps = 5, durationSeconds = 0,
                    performedAt = ProgramGraphFixture.SET_ONE
                )
            )
        )
        rig.startSessionWithSets(abandoned)
        rig.workoutSessionRepository.recordSessionOutcome(
            abandoned.copy(status = SessionStatus.CANCELLED, finishedAt = ProgramGraphFixture.SET_TWO)
        )
        rig.programScheduleRepository.recordSlotOutcome(slotTwo.slotId, SlotStatus.MISSED, null)

        val running = ProgramGraphFixture.session("b", other.slots.first())
        rig.startSessionWithSets(running)

        rig.programScheduleRepository.recordSlotOutcome(other.slots[1].slotId, SlotStatus.MISSED, null)

        // The slots the fixture did not touch are still open; nothing here closes them.
        assertEquals(SlotStatus.PLANNED, rig.programScheduleRepository.slotById(slotThree.slotId)!!.status)
    }

    // ------------------------------------------------------------------ isolation

    @Test
    fun aProgramScopeReadsThatProgramsHistoryAlone() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val service = serviceOver(rig)

            val calendar = service.calendarProgress(ProgressScope.OfProgram(programA))
            assertEquals(
                "Program a's own three opportunities: one taken, one passed, one still open",
                listOf(1, 1, 1, 0),
                listOf(calendar.completed, calendar.missed, calendar.upcoming, calendar.superseded)
            )
            assertEquals(
                "and every entry belongs to it",
                listOf(programA),
                calendar.programIds
            )

            val history = service.history(ProgressScope.OfProgram(programA))
            assertEquals(
                "two attempts of Program a, and none of Program b's",
                listOf("session-a2", "session-a"),
                history.map { it.sessionId.value }
            )
            assertTrue(
                "Program b really does have rows in this database, so the scope above is a read " +
                    "restriction rather than an empty store",
                rig.programProgressRepository.sessionIdsOf(programB).isNotEmpty()
            )

            assertEquals(
                "the opportunity runs are Program a's alone",
                listOf(programA),
                service.trainingProgress(ProgressScope.OfProgram(programA)).streaks.map { it.programId }
            )
            assertEquals(
                "and Program b's scope sees the other side of the same database: its missed opportunity, " +
                    "the one its running attempt is on, and one it has not touched",
                listOf(0, 1, 2, 0),
                service.calendarProgress(ProgressScope.OfProgram(programB))
                    .let { listOf(it.completed, it.missed, it.upcoming, it.superseded) }
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ the aggregate

    @Test
    fun allProgramsIsTheSumOfBothProgramsAndAnEntityOfItsOwn() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val service = serviceOver(rig)

            val aggregate = service.calendarProgress(ProgressScope.AllPrograms)
            assertEquals(
                "one taken opportunity in a and none in b; two passed; and the three still open — one of " +
                    "which b is being worked on right now, because an attempt in progress does not take " +
                    "an opportunity (§19)",
                listOf(1, 2, 3, 0),
                listOf(aggregate.completed, aggregate.missed, aggregate.upcoming, aggregate.superseded)
            )
            assertEquals(6, aggregate.opportunityCount)
            assertEquals(
                "every entry keeps the Program it came from: nothing is merged on the way in",
                listOf(programA, programB),
                aggregate.programIds
            )
            assertEquals(ProgressScope.AllPrograms, aggregate.scope)

            assertEquals(
                "the aggregate's history is both Programs' attempts, newest first",
                3,
                service.history(ProgressScope.AllPrograms).size
            )
            assertEquals(
                "and it is exactly the two Program-scoped histories together — no third fact is invented " +
                    "for the aggregate",
                (service.history(ProgressScope.OfProgram(programA)).map { it.sessionId } +
                    service.history(ProgressScope.OfProgram(programB)).map { it.sessionId }).toSet(),
                service.history(ProgressScope.AllPrograms).map { it.sessionId }.toSet()
            )

            val streaks = service.trainingProgress(ProgressScope.AllPrograms).streaks
            assertEquals(
                "the aggregate reports one opportunity run per Program rather than merging two Programs' " +
                    "slot sequences into a run neither of them had",
                listOf(programA, programB),
                streaks.map { it.programId }
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ the measured numbers

    @Test
    fun theNumbersComeFromTheStoredRows() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val service = serviceOver(rig)

            val training = service.trainingProgress(ProgressScope.OfProgram(programA))
            assertEquals(
                "the window that was used is reported with the measures it produced",
                ProgressWindow(LocalDate.parse("2026-08-29"), LocalDate.parse("2026-09-25")),
                training.window
            )
            assertEquals(
                "one completed workout inside the window; the cancelled attempt is not a workout (§19)",
                1,
                training.frequency.completedSessions
            )
            assertEquals(1, training.frequency.trainingDays)
            assertEquals(
                "the fixture's session ran from 07:30 to 08:20 on 2026-09-21 — fifty minutes, from the two " +
                    "stored stamps",
                3000L,
                training.averageSessionDuration.totalSeconds
            )
            assertEquals(1, training.averageSessionDuration.measuredSessions)

            val pushup = training.seriesOf("knee_pushup", PrescriptionDimension.REP_BASED)!!
            assertEquals(
                "the two sets of the completed workout and the one the cancelled attempt confirmed: " +
                    "exposure follows the sets, whatever became of the attempt (§12)",
                3,
                pushup.performedSets
            )
            assertEquals(12, pushup.bestRepetitions)
            assertEquals(
                "and the presentation is the captured one — 'knee_pushup 10/8', not the live plan's " +
                    "'pushup 12/10/8/6' (§19)",
                listOf("session-a", "session-a2", "session-a"),
                pushup.observations.map { it.sessionId.value }
            )
            assertEquals(27, training.volumeOf("knee_pushup", PrescriptionDimension.REP_BASED)!!.repetitions)

            val cancelled = service.history(ProgressScope.OfProgram(programA)).first()
            assertEquals(SessionStatus.CANCELLED, cancelled.status)
            assertEquals(
                "the cancelled attempt keeps the set it confirmed and its own actual duration",
                1,
                cancelled.performedSets
            )
            assertEquals(900L, cancelled.duration?.seconds)
            assertTrue(
                "and the opportunity it was attempted on is still the missed one — a cancellation does " +
                    "not take an opportunity",
                service.calendarProgress(ProgressScope.OfProgram(programA)).missed == 1
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun anAttemptStillRunningIsNotAWorkoutAndKeepsItsExposureSoFar() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val service = serviceOver(rig)
            val other = programB

            val training = service.trainingProgress(ProgressScope.OfProgram(other))
            assertEquals(
                "Program b has an attempt in progress and no completed workout at all",
                0,
                training.frequency.completedSessions
            )
            assertNull(
                "so there is no average duration to report — not a zero-second average",
                training.averageSessionDuration.averageSeconds
            )
            assertEquals(2, training.seriesOf("knee_pushup", PrescriptionDimension.REP_BASED)!!.performedSets)
            val item = service.history(ProgressScope.OfProgram(other)).single()
            assertEquals(SessionStatus.IN_PROGRESS, item.status)
            assertNull("an attempt that has not finished has no duration", item.duration)
            assertEquals("but the sets it has already confirmed are exposure", 2, item.performedSets)
            assertEquals(
                "and the opportunity it is being worked on is still open — as is the one it has not " +
                    "touched — because an attempt in progress has not taken anything yet (§19)",
                2,
                service.calendarProgress(ProgressScope.OfProgram(other)).upcoming
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ today, and the calendar

    @Test
    fun theDefaultWindowIsTodaysAndTheClockAndTheZoneDecideWhatTodayMeans() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val scope = ProgressScope.OfProgram(programA)

            assertEquals(
                "the default window is the last 28 calendar dates ending on today in the service's zone",
                ProgressWindow(LocalDate.parse("2026-08-29"), LocalDate.parse("2026-09-25")),
                serviceOver(rig).currentWindow()
            )
            assertEquals(
                "and 'today' is read from the injected clock, so a caller that owns its time owns its window",
                ProgressWindow(LocalDate.parse("2026-08-29"), LocalDate.parse("2026-09-25")),
                serviceOver(rig).trainingProgress(scope).window
            )

            val moved = serviceOver(rig, at = Clock { Instant.parse("2026-10-30T10:00:00Z") })
            assertEquals(
                "with the clock moved a month on, the same stored history falls out of the default window",
                ProgressWindow(LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-30")),
                moved.currentWindow()
            )
            assertEquals(
                "so the workout is no longer inside it — the rate is about a span, not about the store",
                0,
                moved.trainingProgress(scope).frequency.completedSessions
            )
            assertEquals(
                "while the cumulative measures are unchanged, because they are about the whole history",
                3,
                moved.trainingProgress(scope).seriesOf("knee_pushup", PrescriptionDimension.REP_BASED)!!.performedSets
            )

            val lateEvening = Clock { Instant.parse("2026-09-25T23:30:00Z") }
            assertEquals(
                "23:30 UTC is still 2026-09-25 in UTC",
                LocalDate.parse("2026-09-25"),
                serviceOver(rig, utc, lateEvening).currentWindow().to
            )
            assertEquals(
                "and it is already 2026-09-26 in Tokyo: the zone is the service's own, read once, rather " +
                    "than the process default",
                LocalDate.parse("2026-09-26"),
                serviceOver(rig, ZoneId.of("Asia/Tokyo"), lateEvening).currentWindow().to
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ history reading

    @Test
    fun historyIsNewestFirstAndLimitedToWhatWasAskedFor() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            twoProgramsWithHistory(rig)
            val service = serviceOver(rig)
            val scope = ProgressScope.OfProgram(programA)

            assertEquals(
                "newest first, with the identity as the tiebreak when two attempts share a start",
                listOf("session-a2", "session-a"),
                service.history(scope).map { it.sessionId.value }
            )
            assertEquals(
                "and a limit takes the most recent attempts, not arbitrary ones",
                listOf("session-a2"),
                service.history(scope, limit = 1).map { it.sessionId.value }
            )
            assertEquals(
                "the aggregate's newest attempt is Program b's running one, which started last",
                SessionId("session-b"),
                service.history(ProgressScope.AllPrograms, limit = 1).single().sessionId
            )
            val failure = try {
                service.history(scope, limit = 0)
                null
            } catch (failure: IllegalArgumentException) {
                failure
            }
            assertTrue(
                "a non-positive limit is a caller defect and is reported as one rather than answered with " +
                    "an empty list: $failure",
                failure != null
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ empty

    @Test
    fun aProgramWithNoHistoryAtAllAnswersWithZeroesAndEmptyValues() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            rig.createGraph()
            val service = serviceOver(rig)
            val scope = ProgressScope.OfProgram(programA)

            val calendar = service.calendarProgress(scope)
            assertEquals(0, calendar.completed)
            assertEquals(0, calendar.missed)
            assertEquals("every opportunity of a Program that has not started is still open", 3, calendar.upcoming)
            assertEquals(0, calendar.superseded)

            assertEquals(emptyList<Any>(), service.history(scope))
            val training = service.trainingProgress(scope)
            assertEquals(0, training.frequency.completedSessions)
            assertEquals(0, training.averageSessionDuration.measuredSessions)
            assertNull(training.averageSessionDuration.averageSeconds)
            assertEquals(emptyList<Any>(), training.series)
            assertEquals(emptyList<Any>(), training.volumes)
            assertEquals(
                "the streak is reported for the Program the scope names, at zero",
                0,
                training.streakOf(programA)!!.current
            )
            assertEquals(
                "and the aggregate over a database with one historyless Program reports that Program's " +
                    "zero run rather than nothing at all",
                listOf(programA),
                service.trainingProgress(ProgressScope.AllPrograms).streaks.map { it.programId }
            )
        } finally {
            rig.close()
        }
    }

    // ------------------------------------------------------------------ a stored attempt that never ran a set

    @Test
    fun anAttemptThatConfirmedNothingProducesNoObservationAndNoVolume() = runBlocking {
        val rig = ProgramDataAccessRig("a")
        try {
            rig.createGraph()
            val slot = rig.graph.slotFor(1)
            val untouched: WorkoutSession = ProgramGraphFixture.session("a", slot, sets = emptyList())
            rig.workoutSessionRepository.startSession(untouched)

            val training = serviceOver(rig).trainingProgress(ProgressScope.OfProgram(programA))
            assertEquals(
                "an attempt that confirmed no set observed nothing: no series, no volume, and no " +
                    "zero-valued set invented for it (§12)",
                emptyList<Any>(),
                training.series
            )
            assertEquals(emptyList<Any>(), training.volumes)
            val item = serviceOver(rig).history(ProgressScope.OfProgram(programA)).single()
            assertEquals(0, item.performedSets)
            assertTrue(!item.hasExposure)
        } finally {
            rig.close()
        }
    }
}
