package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.FAMILY
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.decline
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.exposure
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.improvement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.observation
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.plateau
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.profile
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.signals
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.snapshot
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13's signals, measured from the window's own facts.
 *
 * The claims this suite has to hold are the ones §12 states in negatives: a missed opportunity is not a
 * zero, missing history is not a failure, an idle window is not a decline, and a trend is only ever
 * reported once it has been measured. Each of them is asserted against the counts the calculator
 * produces rather than against prose.
 */
class ProgramAdaptiveSignalCalculatorTest {

    private val policy = ProgramAdaptivePolicy.V1

    private val families = mapOf(
        ProgramAdaptiveRig.EASIER to FAMILY,
        ProgramAdaptiveRig.PRESENTED to FAMILY,
        ProgramAdaptiveRig.HARDER to FAMILY
    )

    private fun signalsOf(
        exposures: List<com.monkfitness.app.domain.adaptive.ExposureObservation>,
        familyId: String = FAMILY,
        baselineLoad: com.monkfitness.app.domain.adaptive.LoadProfile = profile(),
        familyOfExercise: Map<String, String> = families
    ): ProgramAdaptiveSignals = ProgramAdaptiveSignalCalculator.calculate(
        snapshot = snapshot(exposures = exposures, baselineLoad = baselineLoad),
        familyOfExercise = familyOfExercise,
        familyId = familyId,
        policy = policy
    )

    // ------------------------------------------------------------------ counts and comparability

    @Test
    fun theHistoryOfAFamilyIsCountedFromItsOwnOccurrencesAndFromNothingElse() {
        val signals = ProgramAdaptiveSignalCalculator.calculate(
            snapshot = snapshot(
                exposures = listOf(
                    observation(index = 0, prescribed = 3, completed = 3),
                    observation(index = 1, prescribed = 3, completed = 1, exerciseId = "squats"),
                    observation(index = 2, prescribed = 3, completed = 3, exerciseId = ProgramAdaptiveRig.HARDER),
                    observation(index = 3, prescribed = 3, completed = 2, exerciseId = "squats")
                )
            ),
            familyOfExercise = families + ("squats" to "squats"),
            familyId = FAMILY,
            policy = policy
        )

        assertEquals("only the push-up family's own occurrences count", 2, signals.exposure.exposures)
        assertEquals(6, signals.exposure.prescribedSets)
        assertEquals(6, signals.exposure.completedSets)
        assertEquals(2, signals.exposure.fullExposures)
    }

    @Test
    fun anExerciseTheClassificationDoesNotKnowIsItsOwnFamily() {
        val signals = signalsOf(
            exposures = listOf(
                observation(index = 0, prescribed = 3, completed = 3, exerciseId = "mystery_press"),
                observation(index = 1, prescribed = 3, completed = 3)
            ),
            familyId = "mystery_press"
        )

        assertEquals(1, signals.exposure.exposures)
        assertEquals("mystery_press", signals.familyId)
    }

    // ------------------------------------------------------------------ what is missing stays missing

    @Test
    fun aTrendIsOnlyReportedOnceItHasBeenMeasured() {
        val twoExposures = signalsOf(exposures = plateau().take(2))
        val threeExposures = signalsOf(exposures = plateau().take(3))

        assertNull("two exposures are not enough to claim a direction", twoExposures.trend)
        assertNull(twoExposures.olderHalf)
        assertNull(twoExposures.newerHalf)
        assertEquals(ProgramPerformanceTrend.STABLE, threeExposures.trend)
    }

    @Test
    fun anIdleWindowIsIdleAndNotADecline() {
        val signals = signalsOf(exposures = emptyList())

        assertEquals(0, signals.exposure.exposures)
        assertTrue(signals.isIdle)
        assertNull("an untrained window states no direction at all", signals.trend)
    }

    @Test
    fun aMissedOpportunityIsNotAnExposureAndNotAZero() {
        val signals = signalsOf(
            exposures = listOf(observation(index = 0, prescribed = 3, completed = 3)),
            baselineLoad = profile(opportunities = 4, completedOpportunities = 1)
        )

        assertEquals(
            "the one occurrence that happened is one exposure — not four, and not a zero",
            1, signals.exposure.exposures
        )
        assertEquals(
            "and the opportunities that went untaken are attendance, which is where they are read",
            ProgramConsistency.LOW, signals.consistency
        )
        assertNull(signals.trend)
    }

    // ------------------------------------------------------------------ the trend's own arithmetic

    @Test
    fun theTrendReadsWhatTheNewerHalfAchievedAgainstWhatTheOlderHalfWasAskedFor() {
        val improving = signalsOf(exposures = improvement())
        val declining = signalsOf(exposures = decline())
        val plateaued = signalsOf(exposures = plateau())

        assertEquals(ProgramPerformanceTrend.POSITIVE, improving.trend)
        assertEquals(ProgramPerformanceTrend.NEGATIVE, declining.trend)
        assertEquals(ProgramPerformanceTrend.STABLE, plateaued.trend)
        assertEquals(2, improving.olderHalf!!.exposures)
        assertEquals(2, improving.newerHalf!!.exposures)
        assertTrue("the recent end of an improving window fell short of nothing", !improving.newerHalf!!.hasShortfall)
        assertTrue("and the recent end of a declining one did", declining.newerHalf!!.hasShortfall)
    }

    @Test
    fun anOddWindowIsSplitAtAFixedPositionAndTheNewerHalfTakesTheLargerShare() {
        val signals = signalsOf(
            exposures = listOf(
                observation(index = 0, prescribed = 3, completed = 3),
                observation(index = 1, prescribed = 3, completed = 3),
                observation(index = 2, prescribed = 3, completed = 3),
                observation(index = 3, prescribed = 3, completed = 3),
                observation(index = 4, prescribed = 3, completed = 3)
            )
        )

        assertEquals(2, signals.olderHalf!!.exposures)
        assertEquals(3, signals.newerHalf!!.exposures)
        assertEquals(5, signals.exposure.exposures)
    }

    // ------------------------------------------------------------------ attendance

    @Test
    fun attendanceIsMeasuredOverTheOpportunitiesThePlanItselfOffered() {
        assertEquals(
            ProgramConsistency.HIGH,
            signalsOf(
                exposures = emptyList(),
                baselineLoad = profile(opportunities = 8, completedOpportunities = 6)
            ).consistency
        )
        assertEquals(
            ProgramConsistency.MEDIUM,
            signalsOf(
                exposures = emptyList(),
                baselineLoad = profile(opportunities = 4, completedOpportunities = 2)
            ).consistency
        )
        assertEquals(
            ProgramConsistency.LOW,
            signalsOf(
                exposures = emptyList(),
                baselineLoad = profile(opportunities = 3, completedOpportunities = 1)
            ).consistency
        )
        assertNull(
            "a plan that states no opportunity states no attendance either",
            signalsOf(exposures = emptyList()).consistency
        )
    }

    // ------------------------------------------------------------------ the recent context

    @Test
    fun theRecentContextIsComparedAgainstWhatThePlanPrescribes() {
        val withContext = ProgramAdaptiveSignalCalculator.calculate(
            snapshot = snapshot(
                baselineLoad = profile(scope = AdaptiveScope.EXERCISE, repetitions = 30, level = 2),
                recentLoad = profile(scope = AdaptiveScope.EXERCISE, repetitions = 45, level = 3)
            ),
            familyOfExercise = families,
            familyId = FAMILY,
            policy = policy
        )

        assertNull("with no recent load there is nothing to compare", signalsOf(emptyList()).recentContext)
        assertTrue(withContext.recentIsAboveBaseline)
        assertEquals(
            15,
            (withContext.recentContext!!.channelOf(ProgramLoadChannel.VOLUME_REPETITIONS)
                as ProgramChannelComparison.Compared).delta
        )
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun theSignalsAreAFunctionOfTheWindowAndNotOfACollectionOrder() {
        val forward = LinkedHashMap(families)
        val backward = LinkedHashMap<String, String>()
        families.keys.toList().reversed().forEach { backward[it] = FAMILY }

        val first = signalsOf(exposures = improvement(), familyOfExercise = forward)
        val second = signalsOf(exposures = improvement(), familyOfExercise = backward)

        assertEquals(first, second)
    }

    @Test
    fun anOutOfOrderObservationListIsRefusedRatherThanSilentlySorted() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            snapshot(exposures = improvement().reversed())
        }

        assertTrue(
            "the window's order is part of the value, which is why the signals can never depend on a " +
                "caller's list order: ${failure.message}",
            failure.message!!.contains("chronologically")
        )
    }

    @Test
    fun theEvidenceTheCallerStatesIsCarriedAndNeverDerived() {
        val signals = ProgramAdaptiveRig.evidence(
            window = window(),
            signals = signalsOf(exposures = plateau()),
            evidence = EvidenceLevel.STABLE
        )

        assertEquals(EvidenceLevel.STABLE, signals.evidence)
        assertEquals(
            "the signals say what was measured; whether that is enough is the caller's statement",
            ProgramPerformanceTrend.STABLE, signals.signals.trend
        )
        assertEquals(exposure(exposures = 4, prescribedSets = 12, completedSets = 12), signals.signals.exposure)
    }
}
