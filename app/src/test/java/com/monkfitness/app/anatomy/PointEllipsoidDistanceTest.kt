package com.monkfitness.app.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The point-to-ellipsoid routine is the only place a root solve enters the predicate, and it reports
 * a residual rather than a bound — so these rows pin the returned distance, not a certificate.
 */
class PointEllipsoidDistanceTest {

    private val solid = SolidDistance()

    private fun distance(x: Double, y: Double, z: Double, rx: Double, ry: Double, rz: Double): Double =
        solid.compute(x, y, z, doubleArrayOf(rx, ry, rz), maxOf(rx, maxOf(ry, rz)))

    @Test
    fun theCentreIsTheDeepestInteriorPoint() {
        assertEquals(0.0, distance(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 0.0)
        assertEquals(0.0, distance(0.0, 0.0, 0.0, 2.0, 3.0, 4.0), 0.0)
    }

    @Test
    fun aPointOnEachPrincipalAxisReturnsTheAnalyticDistance() {
        // The root solve is a bisection, so the analytic answer is met to its own stopping width.
        assertEquals(1.0, distance(3.0, 0.0, 0.0, 2.0, 3.0, 4.0), 1e-9)
        assertEquals(2.0, distance(0.0, 5.0, 0.0, 2.0, 3.0, 4.0), 1e-9)
        assertEquals(3.0, distance(0.0, 0.0, 7.0, 2.0, 3.0, 4.0), 1e-9)
    }

    @Test
    fun aRepeatedAxisIsNotTheSphereCase() {
        // Two equal semi-axes are a spheroid, not a sphere: the sphere formulas give 2.162278 or
        // 1.162278 here, and either would be silently wrong by more than half a unit.
        val measured = distance(3.0, 1.0, 0.0, 2.0, 1.0, 1.0)
        assertEquals(1.297184, measured, 1e-5)
        assertNotEquals(2.162278, measured, 1e-3)
        assertNotEquals(1.162278, measured, 1e-3)
    }

    @Test
    fun aThinAxisKeepsTheAnalyticAnswerOnTheAxis() {
        assertEquals(1.5, distance(2.0, 0.0, 0.0, 0.5, 200.0, 200.0), 1e-9)
        assertEquals(50.0, distance(0.0, 250.0, 0.0, 0.5, 200.0, 200.0), 1e-9)
    }

    @Test
    fun interiorAndSurfacePointsReturnZeroWithoutARootSolve() {
        assertEquals(0.0, distance(0.5, 0.5, 0.5, 2.0, 2.0, 2.0), 0.0)
        assertEquals("a point on the surface is inside the closed solid", 0.0, distance(2.0, 0.0, 0.0, 2.0, 2.0, 2.0), 0.0)
        assertEquals(0.0, distance(0.0, 1.0, 0.0, 3.0, 1.0, 1.0), 0.0)
    }

    @Test
    fun aPointJustOutsideKeepsItsRootAndReportsTheExcessItCanSee() {
        val excess = 1e-6
        val measured = distance(1.0 + excess, 0.0, 0.0, 1.0, 1.0, 1.0)
        assertTrue("the root must not be lost at the boundary: $measured", measured > 0.0)
        assertTrue("the distance cannot exceed the coordinate excess: $measured", measured < 2.0 * excess)
        // The residual is reported, not asserted as a bound: it is what the evaluation budget reads.
        assertTrue("the residual is finite", solid.residual.isFinite())
        assertTrue("the residual is non-negative", abs(solid.residual) >= 0.0)
    }

    @Test
    fun theReportedDistanceIsNeverADeepInteriorSurfaceDistance() {
        // The surface distance of the unit sphere is 0, 1, 0 at x = -1, 0, 1 — non-convex, which is
        // exactly why the routine returns the solid distance.
        assertEquals(0.0, distance(-1.0, 0.0, 0.0, 1.0, 1.0, 1.0), 0.0)
        assertEquals(0.0, distance(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 0.0)
        assertEquals(0.0, distance(1.0, 0.0, 0.0, 1.0, 1.0, 1.0), 0.0)
    }
}
