package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramRow
import com.monkfitness.app.domain.progress.DeferredMeasure
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.progress.hasComparableUnit
import com.monkfitness.app.domain.progress.TrainingFrequency
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.workout.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

data class ProgressHistoryRowUi(
    val plannedFor: LocalDate,
    val status: SessionStatus,
    val performedSets: Int,
    val exposedExercises: Int,
    /** The measured length of the attempt, in seconds, or `null` when it was not measurable. */
    val durationSeconds: Long?,
    /** The Program the attempt belongs to, so an aggregate history says where each row came from. */
    val programName: String
)

/**
 * One exercise's own performance progression, as far as the facts carry it.
 *
 * The *unit* travels with the exercise because it is what makes two observations comparable at all: a
 * repetition count and a duration are never added, averaged or compared against each other.
 */
data class ProgressPerformanceRowUi(
    val exerciseId: String,
    val dimension: PrescriptionDimension,
    val observations: Int,
    val best: Int
)

data class ProgressStreakRowUi(
    val programName: String,
    val current: Int,
    val longest: Int
)

/** Everything the Progress screen shows, read from the Progress layer and nothing else. */
data class ProgramProgressUiState(
    val loading: Boolean = true,
    val hasProgram: Boolean = false,
    val programName: String? = null,
    val lifecycleStatus: LifecycleStatus? = null,
    /** Whether the numbers below are one Program's or the aggregate the layer calls "All Programs". */
    val isAggregate: Boolean = false,
    val completed: Int = 0,
    val missed: Int = 0,
    val upcoming: Int = 0,
    val superseded: Int = 0,
    val frequency: TrainingFrequency? = null,
    val averageSessionSeconds: Double? = null,
    val measuredSessions: Int = 0,
    val streaks: List<ProgressStreakRowUi> = emptyList(),
    val history: List<ProgressHistoryRowUi> = emptyList(),
    val performance: List<ProgressPerformanceRowUi> = emptyList(),
    /** The measures the layer lists that the target facts cannot carry yet, reported as such. */
    val deferred: List<DeferredMeasure> = emptyList(),
    val notice: ProgramNotice? = null
) {

    val opportunityCount: Int
        get() = completed + missed + upcoming + superseded

    /** Whether anything has been planned at all — what a progress bar may be drawn from. */
    val hasOpportunities: Boolean
        get() = opportunityCount > 0

    /** The share of the opportunities that were taken, `0f` when there is nothing to measure. */
    val completedShare: Float
        get() = if (opportunityCount == 0) 0f else completed.toFloat() / opportunityCount.toFloat()
}

/**
 * The Progress screen's state holder: the Progress layer's own measures, read and never recomputed here.
 *
 * The calendar's counts, the frequency, the session duration, the streaks, the attempts themselves and the
 * list of measures that cannot be computed yet all come from [ProgramProgressService].
 *
 * A selected Program is measured on its own; with no selection the screen shows the **All Programs**
 * aggregate, which is a view over every Program's facts and not an entity — so `isAggregate` says which of
 * the two the numbers are instead of the screen having to guess.
 *
 * Nothing here decides what a measure *is*: a threshold, a rate or an average with a second home is a
 * number that can disagree with the layer that owns it.
 */
class ProgramProgressController(
    private val lifecycle: ProgramLifecycleService,
    private val progress: ProgramProgressService
) {

    private val mutableState = MutableStateFlow(ProgramProgressUiState())

    val state: StateFlow<ProgramProgressUiState> = mutableState.asStateFlow()

    /**
     * Reads the selected Program (or the aggregate) and every measure the Progress layer reports.
     *
     * A failure is reported and never rendered as a zero: "you have no completed workouts" and "the history
     * could not be read" are different facts, and a screen that showed the first for the second would be
     * claiming something about the user's training that nothing measured.
     */
    suspend fun load() {
        mutableState.update { state -> state.copy(loading = true, notice = null) }

        val programs = try {
            lifecycle.myPrograms()
        } catch (failure: Throwable) {
            return failed()
        }
        val listed = when (programs) {
            is ProgramOperationResult.Success -> programs.value
            is ProgramOperationResult.Refused -> return empty()
            is ProgramOperationResult.Failure -> return failed()
        }
        val selected: ProgramRow? = listed.selectedRow
        val scope: ProgressScope = selected
            ?.let { row -> ProgressScope.OfProgram(row.programId) }
            ?: ProgressScope.AllPrograms
        val names: Map<ProgramId, String> = listed.rows.associate { row -> row.programId to row.name }

        try {
            val calendar = progress.calendarProgress(scope)
            val training = progress.trainingProgress(scope)
            val history = progress.history(scope, HISTORY_LIMIT)

            mutableState.value = ProgramProgressUiState(
                loading = false,
                hasProgram = listed.rows.isNotEmpty(),
                programName = selected?.name,
                lifecycleStatus = selected?.lifecycleStatus,
                isAggregate = selected == null,
                completed = calendar.completed,
                missed = calendar.missed,
                upcoming = calendar.upcoming,
                superseded = calendar.superseded,
                frequency = training.frequency,
                averageSessionSeconds = training.averageSessionDuration.averageSeconds,
                measuredSessions = training.averageSessionDuration.measuredSessions,
                streaks = training.streaks.map { streak ->
                    ProgressStreakRowUi(
                        programName = names[streak.programId] ?: streak.programId.value,
                        current = streak.current,
                        longest = streak.longest
                    )
                },
                history = history.map { item ->
                    ProgressHistoryRowUi(
                        plannedFor = item.plannedFor,
                        status = item.status,
                        performedSets = item.performedSets,
                        exposedExercises = item.exposedExercises,
                        durationSeconds = item.duration?.seconds,
                        programName = names[item.programId] ?: item.programId.value
                    )
                },
                performance = training.series
                    .filter { series -> series.context.dimension.hasComparableUnit }
                    .map { series ->
                        ProgressPerformanceRowUi(
                            exerciseId = series.context.exerciseId,
                            dimension = series.context.dimension,
                            observations = series.observations.size,
                            best = bestOf(series.observations)
                        )
                    }
                    .sortedByDescending { row -> row.observations },
                deferred = training.deferred
            )
        } catch (failure: Throwable) {
            failed()
        }
    }

    fun dismissNotice() {
        mutableState.update { state -> state.copy(notice = null) }
    }

    /**
     * The best observation of a series, in the series' **own** unit.
     *
     * Not a personal record: `PROGRAM_PR` needs a comparable progression context the facts do not carry, and
     * the layer reports it as deferred for exactly that reason. This is *"the best set of this exercise
     * this Program recorded"*, which is what the observations do support.
     */
    private fun bestOf(observations: List<com.monkfitness.app.domain.progress.PerformanceObservation>): Int =
        observations.maxOfOrNull { observation ->
            if (observation.isRepetitionObservation) observation.repetitions else observation.seconds
        } ?: 0

    private fun empty() {
        mutableState.value = ProgramProgressUiState(loading = false, hasProgram = false)
    }

    private fun failed() {
        mutableState.value = ProgramProgressUiState(
            loading = false,
            notice = ProgramNotice.STORAGE_FAILED
        )
    }

    private companion object {
        /** How many recent attempts the screen lists. More than this is a screen nobody scrolls. */
        const val HISTORY_LIMIT = 20
    }
}
