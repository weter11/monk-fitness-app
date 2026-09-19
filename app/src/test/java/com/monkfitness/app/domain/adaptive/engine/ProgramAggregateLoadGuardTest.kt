package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.FAMILY
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.profile
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §18's aggregate load guard, measured at its own boundary.
 *
 * The guard is a component and not a step inside the engine, so it is exercised here directly, at every
 * scope §18 names, with profiles stated by hand. That is what makes its refusals checkable: a rule is
 * only proven when the *exact* channel it is about moves, when a channel it may not compare stays out
 * of the verdict, and when the same three profiles always produce the same verdict.
 */
class ProgramAggregateLoadGuardTest {

    private val policy = ProgramAdaptivePolicy.V1

    private val baseline =
        profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 2)

    // ------------------------------------------------------------------ what it approves

    @Test
    fun aSafeCandidateStaysApproved() {
        val verdict = approved(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 3),
                policy = policy
            )
        )

        assertEquals(AdaptiveScope.EXERCISE, verdict.scope)
        assertEquals(
            "the element's own volume did not move",
            0,
            compared(verdict.delta, ProgramLoadChannel.VOLUME_REPETITIONS).delta
        )
        assertEquals(
            "and the family's own level moved one declared step",
            1,
            compared(verdict.delta, ProgramLoadChannel.INTENSITY_LEVEL, FAMILY).delta
        )
    }

    @Test
    fun aChangeWhoseUnitsAreNotComparableIsNotRefusedOnTheirAccount() {
        // A duration-based element against a repetition-based one: the two volume channels share no
        // unit, so neither can be evidence of excess — and the change *reduces* working time, which is
        // not an excess on any channel either.
        val durationBased = profile(scope = AdaptiveScope.EXERCISE, sets = 3, seconds = 90)
        val repetitionBased = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30)

        val verdict = approved(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = durationBased,
                candidateLoad = repetitionBased,
                policy = policy
            )
        )

        assertEquals(
            "a channel the two sides do not share a unit for is not evidence of excess",
            ProgramIncomparableReason.DIFFERENT_UNIT,
            incomparable(verdict.delta, ProgramLoadChannel.VOLUME_REPETITIONS).reason
        )
        assertEquals(
            ProgramIncomparableReason.DIFFERENT_UNIT,
            incomparable(verdict.delta, ProgramLoadChannel.VOLUME_SECONDS).reason
        )
        assertFalse(
            "and the working-time channel fell rather than rose",
            compared(verdict.delta, ProgramLoadChannel.DENSITY_WORKING_SECONDS).increases
        )
    }

    @Test
    fun aLongerWorkingTimeIsRefusedOnItsOwnChannel() {
        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, seconds = 30),
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, seconds = 60),
                policy = policy
            )
        )

        assertEquals(
            "a longer prescription raises the seconds the element prescribes *and* the working time it " +
                "occupies: the two channels are separate readings of the same prescription (§17)",
            listOf(ProgramLoadChannel.VOLUME_SECONDS, ProgramLoadChannel.DENSITY_WORKING_SECONDS),
            verdict.exceeded.map { it.channel }
        )
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    fun anExcessiveAutomaticIncreaseIsFilteredAndNamesTheChannel() {
        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 60, level = 3),
                policy = policy
            )
        )

        assertEquals(ProgramGuardReason.EXCESSIVE_AUTOMATIC_INCREASE, verdict.reason)
        assertEquals(listOf(ProgramLoadChannel.VOLUME_REPETITIONS), verdict.exceeded.map { it.channel })
    }

    @Test
    fun theToleranceIsTheExactIntegerBoundaryThePolicyStates() {
        // +25% of 30 repetitions is 37.5: 37 is inside it and 38 is not.
        val quarter = policy.copy(
            guardAllowedAmountIncreaseNumerator = 1,
            guardAllowedAmountIncreaseDenominator = 4
        )

        val inside = ProgramAggregateLoadGuard.guard(
            action = AdaptiveAction.PROGRESS,
            baselineLoad = baseline,
            candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 37),
            policy = quarter
        )
        val outside = ProgramAggregateLoadGuard.guard(
            action = AdaptiveAction.PROGRESS,
            baselineLoad = baseline,
            candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 38),
            policy = quarter
        )

        assertTrue("37 repetitions is inside +25% of 30", inside is ProgramGuardVerdict.Approved)
        assertTrue("38 is not", outside is ProgramGuardVerdict.Filtered)
    }

    @Test
    fun restMayNotShortenEvenThoughNoOtherChannelMoved() {
        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = profile(
                    scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, restSeconds = 60
                ),
                candidateLoad = profile(
                    scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, restSeconds = 30
                ),
                policy = policy
            )
        )

        assertEquals(listOf(ProgramLoadChannel.DENSITY_REST_SECONDS), verdict.exceeded.map { it.channel })
    }

    @Test
    fun anAutomaticChangeNeverAddsAnOpportunity() {
        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = profile(
                    scope = AdaptiveScope.SESSION, opportunities = 3, completedOpportunities = 3
                ),
                candidateLoad = profile(
                    scope = AdaptiveScope.SESSION, opportunities = 4, completedOpportunities = 4
                ),
                policy = policy
            )
        )

        assertEquals(listOf(ProgramLoadChannel.EXPOSURE_OPPORTUNITIES), verdict.exceeded.map { it.channel })
    }

    @Test
    fun aJumpOfMoreThanTheAllowedNumberOfLevelsIsRefused() {
        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = profile(scope = AdaptiveScope.EXERCISE, repetitions = 30, level = 1),
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, repetitions = 30, level = 3),
                policy = policy
            )
        )

        assertEquals(listOf(ProgramLoadChannel.INTENSITY_LEVEL), verdict.exceeded.map { it.channel })
    }

    // ------------------------------------------------------------------ what it does not guard

    @Test
    fun onlyAChangeIsGuardedAndNeverACondition() {
        assertEquals(
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.EXERCISE, AdaptiveAction.HOLD),
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.HOLD,
                baselineLoad = baseline,
                candidateLoad = baseline,
                policy = policy
            )
        )
        assertEquals(
            "a regression takes load away, and the guard never rules on it (§18)",
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.EXERCISE, AdaptiveAction.REGRESS),
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.REGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 60),
                policy = policy
            )
        )
        assertEquals(
            "a rest change is a dimension the domain cannot express yet (§10), so it is not guarded " +
                "either — it is reported unsupported where it is asked for",
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.EXERCISE, AdaptiveAction.CHANGE_REST),
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.CHANGE_REST,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, restSeconds = 0),
                policy = policy
            )
        )
    }

    @Test
    fun theGuardNeverReturnsARegression() {
        val verdict = ProgramAggregateLoadGuard.guard(
            action = AdaptiveAction.PROGRESS,
            baselineLoad = baseline,
            candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 6, repetitions = 90),
            policy = policy
        )

        assertTrue(
            "a filtered change is a refusal, not a prescription to reduce",
            verdict is ProgramGuardVerdict.Filtered
        )
        assertEquals(
            ProgramGuardReason.EXCESSIVE_AUTOMATIC_INCREASE,
            (verdict as ProgramGuardVerdict.Filtered).reason
        )
    }

    // ------------------------------------------------------------------ comparability

    @Test
    fun profilesStatedAtDifferentScopesAreNeverCompared() {
        val comparison = ProgramLoadComparison.of(
            profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30),
            profile(scope = AdaptiveScope.SESSION, sets = 3, repetitions = 30)
        )

        assertEquals(AdaptiveScope.EXERCISE, comparison.scope)
        assertTrue("nothing is comparable across two granularities", comparison.comparable.isEmpty())
        assertTrue(
            comparison.incomparable.all { it.reason == ProgramIncomparableReason.DIFFERENT_SCOPE }
        )
        assertFalse(comparison.hasComparableChannel)
        assertFalse(comparison.isAboveBaseline)
        assertFalse(comparison.candidateIsAtOrAboveBaselineEverywhere)
    }

    @Test
    fun aLevelIsOnlyComparedInsideItsOwnFamily() {
        val comparison = ProgramLoadComparison.of(
            profile(scope = AdaptiveScope.FAMILY, repetitions = 30, familyId = "pushups", level = 2),
            profile(scope = AdaptiveScope.FAMILY, repetitions = 30, familyId = "squats", level = 2)
        )

        assertEquals(
            ProgramIncomparableReason.FAMILY_NOT_COVERED,
            incomparable(comparison, ProgramLoadChannel.INTENSITY_LEVEL, "pushups").reason
        )
        assertEquals(
            ProgramIncomparableReason.FAMILY_NOT_COVERED,
            incomparable(comparison, ProgramLoadChannel.INTENSITY_LEVEL, "squats").reason
        )
        assertTrue(comparison.comparable.none { it.channel == ProgramLoadChannel.INTENSITY_LEVEL })
    }

    @Test
    fun everyScopeSectionEightNamesCanBeComparedAtItsOwnGranularity() {
        AdaptiveScope.entries.forEach { scope ->
            val comparison = ProgramLoadComparison.of(
                profile(scope = scope, sets = 3, repetitions = 30),
                profile(scope = scope, sets = 3, repetitions = 30)
            )

            assertEquals(scope, comparison.scope)
            assertTrue(
                "a comparison at $scope reads $scope's own numbers",
                comparison.comparable.isNotEmpty()
            )
        }
    }

    @Test
    fun theChannelsOfAComparisonAreHeldInOneCanonicalOrder() {
        val oneOrder = ProgramLoadComparison.of(
            profile(scope = AdaptiveScope.FAMILY, repetitions = 30, familyId = FAMILY, level = 2),
            profile(scope = AdaptiveScope.FAMILY, repetitions = 30, familyId = FAMILY, level = 3)
        )

        assertEquals(
            ProgramLoadChannel.entries.toList(),
            oneOrder.channels.map { it.channel }
        )
    }

    // ------------------------------------------------------------------ the recent context

    @Test
    fun aRecentContextThatMetThePlanAndOwedNothingDoesNotBlock() {
        val plan = profile(
            scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30,
            opportunities = 4, completedOpportunities = 4
        )

        val verdict = approved(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 3),
                recentBaselineLoad = plan,
                recentLoad = plan,
                policy = policy
            )
        )

        assertFalse(verdict.recentContext!!.isAboveBaseline)
    }

    @Test
    fun aRecentContextAtOrAboveThePlanWithAnUnmetOpportunityBlocksTheChange() {
        val stillOwed = profile(
            scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30,
            opportunities = 4, completedOpportunities = 1
        )

        val verdict = filtered(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 3),
                recentBaselineLoad = stillOwed,
                recentLoad = stillOwed,
                policy = policy
            )
        )

        assertEquals(ProgramGuardReason.RECENT_LOAD_UNMET_OPPORTUNITY, verdict.reason)
        assertEquals(
            "the refusal is the context's, and it names no exceeded channel of the delta itself",
            emptyList<ProgramChannelComparison.Compared>(), verdict.exceeded
        )
    }

    @Test
    fun aRecentContextBelowThePlanDoesNotBlock() {
        val plan = profile(
            scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30,
            opportunities = 4, completedOpportunities = 1
        )
        val lighter = profile(
            scope = AdaptiveScope.EXERCISE, sets = 2, repetitions = 20,
            opportunities = 4, completedOpportunities = 1
        )

        val verdict = approved(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 3),
                recentBaselineLoad = plan,
                recentLoad = lighter,
                policy = policy
            )
        )

        assertTrue(
            "the recent context reads below the plan, so it is not a context that has to be absorbed",
            verdict.recentContext!!.decreases.isNotEmpty()
        )
    }

    @Test
    fun aMissingRecentContextIsMissingAndNotAnAssumption() {
        val verdict = approved(
            ProgramAggregateLoadGuard.guard(
                action = AdaptiveAction.PROGRESS,
                baselineLoad = baseline,
                candidateLoad = profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30, level = 3),
                recentBaselineLoad = null,
                recentLoad = null,
                policy = policy
            )
        )

        assertEquals(null, verdict.recentContext)
    }

    @Test
    fun aComparisonsLevelChannelNamesTheFamilyItIsAbout() {
        val comparison = ProgramAggregateLoadGuard.compare(
            profile(scope = AdaptiveScope.EXERCISE, repetitions = 30, familyId = FAMILY, level = 2),
            profile(scope = AdaptiveScope.EXERCISE, repetitions = 30, familyId = FAMILY, level = 3)
        )

        assertEquals(
            FAMILY,
            compared(comparison, ProgramLoadChannel.INTENSITY_LEVEL, FAMILY).subject
        )
        assertEquals(
            "and never as a scope-wide reading",
            null,
            comparison.channelOf(ProgramLoadChannel.INTENSITY_LEVEL, null)
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun approved(verdict: ProgramGuardVerdict): ProgramGuardVerdict.Approved {
        assertTrue("expected an approval, got $verdict", verdict is ProgramGuardVerdict.Approved)
        return verdict as ProgramGuardVerdict.Approved
    }

    private fun filtered(verdict: ProgramGuardVerdict): ProgramGuardVerdict.Filtered {
        assertTrue("expected a refusal, got $verdict", verdict is ProgramGuardVerdict.Filtered)
        return verdict as ProgramGuardVerdict.Filtered
    }

    private fun compared(
        comparison: ProgramLoadComparison,
        channel: ProgramLoadChannel,
        subject: String? = null
    ): ProgramChannelComparison.Compared {
        val found = comparison.channelOf(channel, subject)
        assertTrue("expected $channel($subject) to be compared, got $found", found is ProgramChannelComparison.Compared)
        return found as ProgramChannelComparison.Compared
    }

    private fun incomparable(
        comparison: ProgramLoadComparison,
        channel: ProgramLoadChannel,
        subject: String? = null
    ): ProgramChannelComparison.Incomparable {
        val found = comparison.channelOf(channel, subject)
        assertTrue(
            "expected $channel($subject) to be incomparable, got $found",
            found is ProgramChannelComparison.Incomparable
        )
        return found as ProgramChannelComparison.Incomparable
    }
}
