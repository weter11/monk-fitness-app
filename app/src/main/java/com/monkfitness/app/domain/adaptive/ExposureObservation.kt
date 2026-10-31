package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import java.time.Instant

/**
 * How much of one occurrence was executed. A skipped exercise and a missed slot produce **no observation
 * at all**: "no exposure" is the absence of an [ExposureObservation], never an observation of zero work —
 * zero reps performed and an opportunity that never happened are different facts.
 */
enum class ExposureLevel {

    PARTIAL,

    FULL
}

/**
 * What one completed set amounted to. A set is measured in one unit, exactly as it was prescribed in one:
 * a repetition set carries [completedReps] and no seconds, a timed set the reverse.
 */
data class PerformedSetAmount(
    val completedReps: Int,
    val durationSeconds: Int
) {

    init {
        require(completedReps >= 0) { "completedReps must be >= 0, was $completedReps" }
        require(durationSeconds >= 0) { "durationSeconds must be >= 0, was $durationSeconds" }
        require((completedReps > 0) != (durationSeconds > 0)) {
            "a set is measured in repetitions or in time, and a set that was not performed is " +
                "absent rather than recorded as zero: reps=$completedReps seconds=$durationSeconds"
        }
    }
}

/**
 * One exercise occurrence, as observed — the unit the adaptive stage reasons over, not the slot, the
 * session or the exercise in the abstract. Identity is ([sessionId], [sessionExerciseId]), so one workout
 * is many observations sharing a session.
 *
 * There is no "observed nothing" instance: an observation records work that happened. [prescribedSets] is
 * what the session's own snapshot presented, not what today's plan says, so a later edit cannot change
 * what this is measured against, and a `null` [performedAmounts] is not zero work.
 */
data class ExposureObservation(
    val exerciseId: String,
    val sessionId: SessionId,
    val sessionExerciseId: SessionExerciseId,
    val level: ExposureLevel,
    val completedSets: Int,
    val prescribedSets: Int,
    val performedAmounts: List<PerformedSetAmount>? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null
) {

    init {
        require(exerciseId.isNotBlank()) { "an observation must name the exercise it observed" }
        require(prescribedSets >= 1) {
            "an observation is of a presented exercise, which prescribes at least one set, was " +
                "$prescribedSets"
        }
        require(completedSets >= 1) {
            "an observation records work that happened; a skipped exercise or a missed slot " +
                "produces no observation rather than a zero one, was completedSets=$completedSets"
        }
        require(performedAmounts == null || performedAmounts.size == completedSets) {
            "the performed amounts are recorded one per completed set, in set order: " +
                "performedAmounts=${performedAmounts?.size} completedSets=$completedSets"
        }
        require((level == ExposureLevel.FULL) == (completedSets >= prescribedSets)) {
            "the level must agree with the counts: level=$level completedSets=$completedSets " +
                "prescribedSets=$prescribedSets"
        }
        require(finishedAt == null || finishedAt >= startedAt) {
            "an occurrence cannot end before it started: started=$startedAt finished=$finishedAt"
        }
    }

    val isFull: Boolean
        get() = level == ExposureLevel.FULL
}
