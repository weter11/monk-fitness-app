package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

class MotionCurvesTest {

    @Test
    fun testMotionCurvesLinear() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.LINEAR, 0f), 1e-4f)
        assertEquals(0.5f, MotionCurves.transform(MotionCurve.LINEAR, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.LINEAR, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesEaseIn() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.EASE_IN, 0f), 1e-4f)
        assertEquals(0.25f, MotionCurves.transform(MotionCurve.EASE_IN, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.EASE_IN, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesEaseOut() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.EASE_OUT, 0f), 1e-4f)
        assertEquals(0.75f, MotionCurves.transform(MotionCurve.EASE_OUT, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.EASE_OUT, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesEaseInOut() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.EASE_IN_OUT, 0f), 1e-4f)
        assertEquals(0.5f, MotionCurves.transform(MotionCurve.EASE_IN_OUT, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.EASE_IN_OUT, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesSine() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.SINE, 0f), 1e-4f)
        assertTrue(MotionCurves.transform(MotionCurve.SINE, 0.5f) > 0.5f) // sine starts faster than linear
        assertEquals(1f, MotionCurves.transform(MotionCurve.SINE, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesFastDownSlowUp() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.FAST_DOWN_SLOW_UP, 0f), 1e-4f)
        assertEquals(0.125f, MotionCurves.transform(MotionCurve.FAST_DOWN_SLOW_UP, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.FAST_DOWN_SLOW_UP, 1f), 1e-4f)
    }

    @Test
    fun testMotionCurvesSlowDownFastUp() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.SLOW_DOWN_FAST_UP, 0f), 1e-4f)
        assertEquals(0.875f, MotionCurves.transform(MotionCurve.SLOW_DOWN_FAST_UP, 0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.SLOW_DOWN_FAST_UP, 1f), 1e-4f)
    }

    @Test
    fun testClampBehavior() {
        assertEquals(0f, MotionCurves.transform(MotionCurve.LINEAR, -0.5f), 1e-4f)
        assertEquals(1f, MotionCurves.transform(MotionCurve.LINEAR, 1.5f), 1e-4f)
    }
}
