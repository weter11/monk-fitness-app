package com.monkfitness.app.viewmodel

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.SetLog

/**
 * Records ONE set the workout session confirmed, containing only work the session actually
 * observed — this is the raw input the adaptive program reads back as actual training history, so
 * a configured target must never be written where a measured amount is available.
 *
 * What the session can observe:
 *
 *  * **that a set was finished.** Every call site is a user action (`Complete Set` / `Finish
 *    Exercise`) or a timer exercise reaching zero. One call = one recorded set, so the number of
 *    rows for an exercise is the number of sets the user performed: sets that were never performed
 *    leave no row, which is what makes partial and abandoned sessions representable.
 *  * **how long a timed hold lasted.** `remainingSeconds` is the session countdown at the moment of
 *    confirmation, so the elapsed work is `planned - remaining`. A 30 s hold ended after 10 s
 *    records 10 s — not the planned 30 s — and a hold the user skipped before starting records 0.
 *
 * What the session CANNOT observe: the number of repetitions performed inside a set (the session
 * UI has no per-rep input). A confirmed repetition set is therefore recorded with the reps the user
 * was prescribed for that set (`reps` as the exercise reached the session, phase and difficulty
 * adjustments included). `maxReps` is deliberately NOT used: it is the top of the prescribed range,
 * so writing it would claim the user hit a number nobody measured.
 *
 * Extracted from `MainViewModel.persistCompletedSet` (an `AndroidViewModel`; the project has no
 * Robolectric harness, so a pure top-level function is the only way this path is unit-testable on
 * the JVM).
 *
 * @param remainingSeconds the session countdown for timer exercises; ignored for repetition
 *   exercises, where no timed work exists.
 */
fun observedSetLog(
    exercise: Exercise,
    remainingSeconds: Int,
    timestamp: Long,
    sessionDate: String
): SetLog = SetLog(
    exerciseId = exercise.id,
    repsCompleted = if (exercise.isTimerBased) 0 else exercise.reps.coerceAtLeast(0),
    durationSeconds = if (exercise.isTimerBased) elapsedSeconds(exercise.durationSeconds, remainingSeconds) else 0,
    timestamp = timestamp,
    sessionDate = sessionDate
)

/**
 * The seconds of a timed hold that actually elapsed, clamped to the interval that was planned:
 * stale or overrun countdown values cannot record more (or less) work than the plan allowed.
 */
private fun elapsedSeconds(plannedDurationSeconds: Int, remainingSeconds: Int): Int {
    val planned = plannedDurationSeconds.coerceAtLeast(0)
    return (planned - remainingSeconds).coerceIn(0, planned)
}