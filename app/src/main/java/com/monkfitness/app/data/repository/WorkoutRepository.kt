package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.UserProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf

class WorkoutRepository(private val progressDao: ProgressDao) {

    fun getAllProgress(): Flow<List<UserProgress>> = progressDao.getAllProgress()
        .catch { emit(emptyList()) }

    fun getCompletedDaysCount(): Flow<Int> = progressDao.getCompletedDaysCount()
        .catch { emit(0) }

    fun getAllPostureProgress(): Flow<List<PostureSessionProgress>> = progressDao.getAllPostureProgress()
        .catch { emit(emptyList()) }

    fun getCompletedPostureDaysCount(): Flow<Int> = progressDao.getCompletedPostureDaysCount()
        .catch { emit(0) }

    suspend fun getProgressByDay(day: Int): UserProgress? = try {
        progressDao.getProgressByDay(day)
    } catch (e: Exception) {
        null
    }

    suspend fun updateProgress(progress: UserProgress) = try {
        progressDao.updateProgress(progress)
    } catch (e: Exception) {
        // Log error
    }

    suspend fun getPostureProgressByDay(day: Int): PostureSessionProgress? = try {
        progressDao.getPostureProgressByDay(day)
    } catch (e: Exception) {
        null
    }

    suspend fun updatePostureProgress(progress: PostureSessionProgress) = try {
        progressDao.updatePostureProgress(progress)
    } catch (e: Exception) {
        // Log error
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
    } catch (e: Exception) {
        0
    }
}
