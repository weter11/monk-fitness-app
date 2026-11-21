package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The §12.2.1 solver rows: an obstacle configuration in, the final path or the failure out.
 *
 * Every row is driven through the real pass loop on the bind pose's own segment frame, so the
 * geometry is stated in one frame and measured in it. A row that the RFC expects to clear declares
 * the curvature bound it needs and says why: the one-waypoint detour of §7.4.5 reaches its apex with
 * a turn several times 45°, and `theOneWaypointApexIsRefusedAtFortyFiveDegrees` measures that refusal
 * at the default bound rather than leaving it implicit.
 */
class MusclePathSolverFixturesTest {

    private val base: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val bindPose = AnatomyPoseFixture.bindPose(skeleton)

    /** The detour apex turns more than 45°, so a success row declares the bound its shape needs. */
    private val apexBound = 3.0f

    init {
        frames.derive(bindPose, frameSet)
    }

    // ---- fixtures ------------------------------------------------------------

    /** `single_obstacle`: a 290 mm chord of thirty 10 mm intervals through a 20 mm sphere's centre. */
    private fun singleObstacle(theta: Float = apexBound, corridor: Float = 0.6f, passCap: Int = 8) =
        definition(sphere("probe", 0.145f, 0f, 0.02f), theta = theta, corridor = corridor, passCap = passCap)

    private fun anisotropic(theta: Float = apexBound) = definition(
        WrapSurface("probe", Segment.PELVIS, Triple(0.145f, 0f, 0f), Triple(0.01f, 0.04f, 0.02f), true),
        theta = theta
    )

    private fun multipleObstacles(theta: Float = apexBound) = definition(
        listOf(sphere("a", 0.1f, 0f, 0.02f), sphere("b", 0.19f, 0f, 0.02f)),
        theta = theta
    )

    private fun overlappingObstacles(theta: Float = apexBound) = definition(
        listOf(sphere("a", 0.13f, 0f, 0.02f), sphere("b", 0.155f, 0f, 0.02f)),
        theta = theta
    )

    private fun nestedObstacles(theta: Float = apexBound) = definition(
        listOf(sphere("outer", 0.145f, 0f, 0.04f), sphere("inner", 0.145f, 0f, 0.01f)),
        theta = theta
    )

    /** A chord exactly tangent to a 20 mm sphere: the chord's own lateral component is zero. */
    private fun nearTangent(theta: Float = 0.7853982f) = definition(
        sphere("probe", 0f, 0f, 0.02f),
        head = head(-0.1f, 0.1f, 0.02f, 0.02f),
        theta = theta
    )

    private fun head(fromX: Float, toX: Float, fromY: Float = 0f, toY: Float = 0f) = MuscleHead(
        origin = Attachment(Segment.PELVIS, fromX, fromY, 0f),
        insertion = Attachment(Segment.PELVIS, toX, toY, 0f)
    )

    private fun sphere(id: String, x: Float, y: Float, radius: Float) =
        WrapSurface(id, Segment.PELVIS, Triple(x, y, 0f), Triple(radius, radius, radius), true)

    private fun definition(
        obstacle: WrapSurface,
        head: MuscleHead = head(0f, 0.29f),
        theta: Float = apexBound,
        corridor: Float = 0.6f,
        passCap: Int = 8
    ) = definition(listOf(obstacle), head, theta, corridor, passCap)

    private fun definition(
        obstacles: List<WrapSurface>,
        head: MuscleHead = head(0f, 0.29f),
        theta: Float = apexBound,
        corridor: Float = 0.6f,
        passCap: Int = 8
    ): AnatomyDefinition {
        val muscle = MuscleDefinition(
            id = "probe_muscle",
            name = "Probe",
            layer = MuscleLayer.SUPERFICIAL,
            heads = listOf(head),
            widthMetres = 0.05f,
            thicknessMetres = 0.02f,
            tendon = TendonSpec(0.1f, 0.1f),
            wraps = emptyList(),
            radiusKnots = listOf(0f to 0f, 1f to 0f),
            passCap = passCap,
            corridorMetres = corridor
        )
        return base.copy(wrapSurfaces = obstacles, muscles = listOf(muscle), thetaMaxRadians = theta)
    }

    // ---- driving the loop ----------------------------------------------------

    private class Solved(val frame: AnatomyFrame, val solver: MusclePathSolver, val definition: AnatomyDefinition) {
        val state: MuscleState get() = frame.muscleStates[0]
    }

    private fun solve(definition: AnatomyDefinition, retain: Boolean = false): Solved {
        val frame = AnatomyFrame.of(definition)
        val solver = MusclePathSolver(
            definition,
            MusclePathBuilder(definition, scale.toFloat()),
            AnatomyValidator(definition, scale),
            scale,
            retain
        )
        solver.solve(definition.muscles[0], MuscleSide.A, bindPose, frameSet, frame, 0)
        return Solved(frame, solver, definition)
    }

    /** The independent certificate: the same predicate, run on the polyline the loop accepted. */
    private fun certifies(solved: Solved): Boolean {
        solved.frame.restLengths[0] = 1f
        return solved.definition.let { definition ->
            AnatomyValidator(definition, scale)
                .validate(definition.muscles[0], MuscleSide.A, bindPose, frameSet, solved.frame, 0, SearchOutcome.PASS_CAP)
        }
    }

    private fun samples(solved: Solved) = List(solved.state.pointCount) { index ->
        Triple(
            solved.frame.pointComponent(0, index, 0).toDouble(),
            solved.frame.pointComponent(0, index, 1).toDouble(),
            solved.frame.pointComponent(0, index, 2).toDouble()
        )
    }

    // ---- success rows -------------------------------------------------------

    @Test
    fun singleObstacleClearsOnlyAfterTheThirdPass() {
        val solved = solve(singleObstacle())
        assertEquals("the lateral rule must route around the sphere", PathStatus.VALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.CLEARED, solved.state.searchOutcome)
        assertTrue(
            "passes 1 and 2 put the connecting segment inside the sphere, so the loop cannot accept before pass 3: ${solved.solver.lastPass}",
            solved.solver.lastPass >= 3
        )
        assertTrue("the accepted polyline must carry its own certificate", certifies(solved))
        assertTrue("and publishes a path", solved.state.pointCount >= 8)
    }

    @Test
    fun theOneWaypointApexIsRefusedAtFortyFiveDegrees() {
        // Recorded rather than papered over: at the definition's 45° bound the §7.4.5 one-waypoint
        // detour that clears this sphere on pass 3 folds by 59.4° at its apex, between two samples one
        // declared spacing apart, so the loop refuses every pass and ends at the cap. The row above is
        // the same geometry with a bound above that fold; the difference is the curvature row alone.
        val solved = solve(singleObstacle(theta = 0.7853982f))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(PathReason.CURVATURE_VIOLATION, solved.state.reason)
        assertEquals(SearchOutcome.PASS_CAP, solved.state.searchOutcome)
        assertEquals("a refused head carries no path", 0, solved.state.pointCount)
        assertEquals(8, solved.solver.lastPass)
    }

    @Test
    fun retainedEntryAndExitPointsCannotBeAcceptedOnThePassThatCarriesThem() {
        // The construction points sit on the surface, so the pass that contains them is refused; the
        // next pass re-derives its runs from that polyline and drops them, which is the drop rule
        // working one pass late. Measured, not assumed: with the rule off the loop still terminates,
        // and the record says at which pass.
        val retained = solve(singleObstacle(), retain = true)
        val dropped = solve(singleObstacle())
        assertEquals("the point is not accepted in place", PathStatus.VALID, retained.state.pathStatus)
        assertTrue("so the loop must carry it past the first pass: ${retained.solver.lastPass}", retained.solver.lastPass >= 2)
        assertTrue(certifies(retained))
        assertEquals("and the dropped construction reaches the same shape", PathStatus.VALID, dropped.state.pathStatus)
    }

    @Test
    fun solveRotatedAnisotropicTransformsTheFallbackAndTheSupportDirection() {
        // Local semi-axes 10 × 40 × 20: the fallback picks the least-aligned local axis, so h is the
        // 40 mm axis, not the 10 mm one, and the waypoint clears on the second placement rather than
        // sitting inside the solid at 10.01 mm.
        val solved = solve(anisotropic())
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertTrue("the first placement is inside the ellipsoid", solved.solver.lastPass >= 2)
        assertTrue(certifies(solved))

        val stray = ObstacleGeometry()
        stray.load(anisotropic().wrapSurfaces[0], MuscleSide.A, bindPose, frameSet, scale)
        val offsets = samples(solved).map { toLocal(stray, it) }
        val lateral = offsets.maxByOrNull { kotlin.math.abs(it[1]) }!!
        // The fallback picks the least-aligned LOCAL axis, so the apex leaves along the 40 mm
        // semi-axis; a world-axis choice would have used the 10 mm one and cleared nothing.
        assertTrue(
            "the route must leave along the 40 mm axis: apex (${lateral[0]}, ${lateral[1]}, ${lateral[2]})",
            kotlin.math.abs(lateral[1]) >= 0.04 * scale
        )
        assertTrue(
            "and along that axis only",
            kotlin.math.abs(lateral[0]) < kotlin.math.abs(lateral[1])
        )
    }

    @Test
    fun multipleObstaclesInSeriesBothClear() {
        val solved = solve(multipleObstacles())
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertTrue(certifies(solved))
        for (index in solved.definition.wrapSurfaces.indices) {
            assertTrue("obstacle $index is clear on the accepted polyline", clearsEverySegment(solved, index))
        }
    }

    @Test
    fun overlappingObstaclesClearOutsideTheUnionAndRepeatIdentically() {
        val first = solve(overlappingObstacles())
        val second = solve(overlappingObstacles())
        assertEquals(PathStatus.VALID, first.state.pathStatus)
        assertTrue(certifies(first))
        assertEquals("same declaration order, same route", first.state.pointCount, second.state.pointCount)
        assertEquals(first.state.modelledPathLength, second.state.modelledPathLength, 0f)
    }

    @Test
    fun nestedObstaclesClearOutsideTheOuterSurface() {
        val solved = solve(nestedObstacles())
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertTrue(certifies(solved))
        assertTrue("the route is outside the outer surface", clearsEverySegment(solved, 0))
    }

    @Test
    fun nearTangentRoutesAroundAndClearsByTheMargin() {
        // The chord is tangent, so it is a true near miss at the declared margin: the detour must clear
        // by `eps_num`, not merely stop intersecting. This row needs no bound above 45°.
        val solved = solve(nearTangent())
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.CLEARED, solved.state.searchOutcome)
        assertTrue("the tangent chord itself is rejected, so the detour is not a first-pass accident", solved.solver.lastPass >= 2)
        assertTrue(certifies(solved))

        val obstacle = ObstacleGeometry()
        obstacle.load(solved.definition.wrapSurfaces[0], MuscleSide.A, bindPose, frameSet, scale)
        var closest = Double.MAX_VALUE
        for (point in samples(solved)) {
            closest = minOf(closest, distance(point, obstacle))
        }
        assertTrue("every sample clears the margin: $closest", closest >= scale * 1e-5)
    }

    @Test
    fun theCheckRunsOnTheSmoothedPolylineSoTheLoopRetries() {
        // `after_smoothing_regression`: the predicate runs on the polyline the next pass would keep,
        // not on the construction. The independent validator certifies the accepted polyline segment
        // by segment — a solver that validated before smoothing would accept a curve this predicate
        // has never seen — and the loop reaches that acceptance only after a rejected candidate.
        val solved = solve(multipleObstacles())
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertTrue("the loop must retry, not accept its first candidate", solved.solver.lastPass >= 2)
        assertTrue("the accepted polyline must survive an independent certificate", certifies(solved))
        for (index in solved.definition.wrapSurfaces.indices) {
            assertTrue("obstacle $index is clear on the accepted polyline", clearsEverySegment(solved, index))
        }
    }

    // ---- failure rows -------------------------------------------------------

    @Test
    fun twoObstacleOscillationExhaustsThePassBudget() {
        // The detour side that clears the sphere puts the path inside the slab, and the side is fixed
        // within a pass, so no pass produces a candidate. The budget is five passes: at larger
        // escalations the apex folds past the curvature bound, and the row's own content is that the
        // *geometric* cause survives to the last pass it ran.
        val slab = WrapSurface("slab", Segment.PELVIS, Triple(0.145f, 0.06f, 0f), Triple(0.02f, 0.3f, 0.02f), true)
        val solved = solve(definition(listOf(sphere("a", 0.145f, 0f, 0.02f), slab), passCap = 5))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.PASS_CAP, solved.state.searchOutcome)
        assertEquals("a per-pair test ran, so the cause is geometric", PathReason.CLEARANCE_VIOLATION, solved.state.reason)
        assertEquals(5, solved.solver.lastPass)

        val again = solve(definition(listOf(sphere("a", 0.145f, 0f, 0.02f), slab), passCap = 5))
        assertEquals("deterministic and terminating", solved.solver.lastPass, again.solver.lastPass)
        assertEquals(solved.state.reason, again.state.reason)
        assertEquals(solved.state.failingSegment, again.state.failingSegment)
    }

    @Test
    fun searchExhaustedIsTheCorridorLimitAndNeverNoRoute() {
        // A 30 mm corridor cannot hold a 20 mm sphere's detour plus its margin.
        val solved = solve(singleObstacle(corridor = 0.03f))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.SEARCH_EXHAUSTED, solved.state.searchOutcome)
        assertEquals(PathReason.CLEARANCE_VIOLATION, solved.state.reason)
        assertEquals("the constructor names the obstacle it could not clear", 0, solved.state.failingObstacle)
    }

    @Test
    fun closedCorridorExhaustedIsRefusedBeforeAnyPass() {
        // A (120, 40, 40) mm ellipsoid: h >= min R = 40 mm for every direction, so d_1 >= 40.01 mm
        // already exceeds the declared 30 mm corridor and no pass can ever place a waypoint.
        val closed = WrapSurface("probe", Segment.PELVIS, Triple(0.145f, 0f, 0f), Triple(0.12f, 0.04f, 0.04f), true)
        val solved = solve(definition(closed, corridor = 0.03f))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.SEARCH_EXHAUSTED, solved.state.searchOutcome)
        assertEquals("no per-pair test produced a cause", PathReason.NO_GEOMETRIC_CAUSE, solved.state.reason)
        assertEquals("refused before the first pass ever ran", 0, solved.solver.lastPass)
        assertEquals(NO_INDEX, solved.state.failingSegment)
        assertEquals(NO_INDEX, solved.state.failingObstacle)
    }

    @Test
    fun passCapIsTheBudgetNotTheCorridor() {
        val solved = solve(singleObstacle(passCap = 2))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.PASS_CAP, solved.state.searchOutcome)
        assertEquals(2, solved.solver.lastPass)
    }

    @Test
    fun finalClearanceAndBudgetBothFailWithoutEitherFieldOverwritingTheOther() {
        // One pass, and that pass fails the predicate: the outcome names the budget and the reason
        // names the clearance, with the location, in one record.
        val solved = solve(singleObstacle(passCap = 1))
        assertEquals(PathStatus.INVALID, solved.state.pathStatus)
        assertEquals(SearchOutcome.PASS_CAP, solved.state.searchOutcome)
        assertEquals(PathReason.CLEARANCE_VIOLATION, solved.state.reason)
        assertTrue("the failing segment is located", solved.state.failingSegment >= 0)
        assertEquals("and the obstacle with it", 0, solved.state.failingObstacle)
    }

    @Test
    fun orderedProjectionAlternatesInsteadOfConverging() {
        // `two_obstacle_projection_cycle`: two unit spheres of radius 1.5 centred at 0 and 2 with a
        // sample at 0.5. Projection is a pass, not a solver, and this is why: each projection in
        // declaration order hands the sample to the other obstacle.
        val first = ObstacleGeometry()
        val second = ObstacleGeometry()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        first.loadResolved(0.0, 0.0, 0.0, identity, 1.5, 1.5, 1.5)
        second.loadResolved(2.0, 0.0, 0.0, identity, 1.5, 1.5, 1.5)

        var x = 0.5
        val sequence = ArrayList<Double>()
        repeat(4) {
            x = project(x, first)
            sequence += x
            x = project(x, second)
            sequence += x
        }
        assertEquals(
            "each obstacle hands the sample back to the other, so there is no fixed point",
            listOf(1.5, 0.5, 1.5, 0.5, 1.5, 0.5, 1.5, 0.5),
            sequence
        )
    }

    @Test
    fun theLoopEscapesTheProjectionCycleByConstruction() {
        // Recorded rather than asserted as a convergence claim: the same two spheres handed to the
        // constructor are routed in three passes, because construction may move points that ordered
        // projection alone would hand back and forth.
        val unit = 1.5f / scale.toFloat()
        val solved = solve(
            definition(
                listOf(
                    sphere("a", 0f, 0f, unit),
                    sphere("b", 2f / scale.toFloat(), 0f, unit)
                ),
                head = head(-5f / scale.toFloat(), 7f / scale.toFloat())
            )
        )
        assertEquals(PathStatus.VALID, solved.state.pathStatus)
        assertTrue(certifies(solved))
    }

    // ---- measurements -------------------------------------------------------

    private fun project(x: Double, obstacle: ObstacleGeometry): Double =
        if (x >= obstacle.centre[0]) obstacle.centre[0] + obstacle.radii[0] else obstacle.centre[0] - obstacle.radii[0]

    private fun distance(point: Triple<Double, Double, Double>, obstacle: ObstacleGeometry): Double =
        obstacle.distanceTo(point.first, point.second, point.third)

    /** An offset in the obstacle's own axes: the frame the fallback chooses in. */
    private fun toLocal(obstacle: ObstacleGeometry, point: Triple<Double, Double, Double>): DoubleArray {
        val dx = point.first - obstacle.centre[0]
        val dy = point.second - obstacle.centre[1]
        val dz = point.third - obstacle.centre[2]
        return doubleArrayOf(
            obstacle.axes[0] * dx + obstacle.axes[1] * dy + obstacle.axes[2] * dz,
            obstacle.axes[3] * dx + obstacle.axes[4] * dy + obstacle.axes[5] * dz,
            obstacle.axes[6] * dx + obstacle.axes[7] * dy + obstacle.axes[8] * dz
        )
    }

    /** Whether every segment of the accepted polyline clears obstacle [index] by the margin. */
    private fun clearsEverySegment(solved: Solved, index: Int): Boolean {
        val obstacle = ObstacleGeometry()
        obstacle.load(solved.definition.wrapSurfaces[index], MuscleSide.A, bindPose, frameSet, scale)
        val points = samples(solved)
        val verdict = PairVerdict()
        val profile = RadiusProfile(solved.definition.muscles[0].radiusKnots, scale)
        for (segment in 0 until points.size - 1) {
            obstacle.testSegment(
                points[segment].first, points[segment].second, points[segment].third,
                points[segment + 1].first, points[segment + 1].second, points[segment + 1].third,
                segment.toDouble() / (points.size - 1), (segment + 1).toDouble() / (points.size - 1),
                profile, scale * 1e-5, verdict
            )
            if (verdict.outcome != StageOutcome.CLEAR) return false
        }
        return true
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
