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
 * The frames, the pass loop and the rest carrier are owned here and reused across solves, so a
 * solve adds no allocation of its own. `SegmentFrames.derive` nominally builds five `Vector3`
 * scratch vectors per call, because its purity guard forbids retaining them; nothing else here
 * allocates.
 */
class AnatomySolver(
    private val definition: AnatomyDefinition,
    skeleton: SkeletonDefinition,
    private val attachmentScale: Double,
    restPose: SkeletonPose,
    val telemetry: AnatomyTelemetry = AnatomyTelemetry()
) {

    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val builder = MusclePathBuilder(definition, attachmentScale.toFloat())
    private val validator = AnatomyValidator(definition, attachmentScale)
    private val loop = MusclePathSolver(definition, builder, validator, attachmentScale)

    /** The rest frame the same loop would produce, one entry per head slot. `NaN` where it cannot. */
    private val restLengths = DoubleArray(definition.headCount)

    private val restFrame = AnatomyFrame.of(definition)

    init {
        require(attachmentScale > 0.0 && attachmentScale.isFinite()) {
            "attachmentScale $attachmentScale must be finite and positive"
        }
        frames.derive(restPose, frameSet)
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

        val pathsStart = System.nanoTime()
        for (slot in 0 until out.headCount) {
            val id = out.heads[slot]
            val muscle = definition.muscle(id.muscleId) ?: continue
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
                telemetry.recordPass(0)
                continue
            }

            loop.solve(muscle, id.side, pose, frameSet, out, slot)
            // The pass the loop reports, not the pass this loop assumes: a refusal before the first
            // candidate reports 0 and is kept apart from a search that ran and stopped.
            telemetry.recordPass(loop.lastPass)
            if (out.statuses[slot] != PathStatus.VALID.ordinal) continue

            val modelled = out.modelledPathLengths[slot].toDouble()
            out.visualTendonPortionLengths[slot] =
                ((muscle.tendon.originFraction + muscle.tendon.insertionFraction) * modelled).toFloat()
            out.relativePathLengthChanges[slot] = (modelled / rest - 1.0).toFloat()
        }
        telemetry.recordPaths(System.nanoTime() - pathsStart)

        // Renderability is the conjunction over the instance's heads: one invalid head hides the
        // instance, because a muscle drawn with a head missing is a shape nobody reviewed.
        out.renderable.fill(true)
        for (slot in 0 until out.headCount) {
            if (out.statuses[slot] != PathStatus.VALID.ordinal) out.renderable[out.instanceOf(slot)] = false
        }
        return out
    }
}
