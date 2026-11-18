package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import kotlin.math.abs

/**
 * The rest data every segment frame is derived from: the bind frame, each row's rest direction and
 * rest length, and the thresholds that decide a frame's branches.
 */
object SegmentRest {

    /** Length degeneracy is relative to the segment's own rest length, so metres and units agree. */
    const val TAU_LEN = 1e-6f

    /** Angular threshold that decides the parallel, antiparallel and collinear branches, in radians. */
    const val TAU_ROT = 1e-3f

    /**
     * The bind frame's columns: `diag(-1, 1, -1)`, the half-turn about world `+Y` that maps the world
     * axes onto the rest axes, so `R = BIND_FRAME` holds identically at the bind pose.
     */
    val BIND_X = floatArrayOf(-1f, 0f, 0f)
    val BIND_Y = floatArrayOf(0f, 1f, 0f)
    val BIND_Z = floatArrayOf(0f, 0f, -1f)

    /** `BIND_FRAME` as a 9-slot axis-major matrix, for a caller that compares whole frames. */
    val BIND_FRAME: FloatArray = BIND_X + BIND_Y + BIND_Z

    /**
     * `restDir(S) = BIND_FRAMEᵀ · normalize(p_child(X1) − p_parent(X1))`, or `null` for a row that
     * inherits its parent's frame — those have no direction of their own to express.
     */
    private val restDirs: Array<FloatArray?> = arrayOf(
        floatArrayOf(0f, 1f, 0f),    // PELVIS: PELVIS -> CHEST is world +Y
        floatArrayOf(0f, 1f, 0f),    // LUMBAR: LUMBAR -> CHEST
        floatArrayOf(0f, 1f, 0f),    // THORAX: CHEST -> NECK_END
        floatArrayOf(0f, 1f, 0f),    // NECK: NECK_END -> HEAD_POS
        null,                        // SKULL: terminal, inherits the neck
        null,                        // CLAVICLE_A: its pair is coincident, inherits the thorax
        null,                        // CLAVICLE_P
        floatArrayOf(0f, 0f, 1f),    // SCAPULA_A: SCAPULA_A -> SHOULDER_A is world -Z
        floatArrayOf(0f, 0f, -1f),   // SCAPULA_P
        floatArrayOf(0f, -1f, 0f),   // HUMERUS_A: the arm points down the world -Y in the A-pose
        floatArrayOf(0f, -1f, 0f),   // HUMERUS_P
        floatArrayOf(0f, -1f, 0f),   // FOREARM_A
        floatArrayOf(0f, -1f, 0f),   // FOREARM_P
        floatArrayOf(0f, -1f, 0f),   // HAND_A: HAND_A -> FINGERTIPS_A
        floatArrayOf(0f, -1f, 0f),   // HAND_P
        floatArrayOf(0f, -1f, 0f),   // FEMUR_F: the leg points down the world -Y
        floatArrayOf(0f, -1f, 0f),   // FEMUR_B
        floatArrayOf(0f, -1f, 0f),   // TIBIA_F
        floatArrayOf(0f, -1f, 0f),   // TIBIA_B
        floatArrayOf(-1f, 0f, 0f),   // FOOT_F: ANKLE_F -> TOE_F is world +X
        floatArrayOf(-1f, 0f, 0f),   // FOOT_B
        null,                        // TOES_F: terminal, inherits the foot
        null                         // TOES_B
    )

    /** A fresh copy of the segment's rest direction, or `null` when the row inherits its frame. */
    fun restDir(id: SegmentId): Vector3? {
        val v = restDirs[id.ordinal] ?: return null
        return Vector3(v[0], v[1], v[2])
    }

    /**
     * The bind chain's bend-plane normal in the segment's rest frame — the roll the rest pose
     * declares. `null` where that chain is collinear: a straight limb states no plane.
     */
    private val restPoles: Array<FloatArray?> = arrayOf(
        null,                        // PELVIS
        null,                        // LUMBAR
        null,                        // THORAX: the bind spine is straight
        null,                        // NECK
        null,                        // SKULL
        null,                        // CLAVICLE_A
        null,                        // CLAVICLE_P
        null,                        // SCAPULA_A
        null,                        // SCAPULA_P
        floatArrayOf(1f, 0f, 0f),    // HUMERUS_A: BIND · (−X)
        floatArrayOf(-1f, 0f, 0f),   // HUMERUS_P: BIND · (+X)
        null,                        // FOREARM_A: the bind elbow is straight
        null,                        // FOREARM_P
        null,                        // HAND_A
        null,                        // HAND_P
        floatArrayOf(1f, 0f, 0f),    // FEMUR_F: BIND · (−X)
        floatArrayOf(-1f, 0f, 0f),   // FEMUR_B: BIND · (+X)
        null,                        // TIBIA_F: the bind knee is straight
        null,                        // TIBIA_B
        floatArrayOf(0f, 0f, -1f),   // FOOT_F: BIND · (+Z)
        floatArrayOf(0f, 0f, -1f),   // FOOT_B
        null,                        // TOES_F
        null                         // TOES_B
    )

    /** The rest-plane roll reference, or `null` when the bind chain states no plane. */
    fun restPole(id: SegmentId): Vector3? {
        val v = restPoles[id.ordinal] ?: return null
        return Vector3(v[0], v[1], v[2])
    }

    /** Writes the rest-plane reference into [out]; false when the row has none. */
    internal fun restPoleInto(id: SegmentId, out: Vector3): Boolean {
        val v = restPoles[id.ordinal] ?: return false
        out.set(v[0], v[1], v[2])
        return true
    }

    /** Writes the rest direction into [out]; false when the row has none of its own. */
    internal fun restDirInto(id: SegmentId, out: Vector3): Boolean {
        val v = restDirs[id.ordinal] ?: return false
        out.set(v[0], v[1], v[2])
        return true
    }

    /**
     * The length of the segment's own position pair at the bind pose, in engine units.
     *
     * The thorax row's pair is `CHEST → NECK_END`, which the definition calls [SkeletonDefinition.neckLength];
     * the pelvis and lumbar rows both span the torso. Zero is not a fallback: those rows have no pair.
     */
    fun restLength(segment: Segment, definition: SkeletonDefinition): Float = when (segment) {
        Segment.PELVIS, Segment.LUMBAR -> definition.torsoLength
        Segment.THORAX, Segment.NECK -> definition.neckLength
        Segment.SCAPULA -> definition.shoulderWidth
        Segment.HUMERUS -> definition.upperArmLength
        Segment.FOREARM -> definition.forearmLength
        Segment.HAND -> definition.hand.palmLength + definition.hand.fingerLength
        Segment.FEMUR -> definition.thighLength
        Segment.TIBIA -> definition.shinLength
        Segment.FOOT -> definition.foot.footLength * definition.foot.toeRatio
        Segment.SKULL, Segment.CLAVICLE, Segment.TOES -> 0f
    }

    /** The root frame's hip-line span: `HIP_F → HIP_B` is two hip widths across, never a torso. */
    fun hipLineRestLength(definition: SkeletonDefinition): Float = 2f * definition.hipWidth

    /**
     * Refuses a definition the root frame cannot be built from. The hip line and the torso are the
     * root's two directions, so a zero span is a definition error rather than a posed degeneracy.
     */
    fun validate(definition: SkeletonDefinition) {
        require(definition.hipWidth > 0f) {
            "hipWidth must be positive: the pelvis frame's lateral direction is the hip line (was ${definition.hipWidth})"
        }
        require(definition.torsoLength > 0f) {
            "torsoLength must be positive: the pelvis frame's vertical direction is PELVIS -> CHEST (was ${definition.torsoLength})"
        }
    }

    /**
     * The world axis least aligned with `(x, y, z)`, ties resolved `+X → +Y → +Z`.
     *
     * Used where a direction needs a second vector perpendicular to it by construction: taking the
     * *nearest* axis instead leaves a non-zero component along the direction and a half-turn about
     * it misses the target.
     */
    fun leastAlignedAxis(x: Float, y: Float, z: Float, out: Vector3): Vector3 {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            ax <= ay && ax <= az -> out.set(1f, 0f, 0f)
            ay <= az -> out.set(0f, 1f, 0f)
            else -> out.set(0f, 0f, 1f)
        }
    }
}
