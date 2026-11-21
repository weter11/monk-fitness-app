package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `MusclePathDeterminismTest`: one pose produces one path. Two runs, a reused carrier and a reloaded
 * definition all have to agree bit for bit, because "either route is acceptable" is not a contract.
 */
class MusclePathDeterminismTest {

    private val shipped: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val definition: AnatomyDefinition = shipped.copy(wrapSurfaces = emptyList())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)
    private val frame = AnatomyFrame.of(definition)

    private fun solver() = AnatomySolver(definition, skeleton, scale, restPose)

    private fun posed(delta: Float): SkeletonPose {
        val joints = HashMap(X1Bind.bindJoints(skeleton))
        val chest = joints.getValue(Joint.CHEST)
        joints[Joint.CHEST] = Vector3(chest.x + delta, chest.y, chest.z)
        return AnatomyPoseFixture.poseOf(joints)
    }

    /** Everything one solve publishes, as comparable values. */
    private fun snapshot(): Snapshot = Snapshot(
        statuses = IntArray(frame.headCount) { frame.statuses[it] },
        reasons = IntArray(frame.headCount) { frame.reasonCodes[it] },
        outcomes = IntArray(frame.headCount) { frame.searchOutcomes[it] },
        counts = IntArray(frame.headCount) { frame.pointCounts[it] },
        lengths = FloatArray(frame.headCount) { frame.modelledPathLengths[it] },
        changes = FloatArray(frame.headCount) { frame.relativePathLengthChanges[it] },
        paths = frame.paths.copyOf(),
        renderable = frame.renderable.copyOf()
    )

    private class Snapshot(
        private val statuses: IntArray,
        private val reasons: IntArray,
        private val outcomes: IntArray,
        private val counts: IntArray,
        private val lengths: FloatArray,
        private val changes: FloatArray,
        private val paths: FloatArray,
        private val renderable: BooleanArray
    ) {
        fun assertSameAs(other: Snapshot, what: String) {
            assertEquals("$what: statuses", statuses.toList(), other.statuses.toList())
            assertEquals("$what: reasons", reasons.toList(), other.reasons.toList())
            assertEquals("$what: outcomes", outcomes.toList(), other.outcomes.toList())
            assertEquals("$what: point counts", counts.toList(), other.counts.toList())
            assertEquals("$what: lengths", lengths.toList(), other.lengths.toList())
            assertEquals("$what: relative changes", changes.toList(), other.changes.toList())
            assertEquals("$what: renderability", renderable.toList(), other.renderable.toList())
            assertEquals(
                "$what: every path coordinate, bit for bit",
                paths.map { java.lang.Float.floatToIntBits(it) },
                other.paths.map { java.lang.Float.floatToIntBits(it) }
            )
        }
    }

    @Test
    fun theSamePoseProducesTheSamePathTwice() {
        val solver = solver()
        solver.solve(posed(0.02f), frame)
        val first = snapshot()
        solver.solve(posed(0.02f), frame)
        first.assertSameAs(snapshot(), "same pose, same carrier")
    }

    @Test
    fun aReusedCarrierDoesNotLeakThePreviousPose() {
        val solver = solver()
        solver.solve(posed(0.02f), frame)
        val first = snapshot()
        solver.solve(posed(-0.05f), frame)
        solver.solve(posed(0.02f), frame)
        first.assertSameAs(snapshot(), "pose A, pose B, pose A")
    }

    @Test
    fun aReloadedDefinitionProducesTheSamePath() {
        val reloaded = AnatomyDefinitionLoader.load(File(ASSET).readText())
        assertEquals("the asset must round-trip as one definition", shipped, reloaded)
        solver().solve(posed(0.02f), frame)
        val first = snapshot()
        AnatomySolver(reloaded.copy(wrapSurfaces = emptyList()), skeleton, scale, restPose).solve(posed(0.02f), frame)
        first.assertSameAs(snapshot(), "reloaded definition")
    }

    @Test
    fun twoSolversAgreeOnTheSamePose() {
        solver().solve(restPose, frame)
        val first = snapshot()
        solver().solve(restPose, frame)
        first.assertSameAs(snapshot(), "second solver")
    }

    @Test
    fun theShippedDefinitionRefusesTheSameHeadsOnEveryRun() {
        // Determinism is not only about accepted paths: a refusal has to be the same refusal, at the
        // same location, on a second run.
        val on = AnatomyFrame.of(shipped)
        val solver = AnatomySolver(shipped, skeleton, scale, restPose)
        solver.solve(restPose, on)
        val first = IntArray(on.headCount) { on.reasonCodes[it] } to IntArray(on.headCount) { on.failingSegments[it] }
        solver.solve(restPose, on)
        val second = IntArray(on.headCount) { on.reasonCodes[it] } to IntArray(on.headCount) { on.failingSegments[it] }
        assertEquals("reason codes", first.first.toList(), second.first.toList())
        assertEquals("failing segments", first.second.toList(), second.second.toList())
    }

    @Test
    fun theReferenceStanceIsItsOwnFixedPoint() {
        val solver = solver()
        solver.solve(restPose, frame)
        for (slot in 0 until frame.headCount) {
            if (frame.statuses[slot] != PathStatus.VALID.ordinal) continue
            assertEquals(
                "at the reference stance the modelled length is the reference length",
                0.0,
                frame.relativePathLengthChanges[slot].toDouble(),
                1e-4
            )
        }
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
