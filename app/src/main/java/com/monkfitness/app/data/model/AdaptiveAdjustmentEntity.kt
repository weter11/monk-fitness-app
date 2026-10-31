package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One slot-scoped automatic change: one presented element before and after.
 *
 * An adjustment changes what the user is about to be shown without touching the revision: a revision
 * is immutable history, so an adaptation is a delta for one concrete future slot, consumed when that
 * slot's session snapshot is taken, and a real plan change only if the user saves it.
 *
 * The changed element is named once ([programExerciseId]) — "the same occurrence before and after" is
 * not a check that can be forgotten, it is the absence of a second field that could disagree.
 * [beforePerSetTargets]/[afterPerSetTargets] are the full per-set prescriptions, not one changed
 * number, so what the user was shown is complete in both directions; the guard refuses a row whose
 * before and after are the same (a no-op is `NothingToChange`, an expected result, not an adjustment).
 * [supersedesAdjustmentId] chains by reference: a later adjustment for the same slot supersedes the
 * earlier one without rewriting it, so the history stays explainable. It is deliberately not a
 * foreign key — a cascade would delete the successor with the superseded row, and an audit record
 * another row's removal can rewrite is not an audit record.
 *
 * Program-owned and immutable: rows cascade with their Program, with the decision that produced them
 * and with the slot they apply to.
 *
 * @property revisionId the revision in effect; an adjustment never creates one.
 * @property createdAt when it was produced, in epoch milliseconds.
 * @property supersedesAdjustmentId the adjustment it replaces for this slot, or `null`.
 */
@Entity(
    tableName = "adaptive_adjustment",
    foreignKeys = [
        ForeignKey(
            entity = AdaptiveDecisionRecordEntity::class,
            parentColumns = ["decisionId"],
            childColumns = ["decisionId"],
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
        ),
        ForeignKey(
            entity = ProgramWorkoutSlotEntity::class,
            parentColumns = ["slotId"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("decisionId"),
        Index("programId"),
        Index("revisionId"),
        Index("slotId")
    ]
)
data class AdaptiveAdjustmentEntity(
    @PrimaryKey val adjustmentId: String,
    val decisionId: String,
    val programId: String,
    val revisionId: String,
    val slotId: String,
    val programExerciseId: String,
    val beforeExerciseId: String,
    val beforePrescriptionDimension: String,
    val beforePerSetTargets: List<Int>,
    val afterExerciseId: String,
    val afterPrescriptionDimension: String,
    val afterPerSetTargets: List<Int>,
    val createdAt: Long,
    val supersedesAdjustmentId: String? = null
) {

    init {
        require(supersedesAdjustmentId != adjustmentId) {
            "an adjustment cannot supersede itself"
        }
        require(
            beforeExerciseId != afterExerciseId ||
                beforePrescriptionDimension != afterPrescriptionDimension ||
                beforePerSetTargets != afterPerSetTargets
        ) {
            "an adjustment that changes nothing is NothingToChange, a result, not an adjustment (§28)"
        }
    }
}
