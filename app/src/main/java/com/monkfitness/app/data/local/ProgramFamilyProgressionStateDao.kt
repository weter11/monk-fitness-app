package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.FamilyProgressionStateEntity

/**
 * Persistence for the `program_family_progression_state` table — the Program System's own family
 * progression state, scoped by `(revisionId, familyId)`.
 *
 * The write is an upsert because the table holds one *current* state per family per revision, which is
 * what its primary key states: a family cannot accumulate competing "current" rows. Whether a state
 * *should* transition is the adaptive engine's and the policy's decision — this DAO records the state
 * it is handed and updates nothing else. There is deliberately no delete: a revision's states
 * disappear with the revision through the ownership cascade.
 */
@Dao
interface ProgramFamilyProgressionStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: FamilyProgressionStateEntity)

    @Query("SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId ORDER BY `familyId` ASC")
    suspend fun statesOfRevision(revisionId: String): List<FamilyProgressionStateEntity>

    /** One family's state in one revision, or `null` when it has never been stored. */
    @Query("SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId AND `familyId` = :familyId LIMIT 1")
    suspend fun stateOf(revisionId: String, familyId: String): FamilyProgressionStateEntity?
}
