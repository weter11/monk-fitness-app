package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.SetLogId
import java.time.Instant

/**
 * One confirmed set: what was actually performed, once, for one set of one exercise.
 *
 * It exists only because a set was confirmed — a skipped exercise, a missed slot and a cancelled session
 * produce no set result at all, never a row of zeroes. There is consequently no `zero` factory, no
 * `skipped` flag and no default amount, so "missed" can never be read downstream as "performed zero
 * repetitions".
 *
 * A set is measured in one unit, exactly as the exercise is prescribed in one: a repetition set carries
 * [completedReps] and `0` seconds, a timed set [durationSeconds] and `0` repetitions. The unit is checked
 * against the prescription at [SessionExercise], where the prescription is known.
 */
data class SetResult(
    val setLogId: SetLogId,
    val setIndex: Int,
    val completedReps: Int,
    val durationSeconds: Int,
    val performedAt: Instant
) {

    init {
        require(setIndex >= 1) { "sets are numbered from 1, was $setIndex" }
        require(completedReps >= 0) { "completedReps must be >= 0, was $completedReps" }
        require(durationSeconds >= 0) { "durationSeconds must be >= 0, was $durationSeconds" }
        require((completedReps > 0) != (durationSeconds > 0)) {
            "a set is measured in repetitions or in time, and a set that was not performed is " +
                "absent rather than recorded as zero: reps=$completedReps seconds=$durationSeconds"
        }
    }

    val isRepetitionSet: Boolean
        get() = durationSeconds == 0
}
