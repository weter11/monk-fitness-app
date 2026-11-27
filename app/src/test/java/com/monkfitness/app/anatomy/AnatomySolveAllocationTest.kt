package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * The carrier exists so a solve reuses it: one frame per session, no view, path or buffer per frame.
 *
 * The instrument is the JVM's own allocation counter, reached reflectively because the Android unit
 * test classpath has no `java.lang.management` at compile time while the run itself is a real JVM.
 * It is never compared against a bare zero: measuring an empty loop reports a small cost of its own,
 * so each assertion compares the work against that empty-loop baseline. A zero the instrument could
 * reach without observing anything is not evidence.
 *
 * `SegmentFrames.derive` builds five `Vector3` scratch vectors at its own call site, because its
 * purity guard forbids retaining them. The loop is therefore gated against the empty loop, and the
 * live solve is gated one-sided against that derivation: it may be cheaper when the JIT
 * scalar-replaces the vectors, but it can never add an allocation of its own.
 */
class AnatomySolveAllocationTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val frame = AnatomyFrame.of(definition)
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val bindPose = AnatomyPoseFixture.bindPose(skeleton)
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val solver = AnatomySolver(definition, skeleton, scale, bindPose)
    private val loop = MusclePathSolver(
        definition,
        MusclePathBuilder(definition, X1Bind.unitsPerMetre(skeleton)),
        AnatomyValidator(definition, scale),
        scale
    )
    private val out = Vector3()

    /** A field read of an allocated object cannot be optimised away; a local could be. */
    private var sink: Any? = null
    private var accumulator = 0

    @Test
    fun solvingEveryHeadAddsNothingToItsSegmentDerivation() {
        val counter = allocationCounter()
        repeat(WARMUP) { solver.solve(bindPose, frame) }

        val derive = steadyAllocation(counter) { frames.derive(bindPose, frameSet) }
        val solve = steadyAllocation(counter) { solver.solve(bindPose, frame) }

        assertTrue(
            "the live solve must not allocate beyond its derivation: derive=$derive solve=$solve bytes",
            solve <= derive
        )
        val solved = (0 until frame.headCount).count { frame.statuses[it] == PathStatus.VALID.ordinal }
        assertEquals(
            "the measured solve must have solved every head of the atlas, not a skipped body",
            frame.headCount,
            solved
        )
    }

    @Test
    fun dispatchingLanesCostsTheFrameThreadNoAllocation() {
        val counter = allocationCounter()
        // The solver is built here rather than in a field: three worker threads per test instance
        // would be three threads per test method.
        val parallel = AnatomySolver(definition, skeleton, scale, bindPose, lanes = 4)
        try {
            repeat(WARMUP) { parallel.solve(bindPose, frame) }

            val derive = steadyAllocation(counter) { frames.derive(bindPose, frameSet) }
            val solve = steadyAllocation(counter) { parallel.solve(bindPose, frame) }

            assertTrue(
                "handing heads to three lanes must not allocate on the frame thread: derive=$derive solve=$solve bytes",
                solve <= derive
            )
        } finally {
            parallel.close()
        }
    }

    @Test
    fun thePathLoopAllocatesNothingPerHead() {
        frames.derive(bindPose, frameSet)
        val counter = allocationCounter()
        repeat(WARMUP) { solveEveryHeadWithTheLoop() }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { solveEveryHeadWithTheLoop() }

        assertEquals(
            "the loop over ${frame.headCount} heads must allocate no more than an empty loop",
            baseline,
            measured
        )
        val solved = (0 until frame.headCount).count { frame.statuses[it] == PathStatus.VALID.ordinal }
        assertEquals(
            "the measured loop must have solved every head of the atlas, not a skipped body",
            frame.headCount,
            solved
        )
    }

    private fun solveEveryHeadWithTheLoop() {
        frame.clear()
        for (slot in 0 until frame.headCount) {
            val id = frame.heads[slot]
            val muscle = definition.muscle(id.muscleId) ?: continue
            loop.solve(muscle, id.side, bindPose, frameSet, frame, slot)
        }
    }

    @Test
    fun readingAStateCostsNoMoreThanAnEmptyLoop() {
        val counter = allocationCounter()
        val state = frame.muscleStates[0]
        frame.clear()
        repeat(POINTS_PER_HEAD) { frame.writePoint(0, it.toFloat(), 0f, 0f) }
        repeat(WARMUP) { readEverything(state) }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { readEverything(state) }

        assertEquals("reading a state must not allocate per access", baseline, measured)
    }

    @Test
    fun theCounterSeesARealAllocationSoTheComparisonIsNotVacuous() {
        val counter = allocationCounter()
        repeat(WARMUP) { allocateControl() }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { allocateControl() }

        assertTrue(
            "the instrument must see a known allocation: baseline=$baseline measured=$measured bytes",
            measured > baseline
        )
    }

    /**
     * The smallest count over several identical windows: a JIT transition inside one window can only
     * add bytes, so the minimum is the steady state the assertion is about.
     */
    private inline fun steadyAllocation(counter: AllocationCounter, body: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(WINDOWS) {
            val before = counter.bytes()
            repeat(MEASURED) { body() }
            best = minOf(best, counter.bytes() - before)
        }
        return best
    }

    private fun readEverything(state: MuscleState) {
        accumulator = state.id.length + state.name.length + state.headLabel.length +
            state.pointCount + state.headIndex + state.headCount + state.side.ordinal +
            state.layer.ordinal + state.pathStatus.ordinal + state.reason.ordinal +
            state.searchOutcome.ordinal + state.failingSegment + state.failingObstacle +
            state.modelledPathLength.toInt() + state.restLength.toInt() +
            state.relativePathLengthChange.toInt() + state.visualTendonPortionLength.toInt() +
            state.path.size + if (state.renderable) 1 else 0
        state.path.get(0, out)
        accumulator += out.x.toInt() + out.y.toInt() + out.z.toInt()
    }

    private fun allocateControl() {
        sink = IntArray(ARRAY_INTS)
    }

    /**
     * The JVM's per-thread byte counter, or an assumption failure: a JVM without HotSpot's
     * implementation cannot answer the question, and a silent pass would be worse than a skip.
     */
    private fun allocationCounter(): AllocationCounter {
        val factory = runCatching { Class.forName("java.lang.management.ManagementFactory") }.getOrNull()
        val mxType = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        assumeTrue("this gate needs HotSpot's thread management bean", factory != null && mxType != null)

        val bean = factory!!.getMethod("getThreadMXBean").invoke(null)
        val mx = mxType!!
        assumeTrue("the thread bean is not a com.sun.management.ThreadMXBean", mx.isInstance(bean))
        val supported = mx.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
        assumeTrue("the JVM does not support thread allocation counting", supported)

        val enabled = mx.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean
        if (!enabled) mx.getMethod("setThreadAllocatedMemoryEnabled", java.lang.Boolean.TYPE).invoke(bean, true)
        assumeTrue(
            "thread allocation counting could not be enabled",
            mx.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean
        )

        return AllocationCounter(bean!!, mx.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE), Thread.currentThread().id)
    }

    /** Reads the counter by reflection; two calls per window, so its own boxing is outside it. */
    private class AllocationCounter(private val bean: Any, private val getter: Method, private val threadId: Long) {
        fun bytes(): Long = getter.invoke(bean, threadId) as Long
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
        const val WARMUP = 300
        const val MEASURED = 500
        const val WINDOWS = 3
        const val POINTS_PER_HEAD = 8
        const val ARRAY_INTS = 1024
    }
}
