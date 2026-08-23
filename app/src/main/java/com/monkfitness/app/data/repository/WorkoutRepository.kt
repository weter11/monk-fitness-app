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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRepository(private val progressDao: ProgressDao) {

    fun getAllProgress(cycleNumber: Flow<Int>): Flow<List<UserProgress>> = cycleNumber.flatMapLatest { progressDao.getAllProgress(it) }
        .catch { emit(emptyList()) }

    fun getCompletedDaysCount(cycleNumber: Flow<Int>): Flow<Int> = cycleNumber.flatMapLatest { progressDao.getCompletedDaysCount(it) }
        .catch { emit(0) }

    fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>> = progressDao.getDailyVolumeHistory()
        .catch { emit(emptyList()) }

    fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> = progressDao.getExerciseVolumeHistory(exerciseId)
        .catch { emit(emptyList()) }

    fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>> = progressDao.getWorkoutFrequencyByWeek()
        .catch { emit(emptyList()) }

    fun getBodyWeightEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> = progressDao.getEntriesSince(cutoff)
        .catch { emit(emptyList()) }

    fun getAllPostureProgress(cycleNumber: Flow<Int>): Flow<List<PostureSessionProgress>> = cycleNumber.flatMapLatest { progressDao.getAllPostureProgress(it) }
        .catch { emit(emptyList()) }

    fun getCompletedPostureDaysCount(cycleNumber: Flow<Int>): Flow<Int> = cycleNumber.flatMapLatest { progressDao.getCompletedPostureDaysCount(it) }
        .catch { emit(0) }

    fun getProgramDayStates(cycleNumber: Flow<Int>): Flow<List<ProgramDayState>> = cycleNumber.flatMapLatest { progressDao.getProgramDayStates(it) }
        .catch { emit(emptyList()) }

    fun getProgramStatistics(cycleNumber: Flow<Int>): Flow<ProgramStatisticsSnapshot> = cycleNumber.flatMapLatest { progressDao.getProgramStatistics(it) }
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

    suspend fun getProgressByDay(cycleNumber: Int, day: Int): UserProgress? = try {
        progressDao.getProgressByDay(cycleNumber, day)
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

    suspend fun getPostureProgressByDay(cycleNumber: Int, day: Int): PostureSessionProgress? = try {
        progressDao.getPostureProgressByDay(cycleNumber, day)
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

    suspend fun getProgramDayStatesSnapshot(cycleNumber: Int): List<ProgramDayState> = try {
        progressDao.getProgramDayStatesSnapshot(cycleNumber)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getProgramDayState(cycleNumber: Int, day: Int): ProgramDayState? = try {
        progressDao.getProgramDayState(cycleNumber, day)
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

    /** Highest cycle number present in program_day_state (migration backfill marker). */
    suspend fun getMaxProgramDayStateCycle(): Int? = try {
        progressDao.getMaxProgramDayStateCycle()
    } catch (_: Exception) {
        null
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

    /**
     * Streak is calculated against absolute calendar dates (per C2 decision), not program
     * day numbers: completing Day 56 and next-cycle Day 1 on consecutive calendar days keeps
     * the streak unbroken. Today counts; if nothing is logged today, the streak may continue
     * from yesterday so it doesn't vanish mid-day.
     */
    suspend fun calculateStreak(): Int = try {
        val zone = ZoneId.systemDefault()
        val completedDates = progressDao.getCompletedDays()
            .mapTo(mutableSetOf()) { Instant.ofEpochMilli(it.completionDate).atZone(zone).toLocalDate() }
        if (completedDates.isEmpty()) {
            0
        } else {
            var streak = 0
            var cursor = LocalDate.now(zone)
            if (cursor !in completedDates) {
                cursor = cursor.minusDays(1)
            }
            while (cursor in completedDates) {
                streak++
                cursor = cursor.minusDays(1)
            }
            streak
        }
    } catch (_: Exception) {
        0
    }
}
