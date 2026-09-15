package com.monkfitness.app.domain.adaptive

/**
 * What one exercise of one session was prescribed (`planned*`) and what the session observed
 * (`completed*`), both expressed as **session totals for that exercise**: `plannedSets` is the
 * number of prescribed sets, `plannedReps` the repetitions prescribed across all of them,
 * `plannedDurationSeconds` the hold time prescribed across all of them.
 *
 * A single result describes one exercise, and an exercise is prescribed in exactly one measurement:
 *
 *  * a **repetition** exercise populates the repetition channel and leaves the timed channel at `0`;
 *  * a **timer** exercise populates the timed channel and leaves the repetition channel at `0`. The
 *    placeholder repetition count the exercise library carries for timed exercises is not prescribed
 *    repetition work and must be normalized to `0` by the caller.
 *
 * Both channels populated is therefore a unit-mixing defect, not a hybrid exercise, and is rejected
 * at construction. Nothing here reduces repetitions and seconds into one number — see [exposure] for
 * the only quantity this model derives, which is a fraction within a single channel.
 *
 * Immutable and deterministic: all values are inputs, and [exposure] is a pure function of them.
 */
data class ExerciseResult(
    val exerciseId: String,
    val plannedSets: Int,
    val completedSets: Int,
    val plannedReps: Int,
    val completedReps: Int,
    val plannedDurationSeconds: Int,
    val completedDurationSeconds: Int
) {

    init {
        require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
        require(plannedSets >= 0 && completedSets >= 0) {
            "set counts must be >= 0, were planned=$plannedSets completed=$completedSets"
        }
        require(plannedReps >= 0 && completedReps >= 0) {
            "repetition counts must be >= 0, were planned=$plannedReps completed=$completedReps"
        }
        require(plannedDurationSeconds >= 0 && completedDurationSeconds >= 0) {
            "durations must be >= 0, were planned=$plannedDurationSeconds completed=$completedDurationSeconds"
        }
        require(plannedReps == 0 || plannedDurationSeconds == 0) {
            "an exercise is prescribed in repetitions or in time, never both: " +
                "plannedReps=$plannedReps plannedDurationSeconds=$plannedDurationSeconds"
        }
    }

    /**
     * How much of the planned work was actually performed, as a fraction clamped to `0.0..1.0`.
     *
     *  * repetition exercise — `completedReps / plannedReps`;
     *  * timer exercise — `completedDurationSeconds / plannedDurationSeconds`;
     *  * no planned work — `0.0`, deterministically. Zero planned work is not "fully performed"
     *    (`1.0`) and must not divide: an exercise the session planned nothing for carries no
     *    exposure evidence either way.
     *
     * Because the completed amounts come from observed session events (confirmed sets, elapsed hold
     * seconds), `1.0` means every prescribed set was performed in full, and a value below `1.0` is
     * normal — including for a [SessionOutcome.COMPLETED] session.
     */
    val exposure: Double
        get() = when {
            plannedDurationSeconds > 0 -> timerExposure(completedDurationSeconds, plannedDurationSeconds)
            plannedReps > 0 -> repetitionExposure(completedReps, plannedReps)
            else -> 0.0
        }
}

/**
 * Exposure of a repetition exercise: `completedReps / plannedReps`, clamped to `0.0..1.0`.
 *
 * Zero (or negative) planned repetitions deterministically yield `0.0` instead of dividing.
 */
fun repetitionExposure(completedReps: Int, plannedReps: Int): Double =
    clampedExposure(completedReps, plannedReps)

/**
 * Exposure of a timer exercise: `completedDurationSeconds / plannedDurationSeconds`, clamped to
 * `0.0..1.0`.
 *
 * Zero (or negative) planned duration deterministically yields `0.0` instead of dividing.
 */
fun timerExposure(completedDurationSeconds: Int, plannedDurationSeconds: Int): Double =
    clampedExposure(completedDurationSeconds, plannedDurationSeconds)

/** `completed / planned` within one unit, clamped to `0.0..1.0`; no planned work is `0.0`. */
private fun clampedExposure(completed: Int, planned: Int): Double =
    if (planned <= 0) 0.0 else (completed.toDouble() / planned.toDouble()).coerceIn(0.0, 1.0)