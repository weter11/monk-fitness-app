package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * Training Progress, as distinct values that share one scope and one window. Each measure has its own
 * semantics and its own scope, and each is its own type: this value composes them and derives nothing
 * from one to feed another — it exists so a caller asks for "training progress" once and receives the
 * parts, not so a screen can read one number out of it.
 *
 * [frequency] and [averageSessionDuration] cover the scope's completed workouts inside [window];
 * [series], [volumes] and [streaks] cover the scope's whole recorded history (streaks per Program);
 * [deferred] is nothing, because those measures are not computed and the value says so.
 *
 * The window is deliberately not applied to the history-derived measures: a series and a volume are
 * cumulative descriptions of what has been recorded, while a rate is only readable against a span.
 * Freezing both into one window would make "volume" mean "volume in the last 28 days" without saying so.
 */
data class TrainingProgress(
    val scope: ProgressScope,
    val window: ProgressWindow,
    val frequency: TrainingFrequency,
    val averageSessionDuration: AverageSessionDuration,
    val series: List<ExercisePerformanceSeries>,
    val volumes: List<ContextVolume>,
    val streaks: List<ProgramStreak>,
    val deferred: List<DeferredMeasure>
) {

    init {
        require(streaks.map { it.programId }.toSet().size == streaks.size) {
            "a streak is about one Program, and a Program has one streak: " +
                "${streaks.map { it.programId.value }}"
        }
        require(streaks == streaks.sortedBy { it.programId.value }) {
            "the streaks are in a deterministic order: ${streaks.map { it.programId.value }}"
        }
        require(series.map { it.context }.toSet().size == series.size) {
            "one series per comparable context: ${series.map { it.context }}"
        }
        require(series == series.sortedWith(ORDER)) {
            "the series are in a deterministic order: ${series.map { it.context }}"
        }
        require(volumes.map { it.context }.toSet().size == volumes.size) {
            "one volume per comparable context: ${volumes.map { it.context }}"
        }
        require(volumes == volumes.sortedWith(VOLUME_ORDER)) {
            "the volumes are in a deterministic order: ${volumes.map { it.context }}"
        }
        require(deferred.map { it.measure }.toSet().size == deferred.size) {
            "a measure is reported as deferred once: ${deferred.map { it.measure }}"
        }
        require(frequency.window == window) {
            "the rate-like measures share one window: frequency=${frequency.window} window=$window"
        }
    }

    fun seriesOf(exerciseId: String, dimension: PrescriptionDimension): ExercisePerformanceSeries? =
        series.firstOrNull { it.context == ComparableContext(exerciseId, dimension) }

    fun volumeOf(exerciseId: String, dimension: PrescriptionDimension): ContextVolume? =
        volumes.firstOrNull { it.context == ComparableContext(exerciseId, dimension) }

    fun streakOf(programId: ProgramId): ProgramStreak? =
        streaks.firstOrNull { it.programId == programId }

    fun isDeferred(measure: ProgressMeasure): Boolean = deferred.any { it.measure == measure }

    companion object {

        /**
         * Exercise first (so a screen groups by exercise), then the dimension's declaration order — both
         * values of the facts, so the order is a function of the data, not of iteration or insertion.
         */
        val ORDER: Comparator<ExercisePerformanceSeries> =
            compareBy({ it.context.exerciseId }, { it.context.dimension.ordinal })

        /** The same order, keyed by the same contexts. */
        val VOLUME_ORDER: Comparator<ContextVolume> =
            compareBy({ it.context.exerciseId }, { it.context.dimension.ordinal })
    }
}
