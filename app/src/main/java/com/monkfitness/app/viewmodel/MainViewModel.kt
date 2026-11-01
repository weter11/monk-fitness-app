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
import com.monkfitness.app.data.model.UserPreferences
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.data.model.applyDifficultyAdjustment
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionExclusionIngredients
import com.monkfitness.app.data.model.toMealEntities
import com.monkfitness.app.data.model.toShoppingItemEntities
import com.monkfitness.app.data.model.validateAvailableProductSelection
import com.monkfitness.app.data.repository.NutritionRepository
import com.monkfitness.app.data.repository.PostureRepository
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.language.AppLanguage
import com.monkfitness.app.language.AppLanguageManager
import com.monkfitness.app.platform.ProgramShareSheet
import com.monkfitness.app.domain.track.TrackCalendar
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.ui.programs.ExerciseOptionUi
import com.monkfitness.app.ui.programs.FamilyCatalogue
import com.monkfitness.app.ui.programs.ProgramHomeController
import com.monkfitness.app.ui.programs.ProgramHomeUiState
import com.monkfitness.app.ui.programs.ProgramProgressController
import com.monkfitness.app.ui.programs.ProgramProgressUiState
import com.monkfitness.app.ui.programs.ProgramSessionController
import com.monkfitness.app.ui.programs.ProgramsController
import com.monkfitness.app.ui.programs.SessionRestPreference
import com.monkfitness.app.validation.EngineeringValidationFilter
import com.monkfitness.app.validation.ValidationCategory
import com.monkfitness.app.validation.ValidationPose
import com.monkfitness.app.validation.ValidationSettings
import com.monkfitness.app.validation.ValidationPoseRegistry
import com.monkfitness.app.util.normalize
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

    private val workoutGenerator = WorkoutGenerator()

    /**
     * The settings store plus the two retained global stores — the nutrition tables and the posture /
     * mobility track. Both reach storage through the composition root's database, never a Program
     * repository, and neither is keyed by a cycle or a program day.
     */
    val settingsManager: SettingsManager

    /**
     * The app's one owner of language selection: it writes the choice as the *application locale* and
     * reads it back, so Android's per-app language screen and Settings are the same state, not two.
     */
    private val appLanguageManager: AppLanguageManager =
        (application as MonkFitnessApplication).appLanguageManager

    /** The nutrition domain's storage, and the body-weight log its targets are computed from. */
    private val nutritionRepository: NutritionRepository

    /** The retained posture / mobility track: its own rows, on its own 56-day calendar. */
    private val postureRepository: PostureRepository

    /** The anchor the retained daily tracks keep their 56-day rhythm from, parsed once. */
    private val trackStartDate: Flow<LocalDate>

    /** Home's Program state: the selection, its next opportunity and its calendar. */
    private val homeProgram: ProgramHomeController

    /** The Progress screen's Program state: its measures and history. */
    private val programProgress: ProgramProgressController

    /** The one production workout runtime's UI half. */
    val programSession: ProgramSessionController

    init {
        val container = (application as MonkFitnessApplication).container
        val settings = SettingsManager(application)
        settingsManager = settings
        trackStartDate = settings.trackStartDateFlow.map { raw ->
            runCatching { LocalDate.parse(raw) }.getOrDefault(LocalDate.now())
        }
        nutritionRepository = NutritionRepository(container.database.nutritionDao())
        postureRepository = PostureRepository(
            dao = container.database.postureProgressDao(),
            trackStartDate = trackStartDate,
            zone = container.zone,
            clock = container.clock
        )
        homeProgram = ProgramHomeController(
            lifecycle = container.programLifecycleService,
            scheduler = container.programScheduler,
            progress = container.programProgressService
        )
        programProgress = ProgramProgressController(
            lifecycle = container.programLifecycleService,
            progress = container.programProgressService
        )
        programSession = ProgramSessionController(
            runtime = container.sessionRuntime,
            adaptive = container.programAdaptiveIntegration,
            lifecycle = container.programLifecycleService,
            catalogue = {
                workoutGenerator.getExerciseLibrary().map { exercise ->
                    ExerciseOptionUi(
                        exerciseId = exercise.id,
                        nameRes = exercise.nameRes,
                        familyId = exercise.familyId,
                        isTimerBased = exercise.isTimerBased
                    )
                }
            },
            families = FamilyCatalogue {
                workoutGenerator.families.associate { family -> family.id to family.nameRes }
            },
            restPreference = object : SessionRestPreference {
                override suspend fun read(): Int? = settings.sessionRestSecondsFlow.first()

                override suspend fun remember(seconds: Int) = settings.setSessionRestSeconds(seconds)
            }
        )
        viewModelScope.launch { settings.ensureTrackStartDate() }
    }

    companion object {
        const val ROUTE_HOME = "home"

        /**
         * One cycle of the retained daily-track calendar, in days — the posture track's own rhythm, **not**
         * a length of any Program (a Program's plan is as long as its revision says).
         */
        const val POSTURE_TRACK_DAYS = TrackCalendar.TRACK_DAYS
        const val ROUTE_NUTRITION = "nutrition"

        const val ROUTE_CUSTOM_PROGRAM = "custom-program"

        /**
         * The Program System's destinations. They live here rather than in the Program UI package because
         * `MainActivity` declares every destination in one place, and a route is a stable identifier,
         * never user-visible copy a screen builds from text.
         */
        const val ROUTE_PROGRAMS = "programs"

        const val ROUTE_MY_PROGRAMS = "programs/my-programs"

        /** Program Detail, addressed by the Program's own stable id. */
        const val ROUTE_PROGRAM_DETAIL = "programs/detail/{programId}"

        /** The destination offering the two entry paths: build it myself / build for me. */
        const val ROUTE_PROGRAM_CREATE = "programs/create"

        /** An editor session, addressed by which entry point opened it. */
        const val ROUTE_PROGRAM_EDITOR_CREATE = "programs/editor/create/{mode}"
        const val ROUTE_PROGRAM_EDITOR_EDIT = "programs/editor/edit/{programId}"
        const val ROUTE_PROGRAM_EDITOR_COPY = "programs/editor/copy/{programId}"

        const val ROUTE_PROGRAM_IMPORT = "programs/import"

        /**
         * The app's one workout route, identified by the opportunity it is for: a day number was never an
         * identity (two programs' workouts could not be told apart by it).
         */
        const val ROUTE_PROGRAM_SESSION = "programs/session/{slotId}"

        fun programSessionRoute(slotId: String): String = "programs/session/$slotId"

        fun programDetailRoute(programId: String): String = "programs/detail/$programId"

        fun programEditorCreateRoute(mode: String): String = "programs/editor/create/$mode"

        fun programEditorEditRoute(programId: String): String = "programs/editor/edit/$programId"

        fun programEditorCopyRoute(programId: String): String = "programs/editor/copy/$programId"

        /** The first program day, used only before a session's own day is established. */
        private const val FIRST_PROGRAM_DAY = 1

        /**
         * The revision of the program as first started, matching the persisted family progression's
         * revision column and `SettingsManager.PROGRAM_REVISION`; a revision above it means revised.
         */
        private const val STANDARD_PROGRAM_REVISION = 0

        private const val TAG = "MainViewModel"
    }

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

    private val _timeLeft = MutableStateFlow(0)
    val timeLeft = _timeLeft.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning = _isTimerRunning.asStateFlow()

    private var timerJob: Job? = null
    private var endTimeMillis: Long = 0

    private val _currentDate = MutableStateFlow(LocalDate.now())
    val currentDate = _currentDate.asStateFlow()
    private val _nutritionMessageEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val nutritionMessageEvents = _nutritionMessageEvents.asSharedFlow()

    /**
     * The 1-based day of the retained 56-day daily-track calendar, capped at its length. It is the number
     * the nutrition planner phases its meal rotation by, and **not** a Program's day: it belongs to the
     * daily tracks ([com.monkfitness.app.domain.track.TrackCalendar]).
     */
    val currentTrackDay = combine(trackStartDate, currentDate) { anchor, today ->
        TrackCalendar.cappedDay(anchor, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val nutritionWarningDismissedFor = settingsManager.nutritionWarningDismissedForFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    // The programme day is a pure function of the calendar: one calendar date is one programme day. The
    // stamped cycle number deliberately does NOT move the day shown — letting it start the next cycle
    // early would put cycle N+1 day 1 on two dates and demote the real day 1 to a no-credit repeat.
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

    val bodyWeightHistory = nutritionRepository.getBodyWeightEntriesSince(
        LocalDate.now().minusDays(89).toString()
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val latestBodyWeight = bodyWeightHistory
        .map { history -> history.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The posture / mobility track's rows for its current 56-day cycle. */
    val postureProgress = postureRepository.progressOfCurrentCycle().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    /** How many days of the posture track's current cycle are completed. */
    val postureCompletedCount = postureRepository.completedCountOfCurrentCycle().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val libraryStats = flowOf(workoutGenerator.getLibraryStats()).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        LibraryStats(0, 0, 0, 0, 0, 0)
    )

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

    // The Program screens' state holder owns the Program UI's state and calls the application services;
    // this view model only hands it what the composition root built. Nothing here decides anything about
    // a Program — no selection written, no revision minted, no date chosen, no JSON produced.
    private val programGraph = (application as MonkFitnessApplication).container

    /** The Program screens' state holder, rendered by the Program destinations of the one navigation graph. */
    val programs = ProgramsController(
        lifecycle = programGraph.programLifecycleService,
        starter = programGraph.programStartService,
        editor = programGraph.programEditorService,
        saver = programGraph.programSaveService,
        importer = programGraph.programImportService,
        exporter = programGraph.programExportService,
        progress = programGraph.programProgressService,
        scheduler = programGraph.programScheduler,
        generation = programGraph.programGenerationService,
        // The user's declared equipment, read at the moment of a generation pass and forwarded
        // verbatim. An empty set means the user declared none — deliberately not "unconstrained",
        // which would offer bar and band exercises to someone who owns none of either.
        availableEquipment = { availableEquipment.value },
        catalogue = {
            workoutGenerator.getExerciseLibrary().map { exercise ->
                ExerciseOptionUi(
                    exerciseId = exercise.id,
                    nameRes = exercise.nameRes,
                    familyId = exercise.familyId,
                    isTimerBased = exercise.isTimerBased
                )
            }
        },
        shareTarget = { file -> ProgramShareSheet.share(application, file) },
        clock = programGraph.clock,
        zone = programGraph.zone
    )

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

    // Home shows the selected Program and its next **opportunity**, read through the application
    // services the composition root built; this view model only hands the state holder over.

    val homeProgramState: StateFlow<ProgramHomeUiState> = homeProgram.state

    /** Re-reads Home's Program state. Called when the screen appears and after a session completes. */
    fun refreshHomeProgram() {
        viewModelScope.launch { homeProgram.load() }
    }

    fun dismissHomeNotice() = homeProgram.dismissNotice()

    /** The Progress screen's measures and history, as it renders them. */
    val programProgressState: StateFlow<ProgramProgressUiState> = programProgress.state

    /** Re-reads the Progress screen's measures. Called when the screen appears. */
    fun refreshProgress() {
        viewModelScope.launch { programProgress.load() }
    }

    fun dismissProgressNotice() = programProgress.dismissNotice()

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

        // Visible only when the developer setting is ON and not filtered out. Validation poses are NOT
        // added to `filteredExercises`, so they never reach workouts, statistics or exercise search.
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

    /**
     * Looks one exercise up in the app's own catalogue by its library id. The catalogue is the same list
     * on every day, and a session links to an exercise by the id its plan element stores.
     */
    fun findExerciseById(
        exerciseId: String,
        difficultyAdjustments: Map<String, Int> = exerciseDifficultyAdjustments.value,
        focusAreas: Set<ExerciseSubCategory> = flexibilityFocusAreas.value,
        availableEquipment: Set<Equipment> = this.availableEquipment.value
    ): Exercise? {
        if (exerciseId.isBlank()) return null
        // Every exercise comes from the catalog, so these three sources cover all of them: generation
        // never creates inline exercises with unique ids.
        return getExerciseLibrary(difficultyAdjustments, availableEquipment).find { it.id == exerciseId }
            ?: getPostureExercises(difficultyAdjustments, focusAreas).find { it.id == exerciseId }
            ?: getWarmupExercises(difficultyAdjustments).find { it.id == exerciseId }
    }

    /**
     * Deliberately separate from [findExerciseById], which only searches the exercise catalog: a
     * validation pose is never treated as an [Exercise].
     */
    fun findValidationPoseById(poseId: String): ValidationPose? {
        if (poseId.isBlank()) return null
        return ValidationPoseRegistry.get(poseId)
    }

    /**
     * Case-insensitive, locale-aware search over validation pose names (en / ru / uk), mirroring the
     * exercise library search semantics.
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

    /**
     * The optional posture / mobility routine for the track's current day, shaped only by the generator's
     * own selection rules — no adaptive overlay. It is a presentation: not stored, belonging to no
     * Program, and completing it writes one row of the track's own table rather than a session or set log.
     */
    fun postureMobilityWorkout(): Workout {
        val workout = workoutGenerator.generatePostureMobilityWorkout(
            day = currentTrackDay.value,
            flexibilityTrainingType = flexibilityTrainingType.value,
            focusAreas = flexibilityFocusAreas.value,
            availableEquipment = availableEquipment.value,
            disabledFamilies = disabledExerciseFamilies.value
        )
        return workout.copy(
            exercises = workout.exercises.map { exercise -> enrichExercise(exercise) }
        )
    }

    /**
     * One row of the track's own table and nothing else — no set log, no session, no opportunity. The
     * track resolves *which* day this is from its own calendar.
     */
    fun completePostureWorkout() {
        viewModelScope.launch {
            postureRepository.markCompleted(
                focusArea = flexibilityFocusAreas.value.joinToString(",") { it.name }
            )
        }
    }

    fun logBodyWeight(kg: Float) {
        if (!kg.isFinite() || kg !in 30f..300f) {
            _bodyWeightErrorEvents.tryEmit(getApplication<Application>().getString(R.string.body_weight_validation_error))
            return
        }

        viewModelScope.launch {
            nutritionRepository.insertBodyWeightEntry(
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
                startDay = TrackCalendar.cappedDay(trackStartDate.first(), cycleStartDate),
                daysCount = safeDuration,
                weightKg = nutritionWeight.value.toIntOrNull(),
                heightCm = nutritionHeight.value.toIntOrNull(),
                excludedIngredientKeys = nutritionExcludedFoods.value,
                preferredIngredientKeys = validPreferredKeys,
                cycleId = dummyCycleId,
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
            val storedCycleId = nutritionRepository.insertMealCycle(baseCycle)
            val cycleId = if (storedCycleId == 0L) baseCycle.id else storedCycleId

            val finalPlan = plan.copy(
                cycleId = cycleId,
                days = plan.days.map { day ->
                    day.copy(
                        meals = day.meals.map { it.copy(cycleId = cycleId) }
                    )
                }
            )

            nutritionRepository.replaceCycleMeals(cycleId, finalPlan.toMealEntities(cycleId), finalPlan.toShoppingItemEntities(cycleId))
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

                    playBeep(500)
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

    fun setNotificationTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsManager.setNotificationTime(hour, minute)
            NotificationScheduler.scheduleDailyReminder(getApplication(), hour, minute)
        }
    }

    /** The language the user has selected; [AppLanguage.SYSTEM_DEFAULT] when no override is applied. */
    fun currentAppLanguage(): AppLanguage = appLanguageManager.selection()

    /**
     * [AppLanguage.SYSTEM_DEFAULT] clears the application locale so the app follows the device again; the
     * platform recreates the Activity, which is what makes every screen follow the change.
     */
    fun selectAppLanguage(language: AppLanguage) = appLanguageManager.select(language)

    /**
     * Triggered by `MainActivity.onCreate` before the first composition. It applies and writes nothing when
     * this install never stored a language, which keeps a user who never chose one on the system language.
     */
    suspend fun migrateLanguageSelectionIfNeeded() = appLanguageManager.migrateLegacySelection()

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
                // Everything would be disabled: keep the single last remaining enabled category.
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

    val mealCycles = nutritionRepository.getMealCycles().stateIn(
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
            nutritionRepository.getMealsForCycle(cycle.id).map { meals ->
                if (meals.isEmpty()) NutritionPlan(emptyList(), cycle.id)
                else mealEntitiesToNutritionPlan(cycle.id, meals)
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NutritionPlan(emptyList())
    )

    val todayNutritionPlan = combine(nutritionPlan, currentTrackDay) { plan, day ->
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

    /**
     * Deliberately the **last** thing in this class's initialisation order: `viewModelScope` dispatches on
     * `Dispatchers.Main.immediate`, so this `launch` runs synchronously during construction up to its first
     * suspension point, and every property `syncNutritionCycles()` reads must already be initialised.
     * Reading a `val` before its initializer runs is a silent null — the lambda hides the read from flow
     * analysis — so the position is load-bearing. `MainViewModelInitializationOrderTest` asserts it.
     */
    init {
        viewModelScope.launch {
            syncNutritionCycles()
        }
        viewModelScope.launch {
            while (isActive) {
                val today = LocalDate.now()
                if (_currentDate.value != today) {
                    _currentDate.value = today
                }
                syncNutritionCycles()
                delay(60_000)
            }
        }
    }

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

    fun dismissNutritionExpirationWarning() {
        viewModelScope.launch {
            settingsManager.dismissNutritionWarningFor(activeMealCycle.value?.startDate)
        }
    }

    /** Emits every maintenance outcome so the screen can report success or the exact failure. */
    private val _maintenanceEvents = MutableSharedFlow<MaintenanceResult>(extraBufferCapacity = 1)
    val maintenanceEvents = _maintenanceEvents.asSharedFlow()

    /**
     * Settings → **Full reset**: the app's history and retained daily-track data, wiped in one Room
     * transaction, then the preferences.
     *
     * Cleared: every user-created Program, opportunity, session, snapshot, confirmed set, pause and target
     * adaptive row, plus the posture track and body-weight log. Kept: the built-in Standard Program
     * definition (the lifecycle delete-fallback selects it) and the nutrition plans, whose meal-cycle
     * calendar is independent of the program calendar.
     *
     * Reports Failure if a phase throws — the transaction rolls back, so no partial reset is left behind.
     */
    fun fullReset() {
        viewModelScope.launch {
            val result = runFullReset(
                clearRoomData = { programGraph.maintenanceRepository.clearAll(StandardProgram.programId.value) },
                clearPreferences = { settingsManager.clearAll() }
            )
            _maintenanceEvents.tryEmit(result)
            homeProgram.load()
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
            nutritionRepository.replaceCycleMeals(cycle.id, updatedPlan.toMealEntities(cycle.id), updatedPlan.toShoppingItemEntities(cycle.id))
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

    private suspend fun syncNutritionCycles() {
        if (nutritionCycleLength.value == 0) return

        val today = currentDate.value
        val cycles = nutritionRepository.getMealCyclesSnapshot()
        val expiredCycles = cycles.filter { !it.isCompleted && mealCycleEndDate(it).isBefore(today) }
        expiredCycles.forEach { expired ->
            nutritionRepository.insertMealCycle(expired.copy(isCompleted = true))
        }

        val refreshedCycles = nutritionRepository.getMealCyclesSnapshot()
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
        val storedCycleId = nutritionRepository.insertMealCycle(baseCycle)
        val cycleId = if (storedCycleId == 0L) baseCycle.id else storedCycleId
        val validPreferredKeys = if (validateAvailableProductSelection(preferredIngredientKeys) == null) preferredIngredientKeys else emptySet()
        val plan = generateNutritionPlan(
            seed = cycleStartDate.toEpochDay().toInt(),
            startDay = TrackCalendar.cappedDay(trackStartDate.first(), cycleStartDate),
            daysCount = safeDuration,
            weightKg = nutritionWeight.value.toIntOrNull(),
            heightCm = nutritionHeight.value.toIntOrNull(),
            excludedIngredientKeys = nutritionExcludedFoods.value,
            preferredIngredientKeys = validPreferredKeys,
            cycleId = cycleId,
        )
        nutritionRepository.replaceCycleMeals(cycleId, plan.toMealEntities(cycleId), plan.toShoppingItemEntities(cycleId))
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

    private fun currentSessionDate(): String = LocalDate.now().toString()

    override fun onCleared() {
        super.onCleared()
        toneG.release()
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
