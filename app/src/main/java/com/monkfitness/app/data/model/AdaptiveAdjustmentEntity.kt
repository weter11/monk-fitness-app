package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One slot-scoped automatic change: one presented element before and after (§16, §23
 * `AdaptiveAdjustment`).
 *
 * An adjustment is how the adaptive stage changes what the user is about to be shown **without touching
 * the revision**. A revision is immutable history, so an adaptation cannot be an edit to it: it is a
 * delta that applies to one concrete future slot, is consumed when that slot's session snapshot is
 * taken, and becomes a real plan change only if the user saves it (§16).
 *
 * Three properties of the row carry that meaning:
 *
 *  * the changed element is named **once** ([programExerciseId]) and only once — "the element before and
 *    after is the same occurrence" is therefore not a check that can be forgotten, it is the absence of a
 *    second field that could disagree;
 *  * [beforePerSetTargets] and [afterPerSetTargets] are the full per-set prescriptions, not a single
 *    changed number, so the record of what the user was shown is complete in both directions, and the
 *    construction guard refuses a row whose before and after are the same (a change that changes nothing
 *    is `NothingToChange`, an expected result and not an adjustment, §28);
 *  * [supersedesAdjustmentId] records the chain by reference: a later adjustment for the same slot
 *    supersedes the earlier one without rewriting it, so the history of what the user was shown stays
 *    explainable. It is deliberately **not** a foreign key — a constraint there would either cascade
 *    (deleting a superseded adjustment would delete its successor) or rewrite the successor when the
 *    earlier row is deleted, and an audit record that can be rewritten by another row's removal is not
 *    an audit record.
 *
 * The table is Program-owned and immutable: rows cascade with their Program, with the decision that
 * produced them and with the slot they apply to, and nothing updates them afterwards.
 *
 * @property adjustmentId identity of this adjustment.
 * @property decisionId the decision that produced it; cascades with it.
 * @property programId the Program it belongs to.
 * @property revisionId the revision in effect. An adjustment never creates one.
 * @property slotId the slot it applies to.
 * @property programExerciseId the presented element it changes — the same occurrence before and after.
 * @property beforeExerciseId the exercise as the plan presented it.
 * @property beforePrescriptionDimension the dimension of the prescription before the change (token column).
 * @property beforePerSetTargets the prescription before the change, per set.
 * @property afterExerciseId the exercise as it should now be presented.
 * @property afterPrescriptionDimension the dimension of the prescription after the change (token column).
 * @property afterPerSetTargets the prescription after the change, per set.
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
