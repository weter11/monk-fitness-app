package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmed-set rows a session persisted, as the mapper consumes them — the smallest inputs that
 * still distinguish "every persisted row is work" from "a duplicated row is noise".
 */
private val twoRowsSameTimestamp = listOf(
    SessionSetLog(
        cycleNumber = 1,
        programDay = 2,
        exerciseId = "pushups",
        sessionDate = "2026-09-01",
        timestamp = 1_788_220_800_000L,
        repsCompleted = 10,
        durationSeconds = 0
    ),
    SessionSetLog(
        cycleNumber = 1,
        programDay = 2,
        exerciseId = "pushups",
        sessionDate = "2026-09-01",
        timestamp = 1_788_220_800_000L, // identical millisecond to the row above
        repsCompleted = 8,
        durationSeconds = 0
    )
)

private val pushupsPlan = PlannedExercise(
    exerciseId = "pushups",
    sets = 3,
    repsPerSet = 10,
    durationSecondsPerSet = 0,
    isTimerBased = false
)

private val plankPlan = PlannedExercise(
    exerciseId = "plank",
    sets = 3,
    repsPerSet = 0,
    durationSecondsPerSet = 30,
    isTimerBased = true
)

class SameTimestampRowsTest {

    /**
     * The write path does not prove that a millisecond identifies at most one set. Two rows sharing
     * a timestamp are therefore two persisted confirmed sets, and both must survive: collapsing them
     * would silently erase 8 performed repetitions and under-report the session.
     */
    @Test
    fun twoLegitimateRowsSharingATimestampAreNotCollapsed() {
        val observation = SessionObservationMapper.toObservation(
            cycleNumber = 1,
            programDay = 2,
            plannedExercises = listOf(pushupsPlan),
            sessionDate = "2026-09-01",
            setLogs = twoRowsSameTimestamp,
            isCompleted = true,
            completedAt = 1_788_229_440_000L
        )

        val pushups = observation.exerciseResults.single()
        assertEquals("both rows must count as performed sets", 2, pushups.completedSets)
        assertEquals("both rows must contribute their reps", 18, pushups.completedReps)
        assertEquals("startedAt is the shared timestamp, not empty", 1_788_220_800_000L, observation.startedAt)
    }

    /**
     * Keeping the rows cannot inflate actual work past the plan: the cap still holds, so a genuine
     * duplication bug degrades to the planned amount rather than exceeding it.
     */
    @Test
    fun keptRowsAreStillCappedAtThePlannedWork() {
        val overCompleted = twoRowsSameTimestamp.map { row ->
            row.copy(repsCompleted = 20)
        }

        val observation = SessionObservationMapper.toObservation(
            cycleNumber = 1,
            programDay = 2,
            plannedExercises = listOf(pushupsPlan),
            sessionDate = "2026-09-01",
            setLogs = overCompleted,
            isCompleted = true,
            completedAt = 1_788_229_440_000L
        )

        // plan 3 sets x 10 reps = 30; 2 x 20 = 40 raw, clamped to 30.
        val pushups = observation.exerciseResults.single()
        assertEquals(2, pushups.completedSets)
        assertEquals(30, pushups.completedReps)
    }

    /**
     * The two unit channels stay distinct when rows share a timestamp: a timer exercise's same-time
     * rows accumulate seconds and carry no repetition work.
     */
    @Test
    fun timerRowsSharingATimestampAccumulateSecondsOnly() {
        val timerRows = listOf(
            SessionSetLog(
                cycleNumber = 1,
                programDay = 2,
                exerciseId = "plank",
                sessionDate = "2026-09-01",
                timestamp = 1_788_220_800_000L,
                repsCompleted = 0,
                durationSeconds = 20
            ),
            SessionSetLog(
                cycleNumber = 1,
                programDay = 2,
                exerciseId = "plank",
                sessionDate = "2026-09-01",
                timestamp = 1_788_220_800_000L,
                repsCompleted = 0,
                durationSeconds = 15
            )
        )

        val observation = SessionObservationMapper.toObservation(
            cycleNumber = 1,
            programDay = 2,
            plannedExercises = listOf(plankPlan),
            sessionDate = "2026-09-01",
            setLogs = timerRows,
            isCompleted = false,
            completedAt = null
        )

        val plank = observation.exerciseResults.single()
        assertEquals(2, plank.completedSets)
        assertEquals(35, plank.completedDurationSeconds)
        assertEquals("the repetition channel carries no timer work", 0, plank.completedReps)
        assertEquals("planned duration is 3 x 30", 90, plank.plannedDurationSeconds)
    }
}
