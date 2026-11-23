package com.monkfitness.app.anatomy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * One lane's private half of a solve: its own path loop — hence its own polyline scratch, obstacle
 * frame and radius profiles — and the pass tally that lane recorded for the heads it pulled.
 */
class SolveLane internal constructor(
    internal val loop: MusclePathSolver,
    internal val passes: IntArray
)

/**
 * Solves head slots on a bounded lane set: the caller's thread plus parked worker threads.
 *
 * Session-scoped. The workers are created once, park between solves and are torn down only by
 * [close]; a dispatch is a volatile counter bump and one unpark per worker, and the caller waits for
 * them on a park loop rather than a latch, so a parallel solve hands the frame thread no allocation
 * and no lock it did not already pay for.
 *
 * A head's result depends on its own slot alone, so which lane pulls which slot cannot reach the
 * frame: the same pose produces bit-identical output at any lane count. What lanes genuinely cannot
 * share is solver state, which is why each lane owns a [MusclePathSolver] and the pass counts they
 * record are summed by [mergePasses] after the barrier instead of being written concurrently.
 */
class HeadSolvePool(
    laneCount: Int,
    passCap: Int,
    laneLoop: () -> MusclePathSolver
) : AutoCloseable {

    /** The work one slot is, run on a lane: it may touch that lane's state and its own slot only. */
    fun interface Body {
        fun solveSlot(lane: SolveLane, slot: Int)
    }

    init {
        require(laneCount >= 1) { "a pool needs at least the calling lane, got $laneCount" }
    }

    /** Lane 0 runs on the caller; the others wake only for a dispatch. */
    private val lanes: Array<SolveLane> = Array(laneCount) { SolveLane(laneLoop(), IntArray(passCap + 1)) }

    private val workers = lanes.size - 1

    // Read before the dispatch counter so that the counter's write publishes them to every worker.
    @Volatile private var slots = 0
    @Volatile private var waiter: Thread? = null
    @Volatile private var body: Body? = null
    @Volatile private var closing = false

    private val next = AtomicInteger(0)
    private val finished = AtomicInteger(0)
    private val dispatch = AtomicInteger(0)

    private val threads: Array<Thread> = Array(workers) { index ->
        Thread({ work(lanes[index + 1]) }, "monk-anatomy-lane-${index + 1}").apply {
            // A session that is killed without destroying it must not keep the process alive.
            isDaemon = true
            start()
        }
    }

    /** The lane the caller runs on; its loop is also what a one-off solve (the rest stance) uses. */
    internal val callerLoop: MusclePathSolver get() = lanes[0].loop

    /**
     * Names the work before the first [run]. Bound after construction because the body is the solver
     * that owns this pool, and a worker must not be able to see a half-built solver.
     */
    fun bind(body: Body) {
        this.body = body
    }

    /**
     * Solves [headCount] slots across the lanes and returns once every lane has finished. Called from
     * the frame thread only: two overlapping dispatches would hand one slot out twice.
     */
    fun run(headCount: Int) {
        val work = body ?: throw IllegalStateException("a lane set cannot dispatch before it is bound")
        for (lane in lanes) lane.passes.fill(0)
        slots = headCount
        next.set(0)
        if (closing) {
            // A dispatch after close would wait on workers that have left: solving on the caller's
            // lane keeps a late frame drawing instead of freezing the frame loop.
            drain(lanes[0], work)
            return
        }
        finished.set(0)
        waiter = Thread.currentThread()
        dispatch.incrementAndGet()
        for (index in threads.indices) LockSupport.unpark(threads[index])
        drain(lanes[0], work)
        // The permit an unpark leaves behind makes a lost wakeup impossible, so a bare guard loop is safe.
        while (workers > 0 && finished.get() < workers) LockSupport.park()
    }

    /** Adds every lane's tally to [telemetry]: one writer, after the barrier, so no counter contends. */
    fun mergePasses(telemetry: AnatomyTelemetry) {
        for (lane in lanes) telemetry.recordPassTally(lane.passes)
    }

    /**
     * Stops the workers. Safe between solves, which is where the session calls it: a worker still
     * inside a dispatch finishes it first and reports, so [run] cannot be left waiting.
     */
    override fun close() {
        closing = true
        for (thread in threads) LockSupport.unpark(thread)
        for (thread in threads) thread.join(CLOSE_JOIN_MILLIS)
    }

    private fun drain(lane: SolveLane, work: Body) {
        val end = slots
        while (true) {
            val slot = next.getAndIncrement()
            if (slot >= end) return
            work.solveSlot(lane, slot)
        }
    }

    private fun work(lane: SolveLane) {
        var seen = 0
        while (true) {
            while (dispatch.get() == seen) {
                if (closing) return
                LockSupport.park()
            }
            seen = dispatch.get()
            val work = body ?: continue
            drain(lane, work)
            if (finished.incrementAndGet() == workers) waiter?.let { LockSupport.unpark(it) }
        }
    }

    private companion object {
        /** Long enough to outlast a head solve, short enough that a teardown never hangs on one. */
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
