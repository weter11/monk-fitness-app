package com.monkfitness.app.data.local

import androidx.room.*
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.VolumeHistoryPoint
import com.monkfitness.app.data.model.WorkoutFrequencyPoint
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetLog(setLog: SetLog)

    @Query(
        """
        DELETE FROM set_log
        WHERE id = (
            SELECT id FROM set_log
            WHERE exerciseId = :exerciseId AND sessionDate = :sessionDate
            ORDER BY timestamp DESC
            LIMIT 1
        )
        """
    )
    suspend fun deleteLatestSetLogForExerciseOnDate(exerciseId: String, sessionDate: String)

    @Query(
        """
        SELECT sessionDate, COALESCE(SUM(repsCompleted), 0) AS totalReps
        FROM set_log
        GROUP BY sessionDate
        ORDER BY sessionDate ASC
        """
    )
    fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>>

    @Query(
        """
        SELECT sessionDate, COALESCE(SUM(repsCompleted), 0) AS totalReps
        FROM set_log
        WHERE exerciseId = :exerciseId
        GROUP BY sessionDate
        ORDER BY sessionDate ASC
        """
    )
    fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>>

    @Query(
        """
        SELECT strftime('%Y-W%W', sessionDate) AS weekLabel,
               COUNT(DISTINCT sessionDate) AS sessionCount
        FROM set_log
        GROUP BY strftime('%Y-W%W', sessionDate)
        ORDER BY MIN(sessionDate) ASC
        """
    )
    fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>>

    @Query("SELECT * FROM posture_session_progress ORDER BY day ASC")
    fun getAllPostureProgress(): Flow<List<PostureSessionProgress>>

    @Query("SELECT COUNT(*) FROM posture_session_progress WHERE isCompleted = 1")
    fun getCompletedPostureDaysCount(): Flow<Int>

    @Query("SELECT * FROM posture_session_progress WHERE day = :day LIMIT 1")
    suspend fun getPostureProgressByDay(day: Int): PostureSessionProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updatePostureProgress(progress: PostureSessionProgress)
}
