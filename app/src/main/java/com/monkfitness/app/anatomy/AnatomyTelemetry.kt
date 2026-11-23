package com.monkfitness.app.anatomy

/**
 * Per-frame solver bookkeeping, written in place so a solve pays nothing to report itself.
 *
 * [passCount] is cumulative for the session: one frame of a bounded route search is too noisy to
 * read, and a head that needed detours is the only evidence the search is doing any work at all.
 */
class AnatomyTelemetry(passCap: Int = DEFAULT_PASS_CAP) {

    val passCap: Int = passCap.coerceAtLeast(1)

    /** Nanoseconds `SegmentFrames.derive` took on the last solve. */
    var deriveNanos: Long = 0L
        private set

    /** Nanoseconds the per-head path loop took on the last solve, resampling and detours included. */
    var pathsNanos: Long = 0L
        private set

    /** Head slots the last solve visited. */
    var heads: Int = 0
        private set

    // Index 0 counts heads refused before a pass ran; 1..cap counts the pass that accepted or stopped.
    private val passes = IntArray(this.passCap + 1)

    fun beginFrame(heads: Int) {
        this.heads = heads
        deriveNanos = 0L
        pathsNanos = 0L
    }

    fun recordDerive(nanos: Long) {
        deriveNanos = nanos
    }

    fun recordPaths(nanos: Long) {
        pathsNanos = nanos
    }

    /** Records the pass the solver reported: `0` for a head refused before the loop was entered. */
    fun recordPass(lastPass: Int) {
        passes[lastPass.coerceIn(0, passCap)]++
    }

    fun passCount(pass: Int): Int = passes[pass.coerceIn(0, passCap)]

    /**
     * Adds one lane's tally in a single pass. A parallel solve merges its lanes here, after the
     * barrier, so the cumulative histogram is summed by one thread instead of contended per head.
     */
    fun recordPassTally(tally: IntArray) {
        val limit = minOf(tally.size, passes.size)
        for (index in 0 until limit) passes[index] += tally[index]
    }

    /** Clears the cumulative counts as well as this frame's durations. */
    fun reset() {
        passes.fill(0)
        heads = 0
        deriveNanos = 0L
        pathsNanos = 0L
    }

    private companion object {
        const val DEFAULT_PASS_CAP = 8
    }
}
