package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * §21's **Training Progress**, as distinct values that share one scope and one window.
 *
 * §21 separates Calendar Progress from Training Progress, and the blueprint's contracts list separates
 * them further: frequency, average session duration, per-exercise comparable performance, per-context
 * volume, streak and history each have their own semantics and their own scope, and each is its own type
 * here. This value composes them and derives nothing from one to feed another — it exists so a caller
 * asks for "training progress" once and receives the parts, not so a screen can read one number out of it.
 * Every field states its own scope below, because the honest answer to "over what?" differs between them.
 *
 * ### What scopes what
 *
 * | measure | scope |
 * | --- | --- |
 * | [frequency] | the scope's **completed** workouts inside [window] |
 * | [averageSessionDuration] | the same completed workouts, measured on their actual start and finish |
 * | [series] and [volumes] | the scope's **whole** recorded history, one entry per comparable context |
 * | [streaks] | the scope's whole recorded history, **per Program** |
 * | [deferred] | nothing — these are the measures this layer does not compute, and says so |
 *
 * The window is not applied to the history-derived measures on purpose: a performance series and a
 * volume are cumulative descriptions of what has been recorded, and a streak is history by definition,
 * while a rate is only readable against a span. Freezing both kinds into one window would make
 * "volume" mean "volume in the last 28 days" without saying so.
 *
 * @property scope what this progress is about — one Program, or the aggregate (§21).
 * @property window the calendar range the rate-like measures are computed over.
 * @property frequency how often the scope's workouts happened inside the window.
 * @property averageSessionDuration their mean actual duration.
 * @property series the comparable performance history, one series per exercise-and-unit context.
 * @property volumes the accumulated volume, one entry per context that has one.
 * @property streaks the opportunity runs, one entry per Program in the facts.
 * @property deferred the §21 measures this layer deliberately does not compute, with the reason.
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

    /** The comparable performance history of one exercise in one unit, or `null` when it has none. */
    fun seriesOf(exerciseId: String, dimension: PrescriptionDimension): ExercisePerformanceSeries? =
        series.firstOrNull { it.context == ComparableContext(exerciseId, dimension) }

    /** The accumulated volume of one exercise in one unit, or `null` when it has none. */
    fun volumeOf(exerciseId: String, dimension: PrescriptionDimension): ContextVolume? =
        volumes.firstOrNull { it.context == ComparableContext(exerciseId, dimension) }

    /** The streak of one Program, or `null` when the scope knows nothing about that Program. */
    fun streakOf(programId: ProgramId): ProgramStreak? =
        streaks.firstOrNull { it.programId == programId }

    /** Whether a §21 measure was reported as deferred rather than computed. */
    fun isDeferred(measure: ProgressMeasure): Boolean = deferred.any { it.measure == measure }

    companion object {

        /**
         * The order of the context-keyed lists: exercise first (so a screen groups by exercise), then the
         * dimension's own declaration order. Both are values of the facts, so the order is a function of
         * the data rather than of iteration or insertion.
         */
        val ORDER: Comparator<ExercisePerformanceSeries> =
            compareBy({ it.context.exerciseId }, { it.context.dimension.ordinal })

        /** The same order for the volumes, which are keyed by the same contexts. */
        val VOLUME_ORDER: Comparator<ContextVolume> =
            compareBy({ it.context.exerciseId }, { it.context.dimension.ordinal })
    }
}
