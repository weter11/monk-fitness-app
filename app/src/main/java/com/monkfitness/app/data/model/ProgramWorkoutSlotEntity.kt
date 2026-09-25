package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One planned opportunity to train (§20, §23 `ProgramWorkoutSlot`).
 *
 * This row exists to make one thing impossible: reading a slot as an amount of work. It holds the
 * slot's identity, the Program and revision it was scheduled from, the plan day it presents, the date
 * it was planned for, what happened to it and when that happened — and **no performance of any kind**.
 * There is no repetition count, no set count, no duration and no "completion amount", so a `MISSED`
 * slot cannot be aggregated as a workout that scored zero: the absence is the representation (§12).
 *
 * Sessions are separate rows, and a slot may have several of them: several attempts are legal, which is
 * why nothing here or in [WorkoutSessionEntity] is unique on `slotId`. That at most one attempt is
 * `IN_PROGRESS` is a rule about two rows at once, so it belongs to the transaction that starts a
 * session (§27), not to a column.
 *
 * [completedAt] is kept consistent with [status] at construction: only a `COMPLETED` slot says when it
 * happened, and a slot that did not happen cannot carry a stamp. A `SUPERSEDED` slot is not a missed
 * one — the user was not expected to train it (§20) — which is why both are their own token rather than
 * a boolean.
 *
 * @property slotId identity of this opportunity.
 * @property programId the Program whose plan this slot presents.
 * @property revisionId the revision it was scheduled from. A later revision supersedes the slot; it
 *   never rewrites it.
 * @property programDayId the plan day it presents.
 * @property plannedFor the calendar date it was planned for, `YYYY-MM-DD`. A plan date, never a
 *   session's actual time (§19).
 * @property status `PLANNED`, `COMPLETED`, `MISSED` or `SUPERSEDED` (token column).
 * @property completedAt when a session completed this slot, in epoch milliseconds, or `null`.
 */
@Entity(
    tableName = "program_workout_slot",
    foreignKeys = [
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
        ),
        ForeignKey(
            entity = ProgramDayEntity::class,
            parentColumns = ["programDayId"],
            childColumns = ["programDayId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("programId"),
        Index("programDayId"),
        Index("revisionId", "plannedFor"),
        Index(
            value = ["programId", "targetOccurrenceKey"],
            unique = true,
            name = "index_program_workout_slot_programId_targetOccurrenceKey"
        )
    ]
)
data class ProgramWorkoutSlotEntity(
    @PrimaryKey val slotId: String,
    val programId: String,
    val revisionId: String,
    val programDayId: String,
    val plannedFor: String,
    val status: String,
    val completedAt: Long? = null,
    val targetOccurrenceKey: String? = null
) {

    init {
        require(targetOccurrenceKey == null || targetOccurrenceKey.isNotBlank()) {
            "targetOccurrenceKey is either absent for a legacy slot or non-blank"
        }
        require((status == COMPLETED) == (completedAt != null)) {
            "only a $COMPLETED slot happened, and a completed slot says when: status=$status " +
                "completedAt=$completedAt"
        }
    }

    companion object {
        const val PLANNED: String = "PLANNED"
        const val COMPLETED: String = "COMPLETED"
        const val MISSED: String = "MISSED"
        const val SUPERSEDED: String = "SUPERSEDED"
    }
}
