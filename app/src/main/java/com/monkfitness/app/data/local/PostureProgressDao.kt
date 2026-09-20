package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.monkfitness.app.data.model.PostureSessionProgress
import kotlinx.coroutines.flow.Flow

/**
 * The optional posture / mobility track's own storage (§4: a retained global feature).
 *
 * It was the posture half of the retired `ProgressDao`, which also held the shipped 56-day program's
 * tables. §30 step 15 retired those and the DAO with them; this one holds exactly the track's rows, so
 * there is no longer a DAO through which Program state and an unrelated daily practice share a
 * dependency (§4's "split it first").
 *
 * Nothing here reads or writes any Program table, and no Program path reads this table: the track is
 * independent of Program ownership in both directions.
 */
@Dao
interface PostureProgressDao {

    @Query("SELECT * FROM posture_session_progress WHERE trackCycle = :trackCycle ORDER BY trackDay ASC")
    fun progressOfCycle(trackCycle: Int): Flow<List<PostureSessionProgress>>

    @Query("SELECT * FROM posture_session_progress ORDER BY trackCycle ASC, trackDay ASC")
    fun allProgress(): Flow<List<PostureSessionProgress>>

    @Query("SELECT * FROM posture_session_progress WHERE trackCycle = :trackCycle AND trackDay = :trackDay LIMIT 1")
    suspend fun progressOn(trackCycle: Int, trackDay: Int): PostureSessionProgress?

    @Query("SELECT * FROM posture_session_progress WHERE trackCycle = :trackCycle ORDER BY trackDay ASC")
    suspend fun progressOfCycleSnapshot(trackCycle: Int): List<PostureSessionProgress>

    @Query("SELECT COUNT(*) FROM posture_session_progress WHERE trackCycle = :trackCycle AND isCompleted = 1")
    fun completedCountOfCycle(trackCycle: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM posture_session_progress WHERE isCompleted = 1")
    fun completedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PostureSessionProgress)

    @Query("DELETE FROM posture_session_progress")
    suspend fun clearAll()
}
