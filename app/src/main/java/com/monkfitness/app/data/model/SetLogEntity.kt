package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One confirmed set.
 *
 * A row exists because a set was confirmed. That is the whole contract of this table: an exercise that
 * was skipped, a slot that was missed and a session that was cancelled produce **no row at all**, never
 * a row of zeroes, and the construction guard makes a zero row unrepresentable rather than merely
 * discouraged. It is what keeps "missed" from being read downstream as "performed zero repetitions" —
 * the row's existence *is* the claim that work happened.
 *
 * A set is measured in one unit, exactly as the exercise is prescribed in one unit: a repetition set
 * carries [completedReps] and no seconds, a timed set carries [durationSeconds] and no repetitions.
 * Both carry a [setIndex] because a set's position inside its occurrence is part of what it is, and both
 * carry [performedAt] because a confirmed set happened at a time.
 *
 * The table is named `program_set_log` rather than `set_log` because the version-2 database already
 * owns `set_log` for its own logging shape. The legacy table is neither repurposed nor renamed: the two
 * coexist, they mean different things, and the old one is retired when its readers are gone.
 *
 * @property setIndex 1-based position of the set within its occurrence; unique within that occurrence.
 * @property completedReps repetitions performed; `0` for a timed set.
 * @property durationSeconds seconds performed; `0` for a repetition set.
 * @property performedAt when the set was confirmed, in epoch milliseconds.
 * @property restSeconds seconds rested before this set; `null` when no rest was recorded — which is
 *   every row written before the column existed, and the first set of an occurrence. `null` is not
 *   zero: no rest observed and a rest of no seconds are different facts, and only the second one is a
 *   measurement.
 */
@Entity(
    tableName = "program_set_log",
    foreignKeys = [
        ForeignKey(
            entity = SessionExerciseEntity::class,
            parentColumns = ["sessionExerciseId"],
            childColumns = ["sessionExerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionExerciseId", "setIndex"], unique = true)]
)
data class SetLogEntity(
    @PrimaryKey val setLogId: String,
    val sessionExerciseId: String,
    val setIndex: Int,
    val completedReps: Int,
    val durationSeconds: Int,
    val performedAt: Long,
    val restSeconds: Int? = null
) {

    init {
        require(setIndex >= 1) { "sets are numbered from 1, was $setIndex" }
        require(completedReps >= 0) { "completedReps must be >= 0, was $completedReps" }
        require(durationSeconds >= 0) { "durationSeconds must be >= 0, was $durationSeconds" }
        require(restSeconds == null || restSeconds >= 0) {
            "restSeconds must be absent or >= 0, was $restSeconds"
        }
        require((completedReps > 0) != (durationSeconds > 0)) {
            "a set is measured in repetitions or in time, and a set that was not performed is " +
                "absent rather than stored as zero: reps=$completedReps seconds=$durationSeconds"
        }
    }
}
