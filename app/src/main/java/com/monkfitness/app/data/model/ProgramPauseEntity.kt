package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pause interval of a Program (§3, §23 `ProgramPause`).
 *
 * Keeping the intervals rather than only the current status is what makes "this program was paused"
 * explainable afterwards without rewriting the plan or the history: pausing freezes active program time
 * and missed-opportunity logic, and resuming closes the interval instead of erasing it. The intervals are
 * owned by the Program, so they are destroyed with it and with nothing else (§29).
 *
 * An open interval ([endedAt] `null`) is a pause currently in effect. That this matches the Program's
 * `lifecycleStatus` is a lifecycle rule, checked by the layer that owns both — the row states the
 * interval, not the program's current state.
 *
 * @property pauseId identity of this interval.
 * @property programId the Program that was paused; cascades with it.
 * @property startedAt when the pause began, in epoch milliseconds.
 * @property endedAt when it ended, in epoch milliseconds, or `null` while it is still in effect.
 */
@Entity(
    tableName = "program_pause",
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("programId")]
)
data class ProgramPauseEntity(
    @PrimaryKey val pauseId: String,
    val programId: String,
    val startedAt: Long,
    val endedAt: Long? = null
)
