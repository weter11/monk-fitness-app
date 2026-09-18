package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity

/**
 * Persistence for the `program_adaptive_decision_record` table — the Program System's own audit trail
 * of adaptive decisions (§15, §18, §23).
 *
 * It is **not** the Stage-1 `adaptive_decision_record` DAO: that table is keyed by an autoincrement
 * id, describes a decision window on the legacy program calendar (`cycleNumber`, `programDay`) and is
 * consumed by shipped code. No statement here names it.
 *
 * Append and read, and nothing else. A decision is history: an `APPLIED` decision that produced an
 * adjustment and a `NOT_APPLIED` one the load guard filtered out are both records of what was ordered
 * at the time (§18), so an update that rewrote one and a delete that removed one would destroy the
 * only thing the row exists to prove. The absence of those statements is the immutability, not a
 * convention the caller is asked to respect. Rows disappear only with their Program's cascade (§29).
 *
 * Every read ends in `decidedAt, decisionId` so a history is deterministic rather than dependent on
 * storage order.
 */
@Dao
interface ProgramAdaptiveDecisionDao {

    /** Appends one decision to the trail. Nothing is ever replaced. */
    @Insert
    suspend fun insertDecision(decision: AdaptiveDecisionRecordEntity)

    /** The decision with [decisionId], or `null` when no such decision is recorded. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `decisionId` = :decisionId LIMIT 1")
    suspend fun decisionById(decisionId: String): AdaptiveDecisionRecordEntity?

    /** One Program's decisions, oldest first. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `programId` = :programId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfProgram(programId: String): List<AdaptiveDecisionRecordEntity>

    /** One revision's decisions, oldest first. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `revisionId` = :revisionId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfRevision(revisionId: String): List<AdaptiveDecisionRecordEntity>

    /** One slot's decisions, oldest first — the decision chain a slot was adapted through. */
    @Query("SELECT * FROM `program_adaptive_decision_record` WHERE `slotId` = :slotId ORDER BY `decidedAt` ASC, `decisionId` ASC")
    suspend fun decisionsOfSlot(slotId: String): List<AdaptiveDecisionRecordEntity>
}
