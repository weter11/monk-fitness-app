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
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.screens.WorkoutStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WorkoutRepository
    private val workoutGenerator = WorkoutGenerator()

    // Workout Session State
    private val _currentWorkoutDay = MutableStateFlow<Int?>(null)
    val currentWorkoutDay = _currentWorkoutDay.asStateFlow()

    private val _currentStep = MutableStateFlow(WorkoutStep.OVERVIEW)
    val currentStep = _currentStep.asStateFlow()

    private val _exerciseIndex = MutableStateFlow(0)
    val exerciseIndex = _exerciseIndex.asStateFlow()

    private val _isRestTime = MutableStateFlow(false)
    val isRestTime = _isRestTime.asStateFlow()

    private val _completedExercises = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val completedExercises = _completedExercises.asStateFlow()

    // Timer State
    private val _timeLeft = MutableStateFlow(0)
    val timeLeft = _timeLeft.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning = _isTimerRunning.asStateFlow()

    private var timerJob: Job? = null
    private var endTimeMillis: Long = 0

    val settingsManager: SettingsManager

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

    fun getExerciseLibrary() = workoutGenerator.getExerciseLibrary()

    fun getPostureExercises() = workoutGenerator.getPostureExercises()

    fun getWarmupExercises() = workoutGenerator.getWarmupExercises()

    fun startWorkoutSession(day: Int) {
        if (_currentWorkoutDay.value != day) {
            _currentWorkoutDay.value = day
            _currentStep.value = WorkoutStep.OVERVIEW
            _exerciseIndex.value = 0
            _completedExercises.value = emptyMap()
            stopTimer()
        }
    }

    fun setWorkoutStep(step: WorkoutStep) {
        _currentStep.value = step
        _exerciseIndex.value = 0
        stopTimer()
    }

    fun nextExercise(currentExerciseList: List<Exercise>) {
        if (_isRestTime.value) {
            _isRestTime.value = false
            _exerciseIndex.value++
            stopTimer()
            return
        }

        if (_exerciseIndex.value < currentExerciseList.size - 1) {
            _isRestTime.value = true
            startTimer(5)
        } else {
            val nextStep = when (_currentStep.value) {
                WorkoutStep.WARMUP -> WorkoutStep.MAIN
                WorkoutStep.MAIN -> WorkoutStep.POSTURE
                WorkoutStep.POSTURE -> WorkoutStep.COMPLETE
                else -> WorkoutStep.COMPLETE
            }
            setWorkoutStep(nextStep)
        }
    }

    fun previousExercise() {
        _isRestTime.value = false
        if (_exerciseIndex.value > 0) {
            _exerciseIndex.value--
            stopTimer()
        } else {
            // Optionally handle going back to previous step, but for now just stay at index 0
        }
    }

    fun startTimer(durationSeconds: Int) {
        if (_isTimerRunning.value) return

        endTimeMillis = System.currentTimeMillis() + (durationSeconds * 1000L)
        _timeLeft.value = durationSeconds
        _isTimerRunning.value = true

        runTimer()
    }

    private fun runTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var lastTickSecond = -1
            while (_isTimerRunning.value) {
                val remaining = ((endTimeMillis - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)

                if (remaining != lastTickSecond) {
                    lastTickSecond = remaining
                    if (remaining in 1..3 && timerTicksEnabled.value) {
                        playBeep(100)
                    }
                }

                _timeLeft.value = remaining
                if (remaining <= 0) {
                    _isTimerRunning.value = false
                    if (!_isRestTime.value) {
                        playBeep(500)
                    } else {
                        playStartSound()
                    }
                    if (vibrationEnabled.value) {
                        vibrate()
                    }
                    // Auto-advance logic will be handled by UI observing timeLeft and isTimerRunning
                    break
                }
                delay(100L)
            }
        }
    }

    fun toggleTimer(durationSeconds: Int) {
        if (_isTimerRunning.value) {
            stopTimer()
        } else {
            val remaining = if (_timeLeft.value > 0) _timeLeft.value else durationSeconds
            startTimer(remaining)
        }
    }

    fun stopTimer() {
        _isTimerRunning.value = false
        timerJob?.cancel()
    }

    fun resetTimer(durationSeconds: Int) {
        stopTimer()
        _timeLeft.value = durationSeconds
    }

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

    val timerTicksEnabled = settingsManager.timerTicksEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setTimerTicksEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setTimerTicksEnabled(enabled)
        }
    }

    val vibrationEnabled = settingsManager.vibrationEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setVibrationEnabled(enabled)
        }
    }

    private val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private fun playBeep(duration: Int = 200) {
        toneG.startTone(ToneGenerator.TONE_PROP_BEEP, duration)
    }

    private fun playStartSound() {
        toneG.startTone(ToneGenerator.TONE_DTMF_0, 400)
    }

    override fun onCleared() {
        super.onCleared()
        toneG.release()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }
}
