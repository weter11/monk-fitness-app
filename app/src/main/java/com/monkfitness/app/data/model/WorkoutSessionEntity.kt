package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One started workout session (§19, §23 `WorkoutSession`).
 *
 * It is persisted runtime state: created once, when the user starts it, and it survives the screen
 * closing, the process being recreated, an app restart and a reboot. Going back does not cancel it;
 * only an explicit cancel ends it that way, and the history stays either way.
 *
 * Planned and actual time are different facts and both are preserved, in different places: the planned
 * date lives in the captured presentation ([SessionSnapshotEntity]), while [startedAt] and [finishedAt]
 * here are what actually happened. A session started on one date and completed the next therefore keeps
 * both, which is exactly what replacing the actual timestamps with the planned date would destroy.
 *
 * The session is bound to its slot, its Program and the revision it started under, and none of those
 * bindings reaches the live plan: the presentation the user saw is frozen in
 * [SessionSnapshotEntity]/[SessionSnapshotExerciseEntity] at session start, so a later revision, a
 * later edit or a superseding adjustment cannot re-explain a workout that already happened.
 *
 * [status] and [finishedAt] are held consistent at construction: an `IN_PROGRESS` session has not
 * finished, and a completed or cancelled one says when it did. `CANCELLED` is not a quiet `COMPLETED` —
 * the partial work it recorded stays recorded (§12) — which is why the two are separate tokens rather
 * than one flag.
 *
 * @property sessionId identity of this session.
 * @property slotId the opportunity it attempts. No uniqueness: a slot may be attempted repeatedly.
 * @property programId the Program it belongs to.
 * @property revisionId the revision it started under — identity and scope, not a source of
 *   presentation.
 * @property status `IN_PROGRESS`, `COMPLETED` or `CANCELLED` (token column).
 * @property startedAt when the user started it, in epoch milliseconds.
 * @property finishedAt when it was completed or cancelled, in epoch milliseconds, or `null` while it is
 *   in progress.
 */
@Entity(
    tableName = "workout_session",
    foreignKeys = [
        ForeignKey(
            entity = ProgramWorkoutSlotEntity::class,
            parentColumns = ["slotId"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProgramRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("slotId"),
        Index("programId", "startedAt"),
        Index("revisionId")
    ]
)
data class WorkoutSessionEntity(
    @PrimaryKey val sessionId: String,
    val slotId: String,
    val programId: String,
    val revisionId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null
) {

    init {
        require((status == IN_PROGRESS) == (finishedAt == null)) {
            "an $IN_PROGRESS session has not finished, and a finished session is not $IN_PROGRESS: " +
                "status=$status finishedAt=$finishedAt"
        }
        require(finishedAt == null || finishedAt >= startedAt) {
            "a session cannot finish before it started: startedAt=$startedAt finishedAt=$finishedAt"
        }
    }

    companion object {
        const val IN_PROGRESS: String = "IN_PROGRESS"
        const val COMPLETED: String = "COMPLETED"
        const val CANCELLED: String = "CANCELLED"
    }
}
