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

    /**
     * How many records one decision window already carries for one family.
     *
     * This is the idempotency question the finalized-session path asks: a window is
     * `(programRevision, cycleNumber, programDay)` — the app's own session identity, and the identity
     * every record already carries — so a count above zero means this session's decision has been
     * recorded before and a repeated finalization (a recomposition, a second callback, a re-entry, a
     * restored view model) must add nothing.
     *
     * It is a read, not a constraint: the tables hold no unique index on the window, because the
     * decision path decides whether a session is new inside the same transaction it writes in, and a
     * schema constraint would have to encode a rule this layer does not own. `countDecisionsFor` is
     * only meaningful inside that transaction — two callers that each read and then write outside one
     * would race, which is exactly why the repository performs the check in its own transaction block.
     */
    @Query(
        "SELECT COUNT(*) FROM adaptive_decision_record WHERE programRevision = :programRevision " +
            "AND cycleNumber = :cycleNumber AND programDay = :programDay AND familyId = :familyId"
    )
    suspend fun countDecisionsFor(
        programRevision: Int,
        cycleNumber: Int,
        programDay: Int,
        familyId: String
    ): Int

    /**
     * Clears the whole trail, for the one operation that erases the program's own record: the C3 "Full
     * Reset", which returns the app to its true first-launch state and clears the workout history these
     * entries audit. It is the only delete this DAO exposes, and deliberately not a per-window one: a
     * single decision is never removed, because an audit trail with a hole cannot prove what was
     * ordered, while a whole-program reset that forgets its audit trail is not a reset.
     */
    @Query("DELETE FROM adaptive_decision_record")
    suspend fun clearDecisionHistory()
}
