package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity

/**
 * Persistence for the `adaptive_adjustment` table — one slot-scoped automatic change.
 *
 * Rows are immutable and superseded by reference, so there is no update (the earlier row keeps
 * describing what the user was shown, which keeps the supersession chain explainable) and no delete
 * (a superseded adjustment is history, not garbage); they disappear only through the schema cascade.
 *
 * Chains are read oldest first, so the adjustment in effect is answerable from the rows without a
 * mutable current-state column that could disagree with the history. Reading does not resolve
 * `before`/`after` against the current plan: both are stored per set and returned as stored. The
 * decision table has no adjustment column, so a decision's adjustment is found by the decision it names.
 */
@Dao
interface AdaptiveAdjustmentDao {

    @Insert
    suspend fun insertAdjustment(adjustment: AdaptiveAdjustmentEntity)

    /** The adjustment, or `null` when none is recorded. */
    @Query("SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = :adjustmentId LIMIT 1")
    suspend fun adjustmentById(adjustmentId: String): AdaptiveAdjustmentEntity?

    @Query("SELECT * FROM `adaptive_adjustment` WHERE `decisionId` = :decisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfDecision(decisionId: String): List<AdaptiveAdjustmentEntity>

    @Query("SELECT * FROM `adaptive_adjustment` WHERE `slotId` = :slotId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfSlot(slotId: String): List<AdaptiveAdjustmentEntity>

    @Query("SELECT * FROM `adaptive_adjustment` WHERE `programId` = :programId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfProgram(programId: String): List<AdaptiveAdjustmentEntity>

    @Query("SELECT * FROM `adaptive_adjustment` WHERE `revisionId` = :revisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC")
    suspend fun adjustmentsOfRevision(revisionId: String): List<AdaptiveAdjustmentEntity>
}
