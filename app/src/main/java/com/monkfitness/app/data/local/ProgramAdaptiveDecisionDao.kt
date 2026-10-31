package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity

/**
 * Persistence for the `program_adaptive_decision_record` table — the Program System's own audit trail
 * of adaptive decisions.
 *
 * Append and read, and nothing else. A decision is history: an `APPLIED` decision that produced an
 * adjustment and a `NOT_APPLIED` one the load guard filtered out are both records of what was ordered
 * at the time, so an update that rewrote one and a delete that removed one would destroy the only
 * thing the row exists to prove. The absence of those statements is the immutability, not a convention
 * the caller is asked to respect. Rows disappear only with their Program's cascade.
 *
 * Every read ends in `decidedAt, decisionId` so a history is deterministic rather than dependent on
 * storage order.
 */
@Dao
interface ProgramAdaptiveDecisionDao {

    @Insert
    suspend fun insertDecision(decision: AdaptiveDecisionRecordEntity)

    /** The decision, or `null` when none is recorded. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `decisionId` = :decisionId LIMIT 1")
    suspend fun decisionById(decisionId: String): AdaptiveDecisionRecordEntity?

    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `programId` = :programId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfProgram(programId: String): List<AdaptiveDecisionRecordEntity>

    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `revisionId` = :revisionId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfRevision(revisionId: String): List<AdaptiveDecisionRecordEntity>

    /** One slot's decision chain, oldest first. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `slotId` = :slotId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfSlot(slotId: String): List<AdaptiveDecisionRecordEntity>
}
