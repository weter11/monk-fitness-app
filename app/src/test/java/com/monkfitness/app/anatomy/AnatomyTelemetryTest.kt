package com.monkfitness.app.anatomy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The solver's own bookkeeping: per-frame durations, a session-cumulative pass histogram, no allocation. */
class AnatomyTelemetryTest {

    @Test
    fun aPassIsCountedUnderTheNumberTheSolverReported() {
        val telemetry = AnatomyTelemetry(passCap = 4)
        telemetry.recordPass(1)
        telemetry.recordPass(1)
        telemetry.recordPass(3)

        assertEquals(2, telemetry.passCount(1))
        assertEquals(1, telemetry.passCount(3))
        assertEquals("an unused pass must read zero, not the previous row", 0, telemetry.passCount(2))
    }

    @Test
    fun aRefusalBeforeTheLoopRunsIsItsOwnRowNotAFailedFirstPass() {
        val telemetry = AnatomyTelemetry(passCap = 4)
        telemetry.recordPass(0)
        telemetry.recordPass(0)
        telemetry.recordPass(1)

        assertEquals("refused before any candidate", 2, telemetry.passCount(0))
        assertEquals("a search that ran and accepted", 1, telemetry.passCount(1))
    }

    @Test
    fun anOutOfRangePassIsClampedRatherThanDropped() {
        val telemetry = AnatomyTelemetry(passCap = 3)
        telemetry.recordPass(99)
        telemetry.recordPass(-4)

        assertEquals("a pass past the cap lands on the cap", 1, telemetry.passCount(3))
        assertEquals("a negative pass is a refusal, not a lost sample", 1, telemetry.passCount(0))
        assertEquals("nothing may be counted outside the histogram", 2, (0..3).sumOf { telemetry.passCount(it) })
    }

    @Test
    fun aNewFrameResetsTheDurationsAndKeepsTheHistogram() {
        val telemetry = AnatomyTelemetry(passCap = 4)
        telemetry.recordPass(2)
        telemetry.beginFrame(heads = 18)
        telemetry.recordDerive(1_000_000L)
        telemetry.recordPaths(9_000_000L)

        assertEquals(18, telemetry.heads)
        assertEquals(1_000_000L, telemetry.deriveNanos)

        telemetry.beginFrame(heads = 18)
        assertEquals("a frame that has not run yet reads zero, not the last one", 0L, telemetry.deriveNanos)
        assertEquals(0L, telemetry.pathsNanos)
        assertEquals("the histogram is cumulative across frames", 1, telemetry.passCount(2))
    }

    @Test
    fun aResetClearsTheHistogramToo() {
        val telemetry = AnatomyTelemetry(passCap = 4)
        telemetry.recordPass(1)
        telemetry.beginFrame(heads = 18)
        telemetry.reset()

        assertEquals(0, telemetry.passCount(1))
        assertEquals(0, telemetry.heads)
    }
}
