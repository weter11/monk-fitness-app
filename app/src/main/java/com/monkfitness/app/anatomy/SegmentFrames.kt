package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A caller-owned buffer of derived segment frames: nine floats per instance, one entry per
 * [SegmentId], plus the status and reason each frame was established with.
 *
 * The axes are stored axis-major — column `+X` of instance `i` at `i * 9`, `+Y` at `i * 9 + 3`,
 * `+Z` at `i * 9 + 6` — so a frame is a contiguous run a caller can upload without gathering. A
 * cleared buffer holds no frame at all, which is why `clear()` writes [SegmentFrameStatus.INVALID]
 * rather than leaving the previous frame readable.
 */
class SegmentFrameSet {

    internal val axes = FloatArray(SegmentId.entries.size * 9)

    private val statuses = Array(SegmentId.entries.size) { SegmentFrameStatus.INVALID }
    private val reasons = Array(SegmentId.entries.size) { SegmentFrameReason.UNSET }

    /** Resets every slot to "no frame": zeroed axes, [SegmentFrameStatus.INVALID], [SegmentFrameReason.UNSET]. */
    fun clear() {
        axes.fill(0f)
        statuses.fill(SegmentFrameStatus.INVALID)
        reasons.fill(SegmentFrameReason.UNSET)
    }

    fun status(id: SegmentId): SegmentFrameStatus = statuses[id.ordinal]

    fun reason(id: SegmentId): SegmentFrameReason = reasons[id.ordinal]

    /** One component of one axis: [axisIndex] and [component] both `0..2` for `+X`, `+Y`, `+Z`. */
    fun axis(id: SegmentId, axisIndex: Int, component: Int): Float =
        axes[id.ordinal * 9 + axisIndex * 3 + component]

    /** Rotates a vector expressed in the segment's rest frame into world space: `world = R · v`. */
    fun apply(id: SegmentId, v: Vector3, out: Vector3): Vector3 {
        val base = id.ordinal * 9
        return out.set(
            axes[base] * v.x + axes[base + 3] * v.y + axes[base + 6] * v.z,
            axes[base + 1] * v.x + axes[base + 4] * v.y + axes[base + 7] * v.z,
            axes[base + 2] * v.x + axes[base + 5] * v.y + axes[base + 8] * v.z
        )
    }

    internal fun zeroFrame(id: SegmentId) {
        val base = id.ordinal * 9
        for (k in 0 until 9) axes[base + k] = 0f
    }

    internal fun copyFrame(from: SegmentId, to: SegmentId) {
        val src = from.ordinal * 9
        val dst = to.ordinal * 9
        for (k in 0 until 9) axes[dst + k] = axes[src + k]
    }

    internal fun mark(id: SegmentId, status: SegmentFrameStatus, reason: SegmentFrameReason) {
        statuses[id.ordinal] = status
        reasons[id.ordinal] = reason
    }
}

/**
 * Derives the fourteen segment frames from published positions by X9's minimal rotation
 * `R = D · P`; it reads positions only and writes only into the caller's buffer.
 */
class SegmentFrames(private val definition: SkeletonDefinition) {

    private val restLengths = FloatArray(Segment.entries.size) {
        SegmentRest.restLength(Segment.entries[it], definition)
    }
    private val hipLineRest = SegmentRest.hipLineRestLength(definition)
    private val torsoRest = definition.torsoLength

    init {
        SegmentRest.validate(definition)
    }

    /** The segment's rest length at the bind pose, in engine units; `0` for a row with no pair. */
    fun restLengthOf(id: SegmentId): Float = restLengths[id.segment.ordinal]

    /**
     * Rewrites [out] with every instance's frame for [pose], parent before child. The root is the
     * only `USABLE` frame: two directions pin it, where every other row carries its parent's roll.
     */
    fun derive(pose: SkeletonPose, out: SegmentFrameSet) {
        out.clear()
        deriveRoot(pose, out)

        val a = Vector3()
        val b = Vector3()
        val k = Vector3()
        val e = Vector3()
        val rd = Vector3()

        for (spec in SegmentSpec.ALL) {
            val parent = spec.parent ?: continue
            val id = spec.id
            if (out.status(parent) == SegmentFrameStatus.INVALID) {
                out.zeroFrame(id)
                out.mark(id, SegmentFrameStatus.INVALID, out.reason(parent))
                continue
            }
            if (spec.inherits) {
                out.copyFrame(parent, id)
                out.mark(id, SegmentFrameStatus.TWIST_UNAUTHORED, SegmentFrameReason.COINCIDENT)
                continue
            }

            val node = pose.getJoint(spec.node!!)
            val child = pose.getJoint(spec.child!!)
            if (!node.isFinite() || !child.isFinite()) {
                out.zeroFrame(id)
                out.mark(id, SegmentFrameStatus.INVALID, SegmentFrameReason.NON_FINITE_GEOMETRY)
                continue
            }

            if (!(restLengths[spec.segment.ordinal] > 0f)) {
                // A row with no rest span has no rest direction: a definition defect, not a posed one.
                out.zeroFrame(id)
                out.mark(id, SegmentFrameStatus.INVALID, SegmentFrameReason.ZERO_REST_DIRECTION)
                continue
            }

            val dx = child.x - node.x
            val dy = child.y - node.y
            val dz = child.z - node.z
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!(length > 0f) || !(length >= SegmentRest.TAU_LEN * restLengths[spec.segment.ordinal])) {
                out.copyFrame(parent, id)
                out.mark(id, SegmentFrameStatus.TWIST_UNAUTHORED, SegmentFrameReason.COINCIDENT)
                continue
            }

            if (!SegmentRest.restDirInto(id, rd)) {
                out.zeroFrame(id)
                out.mark(id, SegmentFrameStatus.INVALID, SegmentFrameReason.ZERO_REST_DIRECTION)
                continue
            }

            a.set(0f, 0f, 0f)
            out.apply(parent, rd, a)
            val aLength = a.mag()
            if (!(aLength >= SegmentRest.TAU_ROT)) {
                // A rest direction the parent's frame collapses is a definition defect, not a pose one.
                out.zeroFrame(id)
                out.mark(id, SegmentFrameStatus.INVALID, SegmentFrameReason.ZERO_REST_DIRECTION)
                continue
            }
            a.divide(aLength)
            b.set(dx / length, dy / length, dz / length)
            a.cross(b, k)
            val sin = k.mag()
            val cos = a.dot(b)

            if (sin < SegmentRest.TAU_ROT) {
                if (cos >= 0f) {
                    // Parallel: a normalised tiny cross product would invent a roll the pair cannot state.
                    out.copyFrame(parent, id)
                    out.mark(id, SegmentFrameStatus.TWIST_UNAUTHORED, SegmentFrameReason.NEAR_PARALLEL)
                } else {
                    SegmentRest.leastAlignedAxis(a.x, a.y, a.z, e)
                    a.cross(e, k)
                    k.normalize()
                    writeHalfTurn(parent, k, id, out)
                    out.mark(id, SegmentFrameStatus.TWIST_UNAUTHORED, SegmentFrameReason.NEAR_ANTIPODAL)
                }
                continue
            }

            rotateFrame(parent, k.x / sin, k.y / sin, k.z / sin, atan2(sin, cos), id, out)
            out.mark(id, SegmentFrameStatus.TWIST_UNAUTHORED, SegmentFrameReason.UNSET)
        }
    }

    /**
     * The root frame: `+Z` along the hip line, `+Y` along `PELVIS → CHEST` orthogonalised against it,
     * `+X` completing the triad. `PELVIS → LUMBAR` is coincident in every frame, so the hip line is
     * the only lateral direction the carrier publishes.
     */
    private fun deriveRoot(pose: SkeletonPose, out: SegmentFrameSet) {
        val root = SegmentId.PELVIS
        val hipF = pose.getJoint(Joint.HIP_F)
        val hipB = pose.getJoint(Joint.HIP_B)
        val pelvis = pose.getJoint(Joint.PELVIS)
        val chest = pose.getJoint(Joint.CHEST)
        if (!hipF.isFinite() || !hipB.isFinite() || !pelvis.isFinite() || !chest.isFinite()) {
            out.mark(root, SegmentFrameStatus.INVALID, SegmentFrameReason.NON_FINITE_GEOMETRY)
            return
        }

        var zx = hipF.x - hipB.x
        var zy = hipF.y - hipB.y
        var zz = hipF.z - hipB.z
        val zLength = sqrt(zx * zx + zy * zy + zz * zz)
        if (!(zLength > 0f) || !(zLength >= SegmentRest.TAU_LEN * hipLineRest)) {
            out.mark(root, SegmentFrameStatus.INVALID, SegmentFrameReason.ROOT_DEGENERATE)
            return
        }
        zx /= zLength
        zy /= zLength
        zz /= zLength

        val y0x = chest.x - pelvis.x
        val y0y = chest.y - pelvis.y
        val y0z = chest.z - pelvis.z
        val y0Length = sqrt(y0x * y0x + y0y * y0y + y0z * y0z)
        if (!(y0Length >= SegmentRest.TAU_LEN * torsoRest)) {
            out.mark(root, SegmentFrameStatus.INVALID, SegmentFrameReason.ROOT_DEGENERATE)
            return
        }

        val along = y0x * zx + y0y * zy + y0z * zz
        val yx = y0x - zx * along
        val yy = y0y - zy * along
        val yz = y0z - zz * along
        val yLength = sqrt(yx * yx + yy * yy + yz * yz)
        // The sine of the angle between hip line and torso: below this the triad has no stable normal.
        if (!(yLength / y0Length >= SegmentRest.TAU_ROT)) {
            out.mark(root, SegmentFrameStatus.INVALID, SegmentFrameReason.ROOT_DEGENERATE)
            return
        }
        val nx = yx / yLength
        val ny = yy / yLength
        val nz = yz / yLength

        val base = root.ordinal * 9
        out.axes[base] = ny * zz - nz * zy
        out.axes[base + 1] = nz * zx - nx * zz
        out.axes[base + 2] = nx * zy - ny * zx
        out.axes[base + 3] = nx
        out.axes[base + 4] = ny
        out.axes[base + 5] = nz
        out.axes[base + 6] = zx
        out.axes[base + 7] = zy
        out.axes[base + 8] = zz
        out.mark(root, SegmentFrameStatus.USABLE, SegmentFrameReason.UNSET)
    }

    /** Writes `D · P` for `D` a rotation of [angle] about the unit axis `(kx, ky, kz)`. */
    private fun rotateFrame(
        parent: SegmentId,
        kx: Float,
        ky: Float,
        kz: Float,
        angle: Float,
        to: SegmentId,
        out: SegmentFrameSet
    ) {
        val src = parent.ordinal * 9
        val dst = to.ordinal * 9
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        for (axis in 0 until 3) {
            val vx = out.axes[src + axis * 3]
            val vy = out.axes[src + axis * 3 + 1]
            val vz = out.axes[src + axis * 3 + 2]
            val dot = vx * kx + vy * ky + vz * kz
            out.axes[dst + axis * 3] = vx * cos + (ky * vz - kz * vy) * sin + kx * dot * (1f - cos)
            out.axes[dst + axis * 3 + 1] = vy * cos + (kz * vx - kx * vz) * sin + ky * dot * (1f - cos)
            out.axes[dst + axis * 3 + 2] = vz * cos + (kx * vy - ky * vx) * sin + kz * dot * (1f - cos)
        }
    }

    /**
     * Writes `D · P` for `D` the half-turn of π about the unit axis `(kx, ky, kz)`.
     *
     * Evaluated in closed form rather than as a rotation by π: `sin(π)` is not zero in `Float`,
     * and the antipodal branch is the one place the placed direction is required to be exact.
     */
    private fun writeHalfTurn(parent: SegmentId, k: Vector3, to: SegmentId, out: SegmentFrameSet) {
        val src = parent.ordinal * 9
        val dst = to.ordinal * 9
        for (axis in 0 until 3) {
            val vx = out.axes[src + axis * 3]
            val vy = out.axes[src + axis * 3 + 1]
            val vz = out.axes[src + axis * 3 + 2]
            val dot = 2f * (vx * k.x + vy * k.y + vz * k.z)
            out.axes[dst + axis * 3] = dot * k.x - vx
            out.axes[dst + axis * 3 + 1] = dot * k.y - vy
            out.axes[dst + axis * 3 + 2] = dot * k.z - vz
        }
    }
}

private fun Vector3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
