package com.monkfitness.app.data.local

import androidx.room.*
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.UserProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT * FROM user_progress ORDER BY day ASC")
    fun getAllProgress(): Flow<List<UserProgress>>

    @Query("SELECT COUNT(*) FROM user_progress WHERE isCompleted = 1")
    fun getCompletedDaysCount(): Flow<Int>

    @Query("SELECT * FROM user_progress WHERE isCompleted = 1")
    suspend fun getCompletedDays(): List<UserProgress>

    @Query("SELECT * FROM user_progress WHERE day = :day LIMIT 1")
    suspend fun getProgressByDay(day: Int): UserProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateProgress(progress: UserProgress)

    @Query("SELECT * FROM posture_session_progress ORDER BY day ASC")
    fun getAllPostureProgress(): Flow<List<PostureSessionProgress>>

    @Query("SELECT COUNT(*) FROM posture_session_progress WHERE isCompleted = 1")
    fun getCompletedPostureDaysCount(): Flow<Int>

    @Query("SELECT * FROM posture_session_progress WHERE day = :day LIMIT 1")
    suspend fun getPostureProgressByDay(day: Int): PostureSessionProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updatePostureProgress(progress: PostureSessionProgress)
}
