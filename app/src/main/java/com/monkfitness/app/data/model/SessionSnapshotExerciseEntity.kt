package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One element of a session's captured presentation (§19).
 *
 * Each row is what the user was shown for one plan element: which occurrence it came from, which
 * exercise, and what was asked for — per set, in the dimension the prescription was written in. The
 * prescription is **copied** here rather than read from [ProgramExerciseEntity] at runtime, so an edit
 * to the plan cannot change what an already-started workout was measured against; §19 requires a
 * started session to retain the presentation captured at start.
 *
 * The element's identity inside the snapshot is the occurrence it presented: a presentation contains
 * each plan element at most once, so `(sessionId, programExerciseId)` is the key and [position] is only
 * the place it held in the presentation — an ordering, not an identity, exactly as a plan day's
 * position is not the day's identity. The occurrence is named without a foreign key, because the
 * captured presentation is a value and not a pointer into a plan that may later be superseded.
 *
 * @property sessionId the session (and snapshot) this element belongs to; cascades with it.
 * @property programExerciseId the plan occurrence this presentation came from.
 * @property position 1-based place of this element in the presentation; unique within the snapshot.
 * @property exerciseId the library key that was presented.
 * @property prescriptionDimension the dimension the presented prescription was written in (token column).
 * @property perSetTargets the presented target of every set, in set order.
 */
@Entity(
    tableName = "session_snapshot_exercise",
    primaryKeys = ["sessionId", "programExerciseId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionSnapshotEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId", "position"], unique = true)]
)
data class SessionSnapshotExerciseEntity(
    val sessionId: String,
    val programExerciseId: String,
    val position: Int,
    val exerciseId: String,
    val prescriptionDimension: String,
    val perSetTargets: List<Int>
)
