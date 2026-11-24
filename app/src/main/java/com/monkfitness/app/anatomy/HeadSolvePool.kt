package com.monkfitness.app.anatomy

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** No slot claimed by a lane; also the value that says a lane has no fault to report. */
private const val NO_SLOT = -1

/**
 * One lane's private half of a solve: its own path loop — hence its own polyline scratch, obstacle
 * frame and radius profiles — and the pass tally that lane recorded for the heads it pulled.
 */
class SolveLane internal constructor(
    internal val loop: MusclePathSolver,
    internal val passes: IntArray
) {
    /**
     * The slot this lane last claimed, left set when [HeadSolvePool.Body.solveSlot] throws so the
     * caller can solve that one head again: a claimed slot is not re-issued by [HeadSolvePool.run].
     */
    internal var faultedSlot: Int = NO_SLOT
}

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
 *
 * A lane that throws is a lost head, not a lost frame: the throw is recorded, the barrier still
 * releases, the caller re-solves the slot that lane had claimed, and every later solve runs on the
 * caller's lane alone. Blocking the frame thread on a lane that will never report is the one outcome
 * the pool may not produce, so the wait is bounded even when no lane faults.
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
    @Volatile private var fault: Throwable? = null
    @Volatile private var degraded = false

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

    /** The first fault reported by a lane, or `null`; a set fault means later solves run serially. */
    val failure: Throwable? get() = fault

    /** Whether a lane fault or a missed barrier forced every later solve onto the caller's lane. */
    val isDegraded: Boolean get() = degraded

    /**
     * Names the work before the first [run]. Bound after the rest carrier is filled, so a worker
     * could never observe a half-built body even if a dispatch were added before construction ends.
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
        for (lane in lanes) {
            lane.passes.fill(0)
            lane.faultedSlot = NO_SLOT
        }
        slots = headCount
        next.set(0)
        if (closing || degraded) {
            // A closed or already-faulted lane set would wait on workers that left or on a parallel
            // path that already cost a frame once: the caller's lane still publishes every head.
            drain(lanes[0], work)
            return
        }
        finished.set(0)
        waiter = Thread.currentThread()
        dispatch.incrementAndGet()
        // close() is not serialized with run(), so a worker woken by it may see `closing` while this
        // dispatch is already published; the re-check in work() makes it run the dispatch first.
        for (index in threads.indices) LockSupport.unpark(threads[index])
        var callerFault: Throwable? = null
        try {
            drain(lanes[0], work)
        } catch (t: Throwable) {
            // The caller's own slot failed: release the lanes before rethrowing so they cannot keep
            // writing into a frame the next solve is about to clear.
            callerFault = t
            recordFault(t)
        }
        awaitWorkers()
        recoverFaultedSlots(work)
        callerFault?.let { throw it }
    }

    /** Adds every lane's tally to [telemetry]: one writer, after the barrier, so no counter contends. */
    fun mergePasses(telemetry: AnatomyTelemetry) {
        for (lane in lanes) telemetry.recordPassTally(lane.passes)
    }

    /**
     * Stops the workers. The caller pauses frame dispatch first, so shutdown is serialized with
     * [run] in practice; the join budget is shared across the lanes, so teardown is bounded by one
     * deadline rather than one per lane, and a lane still inside a dispatch finishes and reports.
     */
    override fun close() {
        closing = true
        for (thread in threads) LockSupport.unpark(thread)
        val deadline = System.nanoTime() + CLOSE_JOIN_NANOS
        for (thread in threads) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            try {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun drain(lane: SolveLane, work: Body) {
        val end = slots
        while (true) {
            val slot = next.getAndIncrement()
            if (slot >= end) return
            lane.faultedSlot = slot
            work.solveSlot(lane, slot)
            lane.faultedSlot = NO_SLOT
        }
    }

    private fun work(lane: SolveLane) {
        var seen = 0
        while (true) {
            while (dispatch.get() == seen) {
                if (closing) {
                    // A close can land between run()'s closing check and its dispatch bump; if a
                    // dispatch is outstanding, do it rather than leave the caller waiting for a lane
                    // that has already left.
                    if (dispatch.get() != seen) break
                    return
                }
                LockSupport.park()
            }
            seen = dispatch.get()
            val work = body ?: continue
            try {
                drain(lane, work)
            } catch (t: Throwable) {
                recordFault(t)
            } finally {
                // Every dispatch is reported even when the body threw, or the caller would wait for
                // a lane that is no longer going to answer.
                if (finished.incrementAndGet() == workers) waiter?.let { LockSupport.unpark(it) }
            }
        }
    }

    /**
     * Waits for the lanes with a deadline rather than forever: a head that never returns must cost a
     * frame, not the frame thread. The caller has already claimed every slot, so on expiry only a
     * slot a stuck lane still holds is unanswered.
     */
    private fun awaitWorkers() {
        if (workers == 0) return
        val interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + WAIT_NANOS
        while (finished.get() < workers) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                recordFault(TimeoutException("a solve lane did not report within ${WAIT_NANOS / 1_000_000} ms"))
                break
            }
            LockSupport.parkNanos(minOf(remaining, PARK_SLICE_NANOS))
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** Re-solves the slot each faulted lane had claimed, on the caller's lane, so no head is lost. */
    private fun recoverFaultedSlots(work: Body) {
        val caller = lanes[0]
        for (lane in lanes) {
            val slot = lane.faultedSlot
            if (slot == NO_SLOT) continue
            lane.faultedSlot = NO_SLOT
            try {
                caller.faultedSlot = slot
                work.solveSlot(caller, slot)
                caller.faultedSlot = NO_SLOT
            } catch (t: Throwable) {
                caller.faultedSlot = NO_SLOT
                recordFault(t)
            }
        }
    }

    private fun recordFault(t: Throwable) {
        // A fault is one write per failure, so a monitor is cheaper than the contention a CAS loop
        // would put on the dispatch path this class exists to keep lock-free.
        synchronized(this) {
            if (fault == null) fault = t
            degraded = true
        }
    }

    private companion object {
        /** Long enough for a whole solve at p99, short enough that a stuck lane cannot freeze a frame. */
        const val WAIT_NANOS = 250_000_000L

        /** A park slice keeps the caller responsive to interrupts without spinning on the deadline. */
        const val PARK_SLICE_NANOS = 1_000_000L

        /** Shared by every lane: teardown costs one budget, not one per worker. */
        const val CLOSE_JOIN_NANOS = 500_000_000L
    }
}
