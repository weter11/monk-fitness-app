package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.AppStateEntity

/**
 * Persistence for the single-row `app_state` table (§21, §23).
 *
 * The table has one row, keyed by [AppStateEntity.SINGLE_ROW_ID], so this DAO has one read and one
 * write and no list operation at all: "which row is the state" is not a question the table can ask.
 *
 * It persists application state and decides none of it. There is deliberately no "select the Standard
 * Program when nothing is selected" query, no fallback, no auto-start evaluation and no delete: the
 * selection is a user decision the lifecycle layer makes (§3), and this layer only remembers it.
 *
 * [upsertState] replaces the whole row, so a caller cannot half-update global state: the row that
 * lands is the row it named, with `null` meaning "nothing selected / nothing planned next" rather
 * than "leave the old value".
 */
@Dao
interface AppStateDao {

    /** The application state row, or `null` when the app has never written one. */
    @Query("SELECT * FROM `app_state` WHERE `id` = :id LIMIT 1")
    suspend fun state(id: Int): AppStateEntity?

    /** Stores the application state, replacing the single row if it already exists. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: AppStateEntity)
}
