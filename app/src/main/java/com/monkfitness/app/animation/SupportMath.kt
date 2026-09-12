package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SupportMath is a generic biomechanical support computation engine.
 * Exposes methods to calculate effective support centroid, body lever length,
 * and the LeverModel without exercise-specific branching.
 */
object SupportMath {

    /**
     * B-4 — **THE canonical `SupportPoint` ↔ `Joint` mapping**: the single production definition of
     * what each support point means. Every consumer resolves the side from here; no second map of
     * this relation exists anywhere in `src/main` (guarded by `SupportPointMappingAuthorityTest`).
     *
     * The convention is `docs/ENGINE.md` §4 ("Joint naming: A/P and F/B", an ACTIVE architecture
     * document): **A = the active/foreground limb = the model's LEFT arm · P = the passive/
     * background limb = the model's RIGHT arm · F = foreground = the model's LEFT leg · B =
     * background = the model's RIGHT leg.** `A` and `F` are one physical side of the authored body
     * (both authored at −Z by `BasePose.buildShoulders` / `buildPelvis` — the near limb of the
     * single-plane silhouette) and `P`/`B` are the other (+Z, the far limb), so **`LEFT_*` is the
     * A/F limb family and `RIGHT_*` is the P/B family** — never a cross-family pair. (Corroborated
     * by `BirdDogPose` ("right arm (P) + left leg (F)" ↔ "left arm (A) + right leg (B)"),
     * `PikePushUpPose` ("Right-Side (Side B)"), `WidePushUpPose` ("LEFT_HAND (HAND_A)"),
     * `ARCHITECTURAL_AUDIT_SKELETON_MODEL.md` and the joint→chain map in `ConstraintSolver`.)
     *
     * The **first** entry of each list is the support's *anchor joint* — the single representative
     * used by the centroid/lever math ([anchorJointFor]) — and the remaining entries are the derived
     * contact-layer joints that touch the surface (heel/toe of an ankle, the palm chain of a hand).
     * `*_FOOT` anchors the ankle (whole foot planted) while `*_TOES` anchors the toe end (how the
     * plank/push-up families declare their planted foot): the two name the SAME `{ankle, heel, toe}`
     * support, exactly as `*_HAND` and `*_FOREARM` name the same forearm support for the arm.
     *
     * The CORE points (`HIPS`/`BACK`/`PELVIS`) are the only side-independent entries — the pelvis
     * region is not a paired limb. `CUSTOM` maps to no joint at all: an opaque point the caller
     * owns, so the engine never invents a joint for it.
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
     * B-4 — the canonical **inverse index**: every support point whose canonical joint family
     * contains [joint], ordered by the declaration-family precedence the extremity derivation uses
     * (the `SupportPoint` enumeration order: `*_FOOT` before `*_TOES`, `*_HAND` before `*_FOREARM`).
     *
     * This is what makes the "declaration family" resolution (B-3) a *consequence* of the canonical
     * map instead of a second, hand-written joint→side pair: a foot's family can no longer disagree
     * with the joints the same map names for those support points. Precomputed once, so lookups in
     * the per-frame derivation paths allocate nothing.
     */
    private val supportPointsByJoint: Map<Joint, List<SupportPoint>> =
        Joint.entries.associateWith { joint ->
            SupportPoint.entries.filter { jointsFor(it).contains(joint) }.sortedBy { it.ordinal }
        }.filterValues { it.isNotEmpty() }

    /** The joints of [point]'s OWN limb family; empty for a point with no canonical joint. */
    fun jointsFor(point: SupportPoint): List<Joint> = jointsBySupportPoint[point] ?: emptyList()

    /**
     * The anchor joint of [point] — the single joint that represents the support in centroid/lever
     * math (the ankle of a whole-foot support, the toe of a `*_TOES` support, the elbow of a
     * forearm support, the pelvis of a core support) — or `null` when the point has no canonical
     * joint (`CUSTOM`).
     */
    fun anchorJointFor(point: SupportPoint): Joint? = jointsFor(point).firstOrNull()

    /**
     * The support points whose canonical family contains [joint], in declaration-family precedence
     * — e.g. `ANKLE_F → [LEFT_FOOT, LEFT_TOES]` (the whole-foot kind first), `HAND_A →
     * [LEFT_HAND, LEFT_FOREARM]`. Empty when no support point is defined over [joint].
     */
    fun supportPointsFor(joint: Joint): List<SupportPoint> = supportPointsByJoint[joint] ?: emptyList()

    /**
     * Determines the automatic/effective body lever length based on the PivotType.
     * - FEET: shin + thigh + torso
     * - KNEES: thigh + torso
     * - HANDS: torso
     * - Others / CUSTOM: custom or fallback (e.g., 0f or user-defined)
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
     * Computes the support centroid (the average position of all active support contacts).
     *
     * B-4 — each contact contributes its canonical ANCHOR joint ([anchorJointFor]), resolved from
     * the single canonical map, so the centroid can never be built from the opposite side's joints
     * (a whole foot is represented by its ankle, a `*_TOES` support by its toe, a forearm support by
     * its elbow). A contact with no canonical joint (`CUSTOM`) contributes nothing.
     *
     * If the set of contacts is empty (or contributes no joint), falls back to the pivot position or
     * zero.
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
     * Determines the pivot position in world coordinates from the pose based on PivotType.
     *
     * The local names are the CANONICAL joint names on purpose: the pivot is the two limbs'
     * midpoint, and naming a `*_B`/`*_P` joint "left" (or a `*_F`/`*_A` one "right") is the exact
     * inversion B-4 removed (a symmetric average hid it, but the name taught it).
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

    /**
     * Computes the full LeverModel.
     */
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

    /**
     * Resolves an anchor by its ID from the environment definition.
     */
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

    /**
     * Resolves the world position of an anchor by its ID, with a fallback Vector3.
     */
    fun resolveAnchorPosition(environment: EnvironmentDefinition, anchorId: String, fallback: Vector3): Vector3 {
        val anchor = resolveAnchor(environment, anchorId)
        return anchor?.worldPosition ?: fallback
    }

    /**
     * Resolves an anchor by its type from the environment definition.
     */
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
