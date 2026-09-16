package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.AdaptiveDecisionRecord

/**
 * The append-only adaptive audit trail. Deliberately narrow: append and read, with no update and no
 * delete, because a decision record is history — a rewritten audit entry cannot prove what was
 * ordered at the time, which is the only thing it exists for.
 *
 * Every query names a total order ending in `id`, the insertion identity. `(cycleNumber, programDay)`
 * identifies the decision window but not the row: a window may legitimately hold more than one
 * record (one per family, or more than one pass over the same window), so without the tiebreaker the
 * result would depend on unspecified row order.
 */
@Dao
interface AdaptiveDecisionHistoryDao {

    /**
     * Appends one record and returns its row identity. Nothing is replaced: a record is never
     * overwritten, so the trail only ever grows.
     */
    @Insert
    suspend fun appendDecision(record: AdaptiveDecisionRecord): Long

    /**
     * One program revision's history, oldest window first. The order is
     * `(cycleNumber, programDay, id)`, so the trail reads as the program ran.
     */
    @Query(
        "SELECT * FROM adaptive_decision_record WHERE programRevision = :programRevision " +
            "ORDER BY cycleNumber ASC, programDay ASC, id ASC"
    )
    suspend fun getDecisionHistory(programRevision: Int): List<AdaptiveDecisionRecord>

    /**
     * One family's whole audit trail, across every program revision it was adapted under, oldest
     * first. Deliberately not revision-scoped: an earlier revision's records are exactly what
     * "the old history remains available for analysis" means, and a family's trail is only readable
     * if it is not truncated at the revision boundary.
     */
    @Query(
        "SELECT * FROM adaptive_decision_record WHERE familyId = :familyId " +
            "ORDER BY programRevision ASC, cycleNumber ASC, programDay ASC, id ASC"
    )
    suspend fun getDecisionHistoryForFamily(familyId: String): List<AdaptiveDecisionRecord>
}
