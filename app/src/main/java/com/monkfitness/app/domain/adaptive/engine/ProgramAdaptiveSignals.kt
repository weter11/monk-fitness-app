package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot

/**
 * The direction a family's comparable history points in (§13's *performance trend*).
 *
 * A trend is only ever [POSITIVE], [STABLE] or [NEGATIVE] **after** it has been measured. An
 * unmeasured trend is not represented by one of these values: it is the absence of a trend, and the
 * signal layer reports it as `null`. That absence is the whole difference between *"the user is
 * plateaued"* and *"nobody has trained this family enough to say"* (§12: missing evidence stays
 * missing), and collapsing the two would let a family with no history be treated as a stable one, or a
 * stable one as a direction it never earned.
 */
enum class ProgramPerformanceTrend {
    POSITIVE,
    STABLE,
    NEGATIVE
}

/**
 * How much of the window's opportunity the user actually attended (§13's *consistency*).
 *
 * Attendance is reported over the **opportunities the plan offered**, which is a fact about the plan
 * and the calendar: a low bucket says opportunities went untaken, and it says nothing about ability.
 * `null` is the absence of an opportunity count, not a zero-attendance reading.
 */
enum class ProgramConsistency {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * What the window's comparable exposures add up to, in counts and never in a ratio (§12, §13).
 *
 * These four integers are §13's hard facts — planned sets, completed sets and the exposure they came
 * from — kept exactly as counted. There is deliberately no `completionRatio` here and no percentage:
 * a ratio would be a derived number that cannot say *whether the shortfall was one set or ten*, and the
 * policies that decide on this value need the count, not the quotient. Comparisons between two windows
 * are made by cross-multiplying the counts where a ratio-like question is asked, so no measurement is
 * ever rounded into a bucket.
 *
 * The two exposure counts are counts of **occurrences that happened**. A skipped exercise and a missed
 * slot contribute nothing here — not a zero, not a failure — because no observation exists for them
 * (§12).
 *
 * @property exposures comparable occurrences in the window.
 * @property fullExposures how many of them executed the whole prescription.
 * @property prescribedSets sets presented across those occurrences.
 * @property completedSets sets actually completed across them.
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
     * Sets that were presented and did not happen, counted in sets.
     *
     * A user who completed *more* than was prescribed has no shortfall: extra work is the user's own
     * business and never a negative reading (§12). Missing observations contribute nothing here, so a
     * slot that was never trained does not add to a shortfall — that fact simply is not measured.
     */
    val shortfallSets: Int
        get() = (prescribedSets - completedSets).coerceAtLeast(0)

    /** Whether every comparable occurrence executed the whole prescription. */
    val everyExposureFull: Boolean
        get() = exposures > 0 && fullExposures == exposures

    /** Whether at least one comparable occurrence fell short, by any amount. */
    val hasShortfall: Boolean
        get() = shortfallSets > 0
}

/**
 * What the signal layer derived for one family in one decision window (§13).
 *
 * Every field is either a **measured** value or an explicit absence:
 *
 *  * [exposure] is the window's comparable history, in counts;
 *  * [trend] is `null` when the window holds too few comparable exposures to state a direction — never
 *    [ProgramPerformanceTrend.STABLE], which would claim a plateau nobody measured, and never
 *    `NEGATIVE`, which would turn *"we do not know"* into *"it went badly"* (§8);
 *  * [olderHalf] and [newerHalf] are the two sides the trend was measured between, reported so that a
 *    policy can ask about **the recent end** rather than about the whole window. That distinction is
 *    load-bearing: a family that under-performed three weeks ago and completes everything now has a
 *    whole-window shortfall and has still earned its next step, and only the newer half can tell those
 *    two readings apart. Both are `null` exactly when [trend] is — they are the trend's basis and have
 *    no meaning without it;
 *  * [consistency] is `null` when the caller stated no opportunity count at all;
 *  * [recentContext] is `null` when the caller supplied no recent load, and otherwise the channel-by-
 *    channel comparison of the recent past against what the plan itself prescribes. It is a
 *    [ProgramLoadComparison] and not a bucket, because §7's *"recent load not HIGH"* is a statement
 *    about individual channels: recent work above the plan's own prescription on **any** channel it can
 *    be compared on is a context that is already carrying more than the plan asks for.
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

    /** Whether the recent context is above the plan's own prescription on any comparable channel. */
    val recentIsAboveBaseline: Boolean
        get() = recentContext?.isAboveBaseline == true

    /** Whether no comparable exposure was measured in this window at all. */
    val isIdle: Boolean
        get() = exposure.exposures == 0
}

/**
 * The signal layer of the target adaptive stage: §13's derived signals, from the frozen facts of one
 * window and nothing else.
 *
 * ```text
 * AdaptiveInputSnapshot (observations + the caller's context)  →  ProgramAdaptiveSignals
 * ```
 *
 * ### Comparability
 *
 * Observations are grouped by the **family** of the exercise they observed, using the caller's own
 * exercise-to-family classification — the same shape the pre-existing signal layer uses, because the
 * domain owns no exercise catalogue and resolving one here would be reaching for the library. An
 * exercise the map does not know is its own family, which is the conservative reading: it is compared
 * with itself and nothing else.
 *
 * Family membership is also §12's *"compatible exercise/progression context"*: the variants of one
 * family are positions of one progression hierarchy (§15), which is exactly what makes them comparable
 * with each other — and what makes two different families not comparable, so this layer never mixes
 * them and never totals across them.
 *
 * ### Determinism
 *
 * The observations are re-ordered by their own stamp and their occurrence identity before anything is
 * counted, so a caller that hands the same observations in a different order gets the same signals.
 * The window is split into its older and its newer half at a fixed position (the older half takes the
 * smaller half when the count is odd), so the trend is a function of the window and not of a rounding
 * choice. No clock, no random source, no hash order and no storage is read anywhere.
 *
 * ### What it refuses to do
 *
 * A missed opportunity is not an exposure; a cancelled session's partial work is an exposure but
 * never a full one; an untrained window produces *no* trend rather than a negative one; and the
 * comparison between two windows is exact integer arithmetic — new exposures' completion against old
 * exposures' completion, cross-multiplied, without a division and without floating point.
 */
object ProgramAdaptiveSignalCalculator {

    /**
     * Derives the signals for [familyId] from [snapshot].
     *
     * @param snapshot the window's frozen facts.
     * @param familyOfExercise the caller's exercise-to-family classification.
     * @param familyId the family to measure — the family of the element being decided on.
     * @param policy the policy whose two bucketing thresholds and trend minimum this layer reads, so
     *   that no threshold is re-derived here.
     */
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

    /** One comparable occurrence's own reading: what it prescribed, what it completed, and its level. */
    private data class Reading(val completed: Int, val prescribed: Int, val full: Boolean)

    /** The trend and the two halves it was measured between. */
    private data class TrendMeasurement(
        val trend: ProgramPerformanceTrend,
        val older: ProgramComparableExposure,
        val newer: ProgramComparableExposure
    )

    /** The counts of one group of readings. */
    private fun exposureOf(readings: List<Reading>): ProgramComparableExposure =
        ProgramComparableExposure(
            exposures = readings.size,
            fullExposures = readings.count { it.full },
            prescribedSets = readings.sumOf { it.prescribed },
            completedSets = readings.sumOf { it.completed }
        )

    /**
     * The window's trend and its two halves, or `null` when the window does not hold enough comparable
     * exposures to state one ([ProgramAdaptivePolicy.trendMinimumExposures]).
     *
     * The two halves are compared **exactly**: the newer half's completed sets against the older half's
     * prescribed sets, cross-multiplied, so no ratio is materialized and no boundary is decided by
     * rounding. The split is a fixed position — the older half takes the smaller half when the count is
     * odd — so the trend cannot depend on a rounding choice. Equal halves are
     * [ProgramPerformanceTrend.STABLE]: a plateau measured, not assumed.
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
     * The attendance bucket over the opportunities the plan offered, or `null` when the caller stated
     * none.
     *
     * The bucket is decided by cross-multiplying completed opportunities against the policy's own two
     * ratios, so the comparison is exact and the thresholds have one home.
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

    /** The family an exercise belongs to, by the caller's classification; an unknown one is its own. */
    private fun familyOf(exerciseId: String, familyOfExercise: Map<String, String>): String =
        familyOfExercise[exerciseId] ?: exerciseId
}
