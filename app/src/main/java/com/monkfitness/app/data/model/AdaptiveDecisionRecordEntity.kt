package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One adaptive decision, as the Program System will record it (§15, §16, §18, §23
 * `AdaptiveDecisionRecord`).
 *
 * This is the target table, not a rewrite of the Stage-1 `adaptive_decision_record`: that one is
 * consumed by shipped code, is keyed by the legacy revision integer, and describes a decision window on
 * the program calendar (`cycleNumber`, `programDay`). The Program System's decision is about **one slot
 * of one revision**, has an identity of its own, and carries the grounds it was made on, so its columns
 * are the target model's (`decisionId`, `programId`, `revisionId`, `slotId`, the target and its scope,
 * the action, the outcome, the evidence, the confidence and the recovery context it was made in).
 *
 * The pair of `targetScope` and `targetId` is how one decision names exactly one thing at exactly one
 * granularity, with the scope stored beside the id rather than derived: a `SESSION`-scoped decision has
 * no id to name, and every other scope has one, which is why `targetId` is nullable and the scope is
 * not.
 *
 * The outcome and the adjustment it produced are held consistent by the third table in this family,
 * [AdaptiveAdjustmentEntity]: an `APPLIED` decision has exactly one adjustment row naming it, a
 * `NOT_APPLIED` decision has none. There is no `adjustmentId` column here, because a second copy of the
 * same link could disagree with the row that owns it — the two are written in one transaction (§27).
 *
 * Decisions are Program-owned and cascade with it. Nothing in this table is mutable: a decision is a
 * record of what was ordered and what happened, and §18 keeps even the guard's filtered-out decisions.
 *
 * @property decisionId identity of this decision.
 * @property programId the Program it belongs to.
 * @property revisionId the revision in effect. A decision never creates a revision.
 * @property slotId the slot the change applies to.
 * @property targetScope what the change is about, at one of the four granularities (§18) — token column.
 * @property targetId the id of that target, or `null` for a `SESSION`-scoped decision.
 * @property action what was asked for (token column).
 * @property outcome `APPLIED` or `NOT_APPLIED` (token column).
 * @property evidence how much comparable history justified it (token column).
 * @property confidence how far that history could be trusted (token column).
 * @property recovery the recovery context it was made in (token column).
 * @property decidedAt when it was decided, in epoch milliseconds.
 * @property reason the single rule that answered (`ProgramAdaptiveReason`, token column), or `null`
 *   for a row written before §30 step 12 stored it. It is the one piece of the engine's reasoning the
 *   schema keeps, and it is kept because *"the change was earned and the load guard refused it"* and
 *   *"there was not enough history to say"* are otherwise the same row: one slot, one target, one
 *   action, one outcome. The requested action, the signals and the guard's own verdict stay out —
 *   they are recomputable from the window the decision was taken on, and a second copy of a
 *   recomputable fact is a copy that can disagree with the first.
 */
@Entity(
    tableName = "program_adaptive_decision_record",
    foreignKeys = [
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
    indices = [Index("programId"), Index("revisionId"), Index("slotId")]
)
data class AdaptiveDecisionRecordEntity(
    @PrimaryKey val decisionId: String,
    val programId: String,
    val revisionId: String,
    val slotId: String,
    val targetScope: String,
    val targetId: String? = null,
    val action: String,
    val outcome: String,
    val evidence: String,
    val confidence: String,
    val recovery: String,
    val decidedAt: Long,
    val reason: String? = null
)
