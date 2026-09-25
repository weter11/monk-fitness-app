package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One component of a stored target occurrence, in the order the caller presented it (§30 step 14).
 *
 * Components are rows rather than one converted `TEXT` column for three reasons that are all about
 * not losing anything:
 *
 *  * **Order is a value.** [position] is the presenter's own order, and it is part of the primary
 *    key, so two components cannot claim the same place and a read returns them in exactly the order
 *    they were written. A joined list would have to re-sort on a tie; a serialized blob would have
 *    to be parsed to recover the order at all.
 *  * **Each field is stored as itself.** [ruleId] and [workoutId] are two independent TEXT columns,
 *    so nothing has to be split back out of a delimiter or an escape convention to read one of them.
 *  * **No component is fabricated.** There is no nullable component row, no default identity and no
 *    placeholder token: either the occurrence's real components are here or the occurrence has none.
 *
 * @property programId the Program the parent occurrence belongs to; the child repeats it so the
 *   pair is its own identity and a row can never name a different Program than its parent.
 * @property occurrenceKey the parent occurrence's own key, verbatim and never parsed.
 * @property position the component's place in the presented order, from zero.
 * @property ruleId the schedule rule's own identity.
 * @property workoutId the workout's own identity.
 */
@Entity(
    tableName = "program_target_occurrence_component",
    primaryKeys = ["programId", "occurrenceKey", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ProgramTargetOccurrenceEntity::class,
            parentColumns = ["programId", "occurrenceKey"],
            childColumns = ["programId", "occurrenceKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("programId", "occurrenceKey")]
)
data class ProgramTargetOccurrenceComponentEntity(
    val programId: String,
    val occurrenceKey: String,
    val position: Int,
    val ruleId: String,
    val workoutId: String
) {

    init {
        require(position >= 0) { "a component's position is its place in the presented order" }
        require(ruleId.isNotBlank()) { "a stored component needs a rule identity" }
        require(workoutId.isNotBlank()) { "a stored component needs a workout identity" }
    }
}
