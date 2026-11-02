package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.medianKnownRestSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The density channel reads a rest the window actually recorded, and reports **nothing** when none was.
 *
 * `DensityLoad.restSeconds` has no "unknown", so the recent side carries `0` when no set recorded a rest
 * — the reading it had before rest was stored at all. This pins that an unrecorded rest cannot become a
 * comparison: the channel stays `NOTHING_RECORDED` rather than reporting that the user rested less than
 * the plan asked for.
 */
class ProgramRestChannelTest {

    private fun profile(restSeconds: Int): LoadProfile = LoadProfile(
        scope = AdaptiveScope.FAMILY,
        volume = VolumeLoad(sets = 3, repetitions = 30),
        intensity = IntensityLoad(),
        density = DensityLoad(workingSeconds = 90, restSeconds = restSeconds),
        exposure = ExposureLoad(opportunities = 2, completedOpportunities = 1)
    )

    @Test
    fun aWindowWithNoKnownRestCarriesNoRestSignal() {
        val plan = profile(restSeconds = 0)
        val recent = profile(restSeconds = medianKnownRestSeconds(listOf(null, null)) ?: 0)

        assertEquals(
            "an unrecorded rest is the channel's existing \"nothing recorded\", identical to the window " +
                "that had no rest column at all",
            ProgramChannelComparison.Incomparable(
                channel = ProgramLoadChannel.DENSITY_REST_SECONDS,
                subject = null,
                reason = ProgramIncomparableReason.NOTHING_RECORDED
            ),
            ProgramLoadComparison.of(plan, recent).channelOf(ProgramLoadChannel.DENSITY_REST_SECONDS)
        )
    }

    @Test
    fun aRecordedRestReachesTheChannelAsTheWindowsMedian() {
        val plan = profile(restSeconds = 0)
        val recent = profile(restSeconds = medianKnownRestSeconds(listOf(60, null, 90))!!)

        assertEquals(
            "the recent side carries the measured rest, so the channel states it instead of dropping it",
            ProgramChannelComparison.Compared(
                channel = ProgramLoadChannel.DENSITY_REST_SECONDS,
                subject = null,
                baseline = 0,
                candidate = 75
            ),
            ProgramLoadComparison.of(plan, recent).channelOf(ProgramLoadChannel.DENSITY_REST_SECONDS)
        )
    }
}
