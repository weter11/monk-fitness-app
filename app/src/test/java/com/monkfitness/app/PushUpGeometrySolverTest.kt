package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PushUpGeometrySolverTest {

    @Test
    fun testStandardPushUpGeometrySolver() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val support = SupportDefinition(PivotType.FEET, emptySet(), 0f)
        val gripMultiplier = 1.5f

        val resultTop = PushUpGeometrySolver.solve(def, support, gripMultiplier, 0.0f, PushUpSolverResult())
        val resultBottom = PushUpGeometrySolver.solve(def, support, gripMultiplier, 1.0f, PushUpSolverResult())

        // Standard PushUp top: pelvisHeight is approximately 60f
        assertEquals(60f, resultTop.pelvisHeight, 0.2f)
        // Standard PushUp bottom: pelvisHeight is approximately 40f
        assertEquals(40f, resultBottom.pelvisHeight, 0.2f)

        // Hand anchor horizontal position is stable across reps
        assertEquals(resultTop.handAnchorX, resultBottom.handAnchorX, 0.2f)

        // Ankle position is updated to keep the shoulders aligned with hands
        assertTrue(resultTop.ankleX > 60f)
        assertTrue(resultBottom.ankleX > 60f)
        assertTrue(resultTop.ankleX < resultBottom.ankleX)
    }

    @Test
    fun testDeclinePushUpGeometrySolver() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val support = SupportDefinition(PivotType.FEET, emptySet(), 40f)
        val gripMultiplier = 1.5f

        val resultTop = PushUpGeometrySolver.solve(def, support, gripMultiplier, 0.0f, PushUpSolverResult())
        val resultBottom = PushUpGeometrySolver.solve(def, support, gripMultiplier, 1.0f, PushUpSolverResult())

        // Ankle height is elevated by the decline box height (25f + 40f = 65f)
        assertEquals(65f, resultTop.ankleHeight, 0.2f)
        assertEquals(65f, resultBottom.ankleHeight, 0.2f)

        // Pelvis height is elevated compared to standard push-up
        assertTrue(resultTop.pelvisHeight > 60f)
        assertTrue(resultBottom.pelvisHeight > 40f)
    }

    @Test
    fun testKneePushUpGeometrySolver() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val support = SupportDefinition(PivotType.KNEES, emptySet(), 0f)
        val gripMultiplier = 1.8f

        val resultTop = PushUpGeometrySolver.solve(def, support, gripMultiplier, 0.0f, PushUpSolverResult())
        val resultBottom = PushUpGeometrySolver.solve(def, support, gripMultiplier, 1.0f, PushUpSolverResult())

        // Knee height is 15f
        assertEquals(15f, resultTop.kneeHeight, 0.2f)

        // ROM is not compressed: top is significantly higher than bottom
        val rom = resultTop.pelvisHeight - resultBottom.pelvisHeight
        assertTrue("Knee push-up ROM should be comfortably large, found: $rom", rom > 10f)

        // Checking alignment logic
        assertTrue(resultTop.kneeX > 60f)
        assertTrue(resultBottom.kneeX > 60f)
    }

    @Test
    fun testGripMultiplierInfluence() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val support = SupportDefinition(PivotType.FEET, emptySet(), 0f)

        val standardTop = PushUpGeometrySolver.solve(def, support, 1.5f, 0.0f, PushUpSolverResult())
        val militaryTop = PushUpGeometrySolver.solve(def, support, 1.0f, 0.0f, PushUpSolverResult())

        // Under unified geometry, the body incline and pelvis height remain perfectly aligned
        assertEquals(standardTop.pelvisHeight, militaryTop.pelvisHeight, 0.01f)
        assertEquals(standardTop.theta, militaryTop.theta, 0.01f)
    }
}
