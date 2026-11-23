package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-stage ring: windows stay per stage, wrap to the newest samples, and read zero until measured. */
class FrameStageTimingsTest {

    @Test
    fun anUnmeasuredStageReadsZeroRatherThanGuessing() {
        val timings = FrameStageTimings(capacity = 8)
        assertEquals(0, timings.sampleCount(FrameStage.POSE_BUILD))
        assertEquals(0f, timings.percentileMs(FrameStage.POSE_BUILD, 0.95f), 0f)
    }

    @Test
    fun aStageThatDidNotRunStillCountsItsSample() {
        val timings = FrameStageTimings(capacity = 8)
        timings.record(FrameStage.ANATOMY_SOLVE, 0L)
        assertEquals("a disabled stage must still align with the frames it skipped", 1, timings.sampleCount(FrameStage.ANATOMY_SOLVE))
        assertEquals(0f, timings.percentileMs(FrameStage.ANATOMY_SOLVE, 0.5f), 0f)
    }

    @Test
    fun eachStageKeepsItsOwnWindow() {
        val timings = FrameStageTimings(capacity = 8)
        repeat(8) {
            timings.record(FrameStage.POSE_BUILD, 1_000_000L)
            timings.record(FrameStage.RENDER_END_FRAME, 4_000_000L)
        }
        assertEquals(1f, timings.percentileMs(FrameStage.POSE_BUILD, 0.5f), 0f)
        assertEquals(4f, timings.percentileMs(FrameStage.RENDER_END_FRAME, 0.95f), 0f)
    }

    @Test
    fun theWindowHoldsOnlyTheNewestSamplesAfterItWraps() {
        val timings = FrameStageTimings(capacity = 4)
        repeat(4) { timings.record(FrameStage.ANATOMY_SOLVE, 9_000_000L) }
        repeat(4) { timings.record(FrameStage.ANATOMY_SOLVE, 2_000_000L) }
        assertEquals(
            "the older 9 ms frames must have been overwritten, not averaged in",
            2f,
            timings.percentileMs(FrameStage.ANATOMY_SOLVE, 0.5f),
            0f
        )
        assertEquals(4, timings.sampleCount(FrameStage.ANATOMY_SOLVE))
    }

    @Test
    fun percentilesReadThatStagesSamplesInOrder() {
        val timings = FrameStageTimings(capacity = 100)
        for (millis in 1..100) timings.record(FrameStage.TUBE_WRITE_UPLOAD, millis * 1_000_000L)
        val p50 = timings.percentileMs(FrameStage.TUBE_WRITE_UPLOAD, 0.5f)
        val p95 = timings.percentileMs(FrameStage.TUBE_WRITE_UPLOAD, 0.95f)
        assertEquals(50f, p50, 0f)
        assertEquals(95f, p95, 0f)
        assertTrue("p95 cannot read below the median", p95 > p50)
    }
}
