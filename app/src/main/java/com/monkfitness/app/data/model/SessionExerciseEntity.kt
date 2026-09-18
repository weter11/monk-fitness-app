package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise occurrence as it actually ran inside one session (§19, §23 `SessionExercise`).
 *
 * The row links back to the plan by [programExerciseId] — the same element the session's snapshot
 * presented — which is what makes "what was planned" and "what was performed" comparable without
 * guessing. That link is a plain reference and not a foreign key: the session retains what it was
 * shown, and a plan that changes afterwards must not be able to rewrite, orphan or delete that record.
 *
 * [prescriptionDimension] and [perSetTargets] are the prescription **as presented**, copied into the
 * session's snapshot at start, not re-read from the revision. [skipped] is the user's explicit "not
 * this one", and it is exactly equivalent to having no confirmed sets: a skipped occurrence
 * accumulates no [SetLogEntity] rows at all, because a set that was not performed is absent rather
 * than recorded as zero (§12).
 *
 * @property sessionExerciseId identity of this occurrence within the session.
 * @property sessionId the session that owns it; cascades with it.
 * @property position 1-based presentation order; unique within the session.
 * @property programExerciseId the plan element it presents.
 * @property exerciseId the library key of the exercise, as presented.
 * @property prescriptionDimension the dimension the presented prescription was written in (token column).
 * @property perSetTargets the presented target of every set, in set order.
 * @property skipped whether the user explicitly skipped it.
 */
@Entity(
    tableName = "session_exercise",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId", "position"], unique = true)]
)
data class SessionExerciseEntity(
    @PrimaryKey val sessionExerciseId: String,
    val sessionId: String,
    val position: Int,
    val programExerciseId: String,
    val exerciseId: String,
    val prescriptionDimension: String,
    val perSetTargets: List<Int>,
    val skipped: Boolean = false
)
