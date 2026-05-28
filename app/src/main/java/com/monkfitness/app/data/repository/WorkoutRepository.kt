package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressDao
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
import kotlinx.coroutines.flow.catch

class WorkoutRepository(private val progressDao: ProgressDao) {

    fun getAllProgress(): Flow<List<UserProgress>> = progressDao.getAllProgress()
        .catch { emit(emptyList()) }

    fun getCompletedDaysCount(): Flow<Int> = progressDao.getCompletedDaysCount()
        .catch { emit(0) }

    fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>> = progressDao.getDailyVolumeHistory()
        .catch { emit(emptyList()) }

    fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> = progressDao.getExerciseVolumeHistory(exerciseId)
        .catch { emit(emptyList()) }

    fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>> = progressDao.getWorkoutFrequencyByWeek()
        .catch { emit(emptyList()) }

    fun getBodyWeightEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> = progressDao.getEntriesSince(cutoff)
        .catch { emit(emptyList()) }

    fun getAllPostureProgress(): Flow<List<PostureSessionProgress>> = progressDao.getAllPostureProgress()
        .catch { emit(emptyList()) }

    fun getCompletedPostureDaysCount(): Flow<Int> = progressDao.getCompletedPostureDaysCount()
        .catch { emit(0) }

    fun getProgramDayStates(): Flow<List<ProgramDayState>> = progressDao.getProgramDayStates()
        .catch { emit(emptyList()) }

    fun getProgramStatistics(): Flow<ProgramStatisticsSnapshot> = progressDao.getProgramStatistics()
        .catch {
            emit(
                ProgramStatisticsSnapshot(
                    totalWorkoutsCompleted = 0,
                    totalMissed = 0,
                    totalSets = 0,
                    totalReps = 0,
                    totalTimerSeconds = 0,
                    totalExercisesCompleted = 0,
                    totalWorkoutDays = 0
                )
            )
        }

    fun getMealCycles(): Flow<List<MealCycle>> = progressDao.getMealCycles()
        .catch { emit(emptyList()) }

    fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>> = progressDao.getMealsForCycle(cycleId)
        .catch { emit(emptyList()) }

    fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>> = progressDao.getShoppingItemsForCycle(cycleId)
        .catch { emit(emptyList()) }

    suspend fun getProgressByDay(day: Int): UserProgress? = try {
        progressDao.getProgressByDay(day)
    } catch (e: Exception) {
        null
    }

    suspend fun updateProgress(progress: UserProgress) = try {
        progressDao.updateProgress(progress)
    } catch (_: Exception) {
    }

    suspend fun insertSetLog(setLog: SetLog) = try {
        progressDao.insertSetLog(setLog)
    } catch (_: Exception) {
    }

    suspend fun insertBodyWeightEntry(entry: BodyWeightEntry) = try {
        progressDao.insertEntry(entry)
    } catch (_: Exception) {
    }

    suspend fun deleteLatestSetLogForExerciseOnDate(exerciseId: String, sessionDate: String) = try {
        progressDao.deleteLatestSetLogForExerciseOnDate(exerciseId, sessionDate)
    } catch (_: Exception) {
    }

    suspend fun getPostureProgressByDay(day: Int): PostureSessionProgress? = try {
        progressDao.getPostureProgressByDay(day)
    } catch (e: Exception) {
        null
    }

    suspend fun getLatestBodyWeightEntry(): BodyWeightEntry? = try {
        progressDao.getLatestEntry()
    } catch (e: Exception) {
        null
    }

    suspend fun updatePostureProgress(progress: PostureSessionProgress) = try {
        progressDao.updatePostureProgress(progress)
    } catch (_: Exception) {
    }

    suspend fun getProgramDayStatesSnapshot(): List<ProgramDayState> = try {
        progressDao.getProgramDayStatesSnapshot()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getProgramDayState(day: Int): ProgramDayState? = try {
        progressDao.getProgramDayState(day)
    } catch (_: Exception) {
        null
    }

    suspend fun upsertProgramDayStates(states: List<ProgramDayState>) = try {
        progressDao.upsertProgramDayStates(states)
    } catch (_: Exception) {
    }

    suspend fun upsertProgramDayState(state: ProgramDayState) = try {
        progressDao.upsertProgramDayState(state)
    } catch (_: Exception) {
    }

    suspend fun getMealCyclesSnapshot(): List<MealCycle> = try {
        progressDao.getMealCyclesSnapshot()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun insertMealCycle(cycle: MealCycle): Long = try {
        progressDao.insertMealCycle(cycle)
    } catch (_: Exception) {
        0L
    }

    suspend fun replaceCycleMeals(cycleId: Long, meals: List<MealEntity>, shoppingItems: List<ShoppingItemEntity>) = try {
        progressDao.replaceCycleMeals(cycleId, meals, shoppingItems)
    } catch (_: Exception) {
    }

    suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity> = try {
        progressDao.getMealsForCycleSnapshot(cycleId)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity> = try {
        progressDao.getShoppingItemsForCycleSnapshot(cycleId)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getMealForCycleAndType(cycleId: Long, programDay: Int, mealTypeKey: String): MealEntity? = try {
        progressDao.getMealForCycleAndType(cycleId, programDay, mealTypeKey)
    } catch (_: Exception) {
        null
    }

    suspend fun upsertMeal(meal: MealEntity) = try {
        progressDao.upsertMeal(meal)
    } catch (_: Exception) {
    }

    suspend fun calculateStreak(): Int = try {
        val completedDays = progressDao.getCompletedDays()
        if (completedDays.isEmpty()) 0
        else {
            val dayNumbers = completedDays.map { it.day }.distinct().sortedDescending()
            if (dayNumbers.isEmpty()) 0
            else {
                var streak = 1
                for (i in 0 until dayNumbers.size - 1) {
                    if (dayNumbers[i] - 1 == dayNumbers[i + 1]) {
                        streak++
                    } else {
                        break
                    }
                }
                streak
            }
        }
    } catch (_: Exception) {
        0
    }
}
