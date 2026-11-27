package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomyPoseFixture
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.SkeletonDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * The skinner runs on the frame thread for every solved frame, so it is measured against an empty loop
 * rather than against zero: the counter reports a cost of its own for any window.
 *
 * A calibration case proves the instrument still moves; without it a zero assertion can pass on a dead
 * counter, which is the one failure mode this gate cannot otherwise see.
 */
class MuscleLayerSkinnerAllocationTest {

    // The skinned mesh can only draw sections its own GLB declares, so the fixture is the exported
    // subset of the library rather than the whole atlas; see MuscleExportFixture.
    private val binds = MuscleExportFixture.binds()
    private val definition: AnatomyDefinition = MuscleExportFixture.definition(binds = binds)
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton)
    private val frame = AnatomyFrame.of(definition)
    private val skinner = MuscleLayerSkinner(
        rig = skinningRig(),
        binds = binds,
        heads = frame.heads,
        unitsPerMetre = unitsPerMetre,
        pointCapacity = definition.sampling.maxPointsPerHead
    )
    private val body = FloatArray(binds.skinJoints * 16)
    private val out = FloatArray(binds.palette * 16)

    /** A field read of an allocated object cannot be optimised away; a local could be. */
    private var sink: Any? = null

    @Test
    fun theSteadySkinningPathAllocatesNoMoreThanAnEmptyLoop() {
        solveRest()
        val counter = allocationCounter()
        repeat(WARMUP) { skinner.write(frame, body, out) }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { skinner.write(frame, body, out) }

        assertEquals(
            "writing ${binds.palette} joint matrices must allocate no more than an empty loop",
            baseline,
            measured
        )
        val solved = (0 until frame.headCount).count { frame.statuses[it] == PathStatus.VALID.ordinal }
        assertTrue("the measured frame must have paths, not a skipped body", solved > 0)
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

    private fun solveRest() {
        val solver = AnatomySolver(definition, skeleton, unitsPerMetre.toDouble(), AnatomyPoseFixture.bindPose(skeleton))
        try {
            solver.solve(AnatomyPoseFixture.bindPose(skeleton), frame)
        } finally {
            solver.close()
        }
    }

    private fun allocateControl() {
        sink = IntArray(ARRAY_INTS)
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

    private fun skinningRig(): GltfRig {
        val inverseBinds = List(binds.palette) { index ->
            if (index < binds.skinJoints) {
                Mat4.identity()
            } else {
                FloatArray(16).also { binds.inverseBindInto(index - binds.skinJoints, it) }
            }
        }
        return GltfRig(
            nodes = emptyList(),
            sceneRoots = IntArray(0),
            jointNodes = IntArray(binds.palette) { it },
            skinnedNodes = IntArray(0),
            inverseBinds = inverseBinds,
            triangleCount = 0,
            morphTargetCount = 0,
            animationNames = emptyList()
        )
    }

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

    private class AllocationCounter(private val bean: Any, private val getter: Method, private val threadId: Long) {
        fun bytes(): Long = getter.invoke(bean, threadId) as Long
    }

    private companion object {
        const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
        const val WARMUP = 200
        const val MEASURED = 500
        const val WINDOWS = 3
        const val ARRAY_INTS = 1024
    }
}
