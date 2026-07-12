package com.monkfitness.app.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkfitness.app.R
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.LibraryStats
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.ProgramStatistics
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.UserPreferences
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.VolumeHistoryPoint
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.applyDifficultyAdjustment
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionExclusionIngredients
import com.monkfitness.app.data.model.toMealEntities
import com.monkfitness.app.data.model.toShoppingItemEntities
import com.monkfitness.app.data.model.validateAvailableProductSelection
import com.monkfitness.app.data.repository.WorkoutRepository
import com.monkfitness.app.domain.usecase.TOTAL_PROGRAM_DAYS
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.domain.usecase.calculateProgramDay
import com.monkfitness.app.domain.usecase.synchronizeProgramStates
import com.monkfitness.app.ui.screens.WorkoutStep
import com.monkfitness.app.util.NotificationScheduler
import com.monkfitness.app.util.matchesQuery
import com.monkfitness.app.util.withLocalizedSearchText
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import com.monkfitness.app.data.model.generateNutritionPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

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

    companion object {
        const val ROUTE_HOME = "home"
        const val ROUTE_NUTRITION = "nutrition"
    }

    // Notification Deep-link State
    private val _notificationDestination = MutableStateFlow<String?>(null)
    val notificationDestination = _notificationDestination.asStateFlow()

    fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val type = intent.getStringExtra(NotificationScheduler.EXTRA_NOTIFICATION_TYPE)
        if (type != null) {
            val destination = when (type) {
                NotificationScheduler.TYPE_NUTRITION -> ROUTE_NUTRITION
                NotificationScheduler.TYPE_WORKOUT -> ROUTE_HOME
                else -> ROUTE_HOME
            }
            _notificationDestination.value = destination
        }
    }

    fun clearNotificationDestination() {
        _notificationDestination.value = null
    }

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

    private val _completedExercises = MutableStateFlow<Map<String, Int>>(emptyMap())
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

    private val _currentDate = MutableStateFlow(LocalDate.now())
    val currentDate = _currentDate.asStateFlow()
    private val _nutritionMessageEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val nutritionMessageEvents = _nutritionMessageEvents.asSharedFlow()

    val allProgress = repository.getAllProgress().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val programStartDate = settingsManager.programStartDateFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now().toString()
    )

    val programSummaryDismissed = settingsManager.programSummaryDismissedFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val nutritionWarningDismissedFor = settingsManager.nutritionWarningDismissedForFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val currentProgramDay = combine(programStartDate, currentDate) { startDate, today ->
        calculateProgramDay(parseDate(startDate, today), today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val programDayStates = repository.getProgramDayStates().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val programCompletedDaysCount = programDayStates
        .map { states -> states.count { it.isCompleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayProgramDayState = combine(programDayStates, currentProgramDay) { states, day ->
        states.firstOrNull { it.programDay == day }
            ?: ProgramDayState(
                programDay = day,
                isWorkoutDay = getWorkoutTypeForDay(day) != com.monkfitness.app.data.model.WorkoutType.REST,
                isCompleted = false,
                isMissed = false,
                completedAt = null
            )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProgramDayState(
            programDay = 1,
            isWorkoutDay = true,
            isCompleted = false,
            isMissed = false,
            completedAt = null
        )
    )

    val exerciseDifficultyAdjustments = settingsManager.exerciseDifficultyAdjustmentsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    val additionalPostureTrainingEnabled = settingsManager.additionalPostureTrainingEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val availableEquipment = settingsManager.availableEquipmentFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    val userPreferences = settingsManager.userPreferencesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences()
    )

    val exercisePersonalRecords = settingsManager.exercisePersonalRecordsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
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
    private val _expandedFamilyIds = MutableStateFlow<Set<String>>(emptySet())

    val completedDaysCount = repository.getCompletedDaysCount().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val programStatistics = combine(
        repository.getProgramStatistics(),
        exercisePersonalRecords
    ) { snapshot, personalRecords ->
        val denominator = (snapshot.totalWorkoutsCompleted + snapshot.totalMissed).coerceAtLeast(1)
        ProgramStatistics(
            totalWorkoutsCompleted = snapshot.totalWorkoutsCompleted,
            totalMissed = snapshot.totalMissed,
            totalSets = snapshot.totalSets,
            totalReps = snapshot.totalReps,
            totalTimerSeconds = snapshot.totalTimerSeconds,
            totalExercisesCompleted = snapshot.totalExercisesCompleted,
            totalPersonalRecords = personalRecords.values.count { it > 0 },
            completionPercentage = ((snapshot.totalWorkoutsCompleted * 100f) / denominator).roundToInt()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProgramStatistics(0, 0, 0, 0, 0, 0, 0, 0)
    )

    val volumeHistory = repository.getDailyVolumeHistory().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val workoutFrequencyHistory = repository.getWorkoutFrequencyByWeek().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val bodyWeightHistory = repository.getBodyWeightEntriesSince(
        LocalDate.now().minusDays(89).toString()
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val latestBodyWeight = bodyWeightHistory
        .map { history -> history.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val postureProgress = repository.getAllPostureProgress().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val completedPostureDaysCount = repository.getCompletedPostureDaysCount().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val libraryStats = flowOf(workoutGenerator.getLibraryStats()).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        LibraryStats(0, 0, 0, 0, 0, 0)
    )

    val categoryExerciseCounts: StateFlow<Map<String, Int>> = flowOf(
        com.monkfitness.app.data.model.ExerciseCategoryFilter.entries.associateWith { filter ->
            val allExercises = workoutGenerator.getExerciseLibrary(com.monkfitness.app.data.model.Equipment.entries.toSet())
            allExercises.count { exercise ->
                val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
                families.contains(filter)
            }
        }.mapKeys { it.key.key }
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak
    private val _bodyWeightErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val bodyWeightErrorEvents = _bodyWeightErrorEvents.asSharedFlow()

    val disabledExerciseFamilies = settingsManager.disabledExerciseFamiliesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    val filterLibraryByCategories = settingsManager.filterLibraryByCategoriesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    private val _showCategoryErrorDialog = MutableStateFlow(false)
    val showCategoryErrorDialog = _showCategoryErrorDialog.asStateFlow()

    fun dismissCategoryErrorDialog() {
        _showCategoryErrorDialog.value = false
    }

    private val _showAdjustedValidationMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val showAdjustedValidationMessage = _showAdjustedValidationMessage.asSharedFlow()

    val disabledOptionsExplanations = combine(
        disabledExerciseFamilies,
        flexibilityTrainingType,
        flexibilityFocusAreas
    ) { disabled, flexType, focusAreas ->
        com.monkfitness.app.data.model.SettingsConstraintResolver.resolve(disabled, flexType, focusAreas).disabledOptionsExplanation
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setFilterLibraryByCategories(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setFilterLibraryByCategories(enabled)
        }
    }

    val rewardsGrantedDays = settingsManager.rewardsGrantedDaysFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    private val homeMetrics = combine(
        currentProgramDay,
        programCompletedDaysCount,
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
        availableEquipment,
        todayProgramDayState,
        disabledExerciseFamilies
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
        val todayState = values[6] as ProgramDayState
        @Suppress("UNCHECKED_CAST")
        val disabledFamilies = values[7] as Set<String>
        HomeUiState(
            currentDay = metrics.currentDay,
            workout = getWorkoutForDay(metrics.currentDay, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies),
            completedCount = metrics.completedCount,
            completedPostureCount = metrics.completedPostureCount,
            streak = metrics.streak,
            additionalPostureTrainingEnabled = additionalPostureEnabled,
            flexibilityTrainingType = trainingType,
            flexibilityFocusAreas = focusAreas,
            todayProgramDayState = todayState
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
            flexibilityFocusAreas = setOf(ExerciseSubCategory.FULL_BODY),
            todayProgramDayState = ProgramDayState(1, true, false, false, null)
        )
    )

    val workoutSessionUiState = combine(
        currentWorkoutDay,
        currentSessionMode,
        exerciseDifficultyAdjustments,
        flexibilityTrainingType,
        flexibilityFocusAreas,
        availableEquipment,
        disabledExerciseFamilies
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
        @Suppress("UNCHECKED_CAST")
        val disabledFamilies = values[6] as Set<String>
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
                    getPostureMobilityWorkout(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies)
                } else {
                    getWorkoutForDay(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies)
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
        postureSearchQuery,
        _postureSelectedCategory,
        _postureSelectedSubCategory,
        _expandedFamilyIds,
        availableEquipment
    ) { params ->
        val difficultyAdjustments = params[0] as Map<String, Int>
        val debouncedQuery = params[1] as String
        val selectedCategory = params[2] as ExerciseCategory?
        val selectedSubCategory = params[3] as ExerciseSubCategory?
        val expandedFamilyIds = params[4] as Set<String>
        val availableEquipment = params[5] as Set<Equipment>

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

        val familiesInLibrary = workoutGenerator.families.filter { family ->
            filteredExercises.any { it.familyId == family.id }
        }
        val exercisesByFamily = filteredExercises.groupBy { it.familyId }

        PostureUiState(
            selectedCategory = selectedCategory,
            selectedSubCategory = safeSelectedSubCategory,
            availableSubCategories = availableSubCategories,
            filteredExercises = filteredExercises,
            families = familiesInLibrary,
            exercisesByFamily = exercisesByFamily,
            expandedFamilyIds = expandedFamilyIds
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PostureUiState(
            selectedCategory = null,
            selectedSubCategory = null,
            availableSubCategories = emptyList(),
            filteredExercises = emptyList(),
            families = emptyList(),
            exercisesByFamily = emptyMap(),
            expandedFamilyIds = emptySet()
        )
    )

    init {
        viewModelScope.launch {
            settingsManager.ensureProgramStartDate()
            refreshCalendarState()
        }
        viewModelScope.launch {
            while (isActive) {
                val today = LocalDate.now()
                if (_currentDate.value != today) {
                    _currentDate.value = today
                }
                refreshCalendarState()
                delay(60_000)
            }
        }
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
        availableEquipment: Set<Equipment> = this.availableEquipment.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value
    ): Workout {
        val workout = workoutGenerator.generateWorkout(day, trainingType, focusAreas, availableEquipment, disabledFamilies)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getPostureMobilityWorkout(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value
    ): Workout {
        val workout = workoutGenerator.generatePostureMobilityWorkout(day, trainingType, focusAreas, availableEquipment, disabledFamilies)
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments)) })
    }

    fun getExerciseLibrary(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ): List<Exercise> {
        val baseList = workoutGenerator.getExerciseLibrary(availableEquipment)
        val filteredList = if (filterLibraryByCategories.value) {
            val disabled = disabledExerciseFamilies.value
            baseList.filter { exercise ->
                val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
                families.isEmpty() || families.none { it.key in disabled }
            }
        } else {
            baseList
        }
        return filteredList.map {
            enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments))
        }
    }

    fun getPostureExercises(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value
    ): List<Exercise> {
        val baseList = workoutGenerator.getPostureExercises(focusAreas)
        val filteredList = if (filterLibraryByCategories.value) {
            val disabled = disabledExerciseFamilies.value
            baseList.filter { exercise ->
                val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
                families.isEmpty() || families.none { it.key in disabled }
            }
        } else {
            baseList
        }
        return filteredList.map {
            enrichExercise(applyDifficultyAdjustment(it, difficultyAdjustments))
        }
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
        // All exercises come from the catalog (allExercises), so we only need to look them up
        // from getExerciseLibrary, getPostureExercises, and getWarmupExercises.
        // No inline exercises with unique IDs are created during workout generation.
        return getExerciseLibrary(difficultyAdjustments, availableEquipment).find { it.id == exerciseId }
            ?: getPostureExercises(difficultyAdjustments, focusAreas).find { it.id == exerciseId }
            ?: getWarmupExercises(difficultyAdjustments).find { it.id == exerciseId }
    }

    fun getExerciseDifficultyAdjustment(exerciseId: String): Flow<Int> {
        return settingsManager.getExerciseDifficultyAdjustmentFlow(exerciseId)
    }

    fun getExercisePersonalRecord(exerciseId: String): Flow<Int> {
        return settingsManager.getExercisePersonalRecordFlow(exerciseId)
    }

    fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> {
        return repository.getExerciseVolumeHistory(exerciseId)
    }

    fun logBodyWeight(kg: Float) {
        if (!kg.isFinite() || kg !in 30f..300f) {
            _bodyWeightErrorEvents.tryEmit(getApplication<Application>().getString(R.string.body_weight_validation_error))
            return
        }

        viewModelScope.launch {
            repository.insertBodyWeightEntry(
                BodyWeightEntry(
                    weightKg = kg,
                    date = currentSessionDate()
                )
            )
        }
    }

    private val _previewNutritionPlan = MutableStateFlow<NutritionPlan?>(null)
    val previewNutritionPlan = _previewNutritionPlan.asStateFlow()

    fun previewNextCycle(durationDays: Int) {
        viewModelScope.launch {
            val safeDuration = durationDays.coerceIn(1, 7)
            val today = currentDate.value
            val active = activeMealCycle.value
            val cycleStartDate = active?.let { mealCycleEndDate(it).plusDays(1) } ?: today
            val dummyCycleId = -999L
            val preferredIngredientKeys = nutritionAvailableProducts.value
            val validPreferredKeys = if (validateAvailableProductSelection(preferredIngredientKeys) == null) preferredIngredientKeys else emptySet()
            val plan = generateNutritionPlan(
                seed = cycleStartDate.toEpochDay().toInt(),
                startDay = calculateProgramDay(parseDate(programStartDate.value, cycleStartDate), cycleStartDate),
                daysCount = safeDuration,
                weightKg = nutritionWeight.value.toIntOrNull(),
                heightCm = nutritionHeight.value.toIntOrNull(),
                excludedIngredientKeys = nutritionExcludedFoods.value,
                preferredIngredientKeys = validPreferredKeys,
                cycleId = dummyCycleId,
                workoutTypeForDay = ::getWorkoutTypeForDay
            )
            _previewNutritionPlan.value = plan
        }
    }

    fun savePreviewCycle() {
        viewModelScope.launch {
            val plan = _previewNutritionPlan.value ?: return@launch
            val safeDuration = plan.days.size
            val today = currentDate.value
            val active = activeMealCycle.value
            val pending = pendingMealCycle.value
            val cycleStartDate = active?.let { mealCycleEndDate(it).plusDays(1) } ?: today
            val baseCycle = pending?.copy(
                startDate = cycleStartDate.toString(),
                durationDays = safeDuration,
                createdAt = System.currentTimeMillis(),
                isCompleted = false,
                autoGenerated = false
            ) ?: MealCycle(
                startDate = cycleStartDate.toString(),
                durationDays = safeDuration,
                createdAt = System.currentTimeMillis(),
                isCompleted = false,
                autoGenerated = false
            )
            val storedCycleId = repository.insertMealCycle(baseCycle)
            val cycleId = if (storedCycleId == 0L) baseCycle.id else storedCycleId

            val finalPlan = plan.copy(
                cycleId = cycleId,
                days = plan.days.map { day ->
                    day.copy(
                        meals = day.meals.map { it.copy(cycleId = cycleId) }
                    )
                }
            )

            repository.replaceCycleMeals(cycleId, finalPlan.toMealEntities(cycleId), finalPlan.toShoppingItemEntities(cycleId))
            settingsManager.dismissNutritionWarningFor(null)
            _previewNutritionPlan.value = null
        }
    }

    fun clearPreviewCycle() {
        _previewNutritionPlan.value = null
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
            val totalSets = exercise.sets.coerceAtLeast(1)
            val completedSets = ((_completedExercises.value[exercise.id] ?: 0) + 1).coerceAtMost(totalSets)
            _completedExercises.value = _completedExercises.value + (exercise.id to completedSets)
            persistCompletedSet(exercise)
            updateExercisePersonalRecord(exercise)

            if (completedSets < totalSets) {
                _isRestTime.value = false
                _restTargetIndex.value = null
                if (exercise.isTimerBased) {
                    resetTimer(exercise.durationSeconds)
                } else {
                    stopTimer()
                    _timeLeft.value = 0
                }
                return
            }
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

        val currentExercise = getExercisesForStep(_currentStep.value).getOrNull(_exerciseIndex.value)
        if (currentExercise != null) {
            val completedSets = _completedExercises.value[currentExercise.id] ?: 0
            if (completedSets > 0) {
                val updatedSets = completedSets - 1
                _completedExercises.value = _completedExercises.value.toMutableMap().apply {
                    if (updatedSets > 0) {
                        put(currentExercise.id, updatedSets)
                    } else {
                        remove(currentExercise.id)
                    }
                }
                rollbackCompletedSet(currentExercise.id)
                if (currentExercise.isTimerBased) {
                    resetTimer(currentExercise.durationSeconds)
                } else {
                    stopTimer()
                    _timeLeft.value = 0
                }
                return
            }
        }

        if (_exerciseIndex.value > 0) {
            _exerciseIndex.value--
            stopTimer()
            _timeLeft.value = 0
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
            val completedAt = System.currentTimeMillis()
            val progress = UserProgress(
                day = day,
                isCompleted = true,
                completionDate = completedAt,
                workoutType = workoutGenerator.generateWorkout(day).type.name
            )
            repository.updateProgress(progress)
            repository.upsertProgramDayState(
                ProgramDayState(
                    programDay = day,
                    isWorkoutDay = true,
                    isCompleted = true,
                    isMissed = false,
                    completedAt = completedAt
                )
            )

            // Separate Reward Concept: Grant reward only once
            val rewardKey = "workout_$day"
            if (!rewardsGrantedDays.value.contains(rewardKey)) {
                settingsManager.setRewardGranted(rewardKey)
            }
        }
    }

    fun completeRecoveryDay(day: Int) {
        viewModelScope.launch {
            repository.upsertProgramDayState(
                ProgramDayState(
                    programDay = day,
                    isWorkoutDay = false,
                    isCompleted = true,
                    isMissed = false,
                    completedAt = System.currentTimeMillis()
                )
            )

            // Separate Reward Concept: Grant reward only once
            val rewardKey = "recovery_$day"
            if (!rewardsGrantedDays.value.contains(rewardKey)) {
                settingsManager.setRewardGranted(rewardKey)
            }
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

            // Separate Reward Concept: Grant reward only once
            val rewardKey = "posture_$day"
            if (!rewardsGrantedDays.value.contains(rewardKey)) {
                settingsManager.setRewardGranted(rewardKey)
            }
        }
    }

    fun completeCurrentSession(day: Int) {
        if (_currentSessionMode.value == SessionMode.POSTURE_MOBILITY) {
            completePostureWorkout(day)
        } else if (getWorkoutTypeForDay(day) == com.monkfitness.app.data.model.WorkoutType.REST) {
            completeRecoveryDay(day)
        } else {
            completeWorkout(day)
        }
    }

    fun getCurrentDay(): Int {
        return currentProgramDay.value
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
            val resolution = com.monkfitness.app.data.model.SettingsConstraintResolver.resolve(
                disabledFamilies = disabledExerciseFamilies.value,
                flexibilityType = trainingType,
                focusAreas = flexibilityFocusAreas.value
            )
            if (resolution.adjustedDisabledFamilies != disabledExerciseFamilies.value) {
                settingsManager.setDisabledExerciseFamilies(resolution.adjustedDisabledFamilies)
                _showAdjustedValidationMessage.emit("Some options were adjusted because they conflict with your current selection.")
            }
        }
    }

    fun toggleAvailableEquipment(equipment: Equipment) {
        if (equipment == Equipment.NONE) return

        val current = availableEquipment.value
        val next = if (equipment in current) current - equipment else current + equipment

        viewModelScope.launch {
            settingsManager.setAvailableEquipment(next)
        }
    }

    fun clearAvailableEquipment() {
        viewModelScope.launch {
            settingsManager.setAvailableEquipment(emptySet())
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

    val nutritionCycleLength = settingsManager.nutritionCycleLengthFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 3
    )

    val nutritionExcludedFoods = settingsManager.nutritionExcludedFoodsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    fun toggleExerciseFamily(familyKey: String) {
        viewModelScope.launch {
            val current = disabledExerciseFamilies.value
            val allKeys = com.monkfitness.app.data.model.ExerciseCategoryFilter.entries.map { it.key }.toSet()
            val currentlyEnabled = allKeys - current

            val isCurrentlyEnabled = familyKey !in current
            if (isCurrentlyEnabled) {
                // If it is currently enabled and we want to disable it, check if it's the last remaining enabled category
                if (currentlyEnabled.size <= 1) {
                    _showCategoryErrorDialog.value = true
                    return@launch
                }
            }

            val next = if (familyKey in current) current - familyKey else current + familyKey
            settingsManager.setDisabledExerciseFamilies(next)
        }
    }

    fun enableAllInGroup(categoriesInGroup: List<String>) {
        viewModelScope.launch {
            val currentDisabled = disabledExerciseFamilies.value
            val nextDisabled = currentDisabled - categoriesInGroup.toSet()
            settingsManager.setDisabledExerciseFamilies(nextDisabled)
        }
    }

    fun disableAllInGroup(categoriesInGroup: List<String>) {
        viewModelScope.launch {
            val currentDisabled = disabledExerciseFamilies.value
            val allKeys = com.monkfitness.app.data.model.ExerciseCategoryFilter.entries.map { it.key }.toSet()
            val currentlyEnabled = allKeys - currentDisabled

            val toDisable = categoriesInGroup.filter { it in currentlyEnabled }
            if (toDisable.isEmpty()) return@launch

            if (currentlyEnabled.size - toDisable.size == 0) {
                // Rule #5 violation: everything would be disabled!
                // Keep the single last remaining enabled category across the entire app
                val lastEnabled = currentlyEnabled.first()
                val newDisabled = allKeys - setOf(lastEnabled)
                settingsManager.setDisabledExerciseFamilies(newDisabled)
                _showCategoryErrorDialog.value = true
            } else {
                val nextDisabled = currentDisabled + toDisable
                settingsManager.setDisabledExerciseFamilies(nextDisabled)
            }
        }
    }

    val nutritionAvailableProducts = settingsManager.nutritionAvailableProductsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )

    val showExcludedProductsInNutrition = settingsManager.showExcludedProductsInNutritionFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val nutritionExclusionOptions: List<NutritionIngredient> = nutritionExclusionIngredients

    val mealCycles = repository.getMealCycles().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val activeMealCycle = combine(mealCycles, currentDate) { cycles, today ->
        cycles
            .filter { !it.isCompleted && !parseDate(it.startDate, today).isAfter(today) }
            .maxByOrNull { it.startDate }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pendingMealCycle = combine(mealCycles, currentDate) { cycles, today ->
        cycles
            .filter { !it.isCompleted && parseDate(it.startDate, today).isAfter(today) }
            .minByOrNull { it.startDate }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nutritionPlan = activeMealCycle.flatMapLatest { cycle ->
        if (cycle == null || nutritionCycleLength.value == 0) {
            flowOf(NutritionPlan(emptyList()))
        } else {
            repository.getMealsForCycle(cycle.id).map { meals ->
                if (meals.isEmpty()) NutritionPlan(emptyList(), cycle.id)
                else mealEntitiesToNutritionPlan(cycle.id, meals)
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NutritionPlan(emptyList())
    )

    val todayNutritionPlan = combine(nutritionPlan, currentProgramDay) { plan, day ->
        plan.days.firstOrNull { it.programDay == day }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentNutritionTargets = combine(
        nutritionWeight,
        nutritionHeight,
        todayNutritionPlan
    ) { weight, height, dayPlan ->
        calculateMuscleGainNutritionTargets(
            weightKg = weight.toIntOrNull(),
            heightCm = height.toIntOrNull(),
            dayType = dayPlan?.dayType ?: NutritionDayType.TRAINING
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        calculateMuscleGainNutritionTargets(weightKg = null, heightCm = null)
    )

    val shouldShowNutritionExpirationWarning = combine(
        activeMealCycle,
        pendingMealCycle,
        nutritionWarningDismissedFor,
        currentDate,
        nutritionCycleLength
    ) { activeCycle, pendingCycle, dismissedFor, today, cycleLength ->
        if (cycleLength == 0 || activeCycle == null || pendingCycle != null) {
            false
        } else {
            val warningKey = activeCycle.startDate
            val tomorrowIsEnd = mealCycleEndDate(activeCycle) == today.plusDays(1)
            tomorrowIsEnd && dismissedFor != warningKey
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showProgramSummary = combine(
        currentProgramDay,
        todayProgramDayState,
        programSummaryDismissed
    ) { day, state, dismissed ->
        day == TOTAL_PROGRAM_DAYS && state.isCompleted && !dismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setNutritionCycleLength(days: Int) {
        viewModelScope.launch {
            settingsManager.setNutritionCycleLength(days)
            syncNutritionCycles()
        }
    }

    fun toggleNutritionExcludedFood(foodKey: String) {
        viewModelScope.launch {
            val current = nutritionExcludedFoods.value
            val next = if (foodKey in current) current - foodKey else current + foodKey
            settingsManager.setNutritionExcludedFoods(next)
            syncNutritionCycles()
        }
    }

    fun setShowExcludedProductsInNutrition(show: Boolean) {
        viewModelScope.launch {
            settingsManager.setShowExcludedProductsInNutrition(show)
        }
    }

    fun dismissProgramSummary() {
        viewModelScope.launch {
            settingsManager.setProgramSummaryDismissed(true)
        }
    }

    fun dismissNutritionExpirationWarning() {
        viewModelScope.launch {
            settingsManager.dismissNutritionWarningFor(activeMealCycle.value?.startDate)
        }
    }

    fun generateNextNutritionCycle() {
        viewModelScope.launch {
            createOrQueueMealCycle(
                durationDays = nutritionCycleLength.value.coerceAtLeast(1),
                preferredIngredientKeys = nutritionAvailableProducts.value,
                autoGenerated = false
            )
        }
    }

    fun generateNutritionFromAvailableProducts(selectedIngredientKeys: Set<String>, durationDays: Int) {
        viewModelScope.launch {
            val issue = validateAvailableProductSelection(selectedIngredientKeys)
            if (issue != null) {
                _nutritionMessageEvents.tryEmit(issue.messageRes)
                return@launch
            }
            settingsManager.setNutritionAvailableProducts(selectedIngredientKeys)
            createOrQueueMealCycle(
                durationDays = durationDays,
                preferredIngredientKeys = selectedIngredientKeys,
                autoGenerated = false
            )
        }
    }

    fun replaceNutritionMeal(programDay: Int, mealType: NutritionMealType) {
        viewModelScope.launch {
            val cycle = activeMealCycle.value ?: return@launch
            val currentPlan = nutritionPlan.value
            val dayPlan = currentPlan.days.firstOrNull { it.programDay == programDay } ?: return@launch
            val meal = dayPlan.meals.firstOrNull { it.type == mealType } ?: return@launch
            val replacement = com.monkfitness.app.data.model.replaceMeal(
                meal = meal,
                excludedIngredientKeys = nutritionExcludedFoods.value
            ) ?: return@launch

            val updatedPlan = NutritionPlan(
                days = currentPlan.days.map { day ->
                    if (day.programDay != programDay) {
                        day
                    } else {
                        day.copy(
                            meals = day.meals.map { existing ->
                                if (existing.type == mealType) replacement.copy(cycleId = cycle.id) else existing
                            }
                        )
                    }
                },
                cycleId = cycle.id
            )
            repository.replaceCycleMeals(cycle.id, updatedPlan.toMealEntities(cycle.id), updatedPlan.toShoppingItemEntities(cycle.id))
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
            val resolution = com.monkfitness.app.data.model.SettingsConstraintResolver.resolve(
                disabledFamilies = disabledExerciseFamilies.value,
                flexibilityType = flexibilityTrainingType.value,
                focusAreas = nextSelection
            )
            if (resolution.adjustedDisabledFamilies != disabledExerciseFamilies.value) {
                settingsManager.setDisabledExerciseFamilies(resolution.adjustedDisabledFamilies)
                _showAdjustedValidationMessage.emit("Some options were adjusted because they conflict with your current selection.")
            }
        }
    }

    fun setPostureSearchQuery(query: String) {
        _postureSearchQuery.value = query
        if (query.isNotBlank()) {
            _expandedFamilyIds.value = workoutGenerator.families.map { it.id }.toSet()
        } else {
            _expandedFamilyIds.value = emptySet()
        }
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
        _expandedFamilyIds.value = emptySet()
    }

    fun toggleFamilyExpanded(familyId: String) {
        val current = _expandedFamilyIds.value
        _expandedFamilyIds.value = if (familyId in current) current - familyId else current + familyId
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

    private suspend fun refreshCalendarState() {
        syncProgramDayStates()
        syncNutritionCycles()
    }

    private suspend fun syncProgramDayStates() {
        val legacyProgress = allProgress.value.associateBy { it.day }
        val synchronizedStates = synchronizeProgramStates(
            existing = repository.getProgramDayStatesSnapshot(),
            currentProgramDay = currentProgramDay.value,
            workoutTypeForDay = ::getWorkoutTypeForDay
        ).map { state ->
            val legacy = legacyProgress[state.programDay]
            if (legacy != null && state.isWorkoutDay) {
                state.copy(isCompleted = true, isMissed = false, completedAt = legacy.completionDate)
            } else {
                state
            }
        }
        repository.upsertProgramDayStates(synchronizedStates)
    }

    private suspend fun syncNutritionCycles() {
        if (nutritionCycleLength.value == 0) return

        val today = currentDate.value
        val cycles = repository.getMealCyclesSnapshot()
        val expiredCycles = cycles.filter { !it.isCompleted && mealCycleEndDate(it).isBefore(today) }
        expiredCycles.forEach { expired ->
            repository.insertMealCycle(expired.copy(isCompleted = true))
        }

        val refreshedCycles = repository.getMealCyclesSnapshot()
        val active = refreshedCycles
            .filter { !it.isCompleted && !parseDate(it.startDate, today).isAfter(today) }
            .maxByOrNull { it.startDate }
        val pending = refreshedCycles
            .filter { !it.isCompleted && parseDate(it.startDate, today).isAfter(today) }
            .minByOrNull { it.startDate }

        if (active == null) {
            val sourceCycle = expiredCycles.maxByOrNull { it.startDate }
            if (pending == null) {
                createOrQueueMealCycle(
                    durationDays = sourceCycle?.durationDays ?: nutritionCycleLength.value.coerceAtLeast(1),
                    preferredIngredientKeys = nutritionAvailableProducts.value,
                    autoGenerated = sourceCycle != null
                )
            }
        }
    }

    private suspend fun createOrQueueMealCycle(
        durationDays: Int,
        preferredIngredientKeys: Set<String>,
        autoGenerated: Boolean
    ) {
        val safeDuration = durationDays.coerceIn(1, 7)
        val today = currentDate.value
        val active = activeMealCycle.value
        val pending = pendingMealCycle.value
        val cycleStartDate = active?.let { mealCycleEndDate(it).plusDays(1) } ?: today
        val baseCycle = pending?.copy(
            startDate = cycleStartDate.toString(),
            durationDays = safeDuration,
            createdAt = System.currentTimeMillis(),
            isCompleted = false,
            autoGenerated = autoGenerated
        ) ?: MealCycle(
            startDate = cycleStartDate.toString(),
            durationDays = safeDuration,
            createdAt = System.currentTimeMillis(),
            isCompleted = false,
            autoGenerated = autoGenerated
        )
        val storedCycleId = repository.insertMealCycle(baseCycle)
        val cycleId = if (storedCycleId == 0L) baseCycle.id else storedCycleId
        val validPreferredKeys = if (validateAvailableProductSelection(preferredIngredientKeys) == null) preferredIngredientKeys else emptySet()
        val plan = generateNutritionPlan(
            seed = cycleStartDate.toEpochDay().toInt(),
            startDay = calculateProgramDay(parseDate(programStartDate.value, cycleStartDate), cycleStartDate),
            daysCount = safeDuration,
            weightKg = nutritionWeight.value.toIntOrNull(),
            heightCm = nutritionHeight.value.toIntOrNull(),
            excludedIngredientKeys = nutritionExcludedFoods.value,
            preferredIngredientKeys = validPreferredKeys,
            cycleId = cycleId,
            workoutTypeForDay = ::getWorkoutTypeForDay
        )
        repository.replaceCycleMeals(cycleId, plan.toMealEntities(cycleId), plan.toShoppingItemEntities(cycleId))
        settingsManager.dismissNutritionWarningFor(null)
    }

    private fun mealCycleEndDate(cycle: MealCycle): LocalDate {
        return parseDate(cycle.startDate, currentDate.value).plusDays(cycle.durationDays.toLong() - 1)
    }

    private fun parseDate(raw: String, fallback: LocalDate): LocalDate {
        return runCatching { LocalDate.parse(raw) }.getOrDefault(fallback)
    }

    private val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private fun playBeep(duration: Int = 200) {
        toneG.startTone(ToneGenerator.TONE_PROP_BEEP, duration)
    }

    private fun playStartSound() {
        toneG.startTone(ToneGenerator.TONE_DTMF_0, 400)
    }

    private fun updateExercisePersonalRecord(exercise: Exercise) {
        val recordValue = if (exercise.isTimerBased) {
            exercise.durationSeconds
        } else {
            exercise.maxReps.coerceAtLeast(exercise.reps)
        }

        if (recordValue <= 0) return

        viewModelScope.launch {
            val currentRecord = exercisePersonalRecords.value[exercise.id] ?: 0
            if (recordValue > currentRecord) {
                settingsManager.setExercisePersonalRecord(exercise.id, recordValue)
            }
        }
    }

    private fun persistCompletedSet(exercise: Exercise) {
        val now = System.currentTimeMillis()
        val setLog = SetLog(
            exerciseId = exercise.id,
            repsCompleted = if (exercise.isTimerBased) 0 else exercise.maxReps.coerceAtLeast(exercise.reps).coerceAtLeast(0),
            durationSeconds = if (exercise.isTimerBased) exercise.durationSeconds.coerceAtLeast(0) else 0,
            timestamp = now,
            sessionDate = currentSessionDate()
        )

        viewModelScope.launch {
            repository.insertSetLog(setLog)
        }
    }

    private fun rollbackCompletedSet(exerciseId: String) {
        viewModelScope.launch {
            repository.deleteLatestSetLogForExerciseOnDate(exerciseId, currentSessionDate())
        }
    }

    private fun currentSessionDate(): String = LocalDate.now().toString()

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
