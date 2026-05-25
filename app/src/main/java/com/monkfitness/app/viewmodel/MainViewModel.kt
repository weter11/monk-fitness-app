package com.monkfitness.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.repository.WorkoutRepository
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.util.NotificationScheduler
import com.monkfitness.app.util.matchesQuery
import com.monkfitness.app.util.withLocalizedSearchText
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.UserPreferences
import com.monkfitness.app.data.model.applyDifficultyAdjustment
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.findReplacementMealTemplateId
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.nutritionExclusionIngredients
import com.monkfitness.app.data.model.nutritionReplacementKey
import com.monkfitness.app.ui.screens.WorkoutStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private data class HomeMetrics(
        val currentDay: Int,
        val completedCount: Int,
        val completedPostureCount: Int,
        val streak: Int
    )

    enum class SessionMode {
        DAILY,
        POSTURE_MOBILITY
    }

    private val repository: WorkoutRepository
    private val workoutGenerator = WorkoutGenerator()
    private val emptyWorkout = Workout(id = -1, type = com.monkfitness.app.data.model.WorkoutType.REST, exercises = emptyList())

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

    val currentProgramDay = allProgress
        .map { progress -> calculateCurrentDay(progress) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val exerciseDifficultyAdjustments = settingsManager.exerciseDifficultyAdjustmentsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    val additionalPostureTrainingEnabled = settingsManager.additionalPostureTrainingEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val availableEquipment = settingsManager.availableEquipmentFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(Equipment.NONE)
    )

    val userPreferences = settingsManager.userPreferencesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences()
    )

    val flexibilityTrainingType = settingsManager.flexibilityTrainingTypeFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), FlexibilityTrainingType.BOTH
    )

    val flexibilityFocusAreas = settingsManager.flexibilityFocusAreasFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(ExerciseSubCategory.FULL_BODY)
    )

    private val _postureSearchQuery = MutableStateFlow("")
    val postureSearchQuery = _postureSearchQuery.asStateFlow()
    private val _postureSelectedCategory = MutableStateFlow<ExerciseCategory?>(null)
    private val _postureSelectedSubCategory = MutableStateFlow<ExerciseSubCategory?>(null)
    private val debouncedPostureSearchQuery = _postureSearchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    private val homeMetrics = combine(
        currentProgramDay,
        completedDaysCount,
        completedPostureDaysCount,
        streak
    ) { currentDay, completedCount, completedPostureCount, streakCount ->
        HomeMetrics(
            currentDay = currentDay,
            completedCount = completedCount,
            completedPostureCount = completedPostureCount,
            streak = streakCount
        )
    }

    val homeUiState = combine(
        homeMetrics,
        additionalPostureTrainingEnabled,
        flexibilityTrainingType,
        flexibilityFocusAreas,
        exerciseDifficultyAdjustments,
        availableEquipment
    ) { values ->
        val metrics = values[0] as HomeMetrics
        val additionalPostureEnabled = values[1] as Boolean
        val trainingType = values[2] as FlexibilityTrainingType
        @Suppress("UNCHECKED_CAST")
        val focusAreas = values[3] as Set<ExerciseSubCategory>
        @Suppress("UNCHECKED_CAST")
        val difficultyAdjustments = values[4] as Map<String, Int>
        @Suppress("UNCHECKED_CAST")
        val availableEquipment = values[5] as Set<Equipment>
        HomeUiState(
            currentDay = metrics.currentDay,
            workout = getWorkoutForDay(metrics.currentDay, difficultyAdjustments, trainingType, focusAreas, availableEquipment),
            completedCount = metrics.completedCount,
            completedPostureCount = metrics.completedPostureCount,
            streak = metrics.streak,
            additionalPostureTrainingEnabled = additionalPostureEnabled,
            flexibilityTrainingType = trainingType,
            flexibilityFocusAreas = focusAreas
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState(
            currentDay = 1,
            workout = emptyWorkout,
            completedCount = 0,
            completedPostureCount = 0,
            streak = 0,
            additionalPostureTrainingEnabled = false,
            flexibilityTrainingType = FlexibilityTrainingType.BOTH,
            flexibilityFocusAreas = setOf(ExerciseSubCategory.FULL_BODY)
        )
    )

    val workoutSessionUiState = combine(
        currentWorkoutDay,
        currentSessionMode,
        exerciseDifficultyAdjustments,
        flexibilityTrainingType,
        flexibilityFocusAreas,
        availableEquipment
    ) { values ->
        val day = values[0] as Int?
        val sessionMode = values[1] as SessionMode
        @Suppress("UNCHECKED_CAST")
        val difficultyAdjustments = values[2] as Map<String, Int>
        val trainingType = values[3] as FlexibilityTrainingType
        @Suppress("UNCHECKED_CAST")
        val focusAreas = values[4] as Set<ExerciseSubCategory>
        @Suppress("UNCHECKED_CAST")
        val availableEquipment = values[5] as Set<Equipment>
        if (day == null) {
            WorkoutSessionUiState(
                day = null,
                workout = emptyWorkout,
                warmupExercises = emptyList(),
                isPostureMobilitySession = sessionMode == SessionMode.POSTURE_MOBILITY
            )
        } else {
            WorkoutSessionUiState(
                day = day,
                workout = if (sessionMode == SessionMode.POSTURE_MOBILITY) {
                    getPostureMobilityWorkout(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment)
                } else {
                    getWorkoutForDay(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment)
                },
                warmupExercises = if (sessionMode == SessionMode.POSTURE_MOBILITY) {
                    emptyList()
                } else {
                    getWarmupExercises(difficultyAdjustments)
                },
                isPostureMobilitySession = sessionMode == SessionMode.POSTURE_MOBILITY
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        WorkoutSessionUiState(
            day = null,
            workout = emptyWorkout,
            warmupExercises = emptyList(),
            isPostureMobilitySession = false
        )
    )

    val postureUiState = combine(
        exerciseDifficultyAdjustments,
        debouncedPostureSearchQuery,
        _postureSelectedCategory,
        _postureSelectedSubCategory,
        availableEquipment
    ) { difficultyAdjustments, debouncedQuery, selectedCategory, selectedSubCategory, availableEquipment ->
        val exercises = getExerciseLibrary(difficultyAdjustments, availableEquipment)
        val searchFilteredExercises = if (debouncedQuery.isBlank()) {
            exercises
        } else {
            exercises.filter { matchesQuery(it, debouncedQuery) }
        }
        val availableSubCategories = searchFilteredExercises
            .asSequence()
            .filter { selectedCategory == null || it.category == selectedCategory }
            .map { it.subCategory }
            .distinct()
            .toList()
        val safeSelectedSubCategory = selectedSubCategory?.takeIf { it in availableSubCategories }
        val filteredExercises = searchFilteredExercises.filter { exercise ->
            (selectedCategory == null || exercise.category == selectedCategory) &&
                (safeSelectedSubCategory == null || exercise.subCategory == safeSelectedSubCategory)
        }
        PostureUiState(
            selectedCategory = selectedCategory,
            selectedSubCategory = safeSelectedSubCategory,
            availableSubCategories = availableSubCategories,
            filteredExercises = filteredExercises
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PostureUiState(
            selectedCategory = null,
            selectedSubCategory = null,
            availableSubCategories = emptyList(),
            filteredExercises = emptyList()
        )
    )

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
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ): Workout {
        val workout = workoutGenerator.generateWorkout(day, trainingType, focusAreas, availableEquipment)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getPostureMobilityWorkout(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ): Workout {
        val workout = workoutGenerator.generatePostureMobilityWorkout(day, trainingType, focusAreas, availableEquipment)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getExerciseLibrary(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ) = workoutGenerator.getExerciseLibrary(availableEquipment).map {
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

    fun findExerciseById(
        exerciseId: String,
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ): Exercise? {
        if (exerciseId.isBlank()) return null
        val resolvedDay = day.takeIf { it in 1..56 } ?: currentProgramDay.value

        return getWorkoutForDay(resolvedDay, difficultyAdjustments, trainingType, focusAreas, availableEquipment).exercises.find { it.id == exerciseId }
            ?: getPostureMobilityWorkout(resolvedDay, difficultyAdjustments, trainingType, focusAreas, availableEquipment).exercises.find { it.id == exerciseId }
            ?: getWarmupExercises(difficultyAdjustments).find { it.id == exerciseId }
            ?: getExerciseLibrary(difficultyAdjustments, availableEquipment).find { it.id == exerciseId }
            ?: getPostureExercises(difficultyAdjustments).find { it.id == exerciseId }
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
        return calculateCurrentDay(allProgress.value)
    }

    fun getWorkoutTypeForDay(day: Int) = workoutGenerator.getWorkoutType(day)

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

    fun toggleAvailableEquipment(equipment: Equipment) {
        if (equipment == Equipment.NONE) return

        val current = availableEquipment.value - Equipment.NONE
        val next = if (equipment in current) current - equipment else current + equipment

        viewModelScope.launch {
            settingsManager.setAvailableEquipment(next)
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

    val nutritionPlanDays = settingsManager.nutritionPlanDaysFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 3
    )

    val nutritionExcludedFoods = settingsManager.nutritionExcludedFoodsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    val nutritionExclusionOptions: List<NutritionIngredient> = nutritionExclusionIngredients

    private val _nutritionPlanSeed = MutableStateFlow(0)
    val nutritionPlanSeed: StateFlow<Int> = _nutritionPlanSeed.asStateFlow()

    private val _nutritionMealReplacements = MutableStateFlow<Map<String, String>>(emptyMap())

    val nutritionPlan: StateFlow<NutritionPlan> = combine(
        currentProgramDay,
        nutritionWeight,
        nutritionHeight,
        nutritionPlanDays,
        nutritionExcludedFoods,
        _nutritionPlanSeed,
        _nutritionMealReplacements
    ) { values ->
        val currentDay = values[0] as Int
        val weight = values[1] as String
        val height = values[2] as String
        val planDays = values[3] as Int
        @Suppress("UNCHECKED_CAST")
        val excludedFoods = values[4] as Set<String>
        val planSeed = values[5] as Int
        @Suppress("UNCHECKED_CAST")
        val replacements = values[6] as Map<String, String>
        generateNutritionPlan(
            seed = planSeed,
            startDay = currentDay,
            daysCount = planDays,
            weightKg = weight.toIntOrNull(),
            heightCm = height.toIntOrNull(),
            excludedIngredientKeys = excludedFoods,
            replacements = replacements,
            workoutTypeForDay = ::getWorkoutTypeForDay
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        generateNutritionPlan(seed = 0, daysCount = 3, weightKg = null)
    )

    val currentNutritionTargets = combine(
        nutritionWeight,
        nutritionHeight,
        nutritionPlan
    ) { weight, height, plan ->
        calculateMuscleGainNutritionTargets(
            weightKg = weight.toIntOrNull(),
            heightCm = height.toIntOrNull(),
            dayType = plan.days.firstOrNull()?.dayType ?: NutritionDayType.TRAINING
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        calculateMuscleGainNutritionTargets(weightKg = null, heightCm = null)
    )

    fun regenerateNutritionPlan() {
        _nutritionMealReplacements.value = emptyMap()
        _nutritionPlanSeed.value += 1
    }

    fun setNutritionPlanDays(days: Int) {
        viewModelScope.launch {
            settingsManager.setNutritionPlanDays(days)
        }
        _nutritionMealReplacements.value = emptyMap()
    }

    fun toggleNutritionExcludedFood(foodKey: String) {
        viewModelScope.launch {
            val current = nutritionExcludedFoods.value
            val next = if (foodKey in current) current - foodKey else current + foodKey
            settingsManager.setNutritionExcludedFoods(next)
        }
    }

    fun setNutritionMealCompleted(mealType: NutritionMealType, completed: Boolean) {
        viewModelScope.launch {
            settingsManager.setNutritionMealCompleted(mealType.key, completed)
        }
    }

    fun replaceNutritionMeal(programDay: Int, mealType: NutritionMealType) {
        val dayPlan = nutritionPlan.value.days.firstOrNull { it.programDay == programDay } ?: return
        val meal = dayPlan.meals.firstOrNull { it.type == mealType } ?: return
        val replacementId = findReplacementMealTemplateId(
            meal = meal,
            excludedIngredientKeys = nutritionExcludedFoods.value
        ) ?: return

        _nutritionMealReplacements.value = _nutritionMealReplacements.value.toMutableMap().apply {
            this[nutritionReplacementKey(programDay, mealType)] = replacementId
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

    fun setPostureSearchQuery(query: String) {
        _postureSearchQuery.value = query
    }

    fun setPostureSelectedCategory(category: ExerciseCategory?) {
        _postureSelectedCategory.value = category
        _postureSelectedSubCategory.value = null
    }

    fun setPostureSelectedSubCategory(subCategory: ExerciseSubCategory?) {
        _postureSelectedSubCategory.value = subCategory
    }

    fun clearPostureFilters() {
        _postureSelectedCategory.value = null
        _postureSelectedSubCategory.value = null
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

    private fun calculateCurrentDay(progress: List<UserProgress>): Int {
        val completedDays = progress.filter { it.isCompleted }.map { it.day }.toSet()
        for (i in 1..56) {
            if (i !in completedDays) return i
        }
        return 56
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
        val sessionState = workoutSessionUiState.value
        return when (step) {
            WorkoutStep.WARMUP -> sessionState.warmupExercises
            WorkoutStep.MAIN -> sessionState.workout.exercises
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
