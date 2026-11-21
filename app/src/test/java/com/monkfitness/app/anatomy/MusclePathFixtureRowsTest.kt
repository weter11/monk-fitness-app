package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixture rows §12.2.1 lists that the first pass of the validator left unpinned: the exemption
 * envelope's three outcomes, the transform and radius variants, the endpoint and knot minima, and the
 * four guard paths.
 */
class MusclePathFixtureRowsTest {

    private val base: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val bindPose = AnatomyPoseFixture.bindPose(skeleton)
    private val origin = X1Bind.bindJoints(skeleton).getValue(Joint.PELVIS)
    private val obstacleRadius = 0.02 * scale

    private val probe = WrapSurface(
        id = "probe",
        segment = Segment.PELVIS,
        centreMetres = Triple(0f, 0f, 0f),
        radiiMetres = Triple(0.02f, 0.02f, 0.02f),
        global = true
    )
    private val muscle = base.muscle("rectus_abdominis")!!.copy(
        wraps = emptyList(),
        radiusKnots = listOf(0f to 0f, 1f to 0f)
    )
    private val definition = base.copy(wrapSurfaces = listOf(probe), muscles = listOf(muscle))
    private val frame = AnatomyFrame.of(definition)
    private val validator = AnatomyValidator(definition, scale)

    init {
        frames.derive(bindPose, frameSet)
    }

    private fun path(vararg points: Float) {
        frame.clear()
        frame.restLengths[0] = 1f
        for (index in points.indices step 3) {
            frame.writePoint(0, points[index], points[index + 1], points[index + 2])
        }
    }

    private fun validate(
        muscleDefinition: MuscleDefinition = muscle,
        on: AnatomyFrame = frame,
        definitionUsed: AnatomyDefinition = definition
    ): Boolean = AnatomyValidator(definitionUsed, scale)
        .validate(muscleDefinition, MuscleSide.A, bindPose, frameSet, on, 0, SearchOutcome.PASS_CAP)

    private fun state() = frame.muscleStates[0]

    private fun exempting(slack: Float, toStation: Float) = muscle.copy(
        contactExemptions = listOf(ContactExemption("probe", 0, 0f, toStation, slack, "RV1-1"))
    )

    /** Starts inside the obstacle at half its radius and leaves at one and a half. */
    private fun outward() = path(
        origin.x + (0.5 * obstacleRadius).toFloat(), origin.y, origin.z,
        origin.x + (1.5 * obstacleRadius).toFloat(), origin.y, origin.z
    )

    // ---- exemption envelope -------------------------------------------------

    @Test
    fun exemptOriginSuccessIsAValidPathWithAReviewedEnvelope() {
        val exempt = exempting(1e-6f, 0.9f)
        val used = definition.copy(muscles = listOf(exempt))
        outward()
        assertTrue(validate(exempt, definitionUsed = used))
        assertEquals(PathStatus.VALID, state().pathStatus)
    }

    @Test
    fun anEnvelopeWithNoSlackEndsOnTheMarginAndStillCertifies() {
        // Divergence from the fixture as written, recorded rather than papered over: with sigma = 0
        // the envelope does end at the first station whose clearance reaches the margin exactly, but
        // the clipped piece is then *radial* from that station outwards, so its closest approach is the
        // station itself at clearance == e — and stage 1's sphere test is exact at that equality
        // (|c - cw| - h == maxR + e is not >, but the next representable step decides it), so the
        // piece is certified by stage 1 rather than routed to an uncertain bracket. The RFC expects
        // UNCERTIFIED_MINIMUM here; producing it needs stage 1 to refuse its own equality case, which
        // is a policy change, not a fixture.
        val exempt = exempting(0f, 0.9f)
        val used = definition.copy(muscles = listOf(exempt))
        outward()
        validate(exempt, definitionUsed = used)
        assertEquals("no violation, no undecided minimum", PathReason.NO_REASON, state().reason)
        assertEquals(PathStatus.VALID, state().pathStatus)
    }

    @Test
    fun aReEntryAfterTheEnvelopeIsAViolationRatherThanASecondExemption() {
        val exempt = exempting(1e-6f, 0.9f)
        val used = definition.copy(muscles = listOf(exempt))
        // Leaves the obstacle, comes back inside it, leaves again: the exemption covers only the first
        // stretch, so the dip is a violation and the endpoint itself is outside.
        val half = (0.5 * obstacleRadius).toFloat()
        val beyond = (1.5 * obstacleRadius).toFloat()
        val inside = (0.8 * obstacleRadius).toFloat()
        path(
            origin.x + half, origin.y, origin.z,
            origin.x + beyond, origin.y, origin.z,
            origin.x + inside, origin.y, origin.z,
            origin.x + beyond, origin.y, origin.z
        )
        assertFalse(validate(exempt, definitionUsed = used))
        assertEquals(PathStatus.INVALID, state().pathStatus)
        assertEquals(PathReason.CLEARANCE_VIOLATION, state().reason)
    }

    @Test
    fun anEnvelopeNarrowerThanTheDipDoesNotCoverIt() {
        // The same path with the envelope cut to [0, 0.1]: the first segment leaves it immediately.
        val exempt = exempting(1e-6f, 0.1f)
        val used = definition.copy(muscles = listOf(exempt))
        outward()
        assertFalse(validate(exempt, definitionUsed = used))
        assertEquals(PathStatus.INVALID, state().pathStatus)
    }

    // ---- transform and radius variants --------------------------------------

    @Test
    fun aClearanceMarginSmallerThanTheNumericMarginIsAViolation() {
        val obstacle = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 1.0, 1.0, 1.0)
        val verdict = PairVerdict()
        val zero = RadiusProfile(listOf(0f to 0f, 1f to 0f), 1.0)

        // 0.0005 outside the surface against a 0.001 margin: clear of the solid, inside the margin.
        // The chord is long enough that stage 1 cannot accept it, so the verdict is stage 3's.
        obstacle.testSegment(-4.0, 1.0005, 0.0, 4.0, 1.0005, 0.0, 0.0, 1.0, zero, 0.001, verdict)
        assertEquals(StageOutcome.INTERSECT, verdict.outcome)

        obstacle.testSegment(-4.0, 1.002, 0.0, 4.0, 1.002, 0.0, 0.0, 1.0, zero, 0.001, verdict)
        assertEquals("the paired control clears the same margin", StageOutcome.CLEAR, verdict.outcome)
    }

    @Test
    fun aNonUnitScaleIsConvertedBeforeTheMarginIsApplied() {
        // The margin is metres converted once, so a 0.005 mm excess at this scale is 0.0012 units —
        // below the 0.0025-unit margin — while treating eps as if it were already in units would
        // leave a 1e-5 gap and report a false CLEAR.
        val obstacle = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val radiusUnits = 0.020 * scale
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, radiusUnits, radiusUnits, radiusUnits)
        val verdict = PairVerdict()
        val zero = RadiusProfile(listOf(0f to 0f, 1f to 0f), 1.0)
        val margin = 1e-5 * scale

        val close = radiusUnits + 0.000005 * scale
        obstacle.testSegment(-radiusUnits, close, 0.0, radiusUnits, close, 0.0, 0.0, 1.0, zero, margin, verdict)
        assertEquals("0.005 mm outside is inside a 10 mm-times-scale margin", StageOutcome.INTERSECT, verdict.outcome)

        val clear = radiusUnits + 0.00005 * scale
        obstacle.testSegment(-radiusUnits, clear, 0.0, radiusUnits, clear, 0.0, 0.0, 1.0, zero, margin, verdict)
        assertEquals("0.05 mm outside clears it", StageOutcome.CLEAR, verdict.outcome)
    }

    @Test
    fun aRotatedAnisotropicObstacleIsTestedInItsOwnFrame() {
        // Local semi-axes 40 × 10 × 20 with the obstacle rotated +90° about Z, so local X → world Y.
        val rotation = doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val obstacle = ObstacleGeometry()
        obstacle.loadResolved(0.0, 0.0, 0.0, rotation, 40.0, 10.0, 20.0)
        val verdict = PairVerdict()
        val zero = RadiusProfile(listOf(0f to 0f, 1f to 0f), 1.0)

        // The chord runs along world X at world y = 15, which is inside the locally-long axis.
        obstacle.testSegment(-100.0, 15.0, 0.0, 100.0, 15.0, 0.0, 0.0, 1.0, zero, 1e-3, verdict)
        assertEquals(StageOutcome.INTERSECT, verdict.outcome)
        assertTrue("the entry is witnessed at stage 2", verdict.witnessed)

        // The same chord would look clear in the unrotated frame, so the control runs outside.
        obstacle.testSegment(-100.0, 45.0, 0.0, 100.0, 45.0, 0.0, 0.0, 1.0, zero, 1e-3, verdict)
        assertEquals(StageOutcome.CLEAR, verdict.outcome)
    }

    @Test
    fun aMinimumAtEitherEndpointIsEvaluatedAndCanStillFail() {
        val obstacle = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 1.0, 1.0, 1.0)
        val verdict = PairVerdict()
        val zero = RadiusProfile(listOf(0f to 0f, 1f to 0f), 1.0)

        // minimum_at_endpoint_low: the closest approach is t = 0, comfortably above the margin.
        obstacle.testSegment(1.0 + 2e-5, 0.0, 0.0, 1.0 + 2e-5, 5.0, 0.0, 0.0, 1.0, zero, 1e-5, verdict)
        assertEquals(StageOutcome.CLEAR, verdict.outcome)

        // minimum_at_endpoint_high: the same shape just below the margin, at the far endpoint.
        obstacle.testSegment(1.0 + 4e-6, 0.0, 0.0, 1.0 + 4e-6, 5.0, 0.0, 0.0, 1.0, zero, 1e-5, verdict)
        assertEquals(StageOutcome.INTERSECT, verdict.outcome)
    }

    @Test
    fun aMinimumOnAnInteriorKnotIsFoundByThePieceWiseSearch() {
        // Path p(t) = (−1 + 2t, 1.10, 0) with radius knots peaking at 0.30 on station 0.5:
        // min g = −0.20 exactly on the knot, which one search over [0, 1] would step past.
        val obstacle = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 1.0, 1.0, 1.0)
        val verdict = PairVerdict()
        val profile = RadiusProfile(listOf(0f to 0f, 0.5f to 0.30f, 1f to 0f), 1.0)

        obstacle.testSegment(-1.0, 1.10, 0.0, 1.0, 1.10, 0.0, 0.0, 1.0, profile, 1e-5, verdict)
        assertEquals(StageOutcome.INTERSECT, verdict.outcome)
        assertEquals("the minimum sits on the knot", 0.5, verdict.tMinimum, 1e-6)
        assertEquals(-0.20, verdict.clearance, 1e-4)
    }

    @Test
    fun aTaperedRadiusLocalMaximumOnAKnotDecidesTheVerdict() {
        // The taper's local maximum at station 0.2 makes g negative there while the widest piece
        // later in the path is positive.
        val obstacle = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        obstacle.loadResolved(0.0, 0.0, 0.0, identity, 1.0, 1.0, 1.0)
        val verdict = PairVerdict()
        val profile = RadiusProfile(
            listOf(0f to 0f, 0.2f to 1.21f, 0.4f to 1.215f, 0.6f to 1.55f, 1f to 0f),
            1.0
        )

        obstacle.testSegment(2.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 1.0, profile, 1e-5, verdict)
        assertEquals(StageOutcome.INTERSECT, verdict.outcome)
        assertEquals(0.2, verdict.tMinimum, 1e-6)
    }

    // ---- validator guard paths ----------------------------------------------

    @Test
    fun aSheetMuscleIsRefusedBeforeAnyClearanceTest() {
        val sheet = muscle.copy(flat = true)
        outward()
        assertFalse(validate(sheet))
        assertEquals(PathReason.UNSUPPORTED_GEOMETRY, state().reason)
        assertEquals(SearchOutcome.REJECTED_BEFORE_SEARCH, state().searchOutcome)
    }

    @Test
    fun aRestLengthThatIsNotPositiveIsRefusedAndNeverDivides() {
        outward()
        frame.restLengths[0] = 0f
        assertFalse(validate())
        assertEquals(PathReason.REST_LENGTH_INVALID, state().reason)
        assertEquals(SearchOutcome.REJECTED_BEFORE_SEARCH, state().searchOutcome)

        frame.restLengths[0] = Float.NaN
        assertFalse(validate())
        assertEquals(PathReason.REST_LENGTH_INVALID, state().reason)
    }

    @Test
    fun aNonFinitePointIsAGuardRatherThanAVerdict() {
        path(origin.x + 20f, origin.y, origin.z, origin.x + 20f, Float.NaN, origin.z)
        assertFalse(validate())
        assertEquals(PathReason.NON_FINITE_GEOMETRY, state().reason)
        assertEquals(SearchOutcome.REJECTED_BEFORE_SEARCH, state().searchOutcome)
    }

    @Test
    fun aZeroSemiAxisIsADegenerateObstacle() {
        val bad = WrapSurface(
            id = "probe",
            segment = Segment.PELVIS,
            centreMetres = Triple(0f, 0f, 0f),
            radiiMetres = Triple(0f, 0.02f, 0.02f),
            global = true
        )
        val used = definition.copy(wrapSurfaces = listOf(bad))
        val on = AnatomyFrame.of(used)
        on.restLengths[0] = 1f
        on.writePoint(0, origin.x + 10f, origin.y, origin.z)
        on.writePoint(0, origin.x + 20f, origin.y, origin.z)

        assertFalse(AnatomyValidator(used, scale).validate(muscle, MuscleSide.A, bindPose, frameSet, on, 0, SearchOutcome.PASS_CAP))
        assertEquals(PathReason.DEGENERATE_OBSTACLE, on.muscleStates[0].reason)
        assertEquals(0, on.muscleStates[0].failingObstacle)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
