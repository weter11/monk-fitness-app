package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * The validator's behaviour on a path whose geometry is known: a probe ellipsoid at the pelvis, a
 * straight polyline placed by hand, and no dependence on how the path was built.
 */
class MusclePathGeometryTest {

    private val base: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val bindPose = AnatomyPoseFixture.bindPose(skeleton)

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
    private val validator = AnatomyValidator(definition, scale)
    private val frame = AnatomyFrame.of(definition)
    private val origin = X1Bind.bindJoints(skeleton).getValue(Joint.PELVIS)
    private val obstacleRadius = 0.02 * scale

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

    private fun validate(): Boolean = validator.validate(muscle, MuscleSide.A, bindPose, frameSet, frame, 0, SearchOutcome.PASS_CAP)

    private fun state() = frame.muscleStates[0]

    /** A straight run at [offset] from the pelvis, along world Y. */
    private fun run(offset: Float): Boolean {
        val x = origin.x + offset
        path(x, origin.y, origin.z, x, origin.y + 10f, origin.z)
        return validate()
    }

    @Test
    fun aPathThatClearsTheObstacleIsValid() {
        assertTrue("a path ten units out must clear a 4.9-unit obstacle", run(10f))
        assertEquals(PathStatus.VALID, state().pathStatus)
        assertEquals(PathReason.NO_REASON, state().reason)
        assertEquals(SearchOutcome.CLEARED, state().searchOutcome)
    }

    @Test
    fun aPathThroughTheObstacleIsRejectedWithTheClearanceReason() {
        path(origin.x - 10f, origin.y, origin.z, origin.x + 10f, origin.y, origin.z)
        assertFalse(validate())
        assertEquals(PathStatus.INVALID, state().pathStatus)
        assertEquals(
            "a witnessed entry carries no proved enclosure, so stage 3 names the clearance reason",
            PathReason.CLEARANCE_VIOLATION,
            state().reason
        )
        assertEquals("the failing segment is named", 0, state().failingSegment)
    }

    @Test
    fun anEndpointInsideAnObstacleIsItsOwnReason() {
        path(origin.x, origin.y, origin.z, origin.x + 10f, origin.y, origin.z)
        assertFalse(validate())
        assertEquals(PathReason.ENDPOINT_INSIDE_OBSTACLE, state().reason)
        assertEquals("the endpoint is the location the table names", 0, state().failingSegment)
    }

    @Test
    fun aReviewedEnvelopeLetsAnAttachedOriginStartInside() {
        val exempt = muscle.copy(
            contactExemptions = listOf(ContactExemption("probe", 0, 0f, 0.9f, 1e-6f, "RV1-1"))
        )
        val exempting = AnatomyValidator(definition.copy(muscles = listOf(exempt)), scale)
        path(origin.x, origin.y, origin.z, origin.x + 10f, origin.y, origin.z)
        frame.restLengths[0] = 1f

        assertTrue(
            "an exempted origin plus a cleared tail is a valid path",
            exempting.validate(exempt, MuscleSide.A, bindPose, frameSet, frame, 0, SearchOutcome.PASS_CAP)
        )
        assertEquals(PathStatus.VALID, state().pathStatus)
    }

    @Test
    fun anEnvelopeThatCoversNothingStillFailsOnTheEndpoint() {
        // The same exemption, narrowed to [0, 0.01]: the origin is exempt but the first segment
        // leaves the envelope immediately and is tested from there.
        val narrow = muscle.copy(
            contactExemptions = listOf(ContactExemption("probe", 0, 0f, 0.01f, 1e-6f, "RV1-1"))
        )
        val exempting = AnatomyValidator(definition.copy(muscles = listOf(narrow)), scale)
        path(origin.x, origin.y, origin.z, origin.x + 10f, origin.y, origin.z)
        frame.restLengths[0] = 1f

        assertFalse(exempting.validate(narrow, MuscleSide.A, bindPose, frameSet, frame, 0, SearchOutcome.PASS_CAP))
        assertEquals(PathStatus.INVALID, state().pathStatus)
    }

    @Test
    fun aPathDeeperInsideThanTheTubeStillReportsOnlyTheFirstSegment() {
        // One record per head: the failing location is a single segment and obstacle, not a list.
        path(origin.x - 30f, origin.y, origin.z, origin.x + 30f, origin.y, origin.z)
        assertFalse(validate())
        assertEquals(PathStatus.INVALID, state().pathStatus)
        assertTrue(state().failingSegment >= 0)
        assertTrue(state().failingObstacle >= 0)
    }

    @Test
    fun theVerdictIsRepeatableAcrossBufferReuse() {
        assertTrue(run(10f))
        val first = state().reason
        assertTrue(run(10f))
        assertEquals("the same path gives the same reason on a reused carrier", first, state().reason)
        path(origin.x - 10f, origin.y, origin.z, origin.x + 10f, origin.y, origin.z)
        assertFalse("and a violating path still violates after a valid one", validate())
        assertEquals(PathStatus.INVALID, state().pathStatus)
    }

    @Test
    fun everyNonExemptSampleIsOutsideTheObstacleByTheMargin() {
        assertTrue(run(10f))
        val obstacle = ObstacleGeometry()
        obstacle.load(probe, MuscleSide.A, bindPose, frameSet, scale)
        val points = state().pointCount
        var worst = Double.MAX_VALUE
        for (index in 0 until points) {
            val d = obstacle.distanceTo(
                frame.pointComponent(0, index, 0).toDouble(),
                frame.pointComponent(0, index, 1).toDouble(),
                frame.pointComponent(0, index, 2).toDouble()
            )
            worst = minOf(worst, d)
        }
        assertTrue("a valid path's samples must clear the margin: worst=$worst", worst >= validator.margin)
    }

    @Test
    fun theArcLengthOfThePathIsNeverShorterThanItsChord() {
        // The path model's own invariant, measured on the path this validator accepted.
        assertTrue(run(10f))
        var arc = 0.0
        for (index in 1 until state().pointCount) {
            val dx = frame.pointComponent(0, index, 0) - frame.pointComponent(0, index - 1, 0)
            val dy = frame.pointComponent(0, index, 1) - frame.pointComponent(0, index - 1, 1)
            val dz = frame.pointComponent(0, index, 2) - frame.pointComponent(0, index - 1, 2)
            arc += sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        }
        val dxc = frame.pointComponent(0, state().pointCount - 1, 0) - frame.pointComponent(0, 0, 0)
        val dyc = frame.pointComponent(0, state().pointCount - 1, 1) - frame.pointComponent(0, 0, 1)
        val dzc = frame.pointComponent(0, state().pointCount - 1, 2) - frame.pointComponent(0, 0, 2)
        val chord = sqrt((dxc * dxc + dyc * dyc + dzc * dzc).toDouble())
        assertEquals("a straight path's arc equals its chord", chord, arc, 1e-3)
        assertTrue("arc ≥ chord always", arc >= chord - 1e-6)
    }

    @Test
    fun theObstacleRadiusUsedIsTheDeclaredOne() {
        assertEquals(4.9473684, obstacleRadius, 1e-4)
        val obstacle = ObstacleGeometry()
        obstacle.load(probe, MuscleSide.A, bindPose, frameSet, scale)
        assertEquals(obstacleRadius, obstacle.maxRadius, 1e-4)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
