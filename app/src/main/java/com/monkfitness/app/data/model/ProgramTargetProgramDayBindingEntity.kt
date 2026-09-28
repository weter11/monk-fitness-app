package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One stored **explicit** `workoutId -> ProgramDayId` binding of a revision.
 *
 * The target occurrence presenter requires that mapping to be stated: a `workoutId` cannot be resolved
 * to a plan day from a position, a name, a date, a weekday or an id's own text (§1), so a revision
 * states the binding and the presenter refuses an occurrence whose workout has none. This table is
 * where that statement lives, owned by the same immutable revision as the rules it belongs to.
 *
 * ### The identity, and what it forbids
 *
 * Membership is `(revisionId, workoutId)` and that pair **is** the primary key: one revision states at
 * most one binding per workout, so which plan day a workout presents is a fact rather than a
 * preference, and two bindings about one workout cannot both be true. Two revisions may bind the same
 * workout to different plan days independently, because the pair differs.
 *
 * The `programDayId` foreign key cascades, so a binding cannot outlive the plan day it names. What a
 * foreign key cannot express is that the day belongs to **this** revision — a day of another revision
 * is a perfectly valid `program_day` row — so that half of the claim is enforced where the revision's
 * own plan is in view: the repository checks the named day against the revision's own days before it
 * writes. A binding that crossed revisions would present one revision's target schedule against
 * another revision's plan, and it is refused rather than stored.
 *
 * ### No update and no delete
 *
 * A revision's bindings are written once with its rules, in the same immutable unit. Dropping a
 * binding row on its own would leave a revision stating a schedule it can no longer present, so the
 * DAO offers neither statement.
 *
 * @property revisionId the immutable revision that states this binding.
 * @property workoutId the target workout identity, verbatim and opaque.
 * @property programDayId the plan day this workout presents, verbatim and opaque.
 */
@Entity(
    tableName = "program_target_program_day_binding",
    primaryKeys = ["revisionId", "workoutId"],
    foreignKeys = [
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
    // `programDayId` is a foreign key that is **not** part of the primary key, so it needs its own
    // index — the same reason `program_workout_slot` indexes its three foreign-key columns. Without
    // it SQLite full-scans this table whenever a `program_day` row is modified, and the cascade that
    // deletes a binding would do so. `revisionId` needs no index of its own: it is the primary key's
    // leading column, and it is the only other column this table is ever read by.
    indices = [Index("programDayId")]
)
data class ProgramTargetProgramDayBindingEntity(
    val revisionId: String,
    val workoutId: String,
    val programDayId: String
) {

    init {
        require(revisionId.isNotBlank()) {
            "a stored target ProgramDay binding needs the revision that states it"
        }
        require(workoutId.isNotBlank()) {
            "a stored target ProgramDay binding needs a workout identity"
        }
        require(programDayId.isNotBlank()) {
            "a stored target ProgramDay binding needs a plan day identity"
        }
    }
}
