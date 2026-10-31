package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One component of a stored target occurrence, in the order the caller presented it.
 *
 * Components are rows rather than one converted `TEXT` column so that nothing is lost: [position] is
 * the presenter's own order and part of the primary key, so two components cannot claim the same place
 * and a read returns them in exactly the order they were written; [ruleId] and [workoutId] are two
 * independent columns, so nothing has to be split back out of a delimiter; and no component is
 * fabricated — no nullable component row, no default identity, no placeholder token.
 *
 * @property programId the Program the parent occurrence belongs to; the child repeats it so the
 *   pair is its own identity and a row can never name a different Program than its parent.
 * @property occurrenceKey the parent occurrence's own key, verbatim and never parsed.
 * @property position the component's place in the presented order, from zero.
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
