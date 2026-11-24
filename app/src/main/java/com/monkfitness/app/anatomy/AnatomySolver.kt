package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose

/**
 * §7.4 steps 1–7 for one definition: every head's path in the published pose, its length against the
 * reference stance, and the per-instance mask.
 *
 * Rest lengths are solved by this same loop at [restPose] rather than read from the definition, so
 * the reference state and the runtime cannot disagree. A head whose reference length is not finite
 * and positive is [PathReason.REST_LENGTH_INVALID] on every frame and never divides.
 *
 * The frames, the paths and the rest carrier are owned here and reused across solves, so a solve
 * adds no allocation of its own. `SegmentFrames.derive` nominally builds five `Vector3` scratch
 * vectors per call, because its purity guard forbids retaining them; nothing else here allocates.
 *
 * A head reads one slot of the frame and one rank of the segment frames, so the heads are
 * independent and a lane set can solve them concurrently without any ordering between them; see
 * [HeadSolvePool] for why that leaves the output bit-identical to the serial loop.
 */
class AnatomySolver(
    private val definition: AnatomyDefinition,
    skeleton: SkeletonDefinition,
    private val attachmentScale: Double,
    restPose: SkeletonPose,
    val telemetry: AnatomyTelemetry = AnatomyTelemetry(),
    /** Lanes the solve uses: the frame thread plus `lanes - 1` workers. One lane is the serial loop. */
    lanes: Int = 1
) : HeadSolvePool.Body, AutoCloseable {

    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val pool = HeadSolvePool(lanes, telemetry.passCap) {
        MusclePathSolver(
            definition,
            MusclePathBuilder(definition, attachmentScale.toFloat()),
            AnatomyValidator(definition, attachmentScale),
            attachmentScale
        )
    }

    /** The rest frame the same loop would produce, one entry per head slot. `NaN` where it cannot. */
    private val restLengths = DoubleArray(definition.headCount)

    private val restFrame = AnatomyFrame.of(definition)

    // Written once per solve, before the dispatch that publishes them to the lanes; never mutated
    // while a lane is running.
    private var pose: SkeletonPose = restPose
    private var frame: AnatomyFrame = restFrame

    init {
        require(attachmentScale > 0.0 && attachmentScale.isFinite()) {
            "attachmentScale $attachmentScale must be finite and positive"
        }
        // The rest stance is a one-off solve, serial on purpose: it runs before a session draws a
        // frame, so waking three workers to save 15 ms once would cost more than it saves.
        frames.derive(restPose, frameSet)
        val loop = pool.callerLoop
        var slot = 0
        for (muscle in definition.muscles) {
            for (side in MuscleSide.entries) {
                for (headIndex in muscle.heads.indices) {
                    loop.solve(muscle, side, restPose, frameSet, restFrame, slot)
                    restLengths[slot] = if (restFrame.statuses[slot] == PathStatus.VALID.ordinal) {
                        restFrame.modelledPathLengths[slot].toDouble()
                    } else {
                        Double.NaN
                    }
                    slot++
                }
            }
        }
        // Last, so the body a worker could ever reach already has its rest carrier: a dispatch during
        // construction would otherwise read zeros and refuse every head as REST_LENGTH_INVALID.
        pool.bind(this)
    }

    /**
     * Solves every head of [out] for [pose] and returns it. [out] is cleared first, so a head this
     * call never reaches reads `NOT_PROCESSED` rather than the previous frame's answer.
     */
    fun solve(pose: SkeletonPose, out: AnatomyFrame): AnatomyFrame {
        out.clear()
        telemetry.beginFrame(out.headCount)

        val deriveStart = System.nanoTime()
        frames.derive(pose, frameSet)
        telemetry.recordDerive(System.nanoTime() - deriveStart)

        this.pose = pose
        this.frame = out
        val pathsStart = System.nanoTime()
        pool.run(out.headCount)
        telemetry.recordPaths(System.nanoTime() - pathsStart)
        pool.mergePasses(telemetry)

        // Renderability is the conjunction over the instance's heads: one invalid head hides the
        // instance, because a muscle drawn with a head missing is a shape nobody reviewed.
        out.renderable.fill(true)
        for (slot in 0 until out.headCount) {
            if (out.statuses[slot] != PathStatus.VALID.ordinal) out.renderable[out.instanceOf(slot)] = false
        }
        return out
    }

    /** Stops the solve lanes; the session calls this from its teardown, never mid-solve. */
    override fun close() {
        pool.close()
    }

    /**
     * One head, on whichever lane pulled it. Nothing here reads another slot's result, which is what
     * makes the lanes interchangeable.
     */
    override fun solveSlot(lane: SolveLane, slot: Int) {
        val out = frame
        val id = out.heads[slot]
        val muscle = definition.muscle(id.muscleId) ?: return
        val rest = restLengths[slot]
        out.restLengths[slot] = rest.toFloat()
        if (!rest.isFinite() || rest <= 0.0) {
            // A relative change against a zero or NaN reference is not a small error: it is a
            // meaningless number that reads as a large one, so the head is refused instead.
            out.statuses[slot] = PathStatus.INVALID.ordinal
            out.reasonCodes[slot] = PathReason.REST_LENGTH_INVALID.ordinal
            out.searchOutcomes[slot] = SearchOutcome.REJECTED_BEFORE_SEARCH.ordinal
            out.failingSegments[slot] = NO_INDEX
            out.failingObstacles[slot] = NO_INDEX
            out.pointCounts[slot] = 0
            lane.passes[0]++
            return
        }

        val loop = lane.loop
        loop.solve(muscle, id.side, pose, frameSet, out, slot)
        // Read before anything that can throw, so the head is counted by the pass it actually ran.
        val pass = loop.lastPass.coerceIn(0, telemetry.passCap)
        if (out.statuses[slot] != PathStatus.VALID.ordinal) {
            lane.passes[pass]++
            return
        }

        val modelled = out.modelledPathLengths[slot].toDouble()
        out.visualTendonPortionLengths[slot] =
            ((muscle.tendon.originFraction + muscle.tendon.insertionFraction) * modelled).toFloat()
        out.relativePathLengthChanges[slot] = (modelled / rest - 1.0).toFloat()
        // Counted last: a throw above leaves the head uncounted, so the pool's retry of this slot
        // adds it exactly once instead of twice.
        lane.passes[pass]++
    }
}
