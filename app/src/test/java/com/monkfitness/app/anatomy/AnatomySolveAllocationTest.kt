package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Vector3
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
 */
class AnatomySolveAllocationTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val frame = AnatomyFrame.of(definition)
    private val out = Vector3()

    /** A field read of an allocated object cannot be optimised away; a local could be. */
    private var sink: Any? = null
    private var accumulator = 0

    @Test
    fun clearingAndRefillingTheFrameCostsNoMoreThanAnEmptyLoop() {
        val counter = allocationCounter()
        repeat(WARMUP) { pass() }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { pass() }

        assertEquals(
            "a reuse pass over ${frame.headCount} heads must allocate no more than the loop itself",
            baseline,
            measured
        )
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

    private fun pass() {
        frame.clear()
        for (slot in 0 until frame.headCount) {
            for (point in 0 until POINTS_PER_HEAD) {
                frame.writePoint(slot, point.toFloat(), 0.5f, -0.5f)
            }
            val state = frame.muscleStates[slot]
            frame.modelledPathLengths[slot] = state.pointCount.toFloat()
            frame.restLengths[slot] = 1f
            frame.relativePathLengthChanges[slot] = 0.25f
            frame.statuses[slot] = PathStatus.VALID.ordinal
            frame.reasonCodes[slot] = PathReason.NO_REASON.ordinal
            frame.searchOutcomes[slot] = SearchOutcome.CLEARED.ordinal
            frame.renderable[frame.instanceOf(slot)] = true
            state.pathPoint(0, out)
        }
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
