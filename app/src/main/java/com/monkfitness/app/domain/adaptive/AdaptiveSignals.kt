package com.monkfitness.app.domain.adaptive

/**
 * The high-risk patterns a decision window can qualify for. They name the documented Stage 1
 * combinations, not a clinical finding: [HIGH_LOAD_DETERIORATION] is high recent program load with a
 * declining trend or strongly reduced exposure, [LOW_EXPOSURE_DECLINE] is the prolonged
 * low-exposure-with-decline pattern that carries no load spike.
 */
enum class RecoveryRiskPattern {
    HIGH_LOAD_DETERIORATION,
    LOW_EXPOSURE_DECLINE
}

/**
 * The recovery-risk flags for one decision window, read from the signals they are derived from.
 *
 *  * [poorCompletionStreak] — the newest eligible sessions, read backwards, are consecutive sessions
 *    that did not satisfy the workout completion condition (`policy.poorCompletionStreakSessions` of
 *    them). Only sessions that were started count: a skipped day is attendance evidence, and the
 *    adherence/consistency signals are what report it.
 *  * [performanceDecline] — the whole-workout trend is [PerformanceTrend.NEGATIVE].
 *  * [highRecentLoad] — the recent-load bucket is [RecentLoadBucket.HIGH].
 *  * [recentAbandonment] — one of the newest eligible sessions (`policy.eligibleSessionWindow`) is a
 *    `PARTIAL` observation that carries a finish stamp, i.e. a session whose end is established and
 *    which did not satisfy the completion condition. A `PARTIAL` observation without that stamp has
 *    no established end and is not read as abandonment. **Known data limitation:** the session-history
 *    adapter cannot currently produce that stamp (it is only written for a completed day), so on
 *    today's persistence this flag reads `false`. It is defined on the observation contract so that
 *    abandonment instrumentation does not have to be invented here.
 *  * [pattern] — the qualifying high-risk pattern for this window, or `null`. The conditions are the
 *    policy's own: [RecoveryRiskPattern.HIGH_LOAD_DETERIORATION] requires [highRecentLoad] with
 *    [performanceDecline] or `exposureScore < policy.recoveryStrongLowExposureScore`, and
 *    [RecoveryRiskPattern.LOW_EXPOSURE_DECLINE] requires the strong-low exposure score with
 *    [performanceDecline]. This is the *per-window qualifying condition*, not a decision: the policy
 *    still owns the confirmation counts (`recoveryEntryConfirmingWindows`,
 *    `recoveryProlongedHighRiskWindows`) and the RECOVERY transition itself.
 *
 * A single weak session qualifies for nothing here: one session cannot establish a trend
 * (`policy.performanceTrendMinimumExposures`), and a trending decline needs at least that many
 * exposures, so the window is reported with [pattern] `null`.
 */
data class RecoveryRisk(
    val poorCompletionStreak: Boolean = false,
    val performanceDecline: Boolean = false,
    val highRecentLoad: Boolean = false,
    val recentAbandonment: Boolean = false,
    val pattern: RecoveryRiskPattern? = null
) {

    init {
        require(pattern != RecoveryRiskPattern.HIGH_LOAD_DETERIORATION || highRecentLoad) {
            "HIGH_LOAD_DETERIORATION requires highRecentLoad"
        }
        require(pattern != RecoveryRiskPattern.LOW_EXPOSURE_DECLINE || performanceDecline) {
            "LOW_EXPOSURE_DECLINE requires performanceDecline"
        }
    }
}

/**
 * The Stage 1 signal outputs for one decision window: what the observed history shows about exposure,
 * attendance, per-family performance and recent load, plus the recovery-risk reading.
 *
 * Every field is a deterministic function of the [SessionObservation] history and the [AdaptivePolicy]
 * it was produced under. Nothing here reads a clock, a database or the current calendar: each window
 * is anchored at the newest planned opportunity the history establishes.
 *
 *  * [eligibleSessionCount] — how many sessions the exposure score was measured over, capped by
 *    `policy.eligibleSessionWindow` (a session is eligible when it was started and prescribed at least
 *    one measurable unit of work).
 *  * [exposureScore] — the weighted mean of those sessions' exposure, newest weighted
 *    `policy.eligibleSessionWindow` and one less for each older session; `0.0` with no eligible
 *    session, which can only withhold progression.
 *  * [adherence] — meaningful starts over the planned opportunities of the
 *    `policy.adherenceWindowDays` calendar-day window ending at the newest opportunity; a session
 *    counts as meaningfully started when it completed an exercise or reached
 *    `policy.adherenceMeaningfulStartMinWorkRatio` of its planned work. `0.0` when there is no
 *    planned opportunity at all: zero attendance evidence is never reported as attendance.
 *  * [consistency] — the attendance bucket over the newest
 *    `policy.consistencyOpportunityWindow` opportunities, bucketed by `policy.consistencyOf`. With no
 *    opportunities it stays [ConsistencyBucket.MEDIUM], the neutral band: an empty denominator is not
 *    the LOW pattern.
 *  * [performanceTrend] — the whole-workout trend over the newest
 *    `policy.performanceTrendExposures` eligible sessions, or [PerformanceTrend.STABLE] when there are
 *    fewer than `policy.performanceTrendMinimumExposures` exposures to measure.
 *  * [performanceTrends] — the same trend per exercise/family group. The caller's exercise-to-family
 *    map decides the granularity: an exercise the map knows is grouped under its family id, and an
 *    exercise the map does not know is its own group. A group with too few exposures is absent rather
 *    than reported as stable.
 *  * [recentLoadRatio] — performed sets in the newest `policy.recentLoadWindowDays` program days over
 *    the performed sets in the same-length window before it. Performed sets are the one channel that
 *    counts the same work item for repetition and timer exercises, so repetitions and seconds are
 *    never added together here. It is `null` when the preceding window performed nothing: without a
 *    baseline there is no measured increase, and reporting one would be a claim the evidence cannot
 *    support.
 *  * [recentLoadBucket] — `policy.loadBucketOf(recentLoadRatio)`, or [RecentLoadBucket.NORMAL] when the
 *    ratio is not measurable.
 *  * [recoveryRisk] — the documented flags and the qualifying high-risk pattern for this window.
 */
data class AdaptiveSignals(
    val eligibleSessionCount: Int,
    val exposureScore: Double,
    val adherence: Double,
    val consistency: ConsistencyBucket,
    val performanceTrend: PerformanceTrend,
    val performanceTrends: Map<String, PerformanceTrend>,
    val recentLoadRatio: Double?,
    val recentLoadBucket: RecentLoadBucket,
    val recoveryRisk: RecoveryRisk
) {

    init {
        require(eligibleSessionCount >= 0) { "eligibleSessionCount must be >= 0, was $eligibleSessionCount" }
        require(exposureScore in 0.0..1.0) { "exposureScore must be normalized to 0..1, was $exposureScore" }
        require(adherence in 0.0..1.0) { "adherence must be normalized to 0..1, was $adherence" }
        require(recentLoadRatio == null || recentLoadRatio >= 0.0) {
            "recentLoadRatio must be null or >= 0, was $recentLoadRatio"
        }
        require(recentLoadBucket == RecentLoadBucket.HIGH || !recoveryRisk.highRecentLoad) {
            "highRecentLoad requires the HIGH recent-load bucket, was $recentLoadBucket"
        }
    }
}
