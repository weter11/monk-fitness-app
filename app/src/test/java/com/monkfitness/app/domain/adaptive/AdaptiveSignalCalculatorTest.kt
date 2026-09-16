package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the Stage 1 signal layer: every rule the calculator implements is pinned here with the
 * exact numbers the approved design specifies, so the signal math cannot drift silently.
 *
 * The fixtures are hand-built [SessionObservation]s whose session aggregates are derived from their
 * exercise results the same way the session-history adapter derives them, so a fixture means what a
 * real session means. Two helpers keep that cheap: [repSession] for a one-exercise repetition session
 * (its exposure is `completedReps / plannedReps`) and [session] for anything more specific.
 *
 * Wall-clock independence is covered indirectly throughout: fixtures are placed on program days and
 * every window is anchored at the newest opportunity in the history, never at `now()`.
 */
class AdaptiveSignalCalculatorTest {

    private val policy = AdaptivePolicy.V1

    // ---------------------------------------------------------------- exposure score

    @Test
    fun theSixSessionExposureScoreWeighsTheNewestSessionSix() {
        // Planned 10 reps per session, completed 1..6 -> exposures 0.1..0.6, newest last.
        val history = (1..6).map { day -> repSession(day, completedReps = day, plannedReps = 10) }

        val signals = calculate(history)

        // (0.1*1 + 0.2*2 + 0.3*3 + 0.4*4 + 0.5*5 + 0.6*6) / 21
        assertEquals(9.1 / 21.0, signals.exposureScore, 1e-12)
        assertEquals(6, signals.eligibleSessionCount)
    }

    @Test
    fun theNewestSessionCarriesWeightSixAndNotTheWindowsSize() {
        // Three eligible sessions: the newest keeps the documented weight 6, the older ones 5 and 4.
        val history = listOf(
            repSession(1, completedReps = 1, plannedReps = 10),
            repSession(2, completedReps = 5, plannedReps = 10),
            repSession(3, completedReps = 10, plannedReps = 10)
        )

        val signals = calculate(history)

        // (0.1*4 + 0.5*5 + 1.0*6) / 15
        assertEquals(8.9 / 15.0, signals.exposureScore, 1e-12)
        assertEquals(3, signals.eligibleSessionCount)
    }

    @Test
    fun theExposureScoreReadsOnlyTheNewestSixEligibleSessions() {
        // Two older perfected sessions, then the six-session series: only the newest six count.
        val withTwoOlderPerfectedSessions = listOf(
            repSession(1, completedReps = 10, plannedReps = 10),
            repSession(2, completedReps = 10, plannedReps = 10)
        ) + (3..8).map { day -> repSession(day, completedReps = day - 2, plannedReps = 10) }

        val signals = calculate(withTwoOlderPerfectedSessions)

        assertEquals(9.1 / 21.0, signals.exposureScore, 1e-12)
        assertEquals(6, signals.eligibleSessionCount)
    }

    @Test
    fun sessionsThatNeverStartedCarryNoExposure() {
        // Two planned days the user never opened, then two partial sessions.
        val history = listOf(
            repSession(1, completedReps = 0, plannedReps = 20),
            repSession(2, completedReps = 0, plannedReps = 20),
            repSession(3, completedReps = 8, plannedReps = 20),
            repSession(4, completedReps = 16, plannedReps = 20)
        )

        val signals = calculate(history)

        assertEquals(2, signals.eligibleSessionCount)
        // (0.4*5 + 0.8*6) / 11: only the two sessions that were started carry exposure.
        assertEquals(6.8 / 11.0, signals.exposureScore, 1e-12)
    }

    @Test
    fun exposureIsClampedToThePlanAlreadyRepresentedByTheObservation() {
        // 20 of 10 prescribed repetitions: the observation's own exposure is clamped to 1.0.
        val history = listOf(repSession(1, completedReps = 20, plannedReps = 10))

        val signals = calculate(history)

        assertEquals(1.0, signals.exposureScore, 0.0)
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
    }

    @Test
    fun anEmptyHistoryYieldsTheConservativeDefaults() {
        val signals = calculate(emptyList())

        assertEquals(0, signals.eligibleSessionCount)
        assertEquals(0.0, signals.exposureScore, 0.0)
        assertEquals(0.0, signals.adherence, 0.0)
        assertEquals(ConsistencyBucket.MEDIUM, signals.consistency)
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
        assertTrue(signals.performanceTrends.isEmpty())
        assertNull(signals.recentLoadRatio)
        assertEquals(RecentLoadBucket.NORMAL, signals.recentLoadBucket)
        assertEquals(
            RecoveryRisk(),
            signals.recoveryRisk
        )
    }

    // ---------------------------------------------------------------- adherence

    @Test
    fun adherenceCountsMeaningfulStartsInsideTheFourteenDayWindow() {
        // Days 1 and 2 are attended but fall outside the window that ends at day 16 (days 3..16).
        val history = listOf(
            repSession(1, completedReps = 20, plannedReps = 20),
            repSession(2, completedReps = 20, plannedReps = 20)
        ) + (3..9).map { day -> repSession(day, completedReps = 20, plannedReps = 20) } +
            (10..16).map { day -> repSession(day, completedReps = 0, plannedReps = 20) }

        val signals = calculate(history)

        // Seven attended opportunities out of the fourteen in the window (7/14), not 9/16.
        assertEquals(7.0 / 14.0, signals.adherence, 1e-12)
    }

    @Test
    fun theAdherenceWindowIsFourteenDaysInclusiveOfTheAnchor() {
        // Attended: day 2 (outside), day 3 (the window's first day), then nothing until the anchor.
        val history = listOf(
            repSession(2, completedReps = 20, plannedReps = 20),
            repSession(3, completedReps = 20, plannedReps = 20)
        ) + (4..16).map { day -> repSession(day, completedReps = 0, plannedReps = 20) }

        val signals = calculate(history)

        // Day 2 is excluded, so one attended opportunity in fourteen.
        assertEquals(1.0 / 14.0, signals.adherence, 1e-12)
    }

    @Test
    fun theAdherenceWindowEndsAtTheNewestOpportunityAndNotAtTheCurrentDate() {
        // A 39-day gap: the window covers days 27..40, which contains only the anchor itself.
        val history = listOf(
            repSession(1, completedReps = 20, plannedReps = 20),
            repSession(40, completedReps = 20, plannedReps = 20)
        )

        val signals = calculate(history)

        assertEquals(1.0, signals.adherence, 1e-12)
    }

    @Test
    fun zeroPlannedOpportunitiesReportZeroAdherence() {
        // No planned opportunity means no attendance evidence. Zero is the conservative reading: it
        // can only withhold progression, never assert that the user trained.
        val signals = calculate(emptyList())

        assertEquals(0.0, signals.adherence, 0.0)
    }

    @Test
    fun aSessionThatCompletedOneExerciseIsMeaningfullyStartedEvenWhenTheTotalIsLow() {
        // One exercise fully performed and nine only prescribed: 10 of 460 prescribed repetitions,
        // i.e. below the ten-percent rule, but the "an exercise was completed" rule is satisfied.
        val completedOneExercise = listOf(reps("pushups", plannedReps = 10, completedReps = 10)) +
            (1..9).map { index -> reps("exercise_$index", plannedReps = 50, completedReps = 0) }
        val history = (1..7).map { day -> session(day, SessionOutcome.PARTIAL, completedOneExercise) } +
            listOf(repSession(8, completedReps = 0, plannedReps = 20))

        val signals = calculate(history)

        // Seven of the eight newest opportunities were meaningfully started: 7/8 is HIGH. Reading the
        // sessions by their total exposure alone (about 2%) would have reported LOW.
        assertEquals(ConsistencyBucket.HIGH, signals.consistency)
        assertEquals(7.0 / 8.0, signals.adherence, 1e-12)
    }

    @Test
    fun exactlyTenPercentOfPlannedWorkIsAMeaningfulStart() {
        // No exercise completed in any session; every session reached exactly 10% of its plan.
        val history = (1..8).map { day ->
            session(day, SessionOutcome.PARTIAL, listOf(reps("pushups", plannedReps = 100, completedReps = 10)))
        }

        val signals = calculate(history)

        assertEquals(1.0, signals.adherence, 1e-12)
        assertEquals(ConsistencyBucket.HIGH, signals.consistency)
    }

    @Test
    fun justBelowTenPercentWithoutACompletedExerciseIsNotAMeaningfulStart() {
        // 9 of 100 prescribed repetitions and no completed exercise: not meaningfully started.
        val history = (1..8).map { day ->
            session(day, SessionOutcome.PARTIAL, listOf(reps("pushups", plannedReps = 100, completedReps = 9)))
        }

        val signals = calculate(history)

        assertEquals(0.0, signals.adherence, 0.0)
        assertEquals(ConsistencyBucket.LOW, signals.consistency)
    }

    // ---------------------------------------------------------------- consistency

    @Test
    fun consistencyReadsOnlyTheLatestEightPlannedOpportunities() {
        // The two oldest opportunities were skipped: six attended of the newest eight is HIGH, while
        // six attended of all ten would only be MEDIUM.
        val history = (1..2).map { day -> repSession(day, completedReps = 0, plannedReps = 20) } +
            (3..8).map { day -> repSession(day, completedReps = 20, plannedReps = 20) } +
            (9..10).map { day -> repSession(day, completedReps = 0, plannedReps = 20) }

        val signals = calculate(history)

        assertEquals(ConsistencyBucket.HIGH, signals.consistency)
    }

    @Test
    fun consistencyBucketBoundariesAreHighAt75AndMediumAt50() {
        assertEquals(ConsistencyBucket.HIGH, consistencyOfAttended(6))
        assertEquals(ConsistencyBucket.MEDIUM, consistencyOfAttended(5))
        assertEquals(ConsistencyBucket.MEDIUM, consistencyOfAttended(4))
        assertEquals(ConsistencyBucket.LOW, consistencyOfAttended(3))
    }

    @Test
    fun aGapInTheOpportunitiesIsNotFilledIn() {
        // Non-consecutive opportunities: the model is the history itself, so eight opportunities may
        // span far more than eight calendar days and the bucket is measured over what exists.
        val history = listOf(1, 10, 11, 20, 21, 30, 40, 41).mapIndexed { index, day ->
            repSession(day, completedReps = if (index < 6) 20 else 0, plannedReps = 20)
        }

        val signals = calculate(history)

        assertEquals(ConsistencyBucket.HIGH, signals.consistency)
    }

    // ---------------------------------------------------------------- performance trend

    @Test
    fun threeObservationsRisingArePositive() {
        val history = listOf(
            repSession(1, completedReps = 10, plannedReps = 20),
            repSession(2, completedReps = 14, plannedReps = 20),
            repSession(3, completedReps = 18, plannedReps = 20)
        )

        val signals = calculate(history)

        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrend)
        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrends.getValue("pushups"))
    }

    @Test
    fun fiveObservationsAreMeasuredAndAFallingSeriesIsNegative() {
        val rising = (0..4).map { step -> repSession(step + 1, completedReps = 4 + step * 4, plannedReps = 20) }
        val falling = (0..4).map { step -> repSession(step + 1, completedReps = 20 - step * 4, plannedReps = 20) }

        assertEquals(PerformanceTrend.POSITIVE, calculate(rising).performanceTrend)
        assertEquals(PerformanceTrend.NEGATIVE, calculate(falling).performanceTrend)
    }

    @Test
    fun theTrendReadsOnlyTheLatestFiveExposures() {
        // The oldest exposure is a perfect session; the five newest are flat. Including the sixth
        // point would read as a decline, so STABLE proves the window.
        val history = listOf(repSession(1, completedReps = 20, plannedReps = 20)) +
            (2..6).map { day -> repSession(day, completedReps = 10, plannedReps = 20) }

        val signals = calculate(history)

        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrends.getValue("pushups"))
    }

    @Test
    fun trendBoundaryAtExactlyPlusFiveHundredthsIsPositive() {
        // Exposures 3/20, 4/25, 10/40: the least-squares slope is the policy's +0.05 threshold exactly.
        val history = listOf(
            repSession(1, completedReps = 3, plannedReps = 20),
            repSession(2, completedReps = 4, plannedReps = 25),
            repSession(3, completedReps = 10, plannedReps = 40)
        )

        val signals = calculate(history)

        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrend)
    }

    @Test
    fun trendBoundaryAtExactlyMinusFiveHundredthsIsNegative() {
        val history = listOf(
            repSession(1, completedReps = 10, plannedReps = 40),
            repSession(2, completedReps = 4, plannedReps = 25),
            repSession(3, completedReps = 3, plannedReps = 20)
        )

        val signals = calculate(history)

        assertEquals(PerformanceTrend.NEGATIVE, signals.performanceTrend)
    }

    @Test
    fun aRiseBelowTheThresholdStaysStable() {
        // Exposures 3/20, 2/10, 6/25: a real rise, but the slope (0.045) is under the threshold.
        val history = listOf(
            repSession(1, completedReps = 3, plannedReps = 20),
            repSession(2, completedReps = 2, plannedReps = 10),
            repSession(3, completedReps = 6, plannedReps = 25)
        )

        assertEquals(PerformanceTrend.STABLE, calculate(history).performanceTrend)
    }

    @Test
    fun aFallAboveTheThresholdStaysStable() {
        // Exposures 2/8, 2/10, 4/25: a real fall, but the slope (-0.045) is above the threshold.
        val history = listOf(
            repSession(1, completedReps = 2, plannedReps = 8),
            repSession(2, completedReps = 2, plannedReps = 10),
            repSession(3, completedReps = 4, plannedReps = 25)
        )

        assertEquals(PerformanceTrend.STABLE, calculate(history).performanceTrend)
    }

    @Test
    fun repetitionPerformanceIsNormalizedToThePlannedTarget() {
        // The same fraction of a different prescription is the same performance: raw repetitions
        // triple and the trend still reads STABLE.
        val history = listOf(
            repSession(1, completedReps = 5, plannedReps = 10),
            repSession(2, completedReps = 50, plannedReps = 100),
            repSession(3, completedReps = 30, plannedReps = 60)
        )

        assertEquals(PerformanceTrend.STABLE, calculate(history).performanceTrend)
    }

    @Test
    fun timerPerformanceIsNormalizedToThePlannedTarget() {
        val history = listOf(
            session(1, SessionOutcome.COMPLETED, listOf(timer("plank", plannedSeconds = 30, completedSeconds = 15))),
            session(2, SessionOutcome.COMPLETED, listOf(timer("plank", plannedSeconds = 60, completedSeconds = 30))),
            session(3, SessionOutcome.COMPLETED, listOf(timer("plank", plannedSeconds = 90, completedSeconds = 45)))
        )

        val signals = calculate(history)

        // Half of every prescribed hold, in three different hold lengths: STABLE, not a rise.
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrends.getValue("plank"))
    }

    @Test
    fun repetitionsAreNeverComparedWithSeconds() {
        // One family, prescribed in repetitions in one session and in seconds in the next, all at
        // half exposure. Comparing the raw amounts (5 reps, 30 s, 10 reps) would read as a rise.
        val family = mapOf("pushups" to "press", "pushup_hold" to "press")
        val history = listOf(
            session(1, SessionOutcome.COMPLETED, listOf(reps("pushups", plannedReps = 10, completedReps = 5))),
            session(2, SessionOutcome.COMPLETED, listOf(timer("pushup_hold", plannedSeconds = 60, completedSeconds = 30))),
            session(3, SessionOutcome.COMPLETED, listOf(reps("pushups", plannedReps = 20, completedReps = 10)))
        )

        val signals = calculate(history, family)

        assertEquals(PerformanceTrend.STABLE, signals.performanceTrends.getValue("press"))
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
    }

    @Test
    fun anExerciseTheSessionSkippedIsNotAnExposure() {
        // pushups was performed in every session, deep_squat only prescribed: it has no exposure.
        val history = (1..3).map { day ->
            session(
                day,
                SessionOutcome.PARTIAL,
                listOf(
                    reps("pushups", plannedReps = 20, completedReps = 4 * day),
                    reps("deep_squat", plannedReps = 40, completedReps = 0)
                )
            )
        }

        val signals = calculate(history)

        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrends.getValue("pushups"))
        assertFalse(signals.performanceTrends.containsKey("deep_squat"))
    }

    @Test
    fun anExerciseWithTooFewExposuresClaimsNoTrend() {
        val history = (1..2).map { day -> repSession(day, completedReps = 20, plannedReps = 20) }

        val signals = calculate(history)

        assertTrue(signals.performanceTrends.isEmpty())
        // Fewer than the policy's minimum exposures: the whole-workout trend stays neutral.
        assertEquals(PerformanceTrend.STABLE, signals.performanceTrend)
    }

    @Test
    fun aFamilyGroupsItsExercisesIntoOneSeriesAndAnUnknownExerciseKeepsItsOwn() {
        val family = mapOf("pushups" to "pushups", "pushups_wide" to "pushups")
        val history = (1..3).map { day ->
            session(
                day,
                SessionOutcome.PARTIAL,
                listOf(
                    reps("pushups", plannedReps = 10, completedReps = 4 * day),
                    reps("pushups_wide", plannedReps = 10, completedReps = 4 * day),
                    reps("unknown_hold", plannedReps = 10, completedReps = 4 * day)
                )
            )
        }

        val signals = calculate(history, family)

        // Mean of the family's exercises per session: 0.4, 0.8, 1.2 clamped to 1.0 by the results.
        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrends.getValue("pushups"))
        assertEquals(PerformanceTrend.POSITIVE, signals.performanceTrends.getValue("unknown_hold"))
        assertFalse(signals.performanceTrends.containsKey("pushups_wide"))
    }

    // ---------------------------------------------------------------- recent load

    @Test
    fun loadBucketBoundariesAreTenAndTwentyPercent() {
        assertEquals(RecentLoadBucket.NORMAL, loadRatioBucket(previousSets = 100, recentSets = 100))
        assertEquals(RecentLoadBucket.NORMAL, loadRatioBucket(previousSets = 100, recentSets = 110))
        assertEquals(RecentLoadBucket.ELEVATED, loadRatioBucket(previousSets = 100, recentSets = 120))
        assertEquals(RecentLoadBucket.HIGH, loadRatioBucket(previousSets = 100, recentSets = 121))
    }

    @Test
    fun theRawLoadRatioIsReportedAlongsideItsBucket() {
        val signals = loadSignals(previousSets = 100, recentSets = 120)

        assertEquals(1.20, signals.recentLoadRatio!!, 1e-12)
        assertEquals(RecentLoadBucket.ELEVATED, signals.recentLoadBucket)
    }

    @Test
    fun loadComparesTheLatestSevenDaysWithTheSevenBeforeThem() {
        // Work on days 1..7 (the preceding window), days 12..14 (inside the recent window) and a
        // workload older than both windows that must not be counted.
        val history = listOf(sessionOfSets(1, sets = 50)) +
            listOf(12, 13, 14).map { day -> sessionOfSets(day, sets = 20) } +
            (15..20).map { day -> repSession(day, completedReps = 0, plannedReps = 20) }

        val signals = calculate(history)

        // Anchor day 20: recent = days 14..20 (one 20-set session), preceding = days 7..13 (two).
        assertEquals(20.0 / 40.0, signals.recentLoadRatio!!, 1e-12)
        assertEquals(RecentLoadBucket.NORMAL, signals.recentLoadBucket)
    }

    @Test
    fun aZeroPreviousWindowIsNotAMeasuredIncrease() {
        val noPreviousWindow = listOf(sessionOfSets(12, sets = 30))
        val bothWindowsEmpty = (1..3).map { day -> repSession(day, completedReps = 0, plannedReps = 20) }

        val recentLoad = calculate(noPreviousWindow)
        val noLoad = calculate(bothWindowsEmpty)

        // No baseline is not an infinite increase: the ratio stays unmeasured and the bucket neutral.
        assertNull(recentLoad.recentLoadRatio)
        assertEquals(RecentLoadBucket.NORMAL, recentLoad.recentLoadBucket)
        assertNull(noLoad.recentLoadRatio)
        assertEquals(RecentLoadBucket.NORMAL, noLoad.recentLoadBucket)
    }

    @Test
    fun loadCountsPerformedSetsAndNeverAddsRepetitionsToSeconds() {
        val shortSets = listOf(sessionOfSets(1, sets = 4), sessionOfSets(14, sets = 8))
        val hugeNumbers = listOf(
            session(1, SessionOutcome.COMPLETED, listOf(reps("pushups", plannedReps = 1000, completedReps = 1000, sets = 4))),
            session(
                14,
                SessionOutcome.COMPLETED,
                listOf(
                    reps("pushups", plannedReps = 2000, completedReps = 2000, sets = 4),
                    timer("plank", plannedSeconds = 3600, completedSeconds = 3600, sets = 4)
                )
            )
        )

        // Identical performed-set counts give an identical ratio even though the repetition and
        // duration amounts differ by orders of magnitude: the two channels are never added together.
        assertEquals(2.0, calculate(shortSets).recentLoadRatio!!, 0.0)
        assertEquals(2.0, calculate(hugeNumbers).recentLoadRatio!!, 0.0)
    }

    // ---------------------------------------------------------------- recovery risk

    @Test
    fun aPoorCompletionStreakNeedsTwoConsecutiveUnfinishedSessions() {
        val oneUnfinished = listOf(
            repSession(12, completedReps = 20, plannedReps = 20),
            repSession(13, completedReps = 20, plannedReps = 20),
            repSession(14, completedReps = 10, plannedReps = 20)
        )
        val twoUnfinished = listOf(
            repSession(12, completedReps = 20, plannedReps = 20),
            repSession(13, completedReps = 10, plannedReps = 20),
            repSession(14, completedReps = 10, plannedReps = 20)
        )
        val brokenByTheNewestSession = listOf(
            repSession(12, completedReps = 10, plannedReps = 20),
            repSession(13, completedReps = 10, plannedReps = 20),
            repSession(14, completedReps = 20, plannedReps = 20)
        )

        assertFalse(calculate(oneUnfinished).recoveryRisk.poorCompletionStreak)
        assertTrue(calculate(twoUnfinished).recoveryRisk.poorCompletionStreak)
        assertFalse(calculate(brokenByTheNewestSession).recoveryRisk.poorCompletionStreak)
    }

    @Test
    fun abandonmentIsASessionThatEndedWithoutSatisfyingCompletion() {
        val endedEarly = listOf(repSession(14, completedReps = 10, plannedReps = 20, finishedAt = 14L * MILLIS_PER_DAY + 600_000L))
        val stillOpen = listOf(repSession(14, completedReps = 10, plannedReps = 20))
        val endedLongAgo = listOf(
            repSession(8, completedReps = 10, plannedReps = 20, finishedAt = 8L * MILLIS_PER_DAY + 600_000L)
        ) + (9..14).map { day -> repSession(day, completedReps = 20, plannedReps = 20) }

        // A partial session whose end is established is abandonment; one without an end stamp is not
        // established as ended, and an abandonment older than the recent session window is not recent.
        assertTrue(calculate(endedEarly).recoveryRisk.recentAbandonment)
        assertFalse(calculate(stillOpen).recoveryRisk.recentAbandonment)
        assertFalse(calculate(endedLongAgo).recoveryRisk.recentAbandonment)
    }

    @Test
    fun highLoadWithADeclineIsTheHighLoadDeteriorationPattern() {
        // Preceding window: 6 sets at half exposure. Recent window: 120 sets, exposures falling hard.
        val history = listOf(loadSession(1, sets = 6, completedReps = 20)) +
            listOf(
                loadSession(9, sets = 30, completedReps = 24),
                loadSession(11, sets = 30, completedReps = 20),
                loadSession(13, sets = 30, completedReps = 14),
                loadSession(14, sets = 30, completedReps = 10)
            )

        val risk = calculate(history).recoveryRisk

        assertTrue(risk.highRecentLoad)
        assertTrue(risk.performanceDecline)
        assertEquals(RecoveryRiskPattern.HIGH_LOAD_DETERIORATION, risk.pattern)
    }

    @Test
    fun aSingleWeakSessionIsNotAHighRiskWindow() {
        val risk = calculate(listOf(repSession(14, completedReps = 6, plannedReps = 20))).recoveryRisk

        // Low exposure from one session, but no established trend and no load baseline.
        assertFalse(risk.performanceDecline)
        assertFalse(risk.highRecentLoad)
        assertNull(risk.pattern)
    }

    @Test
    fun prolongedLowExposureWithADeclineQualifiesWithoutHighLoad() {
        val history = listOf(
            repSession(12, completedReps = 20, plannedReps = 40),
            repSession(13, completedReps = 16, plannedReps = 40),
            repSession(14, completedReps = 12, plannedReps = 40)
        )

        val signals = calculate(history)
        val risk = signals.recoveryRisk

        assertTrue(signals.exposureScore < policy.recoveryStrongLowExposureScore)
        assertTrue(risk.performanceDecline)
        assertFalse(risk.highRecentLoad)
        assertEquals(RecoveryRiskPattern.LOW_EXPOSURE_DECLINE, risk.pattern)
    }

    @Test
    fun highLoadAloneIsNotRecoveryRisk() {
        val history = listOf(loadSession(1, sets = 6, completedReps = 40)) +
            listOf(9, 11, 13, 14).map { day -> loadSession(day, sets = 30, completedReps = 40) }

        val signals = calculate(history)

        assertEquals(RecentLoadBucket.HIGH, signals.recentLoadBucket)
        assertTrue(signals.recoveryRisk.highRecentLoad)
        assertFalse(signals.recoveryRisk.performanceDecline)
        assertNull(signals.recoveryRisk.pattern)
    }

    @Test
    fun everyFlagAndPatternStaysConsistentWithTheSignalsItIsDerivedFrom() {
        val history = listOf(sessionOfSets(1, sets = 10)) + (12..14).map { day -> repSession(day, completedReps = 4, plannedReps = 20) }

        val signals = calculate(history)
        val risk = signals.recoveryRisk

        assertEquals(signals.recentLoadBucket == RecentLoadBucket.HIGH, risk.highRecentLoad)
        assertEquals(signals.performanceTrend == PerformanceTrend.NEGATIVE, risk.performanceDecline)
        assertFalse(risk.pattern == RecoveryRiskPattern.HIGH_LOAD_DETERIORATION && !risk.highRecentLoad)
        assertFalse(risk.pattern == RecoveryRiskPattern.LOW_EXPOSURE_DECLINE && !risk.performanceDecline)
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun identicalHistoryYieldsIdenticalSignals() {
        val history = (1..9).map { day -> repSession(day, completedReps = day, plannedReps = 20) }
        val first = calculate(history)
        val second = calculate(history)

        assertEquals(first, second)
        assertEquals(calculate(history.toList()), calculate(history.toList()))
    }

    @Test
    fun opportunitiesOutsideTheActiveWindowsDoNotChangeTheResult() {
        val history = (30..45).map { day -> repSession(day, completedReps = 10, plannedReps = 20) }
        val withAnOlderPerfectSession = listOf(repSession(1, completedReps = 20, plannedReps = 20)) + history

        // Day 1 is older than every window the signals read: neither the exposure score, adherence,
        // consistency, the trends nor the load windows may move.
        assertEquals(calculate(history), calculate(withAnOlderPerfectSession))
    }

    // ---------------------------------------------------------------- policy values

    @Test
    fun theSignalParametersPinTheirV1Values() {
        assertEquals(5, policy.performanceTrendExposures)
        assertEquals(3, policy.performanceTrendMinimumExposures)
        assertEquals(2, policy.poorCompletionStreakSessions)
        assertEquals(0.10, policy.adherenceMeaningfulStartMinWorkRatio, 0.0)
        assertEquals(7, policy.recentLoadWindowDays)
        assertEquals(0.75, policy.consistencyHighMinRatio, 0.0)
        assertEquals(0.50, policy.consistencyMediumMinRatio, 0.0)
    }

    @Test
    fun thePolicyBucketsConsistencyThroughItsOwnThresholds() {
        assertEquals(ConsistencyBucket.HIGH, policy.consistencyOf(0.75))
        assertEquals(ConsistencyBucket.MEDIUM, policy.consistencyOf(0.7499))
        assertEquals(ConsistencyBucket.MEDIUM, policy.consistencyOf(0.50))
        assertEquals(ConsistencyBucket.LOW, policy.consistencyOf(0.4999))
    }

    // ---------------------------------------------------------------- helpers

    private fun calculate(
        history: List<SessionObservation>,
        families: Map<String, String> = emptyMap()
    ): AdaptiveSignals = AdaptiveSignalCalculator.calculate(history, policy, families)

    /** A one-exercise repetition session whose exposure is `completedReps / plannedReps`. */
    private fun repSession(
        day: Int,
        completedReps: Int,
        plannedReps: Int = 20,
        cycle: Int = 1,
        finishedAt: Long? = null
    ): SessionObservation {
        val outcome = when {
            completedReps <= 0 -> SessionOutcome.NOT_STARTED
            completedReps >= plannedReps -> SessionOutcome.COMPLETED
            else -> SessionOutcome.PARTIAL
        }
        return session(day, outcome, listOf(reps("pushups", plannedReps, completedReps)), finishedAt, cycle)
    }

    /** A session whose single exercise performed `sets` full sets of 10 repetitions. */
    private fun sessionOfSets(day: Int, sets: Int): SessionObservation =
        session(day, SessionOutcome.COMPLETED, listOf(reps("pushups", plannedReps = sets * 10, completedReps = sets * 10, sets = sets)))

    /** A session that performed `sets` sets of 10 prescribed repetitions, completing `completedReps`. */
    private fun loadSession(day: Int, sets: Int, completedReps: Int): SessionObservation =
        session(day, SessionOutcome.PARTIAL, listOf(reps("pushups", plannedReps = 40, completedReps = completedReps, sets = sets)))

    private fun reps(exerciseId: String, plannedReps: Int, completedReps: Int, sets: Int = 3): ExerciseResult =
        ExerciseResult(
            exerciseId = exerciseId,
            plannedSets = sets,
            completedSets = if (completedReps > 0) sets else 0,
            plannedReps = plannedReps,
            completedReps = completedReps,
            plannedDurationSeconds = 0,
            completedDurationSeconds = 0
        )

    private fun timer(exerciseId: String, plannedSeconds: Int, completedSeconds: Int, sets: Int = 3): ExerciseResult =
        ExerciseResult(
            exerciseId = exerciseId,
            plannedSets = sets,
            completedSets = if (completedSeconds > 0) sets else 0,
            plannedReps = 0,
            completedReps = 0,
            plannedDurationSeconds = plannedSeconds,
            completedDurationSeconds = completedSeconds
        )

    /** One observation, its session aggregates derived from its results exactly as the mapper derives them. */
    private fun session(
        day: Int,
        outcome: SessionOutcome,
        results: List<ExerciseResult>,
        finishedAt: Long? = null,
        cycle: Int = 1
    ): SessionObservation = SessionObservation(
        cycleNumber = cycle,
        programDay = day,
        startedAt = if (outcome == SessionOutcome.NOT_STARTED) null else day.toLong() * MILLIS_PER_DAY,
        finishedAt = when (outcome) {
            SessionOutcome.NOT_STARTED -> null
            SessionOutcome.COMPLETED -> finishedAt ?: (day.toLong() * MILLIS_PER_DAY + MILLIS_PER_DAY / 2)
            SessionOutcome.PARTIAL -> finishedAt
        },
        outcome = outcome,
        plannedExercises = results.size,
        completedExercises = results.count { it.completedSets > 0 },
        plannedWork = Workload(
            sets = results.sumOf { it.plannedSets },
            reps = results.sumOf { it.plannedReps },
            durationSeconds = results.sumOf { it.plannedDurationSeconds }
        ),
        actualWork = Workload(
            sets = results.sumOf { it.completedSets },
            reps = results.sumOf { it.completedReps },
            durationSeconds = results.sumOf { it.completedDurationSeconds }
        ),
        exerciseResults = results
    )

    /** Six attended and two skipped opportunities among the eight newest: 6/8 is the HIGH boundary. */
    private fun consistencyOfAttended(attended: Int): ConsistencyBucket {
        val history = (1..8).map { position ->
            repSession(position, completedReps = if (position <= attended) 20 else 0, plannedReps = 20)
        }
        return calculate(history).consistency
    }

    /** A preceding-window workload on day 1 and a recent-window workload on day 14. */
    private fun loadSignals(previousSets: Int, recentSets: Int): AdaptiveSignals =
        calculate(listOf(sessionOfSets(1, previousSets), sessionOfSets(14, recentSets)))

    private fun loadRatioBucket(previousSets: Int, recentSets: Int): RecentLoadBucket =
        loadSignals(previousSets, recentSets).recentLoadBucket

    private companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
