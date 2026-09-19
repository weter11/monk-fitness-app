package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A family's progression state **as the Program System will own it** (§23 `FamilyProgressionState`).
 *
 * The Stage-1 adaptive tables are shipped and consumed — a DAO and a repository read them, and the
 * persistence test pins their stored vocabulary — so their contract cannot be reshaped here without
 * pulling the adaptive integration stage into this PR. They key family state by the **legacy revision
 * integer** (`family_progression_state.programRevision`, matching `SettingsManager.PROGRAM_REVISION`),
 * while the Program System's identity is the typed `revisionId` of a real [ProgramRevisionEntity] row
 * (§1, §23). No value of one is expressible as the other, so this is the target table, and it is named
 * for its owner: `program_family_progression_state`.
 *
 * The two coexist until §30 step 15 removes the old persistence; nothing here reads or writes the
 * Stage-1 tables, and no DAO or repository is added for this one in this PR (§30 step 3 owns that).
 *
 * Its identity is `(revisionId, familyId)`: one family has exactly one current state per revision, which
 * is why the pair is the primary key and not a caller convention. It is deliberately **not**
 * cycle-scoped — the 56-day calendar and adaptive progression are independent by design, and a cycle
 * rollover preserves a family's level — while a new revision starts from baseline with the previous
 * revision's rows still readable.
 *
 * Only the fields the blueprint's own vocabulary names are stored. The Stage-1 hysteresis counters and
 * policy version are not carried over: the target engine's state shape belongs to the adaptive stage
 * (§30 step 11), and freezing the pilot's shape into the target schema would make its first revision a
 * migration instead of a design.
 *
 * ### The five window-bookkeeping columns (§30 step 12)
 *
 * The columns after `updatedAt` were appended by the version-10 → version-11 migration, and they hold
 * the **target** engine's own window facts: the confirmation counts, the cooldown position and the
 * recovery exit count the caller maintains between windows ([FamilyProgressionState] states what each
 * one means and why "never had a change" is `null` rather than `0`).
 *
 * All five are **nullable and carry no default**, for the reason the earlier additive steps state: a
 * row written before this step records no window bookkeeping at all, and the mapper reads that
 * absence as the count a family with no preceding window has. A `DEFAULT 0` would instead let the
 * schema claim that every upgraded row had counted zero windows — the same value, arrived at by
 * asserting a fact the row does not hold.
 *
 * @property revisionId the revision this state belongs to.
 * @property familyId the exercise family this state belongs to, by id; no family catalogue is owned here.
 * @property progressionLevel the family's abstract position on its own progression axis.
 * @property adaptationState the state the family currently holds (token column).
 * @property currentExerciseId the exercise id the family is currently on, or `null` when it carries
 *   none.
 * @property updatedAt when this row was last written, in epoch milliseconds.
 * @property precedingProgressQualifyingWindows consecutive preceding progression-qualifying windows.
 * @property precedingRegressQualifyingWindows the same, for the regression conditions.
 * @property precedingRecoveryQualifyingWindows the same, for §14's recovery-entry pattern.
 * @property qualifyingWindowsSinceLastChange the cooldown position, or `null` when there has never
 *   been a change.
 * @property recoveryQualifyingWindows windows completed while the family is in recovery.
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
