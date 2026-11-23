package com.monkfitness.app.character

/**
 * The stages a rendered frame is spent in, in execution order.
 *
 * A stage that did not run records a zero instead of being skipped, so its row reads as "not run" and never
 * vanishes into a neighbour's window.
 */
enum class FrameStage(val label: String) {
    /** `CharacterFrameProducer.next` — pose intent to a solved skeleton frame. */
    POSE_BUILD("pose"),

    /** `AnatomySolver.solve` — path solve for every muscle head. */
    ANATOMY_SOLVE("anatomy"),

    /** `SegmentFrames.derive` inside the solve — the segment frames every head reads. */
    ANATOMY_DERIVE("anatomyFrames"),

    /** The per-head path loop inside the solve, resampling and detours included. */
    ANATOMY_PATHS("anatomyPaths"),

    /** `ProceduralMuscleMesh.write` + `TubeMesh.upload` — tube geometry built and handed to the GPU. */
    TUBE_WRITE_UPLOAD("tube"),

    /** `PoseRetargeter.boneMatrices` + `setBonesAsMatrices` — the skinned body's bone upload. */
    RETARGET_UPLOAD("retarget"),

    /** `Renderer.beginFrame` — includes the swapchain wait, which is vsync, not our work. */
    BEGIN_FRAME_WAIT("beginFrame"),

    /** `Renderer.render` + `endFrame` — command submission on the calling thread. */
    RENDER_END_FRAME("render")
}

/** One stage's window percentiles, in milliseconds; the label names the stage in the overlay. */
data class FrameStageReading(val stage: String, val p50Ms: Float, val p95Ms: Float)

/**
 * Per-stage nanosecond ring buffers, written from the render loop and read by diagnostics.
 *
 * [record] writes in place, so a frame allocates nothing. The copy-and-sort lives in [readings], which only
 * the debug overlay polls twice a second — never the render path.
 */
class FrameStageTimings(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "a timing ring needs at least one sample, got $capacity" }
    }

    private val samples: Array<LongArray> = Array(FrameStage.entries.size) { LongArray(capacity) }
    private val cursors = IntArray(FrameStage.entries.size)
    private val counts = IntArray(FrameStage.entries.size)

    // Reused by percentileMs: sorting happens off the render thread, and one buffer keeps the read cheap.
    private val scratch = LongArray(capacity)

    /** Records one stage's duration for this frame; a stage that did not run records `0`. */
    fun record(stage: FrameStage, nanos: Long) {
        val index = stage.ordinal
        samples[index][cursors[index]] = nanos
        cursors[index] = (cursors[index] + 1) % capacity
        if (counts[index] < capacity) counts[index]++
    }

    fun sampleCount(stage: FrameStage): Int = counts[stage.ordinal]

    /**
     * The [percentile] (0..1) of the samples currently held for [stage], in milliseconds. Zero until the
     * stage has one sample, so an idle session reads `0.00` rather than a number nothing measured.
     */
    fun percentileMs(stage: FrameStage, percentile: Float): Float {
        val index = stage.ordinal
        val count = counts[index]
        if (count == 0) return 0f
        System.arraycopy(samples[index], 0, scratch, 0, count)
        scratch.sort(0, count)
        val position = ((count - 1) * percentile).toInt().coerceIn(0, count - 1)
        return scratch[position] / 1_000_000f
    }

    /** Median and p95 for every stage, in declaration order. */
    fun readings(): List<FrameStageReading> {
        val readings = ArrayList<FrameStageReading>(FrameStage.entries.size)
        for (stage in FrameStage.entries) {
            readings += FrameStageReading(
                stage = stage.label,
                p50Ms = percentileMs(stage, 0.5f),
                p95Ms = percentileMs(stage, 0.95f)
            )
        }
        return readings
    }

    private companion object {
        const val DEFAULT_CAPACITY = 120
    }
}
