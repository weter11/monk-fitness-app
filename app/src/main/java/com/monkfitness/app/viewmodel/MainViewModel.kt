package com.monkfitness.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkfitness.app.R
import com.monkfitness.app.MonkFitnessApplication
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
import com.monkfitness.app.data.repository.AdaptiveSessionDecisionRecorder
import com.monkfitness.app.data.repository.SessionAdaptiveInputs
import com.monkfitness.app.data.repository.SessionAdaptivePlanReader
import com.monkfitness.app.data.repository.WorkoutRepository
import com.monkfitness.app.data.repository.programConfigurationRepository
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.AdaptiveSessionPlan
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutGenerationRequest
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutIntegration
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.ui.customprogram.CustomProgramEditor
import com.monkfitness.app.validation.EngineeringValidationFilter
import com.monkfitness.app.validation.ValidationCategory
import com.monkfitness.app.validation.ValidationPose
import com.monkfitness.app.validation.ValidationSettings
import com.monkfitness.app.validation.ValidationPoseRegistry
import com.monkfitness.app.util.normalize
import com.monkfitness.app.domain.usecase.calculateProgramDay
import com.monkfitness.app.domain.usecase.resolveCycleAndDay
import com.monkfitness.app.domain.usecase.shouldOfferCycleCompletion
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
import kotlinx.coroutines.flow.first
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

        /** The Custom Program editor's destination in the app's single navigation graph. */
        const val ROUTE_CUSTOM_PROGRAM = "custom-program"

        /** The first program day, used only before a session's own day is established. */
        private const val FIRST_PROGRAM_DAY = 1

        /**
         * The program revision of the program as first started, matching the persisted family
         * progression's revision column and `SettingsManager.PROGRAM_REVISION`: a revision above it is
         * a C3 "Start Revised Program".
         */
        private const val STANDARD_PROGRAM_REVISION = 0

        private const val TAG = "MainViewModel"
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

    /** Reads this session's persisted adaptive inputs; writes nothing. See `SessionAdaptivePlanReader`. */
    private val sessionAdaptivePlanReader: SessionAdaptivePlanReader

    /**
     * Records the adaptive decisions of a finalized session; the app's only adaptive writer.
     *
     * It is the mirror of [sessionAdaptivePlanReader]: that one derives what a session is *presented*
     * with and writes nothing, this one records what a finished session *decided* and reads nothing but
     * the history and the current progression rows. Both keep their joins in the data layer, so this
     * class orchestrates the lifecycle without implementing any adaptive rule.
     */
    private val adaptiveDecisionRecorder: AdaptiveSessionDecisionRecorder

    init {
        // The app's one database, from the composition root (§26): a view model does not acquire a
        // database, a DAO or a repository. It is the *same* instance this class used to open —
        // `AppContainer` holds `AppDatabase.getDatabase(application)`'s result — so nothing about
        // session behaviour changes. Everything below is the shipped Stage-1 wiring and is
        // deliberately untouched: §30 step 15 retires it, and no Program System repository is
        // constructed on this path.
        val db = (application as MonkFitnessApplication).container.database
        repository = WorkoutRepository(db)
        sessionAdaptivePlanReader = SessionAdaptivePlanReader.of(db, workoutGenerator)
        adaptiveDecisionRecorder = AdaptiveSessionDecisionRecorder.of(db, workoutGenerator)
        settingsManager = SettingsManager(application)
    }

    private val _currentDate = MutableStateFlow(LocalDate.now())
    val currentDate = _currentDate.asStateFlow()
    private val _nutritionMessageEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val nutritionMessageEvents = _nutritionMessageEvents.asSharedFlow()

    val programStartDate = settingsManager.programStartDateFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now().toString()
    )

    val nutritionWarningDismissedFor = settingsManager.nutritionWarningDismissedForFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    // Cycle-aware "today": the programme day is a pure function of the calendar — one calendar
    // date is one programme day (resolveCycleAndDay), so cycle N day 56 is the last date of cycle
    // N and cycle N+1 day 1 is the next date. The stamped cycle number deliberately does NOT
    // change the day shown: it only gates the completion dialog below and pre-seeds the next
    // cycle's grid. (Letting the stamp start the next cycle early would put cycle N+1 day 1 on
    // two calendar dates — the rollover date and its own — and demote the real day 1 to a
    // no-credit repeat.)
    val currentProgramDay = combine(
        programStartDate,
        currentDate
    ) { startDate, today ->
        resolveCycleAndDay(parseDate(startDate, today), today).second
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val programCycleNumber = combine(
        programStartDate,
        currentDate
    ) { startDate, today ->
        resolveCycleAndDay(parseDate(startDate, today), today).first
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    // C3 "Start Revised Program" marker: bumped on each revised start; currently informational.
    val programRevision = settingsManager.programRevisionFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val allProgress = repository.getAllProgress(programCycleNumber).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val programDayStates = repository.getProgramDayStates(programCycleNumber).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val programCompletedDaysCount = programDayStates
        .map { states -> states.count { it.isCompleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayProgramDayState = combine(programDayStates, currentProgramDay, programCycleNumber) { states, day, cycle ->
        states.firstOrNull { it.programDay == day }
            ?: ProgramDayState(
                cycleNumber = cycle,
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
            cycleNumber = 1,
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

    val completedDaysCount = repository.getCompletedDaysCount(programCycleNumber).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val programStatistics = combine(
        repository.getProgramStatistics(programCycleNumber),
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

    val postureProgress = repository.getAllPostureProgress(programCycleNumber).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val completedPostureDaysCount = repository.getCompletedPostureDaysCount(programCycleNumber).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val libraryStats = flowOf(workoutGenerator.getLibraryStats()).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        LibraryStats(0, 0, 0, 0, 0, 0)
    )

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

    val showEngineeringValidation = settingsManager.showEngineeringValidationFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ValidationSettings.DEFAULT_ENABLED
    )

    // ---- Custom Program editor -----------------------------------------------------------------
    // The editor is a state holder of its own rather than another pile of flows on this view model:
    // it owns the draft, the deterministic family grouping and the apply/reset flow, and this bridge
    // only hands it the app's own library, families and equipment and forwards the user's taps. The
    // configuration repository is the persistence authority; nothing here decides a source, a version,
    // a validation outcome or what a family toggle means.
    private val programConfigurationRepository = application.programConfigurationRepository()

    val customProgramEditor = CustomProgramEditor(
        repository = programConfigurationRepository,
        exerciseLibrary = { workoutGenerator.getExerciseLibrary().map { exercise -> enrichExercise(exercise) } },
        families = workoutGenerator.families,
        availableEquipment = { availableEquipment.value }
    )

    val customProgramState = customProgramEditor.state

    /** Loads the persisted configuration into a fresh draft and opens the editor. */
    fun openCustomProgramEditor() {
        viewModelScope.launch {
            customProgramEditor.open()
        }
    }

    fun setCustomProgramSearchQuery(query: String) {
        customProgramEditor.setSearchQuery(query)
    }

    fun toggleCustomProgramExercise(exerciseId: String) {
        customProgramEditor.toggleExercise(exerciseId)
    }

    fun toggleCustomProgramFamily(familyId: String) {
        customProgramEditor.toggleFamily(familyId)
    }

    /** Cancel: the draft is discarded and nothing that was persisted is touched. */
    fun cancelCustomProgramEditor() {
        customProgramEditor.discardDraft()
    }

    /** Apply: the editor validates the draft and stores it only if the validator accepts it. */
    fun applyCustomProgram() {
        viewModelScope.launch {
            customProgramEditor.apply()
        }
    }

    fun requestCustomProgramReset() {
        customProgramEditor.requestResetToDefault()
    }

    fun dismissCustomProgramReset() {
        customProgramEditor.dismissResetConfirmation()
    }

    /** Reset to default: a separate, confirmed action that restores the selection and nothing else. */
    fun confirmCustomProgramReset() {
        viewModelScope.launch {
            customProgramEditor.confirmResetToDefault()
        }
    }

    fun setShowEngineeringValidation(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setShowEngineeringValidation(enabled)
        }
    }

    private val _showCategoryErrorDialog = MutableStateFlow(false)
    val showCategoryErrorDialog = _showCategoryErrorDialog.asStateFlow()

    fun dismissCategoryErrorDialog() {
        _showCategoryErrorDialog.value = false
    }

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
            todayProgramDayState = ProgramDayState(
                cycleNumber = 1,
                programDay = 1,
                isWorkoutDay = true,
                isCompleted = false,
                isMissed = false,
                completedAt = null
            )
        )
    )

    // ---- The session configuration boundary --------------------------------------------------------
    // The configuration a running workout session is allowed to generate from. It is captured once,
    // at the session start transition below, and never read again: `programConfigurationRepository`
    // is the user's *future* configuration (an edit there belongs to the next workout), while this is
    // what the session already started with. The Custom Program editor's bridge is the only other
    // consumer of the configuration, and it deliberately touches no session state. The holder itself
    // is pure Kotlin and holds no store, so a later edit, state emission, recomposition or navigation
    // has nothing to rebuild the running session's configuration from.
    private val activeWorkoutConfiguration = ActiveWorkoutConfiguration()

    /** The configuration the running workout session runs on, as the session state presents it. */
    val sessionConfiguration: Flow<WorkoutConfigurationSnapshot?> = activeWorkoutConfiguration.effectiveConfiguration

    // ---- Task 13: the session's adaptive plan ------------------------------------------------------
    // Three things stay apart on purpose, and nothing here blurs them:
    //   * the session's *configuration* is Task 12's frozen snapshot — never the live store;
    //   * the session's *adaptive plan* comes from the session's own reader, derived from that
    //     snapshot plus the persisted adaptive state, and is read once per session (see below);
    //   * the *workout* is built by the existing `WorkoutGenerator`, constrained by that plan.
    // No threshold, state transition or progression step is decided in this class: the adaptive
    // domain decides those through `AdaptiveWorkoutIntegration`, and this view model only supplies
    // its inputs and applies the values it returns — through the existing difficulty mechanism, and
    // through the existing generator.
    private val adaptiveWorkoutIntegration = AdaptiveWorkoutIntegration(workoutGenerator)

    /**
     * The adaptive plan of the running session, or `null` before its configuration is captured.
     *
     * It is derived from `sessionConfiguration`, so it is computed from the session's own frozen
     * snapshot and never from the live configuration store: an edit during the session changes the
     * next session's plan, not this one's. It is held eagerly — one read per session start, the value
     * the session generates from — rather than recomputed per subscriber, so navigating away and back
     * into a running session cannot re-derive (and therefore re-shape) the workout it is presenting.
     */
    private val sessionAdaptivePlan: StateFlow<AdaptiveSessionPlan?> = sessionConfiguration
        .map { snapshot -> snapshot?.let { captured -> readSessionAdaptivePlan(captured) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The adaptive inputs of one session, read once, from the session's captured configuration.
     *
     * Which stored value is the source of which number — the normalized history, the current family
     * progression of the session's program revision — is the reader's business, not this class's. The
     * reader writes nothing, so no generation can move a stored level, and a failed read degrades to
     * no evidence (HOLD for every family) rather than to an invented progression step.
     */
    private suspend fun readSessionAdaptivePlan(
        configuration: WorkoutConfigurationSnapshot
    ): AdaptiveSessionPlan {
        val session = requireNotNull(activeWorkoutConfiguration.activeSession.value)
        val context = requireNotNull(session.context)
        val revision = context.programRevision

        return sessionAdaptivePlanReader.read(
            SessionAdaptiveInputs(
                programDay = session.identity.day,
                programCycle = context.programCycle,
                programType = if (revision == STANDARD_PROGRAM_REVISION) {
                    ProgramType.STANDARD
                } else {
                    ProgramType.REVISED
                },
                programRevision = revision,
                configuration = configuration,
                availableEquipment = context.generation.availableEquipment,
                programStartDate = context.programStartDate
            )
        )
    }

    val workoutSessionUiState = combine(
        currentWorkoutDay,
        currentSessionMode,
        activeWorkoutConfiguration.activeSession,
        sessionConfiguration,
        sessionAdaptivePlan
    ) { day, sessionMode, session, effectiveConfiguration, adaptivePlan ->
        val generation = session?.context?.generation ?: WorkoutSessionGeneration()
        val difficultyAdjustments = generation.difficultyAdjustments
        val trainingType = generation.trainingType
        val focusAreas = generation.focusAreas
        val availableEquipment = generation.availableEquipment
        val disabledFamilies = generation.disabledFamilies
        // The configuration this session runs on: captured at the start transition and immutable
        // from then on, so nothing observed here can be re-pointed by a later edit. It is presented
        // as session state because the session's own behaviour is the only thing allowed to consume it.
        // The adaptive plan of this session, derived from that same captured configuration. A session
        // has no workout until both exist: before the configuration is captured there is no
        // configuration to generate from, and generating one anyway would present a workout this
        // session was never configured for — which is exactly how a disabled exercise reaches a
        // session that never enabled it.
        if (day == null || effectiveConfiguration == null || adaptivePlan == null) {
            WorkoutSessionUiState(
                day = day,
                workout = emptyWorkout,
                warmupExercises = emptyList(),
                isPostureMobilitySession = sessionMode == SessionMode.POSTURE_MOBILITY,
                effectiveConfiguration = effectiveConfiguration
            )
        } else {
            WorkoutSessionUiState(
                day = day,
                workout = if (sessionMode == SessionMode.POSTURE_MOBILITY) {
                    getPostureMobilityWorkout(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies, adaptivePlan)
                } else {
                    getWorkoutForDay(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies, adaptivePlan)
                },
                warmupExercises = if (sessionMode == SessionMode.POSTURE_MOBILITY) {
                    emptyList()
                } else {
                    getWarmupExercises(difficultyAdjustments)
                },
                isPostureMobilitySession = sessionMode == SessionMode.POSTURE_MOBILITY,
                effectiveConfiguration = effectiveConfiguration
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        WorkoutSessionUiState(
            day = null,
            workout = emptyWorkout,
            warmupExercises = emptyList(),
            isPostureMobilitySession = false,
            effectiveConfiguration = null
        )
    )

    val postureUiState = combine(
        exerciseDifficultyAdjustments,
        postureSearchQuery,
        _postureSelectedCategory,
        _postureSelectedSubCategory,
        _expandedFamilyIds,
        availableEquipment,
        filterLibraryByCategories,
        disabledExerciseFamilies,
        showEngineeringValidation
    ) { params ->
        val difficultyAdjustments = params[0] as Map<String, Int>
        val debouncedQuery = params[1] as String
        val selectedCategory = params[2] as ExerciseCategory?
        val selectedSubCategory = params[3] as ExerciseSubCategory?
        val expandedFamilyIds = params[4] as Set<String>
        val availableEquipment = params[5] as Set<Equipment>
        val filterByCategories = params[6] as Boolean
        val disabledFamilies = params[7] as Set<String>
        val engineeringValidationEnabled = params[8] as Boolean

        val exercises = getExerciseLibrary(difficultyAdjustments, availableEquipment, filterByCategories, disabledFamilies)
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

        // --- Engineering Validation: parallel subsystem, fully isolated from the catalog. ---
        // Visible only when the developer setting is ON and not filtered out. The validation
        // poses are NOT added to `filteredExercises`, so they never reach workouts, statistics
        // or the normal exercise search indexing.
        val validationCategory: ValidationCategory?
        val validationPoses: List<ValidationPose>
        if (EngineeringValidationFilter.isVisible(engineeringValidationEnabled, filterByCategories, disabledFamilies)) {
            validationCategory = ValidationCategory()
            validationPoses = if (debouncedQuery.isBlank()) {
                ValidationPoseRegistry.poses
            } else {
                ValidationPoseRegistry.poses.filter { pose ->
                    validationPoseMatchesQuery(pose, debouncedQuery)
                }
            }
        } else {
            validationCategory = null
            validationPoses = emptyList()
        }

        PostureUiState(
            selectedCategory = selectedCategory,
            selectedSubCategory = safeSelectedSubCategory,
            availableSubCategories = availableSubCategories,
            filteredExercises = filteredExercises,
            families = familiesInLibrary,
            exercisesByFamily = exercisesByFamily,
            expandedFamilyIds = expandedFamilyIds,
            validationCategory = validationCategory,
            validationPoses = validationPoses
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

    /**
     * The calendar day's workout as the session presents it.
     *
     * With a [sessionPlan] — the running session's own adaptive plan — generation runs inside that
     * plan's constraints: the exercises the session's captured configuration and equipment permit are
     * the only candidates, the families' resolved variations are preferred among the choices the
     * routine's rules already offer, and the resolved low-level steps are composed with the user's own
     * through the existing difficulty mechanism. Without one — the home preview, the library screens —
     * generation is exactly what it always was.
     */
    fun getWorkoutForDay(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value,
        sessionPlan: AdaptiveSessionPlan? = null
    ): Workout {
        val adjustments = sessionPlan?.effectiveAdjustments(difficultyAdjustments) ?: difficultyAdjustments
        val workout = if (sessionPlan == null) {
            workoutGenerator.generateWorkout(day, trainingType, focusAreas, availableEquipment, disabledFamilies)
        } else {
            adaptiveWorkoutIntegration.generateWorkout(
                AdaptiveWorkoutGenerationRequest(
                    programDay = day,
                    trainingType = trainingType,
                    focusAreas = focusAreas,
                    availableEquipment = availableEquipment,
                    disabledFamilies = disabledFamilies
                ),
                sessionPlan
            )
        }
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, adjustments)) })
    }

    /** The optional posture/mobility routine, constrained exactly as [getWorkoutForDay] is. */
    fun getPostureMobilityWorkout(
        day: Int,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        trainingType: FlexibilityTrainingType = flexibilityTrainingType.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value,
        sessionPlan: AdaptiveSessionPlan? = null
    ): Workout {
        val adjustments = sessionPlan?.effectiveAdjustments(difficultyAdjustments) ?: difficultyAdjustments
        val workout = if (sessionPlan == null) {
            workoutGenerator.generatePostureMobilityWorkout(day, trainingType, focusAreas, availableEquipment, disabledFamilies)
        } else {
            adaptiveWorkoutIntegration.generateWorkout(
                AdaptiveWorkoutGenerationRequest(
                    programDay = day,
                    trainingType = trainingType,
                    focusAreas = focusAreas,
                    availableEquipment = availableEquipment,
                    disabledFamilies = disabledFamilies,
                    isPostureMobilitySession = true
                ),
                sessionPlan
            )
        }
        return workout.copy(exercises = workout.exercises.map { enrichExercise(applyDifficultyAdjustment(it, adjustments)) })
    }

    fun getExerciseLibrary(
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value,
        filterByCategories: Boolean = filterLibraryByCategories.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value
    ): List<Exercise> {
        val baseList = workoutGenerator.getExerciseLibrary(availableEquipment)
        val filteredList = if (filterByCategories) {
            baseList.filter { exercise ->
                val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
                families.isEmpty() || families.none { it.key in disabledFamilies }
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
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        filterByCategories: Boolean = filterLibraryByCategories.value,
        disabledFamilies: Set<String> = disabledExerciseFamilies.value
    ): List<Exercise> {
        val baseList = workoutGenerator.getPostureExercises(focusAreas)
        val filteredList = if (filterByCategories) {
            baseList.filter { exercise ->
                val families = com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id].orEmpty()
                families.isEmpty() || families.none { it.key in disabledFamilies }
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

    /**
     * Looks up a validation pose by id. This lives entirely in the validation subsystem and
     * is deliberately separate from [findExerciseById] (which only searches the exercise catalog).
     * A validation pose is never treated as an [Exercise].
     */
    fun findValidationPoseById(poseId: String): ValidationPose? {
        if (poseId.isBlank()) return null
        return ValidationPoseRegistry.get(poseId)
    }

    /**
     * Case-insensitive, locale-aware search over validation pose names (en / ru / uk),
     * mirroring the exercise library search semantics.
     */
    private fun validationPoseMatchesQuery(pose: ValidationPose, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        val app = getApplication<Application>()
        return listOf("en", "ru", "uk").any { tag ->
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(tag))
            val name = app.createConfigurationContext(configuration).resources.getString(pose.nameRes)
            normalize(name).contains(q)
        }
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
        beginWorkoutSessionConfiguration(day, mode)
    }

    /**
     * The session start transition's configuration step — the one moment a workout's effective
     * configuration is decided.
     *
     * This is the app's authoritative "a workout has started" boundary: it is where the session's day
     * and mode become the state the workout session is generated from. The persisted configuration is
     * read here, once, and frozen for that session; `ActiveWorkoutConfiguration` ignores every later
     * entry for the same (day, mode) without reading anything, so a recomposition or a navigation back
     * into the running session cannot create a second capture, and a later edit to the configuration
     * cannot reach this session. Entering a *different* session captures afresh — that is what makes
     * an edit apply to the next workout rather than to none.
     *
     * The screen performs this on entry (`WorkoutScreen`'s start effect) and the app has no second
     * way to start a session, so this is also the only place any session's configuration can be born.
     */
    private fun beginWorkoutSessionConfiguration(day: Int, mode: SessionMode) {
        viewModelScope.launch {
            activeWorkoutConfiguration.beginSession(
                identity = WorkoutSessionIdentity(
                    day = day,
                    isPostureMobilitySession = mode == SessionMode.POSTURE_MOBILITY
                ),
                readContext = { currentProgramContext() }
            ) { programConfigurationRepository.load() }
        }
    }

    /**
     * The program context the session starting right now runs under: the revision, the calendar and the
     * cycle that are live at this instant, read ONCE, at the start transition, and frozen with the session
     * by `ActiveWorkoutConfiguration`.
     *
     * This is the only place those three values are read for a session, and it exists so that no later part
     * of the session path has to: a session that started before a `Start Revised Program`, a cycle
     * rollover or a configuration edit keeps the context it began under, and finalizing it uses exactly
     * that. Reading them again at completion time is what would let a revised program re-file a finished
     * workout under the new revision and calendar.
     */
    private fun currentProgramContext() = WorkoutSessionContext(
        programCycle = programCycleNumber.value,
        programRevision = programRevision.value,
        programStartDate = parseDate(programStartDate.value, LocalDate.now()),
        generation = WorkoutSessionGeneration(
            availableEquipment = availableEquipment.value,
            difficultyAdjustments = exerciseDifficultyAdjustments.value,
            trainingType = flexibilityTrainingType.value,
            focusAreas = flexibilityFocusAreas.value,
            disabledFamilies = disabledExerciseFamilies.value
        )
    )

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
            val cycle = programCycleNumber.value
            val rewardKey = "workout_${cycle}_$day"
            if (rewardsGrantedDays.value.contains(rewardKey)) {
                // Suppress rewards/completion updates for repeated workouts
                return@launch
            }
            val completedAt = System.currentTimeMillis()
            val progress = UserProgress(
                cycleNumber = cycle,
                day = day,
                isCompleted = true,
                completionDate = completedAt,
                workoutType = workoutGenerator.generateWorkout(day).type.name
            )
            repository.updateProgress(progress)
            repository.upsertProgramDayState(
                ProgramDayState(
                    cycleNumber = cycle,
                    programDay = day,
                    isWorkoutDay = true,
                    isCompleted = true,
                    isMissed = false,
                    completedAt = completedAt
                )
            )
            settingsManager.setRewardGranted(rewardKey)

            // The workout is now finalized: the observation of it is the one the stored rows establish,
            // so this is the only place a decision may be recorded from it.
            recordAdaptiveDecision()
        }
    }

    /**
     * Records the adaptive decisions of the session that has just been finalized.
     *
     * Called only from the daily completion path, after the day-level completion has been persisted, and
     * it hands the recorder the session's own frozen facts — the program day, cycle and revision it ran
     * as, the calendar its history is interpreted against, and the configuration it was STARTED with —
     * never the live revision, the live calendar or the live configuration store. A session that began
     * under one revision and is completed after a `Start Revised Program` is therefore finalized as the
     * session it was, not as the one the app is running now.
     *
     * Nothing is recomputed here: which stored value is the source of which number is the recorder's
     * business, and whether a transition is warranted is the adaptive domain's. A session that is not
     * finalized, a rest day, and a session whose context or configuration was never captured all end in no
     * decision rather than in an invented one.
     *
     * A failure is logged and swallowed on purpose: the workout is already persisted and the user has
     * already been credited for it, so a recording problem must not make a finished session look failed.
     */
    private suspend fun recordAdaptiveDecision() {
        val session = activeWorkoutConfiguration.activeSession.value ?: return
        val request = sessionFinalizationRequest(
            session = session,
            availableEquipment = session.context?.generation?.availableEquipment ?: return
        ) ?: return

        try {
            adaptiveDecisionRecorder.recordFinalizedSession(request)
        } catch (e: Exception) {
            Log.w(TAG, "adaptive decisions not recorded for cycle ${request.programCycle} day ${request.programDay}", e)
        }
    }

    fun completeRecoveryDay(day: Int) {
        viewModelScope.launch {
            val cycle = programCycleNumber.value
            val rewardKey = "recovery_${cycle}_$day"
            if (rewardsGrantedDays.value.contains(rewardKey)) {
                return@launch
            }
            repository.upsertProgramDayState(
                ProgramDayState(
                    cycleNumber = cycle,
                    programDay = day,
                    isWorkoutDay = false,
                    isCompleted = true,
                    isMissed = false,
                    completedAt = System.currentTimeMillis()
                )
            )
            settingsManager.setRewardGranted(rewardKey)
        }
    }

    fun completePostureWorkout(day: Int) {
        viewModelScope.launch {
            val cycle = programCycleNumber.value
            val rewardKey = "posture_${cycle}_$day"
            if (rewardsGrantedDays.value.contains(rewardKey)) {
                return@launch
            }
            val progress = PostureSessionProgress(
                cycleNumber = cycle,
                day = day,
                isCompleted = true,
                completionDate = System.currentTimeMillis(),
                focusArea = flexibilityFocusAreas.value.joinToString(",") { it.name }
            )
            repository.updatePostureProgress(progress)
            settingsManager.setRewardGranted(rewardKey)
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
        programCycleNumber,
        settingsManager.programCycleNumberFlow
    ) { day, state, cycle, storedCycle ->
        // C2: the "program completed" dialog is the automatic-rollover gate — it appears on the
        // last day of a finished cycle and stays visible until the rollover runs (or the user
        // dismisses it). The stamp closes it, so it fires once per cycle; see
        // shouldOfferCycleCompletion.
        shouldOfferCycleCompletion(
            programDay = day,
            isDayCompleted = state.isCompleted,
            activeCycle = cycle,
            storedCycle = storedCycle
        )
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
            // C2: dismissing the completion dialog IS the automatic rollover. The dialog
            // reappears on every relaunch until it runs, so the rollover is effectively
            // guaranteed even if this call is interrupted. Works for ANY cycle N -> N+1.
            val finishedCycle = programCycleNumber.value
            val storedCycle = settingsManager.programCycleNumberFlow.first()
            if (shouldOfferCycleCompletion(
                    programDay = currentProgramDay.value,
                    isDayCompleted = todayProgramDayState.value.isCompleted,
                    activeCycle = finishedCycle,
                    storedCycle = storedCycle
                )
            ) {
                val nextCycle = finishedCycle + 1
                // 1) Seed the next cycle's grid as a fresh copy of the template (nothing
                //    completed, nothing missed) stamped onto the new cycle. The grid is scored
                //    against the new cycle's OWN day 1: scoring it against the finished cycle's
                //    day 56 would mark every earlier day of the new cycle as missed. The
                //    finished cycle's rows — completions AND missed days — stay untouched as
                //    history.
                val freshGrid = synchronizeProgramStates(
                    existing = emptyList(),
                    currentProgramDay = 1,
                    cycleNumber = nextCycle,
                    workoutTypeForDay = ::getWorkoutTypeForDay
                )
                repository.upsertProgramDayStates(freshGrid)

                // 2) Stamp the stored cycle number; programCycleNumber flips to nextCycle
                //    and every cycle-scoped flow re-resolves against it automatically.
                settingsManager.setProgramCycleNumber(nextCycle)
            }
            settingsManager.setProgramSummaryDismissed(true)
        }
    }

    fun dismissNutritionExpirationWarning() {
        viewModelScope.launch {
            settingsManager.dismissNutritionWarningFor(activeMealCycle.value?.startDate)
        }
    }

    // --- C3 Manual Controls (Settings): confirmation-guarded maintenance actions --------

    /**
     * The outcome of a C3 maintenance action. A destructive operation must never end in a
     * state the user cannot distinguish from success, so every path reports either what it
     * changed or the failure that stopped it. See [MaintenanceResult] and [runFullReset].
     */
    private val _maintenanceEvents = MutableSharedFlow<MaintenanceResult>(extraBufferCapacity = 1)
    val maintenanceEvents = _maintenanceEvents.asSharedFlow()

    /**
     * C3 "Restart Current Cycle": wipes ONLY the active cycle's progress rows in one Room
     * transaction. Start date, the stored cycle number, settings and prior cycles' history
     * are untouched; the template grid is re-seeded fresh (nothing completed) by the next
     * [syncProgramDayStates] tick, which runs here so the screen reflects the reset at once.
     *
     * Reports [MaintenanceResult.Failure] if the wipe throws — the transaction rolls back, so
     * no partial reset is left behind, and the user is told instead of seeing a silent no-op.
     */
    fun restartCurrentCycle() {
        viewModelScope.launch {
            val cycle = programCycleNumber.value
            val outcome = try {
                repository.deleteProgressForCycle(cycle)
                refreshCalendarState()
                MaintenanceResult.Success(R.string.program_controls_restart_done)
            } catch (e: Exception) {
                Log.w(TAG, "restartCurrentCycle: cycle $cycle not reset", e)
                MaintenanceResult.Failure(R.string.program_controls_restart_failed)
            }
            _maintenanceEvents.tryEmit(outcome)
        }
    }

    /**
     * C3 "Start Revised Program": bumps the program revision marker and restarts the calendar
     * from today at cycle 1, day 1. The stored cycle number is stamped to 1 in the SAME DataStore
     * edit as the new start date, so no interleaving can leave the two disagreeing. Prior program
     * history stays in the database for the archive view.
     */
    fun startRevisedProgram() {
        viewModelScope.launch {
            val outcome = try {
                settingsManager.startRevisedProgram(LocalDate.now())
                refreshCalendarState()
                MaintenanceResult.Success(R.string.program_controls_revised_done)
            } catch (e: Exception) {
                Log.w(TAG, "startRevisedProgram: not applied", e)
                MaintenanceResult.Failure(R.string.program_controls_revised_failed)
            }
            _maintenanceEvents.tryEmit(outcome)
        }
    }

    /**
     * C3 "Full Reset": complete wipe back to first-launch state — every progress/history Room
     * table cleared, all preferences cleared. Onboarding restarts on next navigation because
     * IS_ONBOARDING_COMPLETED is gone.
     *
     * The Room clear and the DataStore clear are independent stores that cannot share a
     * transaction, so this is an ordered sequence, not an atomic one. The Room wipe runs first
     * and reports [MaintenanceResult.Failure] if it throws, leaving a fully intact database and
     * untouched preferences (the DataStore clear is never reached, so the app keeps working and
     * the user can retry). If the Room wipe succeeds and the preference clear then throws, the
     * outcome is also [MaintenanceResult.Failure] — with a message that names the exact partial
     * state: the database is empty, the preferences are not. A destructive operation must never
     * report success while part of what it promised is still half done.
     */
    fun fullReset() {
        viewModelScope.launch {
            _maintenanceEvents.tryEmit(
                runFullReset(
                    clearRoomData = { repository.clearAllProgressData() },
                    // The program configuration lives in a store of its own (deliberately not the
                    // settings store, which a reset of this kind clears wholesale), so returning the
                    // exercise selection to the authoritative default is this path's own step — through
                    // the configuration repository's own API, which advances nothing else and leaves the
                    // workout history alone.
                    clearPreferences = {
                        settingsManager.clearAll()
                        programConfigurationRepository.resetToDefault()
                    }
                )
            )
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
        val cycle = programCycleNumber.value
        val currentDay = currentProgramDay.value
        // Legacy backfill: rows migrated from before the rollover existed carry no
        // template grid for future cycles. If the active cycle has no grid yet, seed it
        // fresh (nothing completed) without touching earlier cycles' history. The grid is
        // scored against the day the active cycle is actually on, not against the last day of
        // the program — a fresh cycle must not open with days it has not reached yet marked
        // missed.
        if (cycle > 1 && repository.getProgramDayStatesSnapshot(cycle).isEmpty()) {
            val freshGrid = synchronizeProgramStates(
                existing = emptyList(),
                currentProgramDay = currentDay,
                cycleNumber = cycle,
                workoutTypeForDay = ::getWorkoutTypeForDay
            )
            repository.upsertProgramDayStates(freshGrid)
        }
        val legacyProgress = allProgress.value.filter { it.cycleNumber == cycle }.associateBy { it.day }
        val synchronizedStates = synchronizeProgramStates(
            existing = repository.getProgramDayStatesSnapshot(cycle),
            currentProgramDay = currentDay,
            cycleNumber = cycle,
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
        val setLog = observedSetLog(
            exercise = exercise,
            remainingSeconds = _timeLeft.value,
            timestamp = System.currentTimeMillis(),
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
