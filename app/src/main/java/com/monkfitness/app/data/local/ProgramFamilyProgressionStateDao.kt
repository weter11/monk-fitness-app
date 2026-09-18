package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.FamilyProgressionStateEntity

/**
 * Persistence for the `program_family_progression_state` table — the Program System's own family
 * progression state, scoped by `(revisionId, familyId)` (§23, §30 step 3).
 *
 * It is **not** the Stage-1 `family_progression_state` DAO: that table is keyed by the legacy revision
 * integer, carries the pilot's hysteresis counters and policy version, and is read and written by
 * shipped code. No statement here names it. The two generations coexist until §30 step 15, and nothing
 * in this file reaches across the boundary.
 *
 * The write is an upsert because the table holds one *current* state per family per revision, which is
 * what its primary key states: a family cannot accumulate competing "current" rows. Whether a state
 * *should* transition is the adaptive engine's and the policy's decision (§11) — this DAO records the
 * state it is handed and updates nothing else. There is deliberately no delete: a revision's states
 * disappear with the revision through the ownership cascade (§29).
 */
@Dao
interface ProgramFamilyProgressionStateDao {

    /** Writes one family's current state for one revision, replacing that pair's row if it exists. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: FamilyProgressionStateEntity)

    /** Every family state of one revision, in ascending family id order. */
    @Query("SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId ORDER BY `familyId` ASC")
    suspend fun statesOfRevision(revisionId: String): List<FamilyProgressionStateEntity>

    /** One family's state in one revision, or `null` when it has never been stored. */
    @Query("SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId AND `familyId` = :familyId LIMIT 1")
    suspend fun stateOf(revisionId: String, familyId: String): FamilyProgressionStateEntity?
}
