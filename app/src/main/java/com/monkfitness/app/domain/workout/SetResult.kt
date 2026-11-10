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
 *
 * [restSeconds] is the rest taken before this set, in seconds, and is `null` when none was recorded —
 * which is what every set stored before the field existed holds, and what the first set of an occurrence
 * holds. `null` is deliberately not `0`: "no rest was observed" is an absence, while `0` is a
 * measurement that says the user went straight on.
 *
 * [loadKg] is the external load the set was performed with, in kilograms, and is `null` when no load was
 * recorded — which is every bodyweight set, every set stored before the field existed, and a loaded set
 * the user did not weigh. `null` is not `0`: zero kilograms is not a load a set can be performed with,
 * and reading the absence as zero would invent a weight for work that was never weighed.
 */
data class SetResult(
    val setLogId: SetLogId,
    val setIndex: Int,
    val completedReps: Int,
    val durationSeconds: Int,
    val performedAt: Instant,
    val restSeconds: Int? = null,
    val loadKg: Double? = null
) {

    init {
        require(setIndex >= 1) { "sets are numbered from 1, was $setIndex" }
        require(completedReps >= 0) { "completedReps must be >= 0, was $completedReps" }
        require(durationSeconds >= 0) { "durationSeconds must be >= 0, was $durationSeconds" }
        require(restSeconds == null || restSeconds >= 0) {
            "restSeconds must be absent or >= 0, was $restSeconds"
        }
        require(isPerformedLoad(loadKg)) {
            "loadKg must be absent or a positive finite load no heavier than $MAX_PERFORMED_LOAD_KG, " +
                "was $loadKg"
        }
        require((completedReps > 0) != (durationSeconds > 0)) {
            "a set is measured in repetitions or in time, and a set that was not performed is " +
                "absent rather than recorded as zero: reps=$completedReps seconds=$durationSeconds"
        }
    }

    val isRepetitionSet: Boolean
        get() = durationSeconds == 0
}
