package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resolves authored attachments into engine space and resamples them into a control-polyline path.
 *
 * It reads published joint positions and the derived segment frames, and nothing else: a pose's
 * rotations are never consulted, so a joint whose rotation is authored elsewhere cannot place a
 * muscle where the mesh is not. One builder per session: it owns its scratch and allocates nothing
 * per call.
 */
class MusclePathBuilder(
    private val definition: AnatomyDefinition,
    /** Authored metres → engine units, supplied by the rig that owns the calibration. It multiplies
     * a translation offset and never enters a rotation, so a frame stays unit-free. */
    private val attachmentScale: Float
) {

    init {
        require(attachmentScale > 0f && attachmentScale.isFinite()) {
            "attachmentScale $attachmentScale must be finite and positive"
        }
        require(definition.sampling.spacingMetres > 0f) { "a path cannot be resampled at zero spacing" }
    }

    /** The longest control polyline any head declares: every waypoint plus its two endpoints. */
    private val controlCapacity: Int =
        definition.muscles.maxOf { muscle -> muscle.heads.maxOf { it.waypoints.size + 2 } }

    private val control = FloatArray(maxOf(controlCapacity, 2) * 3)
    private var controlSize = 0
    private val local = Vector3()
    private val turned = Vector3()

    /**
     * Resolves one attachment for [side] into [out], in engine units: the segment's published origin
     * plus its derived frame turned onto the authored offset.
     *
     * False when this pose offers no usable frame for the segment, so a caller can refuse the path
     * instead of drawing one at a stale origin.
     */
    fun resolve(
        attachment: Attachment,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        out: Vector3
    ): Boolean {
        val id = side.instance(attachment.segment)
        val node = SegmentSpec.of(id).node
            ?: throw IllegalArgumentException("${id.name} has no origin joint, so nothing can pin to it")
        if (frames.status(id) == SegmentFrameStatus.INVALID) return false

        val origin = pose.getJoint(node)
        if (!origin.x.isFinite() || !origin.y.isFinite() || !origin.z.isFinite()) return false

        // The authored side is A; P is the same numbers with the lateral coordinate mirrored.
        local.set(
            attachment.x * attachmentScale,
            attachment.y * attachmentScale,
            attachment.z * side.lateralSign * attachmentScale
        )
        frames.apply(id, local, turned)
        out.set(origin.x + turned.x, origin.y + turned.y, origin.z + turned.z)
        return true
    }

    /**
     * Resolves [head]'s attachments into [out] as x,y,z triples and returns the count, so a caller
     * that routes before resampling does not pay for a resample it will replace.
     */
    fun resolveControl(
        head: MuscleHead,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        out: DoubleArray
    ): Int {
        var written = 0
        var ok = resolve(head.origin, side, pose, frames, turned)
        if (!ok) return 0
        out[0] = turned.x.toDouble(); out[1] = turned.y.toDouble(); out[2] = turned.z.toDouble()
        written = 1
        val waypoints = head.waypoints
        for (index in waypoints.indices) {
            if (!resolve(waypoints[index], side, pose, frames, turned)) return 0
            out[written * 3] = turned.x.toDouble()
            out[written * 3 + 1] = turned.y.toDouble()
            out[written * 3 + 2] = turned.z.toDouble()
            written++
        }
        if (!resolve(head.insertion, side, pose, frames, turned)) return 0
        out[written * 3] = turned.x.toDouble()
        out[written * 3 + 1] = turned.y.toDouble()
        out[written * 3 + 2] = turned.z.toDouble()
        return written + 1
    }

    /**
     * Writes [head]'s path for [side] into [frame]'s [slot] and returns its point count, or
     * [NO_INDEX] when the pose cannot support it — a segment with no usable frame, or a control
     * polyline of zero length, which is refused rather than resampled by a division.
     */
    fun build(
        head: MuscleHead,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        frame: AnatomyFrame,
        slot: Int
    ): Int {
        controlSize = 0
        if (!append(head.origin, side, pose, frames)) return NO_INDEX
        val waypoints = head.waypoints
        for (index in waypoints.indices) {
            if (!append(waypoints[index], side, pose, frames)) return NO_INDEX
        }
        if (!append(head.insertion, side, pose, frames)) return NO_INDEX

        var total = 0f
        for (index in 1 until controlSize) total += distance(index - 1, index)
        if (!(total > 0f)) return NO_INDEX

        val sampling = definition.sampling
        // Intervals, then points: a 0.29 m path at a 0.01 m spacing is 29 intervals and 30 samples.
        val wanted = ((total / sampling.spacingMetres).roundToInt() + 1)
            .coerceIn(sampling.minSamples, sampling.maxSamples)

        var segment = 1
        var segmentStart = 0f
        var segmentLength = distance(0, 1)
        for (point in 0 until wanted) {
            val target = if (wanted == 1) 0f else total * point / (wanted - 1)
            while (segment < controlSize - 1 && segmentStart + segmentLength < target) {
                segmentStart += segmentLength
                segment++
                segmentLength = distance(segment - 1, segment)
            }
            val u = if (segmentLength > 0f) (target - segmentStart) / segmentLength else 0f
            val from = (segment - 1) * 3
            val to = segment * 3
            val x = control[from] + (control[to] - control[from]) * u
            val y = control[from + 1] + (control[to + 1] - control[from + 1]) * u
            val z = control[from + 2] + (control[to + 2] - control[from + 2]) * u
            if (!frame.writePoint(slot, x, y, z)) return frame.pointCounts[slot]
        }
        return wanted
    }

    private fun append(attachment: Attachment, side: MuscleSide, pose: SkeletonPose, frames: SegmentFrameSet): Boolean {
        val base = controlSize * 3
        if (base + 3 > control.size) {
            throw IllegalStateException("control polyline overflows its declared capacity ${control.size / 3}")
        }
        if (!resolve(attachment, side, pose, frames, turned)) return false
        control[base] = turned.x
        control[base + 1] = turned.y
        control[base + 2] = turned.z
        controlSize++
        return true
    }

    private fun distance(from: Int, to: Int): Float {
        val dx = control[to * 3] - control[from * 3]
        val dy = control[to * 3 + 1] - control[from * 3 + 1]
        val dz = control[to * 3 + 2] - control[from * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
