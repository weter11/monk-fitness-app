package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One stored target occurrence: the semantic schedule data a `program_workout_slot` row has nowhere
 * to put (§30 step 14).
 *
 * A target slot remembers only `targetOccurrenceKey`, and the occurrence that key names also carries
 * an ordered list of components — the rule and workout identities that say *what* it is. This table
 * is that payload, stored rather than re-derived: `plannedFor` and every component field are written
 * exactly as the caller presented them.
 *
 * ### The identity, and why it is a composite primary key
 *
 * Membership is exactly `(programId, occurrenceKey)`, and those two columns *are* the primary key.
 * That is what makes two Programs able to hold the same occurrence key independently (a program
 * identity prefixes it, so the pair differs) and one Program unable to hold it twice (the pair is
 * unique). It also means the two columns cannot be updated apart, so a stored record can never be
 * re-pointed at another Program or another key by a write.
 *
 * `occurrenceKey` is stored as an **opaque token**. It is never split on a separator, trimmed,
 * normalized, decoded, re-encoded or compared case-insensitively, and nothing is ever derived from
 * its text — a key is not a serialization of a rule, a workout or a date. [plannedFor] is stored
 * beside it as its own column precisely so that no one has to read the key to learn the date.
 *
 * @property programId the Program whose schedule this occurrence belongs to.
 * @property occurrenceKey the occurrence's own semantic identity, verbatim.
 * @property plannedFor the calendar date the occurrence was planned for, `YYYY-MM-DD`.
 */
@Entity(
    tableName = "program_target_occurrence",
    primaryKeys = ["programId", "occurrenceKey"],
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ProgramTargetOccurrenceEntity(
    val programId: String,
    val occurrenceKey: String,
    val plannedFor: String
) {

    init {
        require(programId.isNotBlank()) { "a stored target occurrence needs a Program identity" }
        require(occurrenceKey.isNotBlank()) {
            "a stored target occurrence needs a non-blank occurrence identity"
        }
    }
}
