package com.monkfitness.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.repository.WorkoutRepository
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.util.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WorkoutRepository
    private val workoutGenerator = WorkoutGenerator()
    private val settingsManager: SettingsManager

    init {
        val db = AppDatabase.getDatabase(application)
        repository = WorkoutRepository(db.progressDao())
        settingsManager = SettingsManager(application)
    }

    val allProgress = repository.getAllProgress().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val completedDaysCount = repository.getCompletedDaysCount().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak

    init {
        viewModelScope.launch {
            allProgress.collect {
                updateStreak()
            }
        }
    }

    private fun updateStreak() {
        viewModelScope.launch {
            _streak.value = repository.calculateStreak()
        }
    }

    fun getWorkoutForDay(day: Int): Workout {
        return workoutGenerator.generateWorkout(day)
    }

    fun getPostureExercises() = workoutGenerator.getPostureExercises()

    fun getWarmupExercises() = workoutGenerator.getWarmupExercises()

    fun completeWorkout(day: Int) {
        viewModelScope.launch {
            val progress = UserProgress(
                day = day,
                isCompleted = true,
                completionDate = System.currentTimeMillis(),
                workoutType = workoutGenerator.generateWorkout(day).type.name
            )
            repository.updateProgress(progress)
        }
    }

    fun getCurrentDay(): Int {
        val progress = allProgress.value
        val completedDays = progress.filter { it.isCompleted }.map { it.day }.toSet()
        for (i in 1..56) {
            if (i !in completedDays) return i
        }
        return 56
    }

    fun setNotificationTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsManager.setNotificationTime(hour, minute)
            NotificationScheduler.scheduleDailyReminder(getApplication(), hour, minute)
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            settingsManager.setLanguage(language)
        }
    }

    val isOnboardingCompleted = settingsManager.isOnboardingCompletedFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setOnboardingCompleted() {
        viewModelScope.launch {
            settingsManager.setOnboardingCompleted()
        }
    }
}
