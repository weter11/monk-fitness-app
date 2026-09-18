package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity

/**
 * Persistence for the `adaptive_adjustment` table — one slot-scoped automatic change (§16, §23).
 *
 * Append and read. An adjustment is immutable and is superseded by reference rather than rewritten, so
 * this DAO offers no update: the earlier adjustment keeps describing exactly what the user was shown,
 * which is what makes the supersession chain explainable afterwards (§16). It offers no delete either —
 * a superseded adjustment is history, not garbage — and rows disappear only with their Program,
 * decision or slot through the schema's cascade (§29).
 *
 * [adjustmentsOfSlot] returns the chain in creation order, so "which adjustment is in effect for this
 * slot" is answerable from the rows without a mutable current-state column that could disagree with the
 * history. Reading does not resolve `before`/`after` against the current plan: both are stored in the
 * row, per set, and are returned as stored.
 *
 * [adjustmentsOfDecision] reads the other direction of the one link the two tables share: the decision
 * table has no adjustment column (§23), so the adjustment that an applied decision produced is found
 * by the decision it names.
 */
@Dao
interface AdaptiveAdjustmentDao {

    /** Appends one adjustment. Nothing is ever replaced or rewritten. */
    @Insert
    suspend fun insertAdjustment(adjustment: AdaptiveAdjustmentEntity)

    /** The adjustment with [adjustmentId], or `null` when no such adjustment is recorded. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = :adjustmentId LIMIT 1")
    suspend fun adjustmentById(adjustmentId: String): AdaptiveAdjustmentEntity?

    /** The adjustments that name one decision, in creation order. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `decisionId` = :decisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfDecision(decisionId: String): List<AdaptiveAdjustmentEntity>

    /** The adjustment chain of one slot, oldest first. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `slotId` = :slotId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfSlot(slotId: String): List<AdaptiveAdjustmentEntity>

    /** Every adjustment of one Program, oldest first. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `programId` = :programId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfProgram(programId: String): List<AdaptiveAdjustmentEntity>

    /** Every adjustment made under one revision, oldest first. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `revisionId` = :revisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfRevision(revisionId: String): List<AdaptiveAdjustmentEntity>
}
