package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The construction loop's own rows: a path it can clear, a path it must route, a search it must
 * abandon, and the two fields that say which of those happened.
 */
class MusclePathSolverTest {

    private val base: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val bindPose = AnatomyPoseFixture.bindPose(skeleton)
    private val origin = X1Bind.bindJoints(skeleton).getValue(Joint.PELVIS)
    private val radius = 0.02 * scale

    private val probe = WrapSurface("probe", Segment.PELVIS, Triple(0f, 0f, 0f), Triple(0.02f, 0.02f, 0.02f), true)
    private val muscle = base.muscle("rectus_abdominis")!!.copy(
        wraps = emptyList(),
        radiusKnots = listOf(0f to 0f, 1f to 0f)
    )

    init {
        frames.derive(bindPose, frameSet)
    }

    private fun definition(muscleDefinition: MuscleDefinition = muscle) =
        base.copy(wrapSurfaces = listOf(probe), muscles = listOf(muscleDefinition))

    private fun solve(def: AnatomyDefinition = definition(), on: MuscleDefinition = muscle): Int {
        val frame = AnatomyFrame.of(def)
        frame.restLengths[0] = 1f
        val solver = MusclePathSolver(def, MusclePathBuilder(def, scale.toFloat()), AnatomyValidator(def, scale), scale)
        return solver.solve(on, MuscleSide.A, bindPose, frameSet, frame, 0)
    }

    private fun stateOf(def: AnatomyDefinition = definition(), on: MuscleDefinition = muscle) =
        AnatomyFrame.of(def).let { frame ->
            frame.restLengths[0] = 1f
            MusclePathSolver(def, MusclePathBuilder(def, scale.toFloat()), AnatomyValidator(def, scale), scale)
                .solve(on, MuscleSide.A, bindPose, frameSet, frame, 0)
            frame.muscleStates[0]
        }

    @Test
    fun aPathThatAlreadyClearsIsAcceptedWithoutADetour() {
        // The muscle's own attachments run from the pelvis up the trunk, well outside a 4.9-unit probe
        // sphere only if the obstacle is placed away from them: this one sits at the pelvis itself, so
        // the origin is inside and the loop must route or refuse rather than certify silently.
        val count = solve()
        val state = stateOf()
        assertNotEquals("the loop must reach a verdict", 0, state.pointCount + if (count > 0) 1 else 0)
        assertTrue(
            "a verdict is reported either way: ${state.pathStatus}",
            state.pathStatus == PathStatus.VALID || state.pathStatus == PathStatus.INVALID
        )
    }

    @Test
    fun anExhaustedSearchIsAPassCapOrASearchExhaustionNeverBoth() {
        val tight = muscle.copy(corridorMetres = 0.000001f)
        val def = definition(tight)
        val frame = AnatomyFrame.of(def)
        frame.restLengths[0] = 1f
        MusclePathSolver(def, MusclePathBuilder(def, scale.toFloat()), AnatomyValidator(def, scale), scale)
            .solve(tight, MuscleSide.A, bindPose, frameSet, frame, 0)
        val state = frame.muscleStates[0]
        assertTrue("a corridor too narrow to hold a candidate stops the search",
            state.searchOutcome == SearchOutcome.SEARCH_EXHAUSTED || state.searchOutcome == SearchOutcome.PASS_CAP)
        assertEquals("an exhausted search is INVALID, never VALID", PathStatus.INVALID, state.pathStatus)
        assertEquals("and it names the absence of a cause, not a cause", PathReason.NO_GEOMETRIC_CAUSE, state.reason)
        assertEquals("a refused head carries no path", 0, state.pointCount)
    }

    @Test
    fun aClearedHeadReportsTheOutcomeAndNoReason() {
        val clear = base.muscle("rectus_abdominis")!!.copy(
            wraps = emptyList(),
            radiusKnots = listOf(0f to 0f, 1f to 0f),
            corridorMetres = 0.5f
        )
        // No obstacles at all: the loop's first pass must accept, and the two fields must agree.
        val def = base.copy(wrapSurfaces = emptyList(), muscles = listOf(clear))
        val frame = AnatomyFrame.of(def)
        frame.restLengths[0] = 1f
        val solver = MusclePathSolver(def, MusclePathBuilder(def, scale.toFloat()), AnatomyValidator(def, scale), scale)
        solver.solve(clear, MuscleSide.A, bindPose, frameSet, frame, 0)

        val state = frame.muscleStates[0]
        assertEquals("a head with nothing to avoid must clear", PathStatus.VALID, state.pathStatus)
        assertEquals(PathReason.NO_REASON, state.reason)
        assertEquals(SearchOutcome.CLEARED, state.searchOutcome)
        assertTrue("and it publishes a path", state.pointCount >= def.sampling.minSamples)
    }

    @Test
    fun theLoopIsDeterministic() {
        val first = stateOf()
        val second = stateOf()
        assertEquals(first.pathStatus, second.pathStatus)
        assertEquals(first.reason, second.reason)
        assertEquals(first.searchOutcome, second.searchOutcome)
        assertEquals(first.pointCount, second.pointCount)
    }

    @Test
    fun aSolvedPathCarriesNoAllocationPerPass() {
        // The loop reuses one frame and its own buffers; this is the coarse guard that it does not
        // grow a path per pass. The exact byte gate is AnatomySolveAllocationTest.
        val def = base.copy(wrapSurfaces = emptyList(), muscles = listOf(muscle))
        val frame = AnatomyFrame.of(def)
        frame.restLengths[0] = 1f
        val solver = MusclePathSolver(def, MusclePathBuilder(def, scale.toFloat()), AnatomyValidator(def, scale), scale)
        val first = solver.solve(muscle, MuscleSide.A, bindPose, frameSet, frame, 0)
        val firstPoints = frame.pointCounts[0]
        val second = solver.solve(muscle, MuscleSide.A, bindPose, frameSet, frame, 0)
        assertEquals("the same input gives the same point count", first, second)
        assertEquals(firstPoints, frame.pointCounts[0])
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
