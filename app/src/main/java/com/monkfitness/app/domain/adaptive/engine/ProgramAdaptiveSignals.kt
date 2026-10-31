package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot

/**
 * The direction comparable history points in. Only ever stated **after** it was measured: too few
 * exposures is `null`, not [STABLE], so "nobody has trained this family enough to say" never becomes
 * "the user is plateaued".
 */
enum class ProgramPerformanceTrend {
    POSITIVE,
    STABLE,
    NEGATIVE
}

/** Attendance over the plan's own opportunities; `null` is a missing opportunity count, not zero attendance. */
enum class ProgramConsistency {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * What the window's comparable exposures add up to, in counts and never in a ratio: a quotient cannot
 * say *whether the shortfall was one set or ten*, and ratio-like questions are answered by
 * cross-multiplying these counts, so nothing is ever rounded into a bucket.
 */
data class ProgramComparableExposure(
    val exposures: Int = 0,
    val fullExposures: Int = 0,
    val prescribedSets: Int = 0,
    val completedSets: Int = 0
) {

    init {
        require(exposures >= 0 && prescribedSets >= 0 && completedSets >= 0) {
            "exposure counts must be >= 0, were exposures=$exposures prescribedSets=$prescribedSets " +
                "completedSets=$completedSets"
        }
        require(fullExposures in 0..exposures) {
            "full exposures must be within the window's own exposures, was $fullExposures of $exposures"
        }
    }

    /**
     * Completed *more* than was prescribed is no shortfall — extra work is the user's own business — and
     * an untrained slot contributes nothing, because that fact simply is not measured.
     */
    val shortfallSets: Int
        get() = (prescribedSets - completedSets).coerceAtLeast(0)

    val everyExposureFull: Boolean
        get() = exposures > 0 && fullExposures == exposures

    val hasShortfall: Boolean
        get() = shortfallSets > 0
}

/**
 * What the signal layer derived for one family in one window.
 *
 * [trend] is `null` when there are too few comparable exposures to state one, never [STABLE];
 * [olderHalf] and [newerHalf] exist so a policy can ask about the **recent end** — a family that fell
 * short weeks ago and completes everything now has a whole-window shortfall and still earned its step.
 */
data class ProgramAdaptiveSignals(
    val familyId: String,
    val exposure: ProgramComparableExposure,
    val trend: ProgramPerformanceTrend?,
    val olderHalf: ProgramComparableExposure?,
    val newerHalf: ProgramComparableExposure?,
    val consistency: ProgramConsistency?,
    val recentContext: ProgramLoadComparison?
) {

    init {
        require(familyId.isNotBlank()) { "signals must name the family they were measured for" }
        require((olderHalf == null) == (newerHalf == null)) {
            "the trend's two halves travel together"
        }
        require((trend == null) == (olderHalf == null)) {
            "a trend is measured between its two halves, so it exists exactly when they do"
        }
        require(recentContext == null || recentContext.channels.isNotEmpty()) {
            "a recent context the caller supplied is a comparison of some channel"
        }
    }

    val recentIsAboveBaseline: Boolean
        get() = recentContext?.isAboveBaseline == true

    val isIdle: Boolean
        get() = exposure.exposures == 0
}

/**
 * The signal layer: derived signals from the frozen facts of one window and nothing else.
 *
 * Observations are grouped by the **family** of the exercise they observed, using the caller's own
 * classification, because the domain owns no exercise catalogue; an exercise the map does not know is
 * its own family, so it is compared with itself and nothing else. The observations are re-ordered by
 * stamp and occurrence identity before anything is counted, so input order cannot change the signals.
 */
object ProgramAdaptiveSignalCalculator {

    fun calculate(
        snapshot: AdaptiveInputSnapshot,
        familyOfExercise: Map<String, String>,
        familyId: String,
        policy: ProgramAdaptivePolicy
    ): ProgramAdaptiveSignals {
        val comparable = snapshot.exposures
            .filter { familyOf(it.exerciseId, familyOfExercise) == familyId }
            .sortedWith(compareBy({ it.startedAt }, { it.sessionExerciseId.value }))
        val readings = comparable.map {
            Reading(completed = it.completedSets, prescribed = it.prescribedSets, full = it.isFull)
        }
        val measurement = measureOf(readings, policy)

        return ProgramAdaptiveSignals(
            familyId = familyId,
            exposure = exposureOf(readings),
            trend = measurement?.trend,
            olderHalf = measurement?.older,
            newerHalf = measurement?.newer,
            consistency = consistencyOf(snapshot, policy),
            recentContext = snapshot.recentLoad?.let { recent ->
                ProgramLoadComparison.of(snapshot.baselineLoad, recent)
            }
        )
    }

    private data class Reading(val completed: Int, val prescribed: Int, val full: Boolean)

    private data class TrendMeasurement(
        val trend: ProgramPerformanceTrend,
        val older: ProgramComparableExposure,
        val newer: ProgramComparableExposure
    )

    private fun exposureOf(readings: List<Reading>): ProgramComparableExposure =
        ProgramComparableExposure(
            exposures = readings.size,
            fullExposures = readings.count { it.full },
            prescribedSets = readings.sumOf { it.prescribed },
            completedSets = readings.sumOf { it.completed }
        )

    /**
     * The halves are compared **exactly** — newer completed against older prescribed, cross-multiplied —
     * so no ratio is materialized and no boundary is decided by rounding. The split is a fixed position,
     * so the trend cannot depend on a rounding choice.
     */
    private fun measureOf(
        readings: List<Reading>,
        policy: ProgramAdaptivePolicy
    ): TrendMeasurement? {
        if (readings.size < policy.trendMinimumExposures) return null
        val older = exposureOf(readings.take(readings.size / 2))
        val newer = exposureOf(readings.drop(readings.size / 2))
        val trend = when {
            newer.completedSets * older.prescribedSets >
                older.completedSets * newer.prescribedSets -> ProgramPerformanceTrend.POSITIVE

            newer.completedSets * older.prescribedSets <
                older.completedSets * newer.prescribedSets -> ProgramPerformanceTrend.NEGATIVE

            else -> ProgramPerformanceTrend.STABLE
        }
        return TrendMeasurement(trend, older, newer)
    }

    /**
     * Decided by cross-multiplying completed opportunities against the policy's own ratios, so the
     * comparison is exact and the thresholds keep one home.
     */
    private fun consistencyOf(
        snapshot: AdaptiveInputSnapshot,
        policy: ProgramAdaptivePolicy
    ): ProgramConsistency? {
        val opportunities = snapshot.baselineLoad.exposure.opportunities
        if (opportunities == 0) return null
        val completed = snapshot.baselineLoad.exposure.completedOpportunities
        return when {
            completed * policy.consistencyHighDenominator >= opportunities * policy.consistencyHighNumerator ->
                ProgramConsistency.HIGH

            completed * policy.consistencyMediumDenominator >= opportunities * policy.consistencyMediumNumerator ->
                ProgramConsistency.MEDIUM

            else -> ProgramConsistency.LOW
        }
    }

/** An exercise the caller did not classify is its own family. */
    private fun familyOf(exerciseId: String, familyOfExercise: Map<String, String>): String =
        familyOfExercise[exerciseId] ?: exerciseId
}
