package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A head's path is a function of its own slot, so the lane count is a scheduling choice and never an
 * input: everything a solve publishes has to come out bit-identical on one thread or four, and the
 * lanes' pass counts have to merge into the histogram the serial loop would have written.
 */
class AnatomyParallelSolveTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)
    private val poses = listOf(restPose, posed(0.02f), posed(-0.05f), posed(0.11f))

    private fun posed(delta: Float): SkeletonPose {
        val joints = HashMap(X1Bind.bindJoints(skeleton))
        val chest = joints.getValue(Joint.CHEST)
        joints[Joint.CHEST] = Vector3(chest.x + delta, chest.y, chest.z)
        return AnatomyPoseFixture.poseOf(joints)
    }

    @Test
    fun fourLanesPublishTheSameFrameAsOneLane() {
        val serial = AnatomySolver(definition, skeleton, scale, restPose)
        val parallel = AnatomySolver(definition, skeleton, scale, restPose, lanes = 4)
        val one = AnatomyFrame.of(definition)
        val four = AnatomyFrame.of(definition)
        try {
            for (index in poses.indices) {
                serial.solve(poses[index], one)
                parallel.solve(poses[index], four)
                assertSameFrame("pose $index", one, four)
            }
        } finally {
            parallel.close()
        }
    }

    @Test
    fun fourLanesMergeToTheSamePassHistogramAsOneLane() {
        val serial = AnatomySolver(definition, skeleton, scale, restPose)
        val parallel = AnatomySolver(definition, skeleton, scale, restPose, lanes = 4)
        val one = AnatomyFrame.of(definition)
        val four = AnatomyFrame.of(definition)
        try {
            for (index in poses.indices) {
                serial.solve(poses[index], one)
                parallel.solve(poses[index], four)
            }
            for (pass in 0..serial.telemetry.passCap) {
                assertEquals(
                    "pass $pass is counted by the lane that ran it, so the merge must not move it",
                    serial.telemetry.passCount(pass),
                    parallel.telemetry.passCount(pass)
                )
            }
            var total = 0
            for (pass in 0..parallel.telemetry.passCap) total += parallel.telemetry.passCount(pass)
            assertEquals(
                "every head of every frame is counted once: a lane that dropped a slot would show here",
                definition.headCount * poses.size,
                total
            )
        } finally {
            parallel.close()
        }
    }

    @Test
    fun aSolveAfterCloseStillPublishesTheFrame() {
        // A frame that arrives after the teardown must degrade to the caller's lane, not wait for
        // workers that have left: a frozen render loop is worse than a slower frame.
        val reference = AnatomySolver(definition, skeleton, scale, restPose)
        val closed = AnatomySolver(definition, skeleton, scale, restPose, lanes = 4)
        val one = AnatomyFrame.of(definition)
        val four = AnatomyFrame.of(definition)
        closed.close()
        reference.solve(restPose, one)
        closed.solve(restPose, four)
        assertSameFrame("solve after close", one, four)
    }

    @Test
    fun closingASolverStopsEveryLaneItStarted() {
        val before = laneThreads()
        val solver = AnatomySolver(definition, skeleton, scale, restPose, lanes = 4)
        assertEquals("three workers behind the caller's lane", before + 3, laneThreads())
        solver.close()
        assertEquals("a closed lane set leaves no thread parked behind the session", before, laneThreads())
    }

    @Test
    fun aRefusalBeforeTheFirstPassReportsZeroRatherThanThePreviousHeadsPass() {
        val muscle = definition.muscle("rectus_femoris")!!
        val loop = MusclePathSolver(
            definition,
            MusclePathBuilder(definition, X1Bind.unitsPerMetre(skeleton)),
            AnatomyValidator(definition, scale),
            scale
        )
        val frames = SegmentFrames(skeleton)
        val frameSet = SegmentFrameSet()
        frames.derive(restPose, frameSet)
        val frame = AnatomyFrame.of(definition)

        loop.solve(muscle, MuscleSide.A, restPose, frameSet, frame, 0)
        assertTrue("the first solve has to run a pass for this to be the stale value", loop.lastPass >= 1)

        // A corridor of zero leaves no room for the smallest detour, so this head stops before pass 1.
        loop.solve(muscle.copy(corridorMetres = 0f), MuscleSide.A, restPose, frameSet, frame, 0)
        assertEquals("a refusal before the pass loop reports 0", 0, loop.lastPass)
    }

    private fun laneThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("monk-anatomy-lane-") }

    private fun assertSameFrame(what: String, one: AnatomyFrame, four: AnatomyFrame) {
        assertEquals("$what: statuses", one.statuses.toList(), four.statuses.toList())
        assertEquals("$what: reasons", one.reasonCodes.toList(), four.reasonCodes.toList())
        assertEquals("$what: outcomes", one.searchOutcomes.toList(), four.searchOutcomes.toList())
        assertEquals("$what: point counts", one.pointCounts.toList(), four.pointCounts.toList())
        assertEquals("$what: lengths", one.modelledPathLengths.toList(), four.modelledPathLengths.toList())
        assertEquals("$what: tendon portions", one.visualTendonPortionLengths.toList(), four.visualTendonPortionLengths.toList())
        assertEquals("$what: relative changes", one.relativePathLengthChanges.toList(), four.relativePathLengthChanges.toList())
        assertEquals("$what: renderability", one.renderable.toList(), four.renderable.toList())
        assertEquals("$what: falling segments", one.failingSegments.toList(), four.failingSegments.toList())
        assertEquals("$what: failing obstacles", one.failingObstacles.toList(), four.failingObstacles.toList())
        assertEquals(
            "$what: every path coordinate, bit for bit",
            one.paths.map { java.lang.Float.floatToIntBits(it) },
            four.paths.map { java.lang.Float.floatToIntBits(it) }
        )
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
