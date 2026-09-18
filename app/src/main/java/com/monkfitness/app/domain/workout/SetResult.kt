package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.SetLogId
import java.time.Instant

/**
 * One confirmed set: what was actually performed, once, for one set of one exercise (§19, §27).
 *
 * A set result exists only because a set was confirmed. That is the load-bearing rule of this type:
 * an exercise that was skipped, a slot that was missed and a session that was cancelled produce **no
 * set result at all** — never a row of zeroes. There is consequently no `zero` factory, no `skipped`
 * flag and no default amount: the only states representable are amounts of work that happened, which
 * is why "missed" can never be read downstream as "performed zero repetitions" (§12).
 *
 * A set is measured in one unit, exactly as the exercise is prescribed in one unit: a repetition set
 * carries [completedReps] and no seconds, a timed set carries [durationSeconds] and no repetitions.
 * The unit the set was prescribed in is checked against the prescription at [SessionExercise], where
 * the prescription is known.
 *
 * @property setLogId identity of the persisted set row.
 * @property setIndex 1-based position of the set within its exercise occurrence.
 * @property completedReps repetitions performed; `0` for a timed set.
 * @property durationSeconds seconds performed; `0` for a repetition set.
 * @property performedAt when the set was confirmed.
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

    /** Whether this set was measured in repetitions. */
    val isRepetitionSet: Boolean
        get() = durationSeconds == 0
}
