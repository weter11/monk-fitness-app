package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.ProgramStatisticsSnapshot
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.ShoppingItemEntity
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: BodyWeightEntry)

    @Query(
        """
        SELECT * FROM body_weight_log
        WHERE date >= :cutoff
        ORDER BY date ASC
        """
    )
    fun getEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>>

    @Query(
        """
        SELECT * FROM body_weight_log
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun getLatestEntry(): BodyWeightEntry?

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

    @Query("SELECT * FROM program_day_state ORDER BY programDay ASC")
    fun getProgramDayStates(): Flow<List<ProgramDayState>>

    @Query("SELECT * FROM program_day_state ORDER BY programDay ASC")
    suspend fun getProgramDayStatesSnapshot(): List<ProgramDayState>

    @Query("SELECT * FROM program_day_state WHERE programDay = :day LIMIT 1")
    suspend fun getProgramDayState(day: Int): ProgramDayState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgramDayStates(states: List<ProgramDayState>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgramDayState(state: ProgramDayState)

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM program_day_state WHERE isWorkoutDay = 1 AND isCompleted = 1) AS totalWorkoutsCompleted,
            (SELECT COUNT(*) FROM program_day_state WHERE isWorkoutDay = 1 AND isMissed = 1) AS totalMissed,
            (SELECT COUNT(*) FROM set_log) AS totalSets,
            (SELECT COALESCE(SUM(repsCompleted), 0) FROM set_log) AS totalReps,
            (SELECT COALESCE(SUM(durationSeconds), 0) FROM set_log) AS totalTimerSeconds,
            (SELECT COUNT(DISTINCT sessionDate || ':' || exerciseId) FROM set_log) AS totalExercisesCompleted,
            (SELECT COUNT(*) FROM program_day_state WHERE isWorkoutDay = 1) AS totalWorkoutDays
        """
    )
    fun getProgramStatistics(): Flow<ProgramStatisticsSnapshot>

    @Query("SELECT * FROM meal_cycles ORDER BY startDate ASC, id ASC")
    fun getMealCycles(): Flow<List<MealCycle>>

    @Query("SELECT * FROM meal_cycles ORDER BY startDate ASC, id ASC")
    suspend fun getMealCyclesSnapshot(): List<MealCycle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealCycle(cycle: MealCycle): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeals(meals: List<MealEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingItems(items: List<ShoppingItemEntity>)

    @Query("DELETE FROM meals WHERE cycleId = :cycleId")
    suspend fun deleteMealsForCycle(cycleId: Long)

    @Query("DELETE FROM shopping_items WHERE cycleId = :cycleId")
    suspend fun deleteShoppingItemsForCycle(cycleId: Long)

    @Query("SELECT * FROM meals WHERE cycleId = :cycleId ORDER BY dayNumber ASC, mealTypeKey ASC")
    fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>>

    @Query("SELECT * FROM shopping_items WHERE cycleId = :cycleId ORDER BY ingredientKey ASC")
    fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM meals WHERE cycleId = :cycleId ORDER BY dayNumber ASC, mealTypeKey ASC")
    suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity>

    @Query("SELECT * FROM shopping_items WHERE cycleId = :cycleId ORDER BY ingredientKey ASC")
    suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity>

    @Query("SELECT * FROM meals WHERE cycleId = :cycleId AND programDay = :programDay AND mealTypeKey = :mealTypeKey LIMIT 1")
    suspend fun getMealForCycleAndType(cycleId: Long, programDay: Int, mealTypeKey: String): MealEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeal(meal: MealEntity)

    @Transaction
    suspend fun replaceCycleMeals(cycleId: Long, meals: List<MealEntity>, shoppingItems: List<ShoppingItemEntity>) {
        deleteMealsForCycle(cycleId)
        deleteShoppingItemsForCycle(cycleId)
        if (meals.isNotEmpty()) {
            insertMeals(meals)
        }
        if (shoppingItems.isNotEmpty()) {
            insertShoppingItems(shoppingItems)
        }
    }
}
