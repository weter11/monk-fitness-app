package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.usecase.TOTAL_PROGRAM_DAYS

/**
 * The Stage 1 signal layer: a pure function from the observed session history to [AdaptiveSignals].
 *
 * It reads no clock, no calendar, no database and no preference store. Every window it uses ends at
 * the newest planned opportunity the history establishes, so the same history always yields the same
 * signals — that is what makes a decision reproducible for the audit trail.
 *
 * ## The planned-opportunity model
 *
 * One [SessionObservation] is one planned workout opportunity; the history is that model, and it is
 * read ordered by calendar position ((cycle, day), oldest first) rather than in the caller's order.
 * A rest day produces no observation and a planned day that never happened is the history's
 * `NOT_STARTED` entry, so nothing is inferred about days the history does not carry — and no window
 * is measured against wall-clock time. A program day advances one calendar day, exactly as
 * `resolveCycleAndDay` defines the program calendar, so a day index is
 * `(cycleNumber - 1) * TOTAL_PROGRAM_DAYS + programDay` and a window of day indices is a window of
 * calendar days.
 *
 * ## What the numbers mean here
 *
 *  * **Eligible session** — started (`PARTIAL` or `COMPLETED`) and prescribed at least one
 *    measurable unit of work. A `NOT_STARTED` day is attendance evidence, not exposure evidence, and
 *    a session planned in no unit at all carries no exposure in either unit.
 *  * **Session exposure** — the mean of the session's own planned/actual ratios, one ratio per unit
 *    channel it prescribed (`reps` and `durationSeconds`). Each ratio is clamped to `0..1`, so the
 *    result is between the session's two extreme channels and a repetition is never added to a
 *    second. A session that prescribed only sets has no measurable channel: it is ineligible.
 *  * **Meaningful start** — the session completed an exercise (one of its exercise results performed
 *    its full prescribed work), or reached
 *    `policy.adherenceMeaningfulStartMinWorkRatio` of its planned work. The observation's own
 *    `completedExercises` count answers a different question ("observed any work at all"), so it is
 *    deliberately not used as the completion criterion.
 *  * **Performance trend** — the least-squares slope of the exposure series against its position in
 *    the window, oldest first, i.e. the change in exposure per consecutive exposure:
 *    `Σ(i - ī)(eᵢ - ē) / Σ(i - ī)²`, bucketed by `policy.trendOf` (±0.05). Exposures are fractions of
 *    the planned target, so the slope is unit-free and a timed hold is never compared with a
 *    repetition. A series shorter than `policy.performanceTrendMinimumExposures` claims no trend.
 *  * **Group trend** — the same slope over one exercise/family series. The caller's
 *    [familyOfExercise] map decides the granularity (family id, or the exercise id when the map does
 *    not know it), and the group's value for one session is the mean of the exposures its exercises
 *    observed in that session. An exercise the session prescribed but observed no set for is not an
 *    exposure: it was skipped, which is attendance, not performance.
 *  * **Recent load** — performed sets, the one channel that counts the same work item for a
 *    repetition and a timer exercise, summed over the newest `policy.recentLoadWindowDays` day
 *    indices and over the same-length window before it. A preceding window that performed nothing
 *    establishes no baseline: the ratio is `null` and the bucket NORMAL, because an undefined ratio
 *    is not an infinite increase.
 *  * **Recovery risk** — see [RecoveryRisk]. The flags and the qualifying pattern are reported; the
 *    confirmation counts and the RECOVERY transition stay with [AdaptivePolicy.evaluate].
 */
object AdaptiveSignalCalculator {

    /**
     * @param history the observed planned opportunities, any order; read ordered by calendar position.
     * @param policy the single source of the windows, minimums and thresholds used here.
     * @param familyOfExercise exercise id to family id, for callers that know the exercise library.
     *   An exercise the map does not contain is its own trend group.
     */
    fun calculate(
        history: List<SessionObservation>,
        policy: AdaptivePolicy = AdaptivePolicy.V1,
        familyOfExercise: Map<String, String> = emptyMap()
    ): AdaptiveSignals {
        val opportunities = history
            .sortedWith(compareBy({ it.cycleNumber }, { it.programDay }))
            .map { Opportunity(dayIndex(it), it) }
        val eligible = opportunities.filter { isEligible(it.observation) }
        val exposureWindow = eligible.takeLast(policy.eligibleSessionWindow)
        val exposureScore = weightedExposure(exposureWindow, policy)
        val sessionTrend = trendOf(
            exposureWindow.takeLast(policy.performanceTrendExposures).map { sessionExposure(it.observation) },
            policy
        ) ?: PerformanceTrend.STABLE
        val load = recentLoad(opportunities, policy)

        return AdaptiveSignals(
            eligibleSessionCount = exposureWindow.size,
            exposureScore = exposureScore,
            adherence = adherence(opportunities, policy),
            consistency = consistency(opportunities, policy),
            performanceTrend = sessionTrend,
            performanceTrends = groupTrends(opportunities, policy, familyOfExercise),
            recentLoadRatio = load.ratio,
            recentLoadBucket = load.bucket,
            recoveryRisk = recoveryRisk(
                eligible = eligible,
                exposureScore = exposureScore,
                performanceDecline = sessionTrend == PerformanceTrend.NEGATIVE,
                highRecentLoad = load.bucket == RecentLoadBucket.HIGH,
                policy = policy
            )
        )
    }

    /** One planned opportunity on the program calendar axis: a program day is one calendar day. */
    private data class Opportunity(val dayIndex: Int, val observation: SessionObservation)

    /** A load window's measurement and the bucket it falls into. */
    private data class RecentLoad(val ratio: Double?, val bucket: RecentLoadBucket)

    // -------------------------------------------------------------- exposure

    /**
     * `Σ(exposure × weight) / Σ(weight)`, the newest eligible session weighted
     * `policy.eligibleSessionWindow` (6) and each older one a single weight less. With fewer sessions
     * than the window the same scheme is kept, so the newest session always carries the full weight
     * rather than the (smaller) number of sessions available. No eligible session scores `0.0`, which
     * can only withhold progression.
     */
    private fun weightedExposure(window: List<Opportunity>, policy: AdaptivePolicy): Double {
        var weighted = 0.0
        var weights = 0.0
        window.forEachIndexed { index, opportunity ->
            val rank = window.size - index // 1 = the newest session
            val weight = (policy.eligibleSessionWindow + 1 - rank).toDouble()
            weighted += sessionExposure(opportunity.observation) * weight
            weights += weight
        }
        return if (weights == 0.0) 0.0 else weighted / weights
    }

    /** The session's performed fraction per prescribed unit channel, averaged; `0.0` when unmeasurable. */
    private fun sessionExposure(observation: SessionObservation): Double {
        val reps = channelExposure(observation.actualWork.reps, observation.plannedWork.reps)
        val duration = channelExposure(
            observation.actualWork.durationSeconds,
            observation.plannedWork.durationSeconds
        )
        return when {
            observation.plannedWork.reps > 0 && observation.plannedWork.durationSeconds > 0 -> (reps + duration) / 2.0
            observation.plannedWork.reps > 0 -> reps
            observation.plannedWork.durationSeconds > 0 -> duration
            else -> 0.0
        }
    }

    /** `completed / planned` inside one unit, clamped to `0..1`; no planned work is `0.0`. */
    private fun channelExposure(completed: Int, planned: Int): Double =
        if (planned <= 0) 0.0 else (completed.toDouble() / planned.toDouble()).coerceIn(0.0, 1.0)

    /** A session carries exposure evidence when it was started and prescribed a measurable amount. */
    private fun isEligible(observation: SessionObservation): Boolean =
        observation.outcome != SessionOutcome.NOT_STARTED &&
            (observation.plannedWork.reps > 0 || observation.plannedWork.durationSeconds > 0)

    // -------------------------------------------------------------- adherence and consistency

    /**
     * Meaningful starts over the planned opportunities of the calendar window that ends at the newest
     * opportunity. `0.0` when there is no opportunity at all: no attendance evidence is not attendance.
     * The anchor is the newest opportunity in the history, so the window never moves with the clock.
     */
    private fun adherence(opportunities: List<Opportunity>, policy: AdaptivePolicy): Double {
        val anchor = opportunities.lastOrNull()?.dayIndex ?: return 0.0
        val window = opportunities.inDays(anchor - (policy.adherenceWindowDays - 1), anchor)
        if (window.isEmpty()) return 0.0
        return window.count { meaningfullyStarted(it.observation, policy) }.toDouble() / window.size
    }

    /**
     * The attendance bucket over the newest `policy.consistencyOpportunityWindow` opportunities. The
     * history is the opportunity model, so a gap between opportunities is not filled in. With no
     * opportunity the bucket stays [ConsistencyBucket.MEDIUM]: an empty denominator shows no
     * inconsistent pattern, and LOW would be a claim the history does not support.
     */
    private fun consistency(opportunities: List<Opportunity>, policy: AdaptivePolicy): ConsistencyBucket {
        val window = opportunities.takeLast(policy.consistencyOpportunityWindow)
        if (window.isEmpty()) return ConsistencyBucket.MEDIUM
        val attended = window.count { meaningfullyStarted(it.observation, policy) }
        return policy.consistencyOf(attended.toDouble() / window.size)
    }

    /**
     * The documented meaningful-start rule: the session completed an exercise, or performed
     * `policy.adherenceMeaningfulStartMinWorkRatio` of its planned work. A completed exercise is one
     * of the session's observed exercise results whose exposure reached its plan in full.
     */
    private fun meaningfullyStarted(observation: SessionObservation, policy: AdaptivePolicy): Boolean =
        observation.exerciseResults.any { it.exposure >= FULL_EXPOSURE } ||
            sessionExposure(observation) >= policy.adherenceMeaningfulStartMinWorkRatio

    // -------------------------------------------------------------- performance trends

    /** The trend of one exposure series per group, dropping the groups that have too few exposures. */
    private fun groupTrends(
        opportunities: List<Opportunity>,
        policy: AdaptivePolicy,
        familyOfExercise: Map<String, String>
    ): Map<String, PerformanceTrend> {
        val series = LinkedHashMap<String, MutableList<Double>>()
        opportunities.forEach { opportunity ->
            observedGroupExposures(opportunity.observation, familyOfExercise).forEach { (group, exposure) ->
                series.getOrPut(group) { mutableListOf() }.add(exposure)
            }
        }
        return series.mapNotNull { (group, exposures) ->
            trendOf(exposures.takeLast(policy.performanceTrendExposures), policy)?.let { group to it }
        }.toMap()
    }

    /**
     * One session's exposure per trend group: the mean of the exposures its exercises observed, keyed
     * by family when the caller knows one and by exercise id otherwise. Prescribed-but-skipped
     * exercises contribute nothing — no confirmed set means no exposure was observed.
     */
    private fun observedGroupExposures(
        observation: SessionObservation,
        familyOfExercise: Map<String, String>
    ): Map<String, Double> {
        val observed = LinkedHashMap<String, MutableList<Double>>()
        observation.exerciseResults
            .filter { result -> result.completedSets > 0 && result.hasMeasurablePlan() }
            .forEach { result ->
                val group = familyOfExercise[result.exerciseId] ?: result.exerciseId
                observed.getOrPut(group) { mutableListOf() }.add(result.exposure)
            }
        return observed.mapValues { (_, exposures) -> exposures.average() }
    }

    /** An exercise result is an exposure only when its plan is measurable in one of the two units. */
    private fun ExerciseResult.hasMeasurablePlan(): Boolean =
        plannedReps > 0 || plannedDurationSeconds > 0

    /**
     * The trend bucket of one exposure series, or `null` when the series is too short to claim one.
     * The slope is the least-squares slope against position in the window (oldest first), so it reads
     * as the change in exposure per consecutive exposure.
     */
    private fun trendOf(exposures: List<Double>, policy: AdaptivePolicy): PerformanceTrend? {
        if (exposures.size < policy.performanceTrendMinimumExposures) return null
        val meanPosition = (exposures.size - 1) / 2.0
        val meanExposure = exposures.average()
        var covariance = 0.0
        var variance = 0.0
        exposures.forEachIndexed { index, exposure ->
            val positionOffset = index - meanPosition
            covariance += positionOffset * (exposure - meanExposure)
            variance += positionOffset * positionOffset
        }
        return policy.trendOf(covariance / variance)
    }

    // -------------------------------------------------------------- recent load

    /**
     * Performed sets in the newest `policy.recentLoadWindowDays` program days, against performed sets
     * in the same-length window before it. A preceding window with no performed work yields no ratio:
     * the bucket is NORMAL because there is no measured increase to report.
     */
    private fun recentLoad(opportunities: List<Opportunity>, policy: AdaptivePolicy): RecentLoad {
        val anchor = opportunities.lastOrNull()?.dayIndex ?: return RecentLoad(null, RecentLoadBucket.NORMAL)
        val recent = opportunities.performedSets(anchor - (policy.recentLoadWindowDays - 1), anchor)
        val preceding = opportunities.performedSets(
            anchor - (policy.recentLoadWindowDays * 2 - 1),
            anchor - policy.recentLoadWindowDays
        )
        if (preceding == 0) return RecentLoad(null, RecentLoadBucket.NORMAL)

        val ratio = recent.toDouble() / preceding.toDouble()
        return RecentLoad(ratio, policy.loadBucketOf(ratio))
    }

    private fun List<Opportunity>.performedSets(fromDayIndex: Int, toDayIndex: Int): Int =
        inDays(fromDayIndex, toDayIndex).sumOf { it.observation.actualWork.sets }

    // -------------------------------------------------------------- recovery risk

    /**
     * The documented flags plus this window's qualifying high-risk pattern. The pattern is the
     * per-window condition the policy confirms across windows; it is not a RECOVERY decision, and a
     * single weak session never qualifies (one exposure establishes no trend).
     */
    private fun recoveryRisk(
        eligible: List<Opportunity>,
        exposureScore: Double,
        performanceDecline: Boolean,
        highRecentLoad: Boolean,
        policy: AdaptivePolicy
    ): RecoveryRisk {
        val pattern = when {
            highRecentLoad && (performanceDecline || exposureScore < policy.recoveryStrongLowExposureScore) ->
                RecoveryRiskPattern.HIGH_LOAD_DETERIORATION

            exposureScore < policy.recoveryStrongLowExposureScore && performanceDecline ->
                RecoveryRiskPattern.LOW_EXPOSURE_DECLINE

            else -> null
        }

        return RecoveryRisk(
            poorCompletionStreak = poorCompletionStreak(eligible) >= policy.poorCompletionStreakSessions,
            performanceDecline = performanceDecline,
            highRecentLoad = highRecentLoad,
            recentAbandonment = eligible.takeLast(policy.eligibleSessionWindow)
                .any { it.observation.wasAbandoned() },
            pattern = pattern
        )
    }

    /** Consecutive newest eligible sessions that did not satisfy the completion condition. */
    private fun poorCompletionStreak(eligible: List<Opportunity>): Int {
        var streak = 0
        for (opportunity in eligible.asReversed()) {
            if (opportunity.observation.outcome == SessionOutcome.COMPLETED) break
            streak++
        }
        return streak
    }

    /**
     * An abandoned workout is a session that ended without satisfying the completion condition: a
     * `PARTIAL` observation carrying a finish stamp. Without that stamp the observation's end is not
     * established and nothing is claimed about it.
     */
    private fun SessionObservation.wasAbandoned(): Boolean =
        outcome == SessionOutcome.PARTIAL && finishedAt != null

    // -------------------------------------------------------------- calendar axis

    private fun List<Opportunity>.inDays(fromDayIndex: Int, toDayIndex: Int): List<Opportunity> =
        filter { it.dayIndex in fromDayIndex..toDayIndex }

    /** The program day's position on the program calendar axis, where one program day is one day. */
    private fun dayIndex(observation: SessionObservation): Int =
        (observation.cycleNumber - 1) * TOTAL_PROGRAM_DAYS + observation.programDay

    private const val FULL_EXPOSURE = 1.0
}
