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
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.util.NotificationScheduler
import com.monkfitness.app.util.withLocalizedSearchText
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.applyDifficultyAdjustment
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import com.monkfitness.app.ui.screens.WorkoutStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class SessionMode {
        DAILY,
        POSTURE_MOBILITY
    }

    private val repository: WorkoutRepository
    private val workoutGenerator = WorkoutGenerator()

    // Workout Session State
    private val _currentWorkoutDay = MutableStateFlow<Int?>(null)
    val currentWorkoutDay = _currentWorkoutDay.asStateFlow()

    private val _currentSessionMode = MutableStateFlow(SessionMode.DAILY)
    val currentSessionMode = _currentSessionMode.asStateFlow()

    private val _currentStep = MutableStateFlow(WorkoutStep.OVERVIEW)
    val currentStep = _currentStep.asStateFlow()

    private val _exerciseIndex = MutableStateFlow(0)
    val exerciseIndex = _exerciseIndex.asStateFlow()

    private val _isRestTime = MutableStateFlow(false)
    val isRestTime = _isRestTime.asStateFlow()

    private val _restTargetIndex = MutableStateFlow<Int?>(null)
    val restTargetIndex = _restTargetIndex.asStateFlow()

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

    val exerciseDifficultyAdjustments = settingsManager.exerciseDifficultyAdjustmentsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    val additionalPostureTrainingEnabled = settingsManager.additionalPostureTrainingEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val flexibilityTrainingType = settingsManager.flexibilityTrainingTypeFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), FlexibilityTrainingType.BOTH
    )

    val flexibilityFocusAreas = settingsManager.flexibilityFocusAreasFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(ExerciseSubCategory.FULL_BODY)
    )

    val completedDaysCount = repository.getCompletedDaysCount().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val postureProgress = repository.getAllPostureProgress().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val completedPostureDaysCount = repository.getCompletedPostureDaysCount().stateIn(
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

    fun getWorkoutForDay(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value
    ): Workout {
        val workout = workoutGenerator.generateWorkout(day, trainingType, focusAreas)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getPostureMobilityWorkout(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value
    ): Workout {
        val workout = workoutGenerator.generatePostureMobilityWorkout(day, trainingType, focusAreas)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getExerciseLibrary(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value
    ) = workoutGenerator.getExerciseLibrary().map {
        enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments))
    }

    fun getPostureExercises(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value
    ) = workoutGenerator.getPostureExercises().map {
        enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments))
    }

    fun getWarmupExercises(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value
    ) = workoutGenerator.getWarmupExercises().map {
        enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments))
    }

    fun getExerciseDifficultyAdjustment(exerciseId: String): Flow<Int> {
        return settingsManager.getExerciseDifficultyAdjustmentFlow(exerciseId)
    }

    fun adjustExerciseDifficulty(exerciseId: String, delta: Int) {
        viewModelScope.launch {
            val current = exerciseDifficultyAdjustments.value[exerciseId] ?: 0
            settingsManager.setExerciseDifficultyAdjustment(exerciseId, current + delta)
        }
    }

    fun startWorkoutSession(day: Int, mode: SessionMode = SessionMode.DAILY) {
        if (_currentWorkoutDay.value != day || _currentSessionMode.value != mode) {
            _currentWorkoutDay.value = day
            _currentSessionMode.value = mode
            _currentStep.value = WorkoutStep.OVERVIEW
            _exerciseIndex.value = 0
            _isRestTime.value = false
            _restTargetIndex.value = null
            _completedExercises.value = emptyMap()
            stopTimer()
        }
    }

    fun setWorkoutStep(step: WorkoutStep) {
        _currentStep.value = step
        _exerciseIndex.value = 0
        _isRestTime.value = false
        _restTargetIndex.value = null
        stopTimer()

        if (shouldStartRestFor(getExercisesForStep(step).firstOrNull())) {
            startRestBefore(0)
        }
    }

    fun nextExercise(currentExerciseList: List<Exercise>) {
        if (_isRestTime.value) {
            _isRestTime.value = false
            _exerciseIndex.value = _restTargetIndex.value ?: _exerciseIndex.value
            _restTargetIndex.value = null
            stopTimer()
            _timeLeft.value = 0
            return
        }

        currentExerciseList.getOrNull(_exerciseIndex.value)?.let { exercise ->
            _completedExercises.value = _completedExercises.value + (exercise.id to true)
        }

        if (_exerciseIndex.value < currentExerciseList.size - 1) {
            val nextIndex = _exerciseIndex.value + 1
            if (shouldStartRestFor(currentExerciseList.getOrNull(nextIndex))) {
                startRestBefore(nextIndex)
            } else {
                _exerciseIndex.value = nextIndex
                _restTargetIndex.value = null
                stopTimer()
            }
        } else {
            val nextStep = when (_currentStep.value) {
                WorkoutStep.WARMUP -> WorkoutStep.MAIN
                WorkoutStep.MAIN -> WorkoutStep.COMPLETE
                else -> WorkoutStep.COMPLETE
            }
            setWorkoutStep(nextStep)
        }
    }

    fun previousExercise() {
        _isRestTime.value = false
        _restTargetIndex.value = null
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

    fun completePostureWorkout(day: Int) {
        viewModelScope.launch {
            val progress = PostureSessionProgress(
                day = day,
                isCompleted = true,
                completionDate = System.currentTimeMillis(),
                focusArea = flexibilityFocusAreas.value.joinToString(",") { it.name }
            )
            repository.updatePostureProgress(progress)
        }
    }

    fun completeCurrentSession(day: Int) {
        if (_currentSessionMode.value == SessionMode.POSTURE_MOBILITY) {
            completePostureWorkout(day)
        } else {
            completeWorkout(day)
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

    fun setAdditionalPostureTrainingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setAdditionalPostureTrainingEnabled(enabled)
        }
    }

    fun setFlexibilityTrainingType(trainingType: FlexibilityTrainingType) {
        viewModelScope.launch {
            settingsManager.setFlexibilityTrainingType(trainingType)
        }
    }

    val nutritionWeight = settingsManager.nutritionWeightFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    fun setNutritionWeight(weight: String) {
        viewModelScope.launch {
            settingsManager.setNutritionWeight(weight)
        }
    }

    val nutritionHeight = settingsManager.nutritionHeightFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    fun setNutritionHeight(height: String) {
        viewModelScope.launch {
            settingsManager.setNutritionHeight(height)
        }
    }

    val completedNutritionMeals = settingsManager.completedNutritionMealsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    private val _nutritionPlanSeed = MutableStateFlow(0)
    val nutritionPlanSeed: StateFlow<Int> = _nutritionPlanSeed.asStateFlow()

    fun regenerateNutritionPlan() {
        _nutritionPlanSeed.value += 1
    }

    fun setNutritionMealCompleted(mealType: NutritionMealType, completed: Boolean) {
        viewModelScope.launch {
            settingsManager.setNutritionMealCompleted(mealType.key, completed)
        }
    }

    fun toggleFlexibilityFocusArea(focusArea: ExerciseSubCategory) {
        if (focusArea !in flexibilityFocusAreaOptions) return

        val current = flexibilityFocusAreas.value
        val nextSelection = when {
            focusArea == ExerciseSubCategory.FULL_BODY -> setOf(ExerciseSubCategory.FULL_BODY)
            ExerciseSubCategory.FULL_BODY in current -> setOf(focusArea)
            focusArea in current -> (current - focusArea).ifEmpty { setOf(ExerciseSubCategory.FULL_BODY) }
            else -> (current - ExerciseSubCategory.FULL_BODY) + focusArea
        }.filter { it == ExerciseSubCategory.FULL_BODY || it in flexibilitySpecificFocusAreas }
            .toSet()

        viewModelScope.launch {
            settingsManager.setFlexibilityFocusAreas(nextSelection)
        }
    }

    fun getDifficultyLevelLabel(adjustment: Int): Int {
        return when (adjustment.coerceIn(-2, 2)) {
            -2 -> com.monkfitness.app.R.string.difficulty_very_easy
            -1 -> com.monkfitness.app.R.string.difficulty_easy
            1 -> com.monkfitness.app.R.string.difficulty_hard
            2 -> com.monkfitness.app.R.string.difficulty_very_hard
            else -> com.monkfitness.app.R.string.difficulty_normal
        }
    }

    private val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private fun playBeep(duration: Int = 200) {
        toneG.startTone(ToneGenerator.TONE_PROP_BEEP, duration)
    }

    private fun playStartSound() {
        toneG.startTone(ToneGenerator.TONE_DTMF_0, 400)
    }

    private fun shouldStartRestFor(exercise: Exercise?): Boolean {
        return exercise?.isTimerBased == true
    }

    private fun startRestBefore(targetIndex: Int) {
        _restTargetIndex.value = targetIndex
        _isRestTime.value = true
        startTimer(5)
    }

    private fun getExercisesForStep(step: WorkoutStep): List<Exercise> {
        val day = _currentWorkoutDay.value ?: return emptyList()
        return when (step) {
            WorkoutStep.WARMUP -> getWarmupExercises()
            WorkoutStep.MAIN -> when (_currentSessionMode.value) {
                SessionMode.POSTURE_MOBILITY -> getPostureMobilityWorkout(day).exercises
                SessionMode.DAILY -> getWorkoutForDay(day).exercises
            }
            else -> emptyList()
        }
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

    private fun applyDifficultyAdjustment(
        exercise: Exercise,
        difficultyAdjustments: Map<String, Int>
    ): Exercise {
        return exercise.applyDifficultyAdjustment(difficultyAdjustments[exercise.id] ?: 0)
    }

    private fun enrichExercise(exercise: Exercise): Exercise {
        return exercise.withLocalizedSearchText(getApplication())
    }
}
