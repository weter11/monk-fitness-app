package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A family's progression state as the Program System owns it.
 *
 * This is the target table, not the shipped `family_progression_state`: that one is consumed
 * by a DAO and a repository, its stored vocabulary is pinned by the persistence test, and it keys
 * family state by the **legacy revision integer** while the Program System's identity is the typed
 * `revisionId` of a real [ProgramRevisionEntity] row. No value of one is expressible as the other, so
 * the pair coexists until the old persistence is removed; nothing here reads or writes the legacy
 * tables.
 *
 * Identity is `(revisionId, familyId)`: one family has exactly one current state per revision, which
 * is why the pair is the primary key and not a caller convention. Deliberately **not** cycle-scoped —
 * the 56-day calendar and adaptive progression are independent by design, and a cycle rollover
 * preserves a family's level — while a new revision starts from baseline with the previous revision's
 * rows still readable.
 *
 * Only the fields the blueprint's own vocabulary names are stored: the Stage-1 hysteresis counters and
 * policy version are not carried over, because freezing the pilot's shape into the target schema would
 * make its first revision a migration instead of a design.
 *
 * The five window-bookkeeping columns after `updatedAt` hold the **target** engine's own window facts
 * (the confirmation counts, the cooldown position and the recovery exit count the caller maintains
 * between windows; [FamilyProgressionState] states what each one means and why "never had a change" is
 * `null` rather than `0`). All five are **nullable and carry no default**: a row written before they
 * existed records no window bookkeeping at all, and the mapper reads that absence as the count a
 * family with no preceding window has. A `DEFAULT 0` would instead let the schema claim that every
 * upgraded row had counted zero windows — the same value, arrived at by asserting a fact the row does
 * not hold.
 *
 * @property currentExerciseId the exercise id the family is currently on, or `null` when it carries
 *   none.
 * @property updatedAt when this row was last written, in epoch milliseconds.
 * @property qualifyingWindowsSinceLastChange the cooldown position, or `null` when there has never
 *   been a change.
 */
@Entity(
    tableName = "program_family_progression_state",
    primaryKeys = ["revisionId", "familyId"],
    foreignKeys = [
        ForeignKey(
            entity = ProgramRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FamilyProgressionStateEntity(
    val revisionId: String,
    val familyId: String,
    val progressionLevel: Int,
    val adaptationState: String,
    val currentExerciseId: String? = null,
    val updatedAt: Long,
    val precedingProgressQualifyingWindows: Int? = null,
    val precedingRegressQualifyingWindows: Int? = null,
    val precedingRecoveryQualifyingWindows: Int? = null,
    val qualifyingWindowsSinceLastChange: Int? = null,
    val recoveryQualifyingWindows: Int? = null
)
