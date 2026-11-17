package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import kotlin.math.PI
import kotlin.math.atan2

/** What one frame's comparison yielded; declaration order is the precedence the report resolves by. */
internal enum class PoleRollStatus {
    POLE_UNAVAILABLE,
    POLE_LEG_DEGENERATE,
    POLE_UNINITIALIZED,
    POLE_TRANSITION,
    POLE_HELD,
    POLE_MEASURED
}

/**
 * Measures the derived frame's roll against the bend plane of `p_grandparent → p_node → p_child`,
 * holding the previous pole while that plane is ill-conditioned.
 */
internal class PoleComparator(private val definition: SkeletonDefinition) {

    private val theta = FloatArray(SegmentId.entries.size) { Float.NaN }
    private val statuses = Array(SegmentId.entries.size) { PoleRollStatus.POLE_UNAVAILABLE }
    private val poles = FloatArray(SegmentId.entries.size * 3)
    private val hasPole = BooleanArray(SegmentId.entries.size)
    private val held = BooleanArray(SegmentId.entries.size)
    private val restLengths = FloatArray(Segment.entries.size) {
        SegmentRest.restLength(Segment.entries[it], definition)
    }

    /** The roll angle between the derived frame and the bend plane, in radians; `NaN` unless measured. */
    fun theta(id: SegmentId): Float = theta[id.ordinal]

    fun status(id: SegmentId): PoleRollStatus = statuses[id.ordinal]

    /** The pole the comparison used, for a report that has to say which reference it was. */
    fun pole(id: SegmentId, out: Vector3): Boolean {
        if (!hasPole[id.ordinal]) return false
        val base = id.ordinal * 3
        out.set(poles[base], poles[base + 1], poles[base + 2])
        return true
    }

    /** Drops every carried pole, so the next frame re-seeds from its own bend. */
    fun reset() {
        theta.fill(Float.NaN)
        statuses.fill(PoleRollStatus.POLE_UNAVAILABLE)
        hasPole.fill(false)
        held.fill(false)
    }

    /** Re-seeds, holds or transitions every instance's pole from one frame's published positions. */
    fun measure(pose: SkeletonPose, frames: SegmentFrameSet) {
        val u = Vector3()
        val w = Vector3()
        val e = Vector3()
        val restDir = Vector3()
        val tLocal = Vector3()
        val tFrame = Vector3()

        for (spec in SegmentSpec.ALL) {
            val id = spec.id
            val chain = CHAINS[id.ordinal]
            if (chain == null) {
                theta[id.ordinal] = Float.NaN
                statuses[id.ordinal] = PoleRollStatus.POLE_UNAVAILABLE
                hasPole[id.ordinal] = false
                held[id.ordinal] = false
                continue
            }

            theta[id.ordinal] = Float.NaN
            val before = pose.getJoint(chain[0])
            val mid = pose.getJoint(chain[1])
            val after = pose.getJoint(chain[2])
            u.set(mid.x - before.x, mid.y - before.y, mid.z - before.z)
            w.set(after.x - mid.x, after.y - mid.y, after.z - mid.z)
            val minLeg = SegmentRest.TAU_LEN * restLengths[id.segment.ordinal]
            val frameValid = frames.status(id) != SegmentFrameStatus.INVALID
            if (!u.isFinite() || !w.isFinite() || !frameValid
                || !(u.mag() > minLeg) || !(w.mag() > minLeg)
            ) {
                // A leg or the frame it is compared against carries no direction: the carried pole is void.
                statuses[id.ordinal] = PoleRollStatus.POLE_LEG_DEGENERATE
                hasPole[id.ordinal] = false
                held[id.ordinal] = false
                continue
            }

            val cross = u.cross(w)
            val sinBend = cross.mag() / (u.mag() * w.mag())
            // A nearly straight chain states no plane, so the previous pole is held rather than a
            // normalised noise vector being taken for one.
            if (sinBend < SIN_BEND_MIN) {
                statuses[id.ordinal] = when {
                    !hasPole[id.ordinal] -> PoleRollStatus.POLE_UNINITIALIZED
                    !held[id.ordinal] -> {
                        held[id.ordinal] = true
                        PoleRollStatus.POLE_TRANSITION
                    }
                    else -> PoleRollStatus.POLE_HELD
                }
                continue
            }

            val base = id.ordinal * 3
            cross.normalize()
            poles[base] = cross.x
            poles[base + 1] = cross.y
            poles[base + 2] = cross.z
            hasPole[id.ordinal] = true
            if (held[id.ordinal]) {
                // The reference changed across the threshold, so this frame re-seeds without a θ.
                held[id.ordinal] = false
                statuses[id.ordinal] = PoleRollStatus.POLE_TRANSITION
                continue
            }

            // The reference is the roll the rest configuration declares; a straight rest chain has
            // no plane, so F3's transported axis is the declared convention for those rows.
            if (!SegmentRest.restPoleInto(id, tLocal)) {
                if (!SegmentRest.restDirInto(id, restDir)) {
                    statuses[id.ordinal] = PoleRollStatus.POLE_LEG_DEGENERATE
                    continue
                }
                SegmentRest.leastAlignedAxis(restDir.x, restDir.y, restDir.z, e)
                restDir.cross(e, tLocal).normalize()
            }
            frames.apply(id, tLocal, tFrame)

            // The roll is measured about the segment's own direction, which the pole is perpendicular to.
            val dir = w.normalizedCopy()
            val sinPart = tFrame.cross(cross).dot(dir)
            val cosPart = tFrame.dot(cross)
            theta[id.ordinal] = foldToLine(atan2(sinPart, cosPart))
            statuses[id.ordinal] = PoleRollStatus.POLE_MEASURED
        }
    }

    companion object {
        /**
         * The sine of the smallest bend the plane is trusted for — sin 10°, a proposed stage-1 value
         * rather than a measured one.
         */
        const val SIN_BEND_MIN = 0.17364818f

        /**
         * `p_grandparent → p_node → p_child` per instance, or `null` where no such triple exists.
         *
         * Six segment types are structurally unmeasurable: the root has no grandparent, `LUMBAR`
         * and `CLAVICLE` sit on their parent, and `SKULL`/`TOES` are terminal.
         */
        internal fun chainOf(id: SegmentId): Array<Joint>? = CHAINS[id.ordinal]

        private val CHAINS: Array<Array<Joint>?> = arrayOf(
            null,                                                      // PELVIS
            null,                                                      // LUMBAR
            arrayOf(Joint.PELVIS, Joint.CHEST, Joint.NECK_END),        // THORAX
            arrayOf(Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS),      // NECK
            null,                                                      // SKULL
            null,                                                      // CLAVICLE_A
            null,                                                      // CLAVICLE_P
            null,                                                      // SCAPULA_A
            null,                                                      // SCAPULA_P
            arrayOf(Joint.SCAPULA_A, Joint.SHOULDER_A, Joint.ELBOW_A), // HUMERUS_A
            arrayOf(Joint.SCAPULA_P, Joint.SHOULDER_P, Joint.ELBOW_P), // HUMERUS_P
            arrayOf(Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A),    // FOREARM_A
            arrayOf(Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P),    // FOREARM_P
            arrayOf(Joint.ELBOW_A, Joint.HAND_A, Joint.FINGERTIPS_A),  // HAND_A
            arrayOf(Joint.ELBOW_P, Joint.HAND_P, Joint.FINGERTIPS_P),  // HAND_P
            arrayOf(Joint.PELVIS, Joint.HIP_F, Joint.KNEE_F),          // FEMUR_F
            arrayOf(Joint.PELVIS, Joint.HIP_B, Joint.KNEE_B),          // FEMUR_B
            arrayOf(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),         // TIBIA_F
            arrayOf(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B),         // TIBIA_B
            arrayOf(Joint.KNEE_F, Joint.ANKLE_F, Joint.TOE_F),         // FOOT_F
            arrayOf(Joint.KNEE_B, Joint.ANKLE_B, Joint.TOE_B),         // FOOT_B
            null,                                                      // TOES_F
            null                                                       // TOES_B
        )
    }
}

private fun Vector3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

/**
 * Folds a signed roll into `[0, π/2]`: a bend plane is a plane, not an oriented disc, so the error
 * between two transverse axes is the angle between their lines and the sign of the normal is a
 * convention the measurement must not carry.
 */
private fun foldToLine(angle: Float): Float {
    val magnitude = kotlin.math.abs(angle)
    val halfPi = (PI / 2.0).toFloat()
    return if (magnitude > halfPi) PI.toFloat() - magnitude else magnitude
}
