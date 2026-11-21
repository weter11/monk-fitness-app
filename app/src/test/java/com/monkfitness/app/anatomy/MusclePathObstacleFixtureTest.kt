package com.monkfitness.app.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §12.2.1 chord and minimiser rows, driven at the pair level where their arithmetic lives.
 *
 * Every expectation is recomputed from the fixture's own inputs, and each crossing row carries a
 * near-miss control so a predicate that rejects everything cannot pass the set.
 */
class MusclePathObstacleFixtureTest {

    private val obstacle = ObstacleGeometry()
    private val verdict = PairVerdict()
    private val zeroRadius = RadiusProfile(listOf(0f to 0f, 1f to 0f), 1.0)
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun sphere(radius: Double = 1.0) {
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, radius, radius, radius)
    }

    private fun test(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        margin: Double = MARGIN,
        radius: RadiusProfile = zeroRadius
    ): PairVerdict {
        obstacle.testSegment(ax, ay, az, bx, by, bz, 0.0, 1.0, radius, margin, verdict)
        return verdict
    }

    @Test
    fun aChordThatMissesTheEllipsoidIsCleared() {
        sphere()
        assertEquals(StageOutcome.CLEAR, test(2.0, 0.0, 0.0, 2.0, 1.0, 0.0).outcome)
        assertEquals("a shorter chord further out is the control", StageOutcome.CLEAR, test(3.0, 0.0, 0.0, 3.0, 1.0, 0.0).outcome)
    }

    @Test
    fun theInfiniteLineCrossingIsNotTheSegmentCrossing() {
        sphere()
        // q0 = (2, 0, 0), qd = (1, 0, 0): Δ = 4 and the roots are [-3, -1], which miss [0, 1].
        val clear = test(2.0, 0.0, 0.0, 3.0, 0.0, 0.0)
        assertEquals(StageOutcome.CLEAR, clear.outcome)
        assertTrue("the roots are diagnostics only", clear.rootLo.isNaN() || clear.rootHi < 0.0)
    }

    @Test
    fun aChordThroughTheCentreIsAWitnessedEntry() {
        sphere()
        val crossing = test(-2.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, crossing.outcome)
        assertTrue("the entry must carry a witness station", crossing.witnessed)
        assertEquals("the roots bracket the sphere", 0.25, crossing.rootLo, 1e-9)
        assertEquals(0.75, crossing.rootHi, 1e-9)
    }

    @Test
    fun aSegmentLyingEntirelyInsideIsWitnessedAtTheCentreOfItsOverlap() {
        sphere()
        val inside = test(0.0, 0.0, 0.0, 0.4, 0.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, inside.outcome)
        assertTrue(inside.witnessed)

        val control = test(1.3, 0.0, 0.0, 1.7, 0.0, 0.0)
        assertEquals("the near-miss control must stay clear", StageOutcome.CLEAR, control.outcome)
    }

    @Test
    fun aChordFromTheCentreTakesTheZeroSignBranch() {
        sphere()
        // q0 = 0 so b = 0: with the textbook sign(0) = 0 the second root divides by zero.
        val fromCentre = test(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, fromCentre.outcome)
        assertTrue(fromCentre.witnessed)
    }

    @Test
    fun tangencyOffTheSegmentIsClearAndOnItIsNot() {
        sphere()
        // q0 = (2, 1, 0), qd = (1, 0, 0): the tangency is at t = -2, outside the segment.
        assertEquals(StageOutcome.CLEAR, test(2.0, 1.0, 0.0, 3.0, 1.0, 0.0).outcome)
        assertEquals(StageOutcome.CLEAR, test(3.0, 1.0, 0.0, 4.0, 1.0, 0.0).outcome)
        // The same tangency pulled onto the segment is a violation decided by distance: the chord
        // touches the surface at x = 0, so the minimum is exactly on it.
        assertEquals(StageOutcome.INTERSECT, test(-2.0, 1.0, 0.0, 2.0, 1.0, 0.0).outcome)
        // A parallel chord 0.4 further out is the control that keeps the row non-vacuous.
        assertEquals(StageOutcome.CLEAR, test(-1.0, 1.4, 0.0, 1.0, 1.4, 0.0).outcome)
    }

    @Test
    fun aShallowCrossingAndItsNearMissAreSeparated() {
        sphere()
        // Closest approach 0.002 inside the surface, and the control 0.002 outside it.
        val crossing = test(-2.0, 0.998, 0.0, 2.0, 0.998, 0.0)
        assertEquals(StageOutcome.INTERSECT, crossing.outcome)
        assertTrue(crossing.witnessed)

        val control = test(-2.0, 1.002, 0.0, 2.0, 1.002, 0.0)
        assertEquals(StageOutcome.CLEAR, control.outcome)
        assertEquals("the control clears by its actual excess", 0.002, control.clearance, 1e-6)
    }

    @Test
    fun anObstacleThinnerThanTheSampleSpacingIsStillCaught() {
        // Semi-axes 4 × 2 × 2 with a chord through the long axis: no sample of a 10 mm grid lands
        // inside, so only the segment test can find it.
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 4.0, 2.0, 2.0)
        val crossing = test(-5.0, 0.0, 0.0, 5.0, 0.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, crossing.outcome)
        assertTrue("no sample is inside, so the witness comes from the roots", crossing.witnessed)

        val control = test(-5.0, 5.0, 0.0, 5.0, 5.0, 0.0)
        assertEquals("the laterally offset control must stay clear", StageOutcome.CLEAR, control.outcome)
    }

    @Test
    fun aRotatedObstacleIsTestedInItsOwnFrame() {
        // World axes rotated 90° about Z: the local X semi-axis (4) lies along world Y.
        obstacle.loadResolved(
            0.0, 0.0, 0.0,
            doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            4.0, 1.0, 1.0
        )
        val crossing = test(-3.0, 5.0, 0.0, 3.0, 5.0, 0.0)
        assertEquals("the long axis points along world Y, so y = 5 is outside", StageOutcome.CLEAR, crossing.outcome)

        val throughLongAxis = test(0.0, -5.0, 0.0, 0.0, 5.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, throughLongAxis.outcome)
    }

    @Test
    fun aTaperedRadiusPutsTheMinimumOnAKnotRatherThanInTheWidestPiece() {
        sphere()
        // Radius knots with a local maximum of 1.21 at station 0.2 and 1.55 at 0.6 on the path
        // p(t) = (2 + t, 0, 0), where d(t) = 1 + t: the minimum is at the 0.2 knot, not inside a piece.
        val profile = RadiusProfile(
            listOf(0f to 0f, 0.2f to 1.21f, 0.4f to 1.215f, 0.6f to 1.55f, 1f to 0f),
            1.0
        )
        val tapered = test(2.0, 0.0, 0.0, 3.0, 0.0, 0.0, radius = profile)
        assertEquals(StageOutcome.INTERSECT, tapered.outcome)
        assertEquals("the minimum sits on the 0.2 knot", 0.2, tapered.tMinimum, 1e-6)
        assertEquals(-0.01, tapered.clearance, 1e-4)
    }

    @Test
    fun aMinimumWithinOneBudgetOfTheMarginIsUncertainRatherThanACoinFlip() {
        sphere()
        // The whole segment sits at exactly the margin, so the bracket cannot certify either side.
        val equality = test(1.0 + MARGIN, 0.0, 0.0, 1.0 + MARGIN, 1.0, 0.0)
        assertEquals(StageOutcome.UNCERTAIN, equality.outcome)
        assertTrue("lower must fall below the margin", equality.lower < MARGIN)
        assertTrue("upper must not also fall below it", equality.upper >= MARGIN)
    }

    @Test
    fun aGuardedObstacleIsNeverAVerdict() {
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 0.0, 1.0, 1.0)
        assertTrue("a zero semi-axis is degenerate", obstacle.degenerate)
        assertEquals(StageOutcome.UNCERTAIN, test(-2.0, 0.0, 0.0, 2.0, 0.0, 0.0).outcome)

        obstacle.loadResolved(Double.NaN, 0.0, 0.0, identity, 1.0, 1.0, 1.0)
        assertTrue("a NaN centre is non-finite", obstacle.nonFinite)
        assertEquals(StageOutcome.UNCERTAIN, test(-2.0, 0.0, 0.0, 2.0, 0.0, 0.0).outcome)
    }

    @Test
    fun aZeroLengthSegmentIsDecidedByThePointAndNotByTheQuadratic() {
        sphere()
        val point = test(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(StageOutcome.INTERSECT, point.outcome)
        assertFalse("a = 0 never produces a root", point.witnessed)
        assertTrue(point.rootLo.isNaN())

        val outside = test(2.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        assertEquals(StageOutcome.CLEAR, outside.outcome)
    }

    private companion object {
        const val MARGIN = 1e-5
    }
}
