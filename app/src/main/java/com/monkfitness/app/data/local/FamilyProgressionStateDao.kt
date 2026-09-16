package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.FamilyProgressionState

/**
 * The current adaptive state of every family — the mutable half of the adaptive persistence
 * contract. Deliberately narrow: read, read one, and write, with no adaptive domain logic and no
 * history concern. Deciding whether a transition should happen is the engine's and the policy's
 * business, never this layer's.
 *
 * Both read queries are scoped by program revision and share one order (ascending family id), so
 * "all family states" means exactly the active revision's families, in a deterministic order, rather
 * than a set whose composition depends on how many revisions the database has seen.
 */
@Dao
interface FamilyProgressionStateDao {

    /** Every family state of one program revision, ordered by ascending family id. */
    @Query(
        "SELECT * FROM family_progression_state WHERE programRevision = :programRevision " +
            "ORDER BY familyId ASC"
    )
    suspend fun getFamilyStates(programRevision: Int): List<FamilyProgressionState>

    /** The state of one family in one program revision, or `null` when it has never been stored. */
    @Query(
        "SELECT * FROM family_progression_state WHERE programRevision = :programRevision " +
            "AND familyId = :familyId LIMIT 1"
    )
    suspend fun getFamilyState(programRevision: Int, familyId: String): FamilyProgressionState?

    /**
     * Writes one family's current state, replacing any row that already holds that family's state for
     * that revision. The replacement is what makes this an "insert or update one current state"
     * operation: the `(programRevision, familyId)` primary key guarantees the table can hold exactly
     * one current-state row per family per revision, so no caller can accidentally accumulate
     * competing "current" rows.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilyState(state: FamilyProgressionState)

    /**
     * Clears every family's current state, for the one operation that erases the program's own record:
     * the C3 "Full Reset", after which the next session of every family is evaluated from the domain's
     * documented `notYetTracked` baseline exactly as it was on first launch.
     *
     * It is table-wide on purpose and there is deliberately no revision-scoped delete. "Start Revised
     * Program" must NOT come through here: that action starts a new revision from baseline by writing
     * its own revision's rows, while the earlier revision's rows stay readable as history — clearing
     * them would destroy the results the design says are retained.
     */
    @Query("DELETE FROM family_progression_state")
    suspend fun clearFamilyStates()
}
