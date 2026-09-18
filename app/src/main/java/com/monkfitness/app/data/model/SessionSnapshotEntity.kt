package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The immutable record of what one session was presented when it started (§19).
 *
 * The distinction this table exists for is subtle and load-bearing:
 *
 * ```text
 * the revision + the adjustments in effect  = what should be presented for a slot (recomputable)
 * session_snapshot                          = what WAS presented when this session started (frozen)
 * ```
 *
 * A session may only ever run what its snapshot presented, so the snapshot is the plan of record for
 * that session — and it is stored **here**, next to the session, rather than joined to the live plan.
 * That is why this table has no foreign key into `program_revision`, `program_day` or
 * `program_exercise`: an edit, a new revision or a superseding adjustment must never be able to change
 * what a workout already recorded, and a live join would make exactly that possible.
 *
 * The identity triple (slot, program, revision) is not duplicated here: the domain binds a session to a
 * snapshot that names the same three, and [WorkoutSessionEntity] holds them. What the session row does
 * not hold, this table does — when the presentation was captured, the date it was planned for, when it
 * was computed, and which adjustments it had already applied. The captured elements are the rows of
 * [SessionSnapshotExerciseEntity].
 *
 * The table is one row per session, enforced by its primary key being the session's own id.
 *
 * @property sessionId the session this snapshot belongs to; cascades with it, and never outlives it.
 * @property capturedAt when the session started and this record was taken, in epoch milliseconds.
 * @property plannedFor the date the slot was planned for, `YYYY-MM-DD` — kept beside the session's
 *   actual timestamps, never in place of them (§19).
 * @property computedAt when the presentation was computed, in epoch milliseconds.
 * @property appliedAdjustmentIds the adjustments already applied to this presentation, in application
 *   order, as recorded ids — deliberately not foreign keys, because the record of what was shown must
 *   not lose a member if a later stage prunes or rewrites an adjustment row.
 */
@Entity(
    tableName = "session_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionSnapshotEntity(
    @PrimaryKey val sessionId: String,
    val capturedAt: Long,
    val plannedFor: String,
    val computedAt: Long,
    val appliedAdjustmentIds: List<String> = emptyList()
)
