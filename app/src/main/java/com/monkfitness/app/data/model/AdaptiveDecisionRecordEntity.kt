package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One adaptive decision as the Program System records it: about one slot of one revision, with an
 * identity of its own and the grounds it was made on.
 *
 * Not a rewrite of the Stage-1 `adaptive_decision_record`: that one is consumed by shipped code, is
 * keyed by the legacy revision integer and describes a decision window on the program calendar.
 *
 * `targetScope` and `targetId` name exactly one thing at exactly one granularity, with the scope
 * stored beside the id rather than derived: a `SESSION`-scoped decision has no id to name, which is
 * why `targetId` is nullable and the scope is not.
 *
 * The outcome and the adjustment it produced are held consistent by [AdaptiveAdjustmentEntity]: an
 * `APPLIED` decision has exactly one adjustment row naming it, a `NOT_APPLIED` decision has none.
 * There is no `adjustmentId` column here, because a second copy of the same link could disagree with
 * the row that owns it — the two are written in one transaction.
 *
 * Program-owned and immutable: a decision is a record of what was ordered and what happened, and the
 * guard's filtered-out decisions are kept too.
 *
 * @property targetScope what the change is about, at one of the four granularities (token column).
 * @property targetId the id of that target, or `null` for a `SESSION`-scoped decision.
 * @property reason the single rule that answered (`ProgramAdaptiveReason`, token column), or `null`
 *   for a row written before it was stored. It is the one piece of the engine's reasoning the schema
 *   keeps, because "the change was earned and the load guard refused it" and "there was not enough
 *   history to say" are otherwise the same row. The requested action, the signals and the guard's own
 *   verdict stay out: they are recomputable from the window, and a second copy of a recomputable fact
 *   is a copy that can disagree with the first.
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
