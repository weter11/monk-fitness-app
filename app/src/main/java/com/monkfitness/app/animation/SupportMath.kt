package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Biomechanical support computation: support centroid, body lever length and the LeverModel, with
 * no exercise-specific branching.
 */
object SupportMath {

    /**
     * The canonical `SupportPoint` -> `Joint` mapping: the single production definition of what each
     * support point means. Every consumer resolves the side from here; no second map of this
     * relation exists (guarded by `SupportPointMappingAuthorityTest`).
     *
     * A = the active/foreground limb = the model's LEFT arm; P = passive/background = its RIGHT arm;
     * F = foreground = LEFT leg; B = background = RIGHT leg. A and F are one physical side of the
     * authored body (both at -Z, the near limb of the single-plane silhouette), P and B the other
     * (+Z, the far limb), so `LEFT_*` is the A/F family and `RIGHT_*` the P/B family — never a
     * cross-family pair.
     *
     * The **first** entry of each list is the anchor joint, the single representative the
     * centroid/lever math uses ([anchorJointFor]); the rest are the derived contact-layer joints that
     * touch the surface (heel/toe of an ankle, the palm chain of a hand). `*_FOOT` anchors the ankle
     * (whole foot planted) while `*_TOES` anchors the toe end, but the two name the SAME
     * `{ankle, heel, toe}` support, exactly as `*_HAND` and `*_FOREARM` name the same forearm support.
     *
     * `HIPS`/`BACK`/`PELVIS` are the only side-independent entries — the pelvis region is not a
     * paired limb. `CUSTOM` maps to no joint at all: an opaque point the caller owns, so the engine
     * never invents a joint for it.
     */
    private val jointsBySupportPoint: Map<SupportPoint, List<Joint>> = mapOf(
        SupportPoint.LEFT_FOOT to listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
        SupportPoint.RIGHT_FOOT to listOf(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B),
        SupportPoint.LEFT_TOES to listOf(Joint.TOE_F, Joint.ANKLE_F, Joint.HEEL_F),
        SupportPoint.RIGHT_TOES to listOf(Joint.TOE_B, Joint.ANKLE_B, Joint.HEEL_B),
        SupportPoint.LEFT_KNEE to listOf(Joint.KNEE_F),
        SupportPoint.RIGHT_KNEE to listOf(Joint.KNEE_B),
        SupportPoint.LEFT_HAND to listOf(Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A),
        SupportPoint.RIGHT_HAND to listOf(Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P),
        SupportPoint.LEFT_ELBOW to listOf(Joint.ELBOW_A),
        SupportPoint.RIGHT_ELBOW to listOf(Joint.ELBOW_P),
        SupportPoint.LEFT_FOREARM to listOf(Joint.ELBOW_A, Joint.HAND_A),
        SupportPoint.RIGHT_FOREARM to listOf(Joint.ELBOW_P, Joint.HAND_P),
        SupportPoint.PELVIS to listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
        SupportPoint.HIPS to listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
        SupportPoint.BACK to listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
        SupportPoint.CUSTOM to emptyList()
    )

    /**
     * The inverse index: every support point whose canonical family contains [joint], ordered by
     * declaration-family precedence (`*_FOOT` before `*_TOES`, `*_HAND` before `*_FOREARM`). This
     * makes family resolution a consequence of the canonical map instead of a second hand-written
     * joint->side pair that could disagree with it. Precomputed, so per-frame lookups allocate
     * nothing.
     */
    private val supportPointsByJoint: Map<Joint, List<SupportPoint>> =
        Joint.entries.associateWith { joint ->
            SupportPoint.entries.filter { jointsFor(it).contains(joint) }.sortedBy { it.ordinal }
        }.filterValues { it.isNotEmpty() }

    /** The joints of [point]'s OWN limb family; empty for a point with no canonical joint. */
    fun jointsFor(point: SupportPoint): List<Joint> = jointsBySupportPoint[point] ?: emptyList()

    /**
     * The joint representing [point] in centroid/lever math — the ankle of a whole-foot support, the
     * toe of a `*_TOES` support, the elbow of a forearm support, the pelvis of a core support — or
     * `null` when the point has no canonical joint (`CUSTOM`).
     */
    fun anchorJointFor(point: SupportPoint): Joint? = jointsFor(point).firstOrNull()

    /**
     * The support points whose canonical family contains [joint], in declaration-family precedence —
     * e.g. `ANKLE_F -> [LEFT_FOOT, LEFT_TOES]`, `HAND_A -> [LEFT_HAND, LEFT_FOREARM]`. Empty when no
     * support point is defined over [joint].
     */
    fun supportPointsFor(joint: Joint): List<SupportPoint> = supportPointsByJoint[joint] ?: emptyList()

    /**
     * The effective body lever length for a pivot: FEET = shin + thigh + torso, KNEES = thigh +
     * torso, HANDS = torso, CUSTOM = [customLength], anything else 0.
     */
    fun computeLeverLength(pivot: PivotType, definition: SkeletonDefinition, customLength: Float = 0f): Float {
        return when (pivot) {
            PivotType.FEET -> definition.shinLength + definition.thighLength + definition.torsoLength
            PivotType.KNEES -> definition.thighLength + definition.torsoLength
            PivotType.HANDS -> definition.torsoLength
            PivotType.CUSTOM -> customLength
            else -> 0f
        }
    }

    /**
     * The support centroid: the average position of the active contacts' anchor joints. Resolving
     * each through the canonical map means the centroid can never be built from the opposite side's
     * joints; a contact with no canonical joint (`CUSTOM`) contributes nothing.
     *
     * Falls back to [fallback] when there are no contacts or none of them yields a joint.
     */
    fun computeSupportCentroid(
        pose: SkeletonPose,
        contacts: Set<SupportContact>,
        fallback: Vector3 = Vector3(0f, 0f, 0f)
    ): Vector3 {
        if (contacts.isEmpty()) {
            return fallback.copy()
        }
        val sum = Vector3(0f, 0f, 0f)
        var count = 0
        for (contact in contacts) {
            val anchor = anchorJointFor(contact.point) ?: continue
            sum.add(pose.getJoint(anchor))
            count++
        }
        if (count > 0) {
            sum.divide(count.toFloat())
        } else {
            sum.set(fallback)
        }
        return sum
    }

    /**
     * The pivot position in world coordinates: the two limbs' midpoint for the paired pivots.
     *
     * The local names are the canonical joint names on purpose — naming a `*_B`/`*_P` joint "left"
     * (or a `*_F`/`*_A` one "right") is an inversion the symmetric average hides but the name teaches.
     */
    fun getPivotPosition(pose: SkeletonPose, pivot: PivotType): Vector3 {
        return when (pivot) {
            PivotType.FEET -> {
                val f = pose.getJoint(Joint.ANKLE_F)
                val b = pose.getJoint(Joint.ANKLE_B)
                Vector3((f.x + b.x) / 2f, (f.y + b.y) / 2f, (f.z + b.z) / 2f)
            }
            PivotType.KNEES -> {
                val f = pose.getJoint(Joint.KNEE_F)
                val b = pose.getJoint(Joint.KNEE_B)
                Vector3((f.x + b.x) / 2f, (f.y + b.y) / 2f, (f.z + b.z) / 2f)
            }
            PivotType.HANDS -> {
                val a = pose.getJoint(Joint.HAND_A)
                val p = pose.getJoint(Joint.HAND_P)
                Vector3((a.x + p.x) / 2f, (a.y + p.y) / 2f, (a.z + p.z) / 2f)
            }
            PivotType.HIPS, PivotType.PELVIS -> {
                pose.getJoint(Joint.PELVIS).copy()
            }
            PivotType.ELBOWS -> {
                val a = pose.getJoint(Joint.ELBOW_A)
                val p = pose.getJoint(Joint.ELBOW_P)
                Vector3((a.x + p.x) / 2f, (a.y + p.y) / 2f, (a.z + p.z) / 2f)
            }
            PivotType.CUSTOM -> {
                pose.getJoint(Joint.PELVIS).copy()
            }
        }
    }

    fun computeLeverModel(
        pose: SkeletonPose,
        definition: SkeletonDefinition,
        support: SupportDefinition,
        customLeverLength: Float = 0f
    ): LeverModel {
        val pivotPos = getPivotPosition(pose, support.pivot)
        pivotPos.add(Vector3(support.offsetX, support.offsetY, support.offsetZ))
        val len = computeLeverLength(support.pivot, definition, customLeverLength)
        return LeverModel(len, pivotPos)
    }

    fun resolveAnchor(environment: EnvironmentDefinition, anchorId: String): EnvironmentAnchor? {
        val anchors = environment.anchors
        for (i in 0 until anchors.size) {
            val anchor = anchors[i]
            if (anchor.id == anchorId) {
                return anchor
            }
        }
        return null
    }

    fun resolveAnchorPosition(environment: EnvironmentDefinition, anchorId: String, fallback: Vector3): Vector3 {
        val anchor = resolveAnchor(environment, anchorId)
        return anchor?.worldPosition ?: fallback
    }

    fun resolveAnchorByType(environment: EnvironmentDefinition, type: EnvironmentAnchorType): EnvironmentAnchor? {
        val anchors = environment.anchors
        for (i in 0 until anchors.size) {
            val anchor = anchors[i]
            if (anchor.type == type) {
                return anchor
            }
        }
        return null
    }
}
