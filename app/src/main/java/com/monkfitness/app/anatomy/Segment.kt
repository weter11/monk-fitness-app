package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint

/** The fourteen canonical segment frames — one per anatomical row, never a free-form name. */
enum class Segment {
    PELVIS, LUMBAR, THORAX, NECK, SKULL,
    CLAVICLE, SCAPULA, HUMERUS, FOREARM, HAND,
    FEMUR, TIBIA, FOOT, TOES
}

/**
 * One per-instance segment frame key: the fourteen rows split where the row is bilateral.
 *
 * The suffix is the anatomy side (`_A`/`_P` arms, `_F`/`_B` legs), not [com.monkfitness.app.animation.Side]
 * — that names the side a *pose* advances. Declaration order is topological, so one pass suffices.
 */
enum class SegmentId(val segment: Segment) {
    PELVIS(Segment.PELVIS),
    LUMBAR(Segment.LUMBAR),
    THORAX(Segment.THORAX),
    NECK(Segment.NECK),
    SKULL(Segment.SKULL),
    CLAVICLE_A(Segment.CLAVICLE),
    CLAVICLE_P(Segment.CLAVICLE),
    SCAPULA_A(Segment.SCAPULA),
    SCAPULA_P(Segment.SCAPULA),
    HUMERUS_A(Segment.HUMERUS),
    HUMERUS_P(Segment.HUMERUS),
    FOREARM_A(Segment.FOREARM),
    FOREARM_P(Segment.FOREARM),
    HAND_A(Segment.HAND),
    HAND_P(Segment.HAND),
    FEMUR_F(Segment.FEMUR),
    FEMUR_B(Segment.FEMUR),
    TIBIA_F(Segment.TIBIA),
    TIBIA_B(Segment.TIBIA),
    FOOT_F(Segment.FOOT),
    FOOT_B(Segment.FOOT),
    TOES_F(Segment.TOES),
    TOES_B(Segment.TOES);

    /** `_A`, `_P`, `_F`, `_B` for a bilateral row; `null` for a central one. */
    val suffix: String? get() = name.removePrefix(segment.name).ifEmpty { null }

    companion object {
        fun of(segment: Segment): List<SegmentId> = entries.filter { it.segment == segment }

        /** The opposite-side instance of a bilateral row, or `null` for a central one. */
        fun mirror(id: SegmentId): SegmentId? = when (id.suffix) {
            "_A" -> valueOf(id.name.dropLast(1) + "P")
            "_P" -> valueOf(id.name.dropLast(1) + "A")
            "_F" -> valueOf(id.name.dropLast(1) + "B")
            "_B" -> valueOf(id.name.dropLast(1) + "F")
            else -> null
        }
    }
}

/**
 * How far a derived frame can be trusted.
 *
 * [USABLE] means the frame is fully determined by published positions; [TWIST_UNAUTHORED] means its
 * aim is derived but the roll about the segment direction is the carried one, so a twist has not
 * been authored; [INVALID] means no frame exists this frame.
 */
enum class SegmentFrameStatus {
    USABLE,
    TWIST_UNAUTHORED,
    INVALID
}

/**
 * The construction path a frame took, reported beside its status.
 *
 * [COINCIDENT] covers every row with no separable direction — a zero-length position pair or a
 * terminal joint with no child — because those rows inherit the parent frame for the same reason.
 */
enum class SegmentFrameReason {
    COINCIDENT,
    NEAR_PARALLEL,
    NEAR_ANTIPODAL,
    ROOT_DEGENERATE,
    NON_FINITE_GEOMETRY,
    ZERO_REST_DIRECTION,
    UNSET
}

/**
 * The topology of one segment frame: the published position pair that carries its direction and the
 * segment frame that carries it when that pair has none.
 *
 * [derives] is false for rows that can never derive a direction — the terminal rows (no child joint)
 * and the clavicle (its pair is coincident in the definition) — so the derivation does not have to
 * rediscover a structural fact from a posed length.
 */
data class SegmentSpec(
    val id: SegmentId,
    val parent: SegmentId?,
    val node: Joint?,
    val child: Joint?,
    val derives: Boolean
) {
    val segment: Segment get() = id.segment

    /** Whether the segment frame is the parent's frame rather than its own. */
    val inherits: Boolean get() = !derives

    companion object {
        /** Every instance in topological order: [SegmentId.entries] is declared to match. */
        val ALL: List<SegmentSpec> = listOf(
            SegmentSpec(SegmentId.PELVIS, null, Joint.PELVIS, Joint.CHEST, derives = true),
            SegmentSpec(SegmentId.LUMBAR, SegmentId.PELVIS, Joint.LUMBAR, Joint.CHEST, derives = true),
            SegmentSpec(SegmentId.THORAX, SegmentId.LUMBAR, Joint.CHEST, Joint.NECK_END, derives = true),
            SegmentSpec(SegmentId.NECK, SegmentId.THORAX, Joint.NECK_END, Joint.HEAD_POS, derives = true),
            SegmentSpec(SegmentId.SKULL, SegmentId.NECK, null, null, derives = false),
            SegmentSpec(SegmentId.CLAVICLE_A, SegmentId.THORAX, Joint.CLAVICLE_A, Joint.SCAPULA_A, derives = false),
            SegmentSpec(SegmentId.CLAVICLE_P, SegmentId.THORAX, Joint.CLAVICLE_P, Joint.SCAPULA_P, derives = false),
            SegmentSpec(SegmentId.SCAPULA_A, SegmentId.CLAVICLE_A, Joint.SCAPULA_A, Joint.SHOULDER_A, derives = true),
            SegmentSpec(SegmentId.SCAPULA_P, SegmentId.CLAVICLE_P, Joint.SCAPULA_P, Joint.SHOULDER_P, derives = true),
            SegmentSpec(SegmentId.HUMERUS_A, SegmentId.SCAPULA_A, Joint.SHOULDER_A, Joint.ELBOW_A, derives = true),
            SegmentSpec(SegmentId.HUMERUS_P, SegmentId.SCAPULA_P, Joint.SHOULDER_P, Joint.ELBOW_P, derives = true),
            SegmentSpec(SegmentId.FOREARM_A, SegmentId.HUMERUS_A, Joint.ELBOW_A, Joint.HAND_A, derives = true),
            SegmentSpec(SegmentId.FOREARM_P, SegmentId.HUMERUS_P, Joint.ELBOW_P, Joint.HAND_P, derives = true),
            SegmentSpec(SegmentId.HAND_A, SegmentId.FOREARM_A, Joint.HAND_A, Joint.FINGERTIPS_A, derives = true),
            SegmentSpec(SegmentId.HAND_P, SegmentId.FOREARM_P, Joint.HAND_P, Joint.FINGERTIPS_P, derives = true),
            SegmentSpec(SegmentId.FEMUR_F, SegmentId.PELVIS, Joint.HIP_F, Joint.KNEE_F, derives = true),
            SegmentSpec(SegmentId.FEMUR_B, SegmentId.PELVIS, Joint.HIP_B, Joint.KNEE_B, derives = true),
            SegmentSpec(SegmentId.TIBIA_F, SegmentId.FEMUR_F, Joint.KNEE_F, Joint.ANKLE_F, derives = true),
            SegmentSpec(SegmentId.TIBIA_B, SegmentId.FEMUR_B, Joint.KNEE_B, Joint.ANKLE_B, derives = true),
            SegmentSpec(SegmentId.FOOT_F, SegmentId.TIBIA_F, Joint.ANKLE_F, Joint.TOE_F, derives = true),
            SegmentSpec(SegmentId.FOOT_B, SegmentId.TIBIA_B, Joint.ANKLE_B, Joint.TOE_B, derives = true),
            SegmentSpec(SegmentId.TOES_F, SegmentId.FOOT_F, null, null, derives = false),
            SegmentSpec(SegmentId.TOES_B, SegmentId.FOOT_B, null, null, derives = false)
        )

        private val byId: Map<SegmentId, SegmentSpec> = ALL.associateBy { it.id }

        fun of(id: SegmentId): SegmentSpec = byId.getValue(id)
    }
}
