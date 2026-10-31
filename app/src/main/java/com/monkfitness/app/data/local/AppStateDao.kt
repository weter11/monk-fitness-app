package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.AppStateEntity

/**
 * Persistence for the single-row `app_state` table.
 *
 * One row, keyed by [AppStateEntity.SINGLE_ROW_ID], so one read and one write and no list operation:
 * "which row is the state" is not a question the table can ask. It persists application state and
 * decides none of it — no fallback selection, no auto-start evaluation, no delete: the selection is a
 * user decision the lifecycle layer makes, and this layer only remembers it.
 *
 * [upsertState] replaces the whole row, so a caller cannot half-update global state: `null` means
 * "nothing selected / nothing planned next" rather than "leave the old value".
 */
@Dao
interface AppStateDao {

    /** The state row, or `null` when the app has never written one. */
    @Query("SELECT * FROM `app_state` WHERE `id` = :id LIMIT 1")
    suspend fun state(id: Int): AppStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: AppStateEntity)
}
