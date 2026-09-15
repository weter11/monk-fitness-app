package com.monkfitness.app.viewmodel

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression suite for the ACTUAL work a completed set records.
 *
 * The workout session is the only place that sees what the user really did, and it can observe
 * exactly two things:
 *
 *  * **that a set was finished** — the user pressed `Complete Set` / `Finish Exercise`, or a timer
 *    exercise ran its countdown to zero. Every [com.monkfitness.app.data.model.SetLog] row is
 *    written from that single event, so the number of rows equals the number of performed sets.
 *  * **how long a timed hold actually lasted** — the session counts down `timeLeft`, so the
 *    elapsed seconds of a confirmed set are `planned - remaining`, not the planned duration.
 *
 * It CANNOT observe how many repetitions were performed: there is no per-rep input anywhere in the
 * session UI (the user confirms a set, not a rep count). A repetition set is therefore recorded
 * with the reps the user was prescribed for that set — the exercise's `reps` target as it reached
 * the session — and never with `maxReps`, which is the top of the prescribed range and would claim
 * the user hit a number nobody observed. (`WorkoutGenerator` happens to make `reps == maxReps`, so
 * the two only diverge for a genuine range prescription; these tests pin the contract either way.)
 *
 * [observedSetLog] is extracted from `MainViewModel` (an `AndroidViewModel`; the project has no
 * Robolectric harness and CI is JVM-only) so the recorded values are directly testable.
 */
class SetLogObservationTest {

    private val timestamp = 1_760_000_000_000L
    private val sessionDate = "2026-09-15"

    /** A repetition exercise prescribed as a RANGE: `reps` (the target) is below `maxReps`. */
    private fun repExercise(
        reps: Int = 10,
        minReps: Int = 6,
        maxReps: Int = 20,
        sets: Int = 3
    ) = Exercise(
        id = "pushups",
        familyId = "pushups",
        animationId = "pushup_standard",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = sets,
        reps = reps,
        minReps = minReps,
        maxReps = maxReps,
        category = ExerciseCategory.STRENGTH
    )

    private fun timerExercise(durationSeconds: Int = 30, sets: Int = 3) = Exercise(
        id = "plank",
        familyId = "plank",
        animationId = "plank_standard",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = sets,
        reps = 1,
        minReps = 0,
        maxReps = 0,
        durationSeconds = durationSeconds,
        isTimerBased = true,
        category = ExerciseCategory.STRENGTH
    )

    // ---------------------------------------------------------------- repetition exercises

    @Test
    fun repSetRecordsThePrescribedTargetNotTheConfiguredRangeMaximum() {
        val log = observedSetLog(repExercise(reps = 10, maxReps = 20), remainingSeconds = 0, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals(
            "a confirmed set records the prescribed target reps, not the top of the prescribed range",
            10,
            log.repsCompleted
        )
    }

    @Test
    fun repSetRecordsNoDuration() {
        val log = observedSetLog(repExercise(), remainingSeconds = 17, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("a repetition set holds no timed work", 0, log.durationSeconds)
    }

    @Test
    fun repSetKeepsTheExerciseIdentityAndSessionStamp() {
        val log = observedSetLog(repExercise(), remainingSeconds = 0, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("pushups", log.exerciseId)
        assertEquals(timestamp, log.timestamp)
        assertEquals(sessionDate, log.sessionDate)
    }

    // ---------------------------------------------------------------- timer exercises

    @Test
    fun timerSetStoppedEarlyRecordsTheElapsedSecondsNotThePlannedDuration() {
        val log = observedSetLog(timerExercise(durationSeconds = 30), remainingSeconds = 20, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals(
            "a 30 s hold the user ended after 10 s is 10 s of actual work, not the planned 30 s",
            10,
            log.durationSeconds
        )
    }

    @Test
    fun timerSetThatRanToZeroRecordsTheFullDuration() {
        val log = observedSetLog(timerExercise(durationSeconds = 30), remainingSeconds = 0, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("a hold that completed its countdown is the planned duration", 30, log.durationSeconds)
    }

    @Test
    fun timerSetThatNeverRanRecordsNoWork() {
        val log = observedSetLog(timerExercise(durationSeconds = 30), remainingSeconds = 30, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals(
            "a hold the user skipped without any countdown is zero actual work, not the planned 30 s",
            0,
            log.durationSeconds
        )
    }

    @Test
    fun timerSetRecordsNoRepetitions() {
        val log = observedSetLog(timerExercise(), remainingSeconds = 0, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("a timed hold holds no repetition work", 0, log.repsCompleted)
    }

    @Test
    fun elapsedIsClampedToThePlannedDuration() {
        val log = observedSetLog(timerExercise(durationSeconds = 30), remainingSeconds = -5, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("a clock overrun cannot record more work than was planned", 30, log.durationSeconds)
    }

    @Test
    fun elapsedIsClampedToZeroWhenTheRemainingTimeExceedsThePlan() {
        val log = observedSetLog(timerExercise(durationSeconds = 30), remainingSeconds = 99, timestamp = timestamp, sessionDate = sessionDate)

        assertEquals("stale remaining time cannot record negative work", 0, log.durationSeconds)
    }

    // ---------------------------------------------------------------- partial / abandoned sessions

    /** Replays a session: one entry per set the user actually confirmed, holding the timer's
     *  remaining seconds at that moment (0 for repetition sets, where the clock is irrelevant). */
    private fun replaySession(exercise: Exercise, confirmedSetRemainingSeconds: List<Int>) =
        confirmedSetRemainingSeconds.map { remaining ->
            observedSetLog(exercise, remaining, timestamp = timestamp, sessionDate = sessionDate)
        }

    @Test
    fun aPartialTimerSessionRecordsTheElapsedWorkOfTheConfirmedSets() {
        // 3 x 30 s planned; set 1 stopped after 10 s, set 2 ran out, the user then abandoned the
        // session before set 3. The two sets that happened carry 10 s and 30 s; nothing is recorded
        // for the set that never happened.
        val rows = replaySession(timerExercise(durationSeconds = 30, sets = 3), listOf(20, 0))

        assertEquals("only the sets that were confirmed are recorded", 2, rows.size)
        assertEquals(listOf(10, 30), rows.map { it.durationSeconds })
        assertEquals("40 of 90 planned seconds of work were observed", 40, rows.sumOf { it.durationSeconds })
    }

    @Test
    fun anAbandonedTimerSessionNeverRecordsTheUnperformedSetsAsComplete() {
        // The regression this suite exists for: the abandoned set 3 used to be indistinguishable
        // from a completed set because every row claimed the full planned duration.
        val plannedSets = timerExercise(durationSeconds = 30, sets = 3).sets
        val rows = replaySession(timerExercise(durationSeconds = 30, sets = 3), listOf(20))

        assertEquals("one set was performed", 1, rows.size)
        assertEquals("two planned sets have no record at all", 2, plannedSets - rows.size)
        assertEquals("the abandoned session recorded 10 s, not 90 s", 10, rows.sumOf { it.durationSeconds })
    }

    @Test
    fun aPartialRepSessionRecordsOnlyTheCompletedSets() {
        // 3 x 10 reps prescribed; the user completed one set and abandoned the session.
        val rows = replaySession(repExercise(reps = 10, sets = 3), listOf(0))

        assertEquals("one performed set", 1, rows.size)
        assertEquals("10 reps of 30 planned were observed", 10, rows.sumOf { it.repsCompleted })
    }
}