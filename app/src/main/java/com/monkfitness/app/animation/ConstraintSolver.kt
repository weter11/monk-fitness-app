package com.monkfitness.app.animation

import kotlin.math.*

/**
 * A fixed support contact registered by [BasePose.bakeIkLimb] / the validation pose base
 * when a limb is baked against a [ContactConstraint]. It captures everything the global
 * constraint solver needs to re-solve that limb after the root (pelvis) has been
 * repositioned, without the pose having to micro-manage the root.
 *
 * `endJoint` is the contact end-effector. The proximal chain is reconstructed from the fixed
 * skeleton topology via [chainForEnd]: the IK root is the joint whose world position seeds the
 * solve (`rootJoint`), the middle joint of the 2-bone chain is `middleJoint`, and the parent
 * frame whose rotation the offsets are authored in is `parentRotationJoint`. The solver now
 * supports any planted body part — ankles, hands, knees (kneeling), elbows/forearms (planks),
 * hips (seated), head (head-stand) and the toe/heel ends of the foot — not just ANKLE_*/HAND_*.
 * For the single-bone contacts (knee/elbow/hip/head/toe/heel) `middleJoint == endJoint` and the
 * re-bake treats the segment as one rigid bone.
 */
class ContactSpec(
    val endJoint: Joint,
    val rootJoint: Joint,
    val parentRotationJoint: Joint,
    val middleJoint: Joint,
    val targetWorld: Vector3,
    val pole: Vector3,
    val length1: Float,
    val length2: Float,
    val constraint: IKConstraint,
    val straight: Boolean,
    val contact: ContactConstraint?
)

/**
 * PR-04 — global contact-constraint / root-repositioning layer.
 *
 * The engine is otherwise purely local (per-limb 2-bone IK + FK): it cannot satisfy
 * "fixed contacts + posture" simultaneously, so inconsistent authored geometry produces
 * penetration / folded limbs / floating contacts instead of redistributing the error
 * upstream. This solver is a deterministic, allocation-free relaxation that runs *between*
 * IK and FK: it gathers the fixed [ContactSpec]s (planted feet/hands) and repositions the
 * skeleton root (pelvis) so every contact is reached exactly, then re-bakes each contact
 * limb from the moved root. A fixed small number of iterations over {root transform, limb
 * IK} keeps it bounded and smooth (no flicker between frames).
 *
 * Issue A fix — posture, not point-reach. The solver is **not** pelvis-translation-only
 * anymore. A contact is treated as a *surface/anchor constraint* to be honored where it is,
 * not a hard 3-D point the root must be dragged to at full extension:
 *  - The root is translated **only** to restore *reachability* (bring an out-of-band target
 *    into the biological band). A reachable contact is left in place and the limb is re-baked
 *    to aim at it — this stops the old bug where a straight limb whose target sat closer than
 *    L1 was forced to `maxReach`, lifting the pelvis into a floating "V" (Middle Split).
 *  - A straight limb whose target falls inside the proximal-bone length is re-baked as a
 *    (non-degenerate) bent limb, so it stays valid and keeps the contact on its surface
 *    instead of collapsing the knee onto the ankle.
 *  - When a reach residual remains (the root alone cannot satisfy asymmetric contacts), the
 *    correction is shared into a **pelvis tilt** (composed with the authored pelvis rotation,
 *    never replacing it) — a second DOF beyond translation. Symmetric stances have zero
 *    imbalance, so correct poses keep their exact authored orientation.
 *
 * Design notes (constitution: ENGINE.md §2/§5 — engine solves motion, poses declare
 * contacts):
 * - It never invents contacts; it only consumes those the pose already registered via the
 *   contact-bearing `bakeIkLimb` calls.
 * - It never moves a non-contact limb's authored shape; it only translates/tilts the root, so
 *   every other limb follows rigidly and stays correct.
 * - It preserves bone lengths (it re-bakes through the existing analytical IK + local-frame
 *   bake, exactly like the pose did).
 */
object ConstraintSolver {

    private const val MAX_ITERATIONS = 16
    private const val RELAX = 0.5f
    private const val EPS = 1e-3f
    private const val DEG2RAD = 3.1415927f / 180f

    // Persistent scratch — no hot-path allocation.
    private val zero = Vector3()
    private val identity = JointRotation()
    private val delta = Vector3()
    private val away = Vector3()
    private val dir = Vector3()
    private val rootWorld = Vector3()
    private val ikResult = SkeletonMath.IKResult()
    private val nodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }

    // Posture-DOF scratch (pelvis tilt relaxation, PR-04 / Issue A). The solver is no longer
    // translation-only: when contacts are reachable it leaves the root where the contact pins it
    // and, when an asymmetric reach residual exists, it shares the correction into a pelvis tilt
    // (composed with the authored pelvis rotation) instead of dumping everything into one
    // vertical translation. For symmetric contacts the imbalance is zero, so the authored pelvis
    // orientation is preserved verbatim (a strict no-op for correct poses).
    private val tiltDelta = JointRotation()
    private val authoredPelvisRot = JointRotation()
    private val pelvisMatX = Vector3(); private val pelvisMatY = Vector3(); private val pelvisMatZ = Vector3()
    private val tiltMatX = Vector3(); private val tiltMatY = Vector3(); private val tiltMatZ = Vector3()
    private val outMatX = Vector3(); private val outMatY = Vector3(); private val outMatZ = Vector3()
    private val imbA = Vector3(); private val imbB = Vector3()
    private const val TILT_GAIN = 0.01f

    /**
     * Maps a contact end-effector to its proximal chain in the fixed skeleton topology.
     * Returns null for joints that are not a supported contact limb (the solver then simply
     * skips registration at bake time).
     *
     * Every contact is expressed as a 2-bone chain `(rootJoint -> middleJoint -> endJoint)`.
     * The original scope only covered the two true 2-bone limbs (legs: HIP->KNEE->ANKLE and
     * arms: SHOULDER->ELBOW->HAND). Issue D extends this to every other planted body part:
     * knees, elbows/forearms, hips, head, and the toe/heel ends of the foot. For those the
     * contact joint is the *end* of a single bone (the thigh, forearm, neck, foot, …), so the
     * chain is modelled as a degenerate 2-bone limb with `middleJoint == endJoint` — the
     * re-bake treats it as a rigid one-bone segment (see [solve]). `parentRotationJoint` is
     * the frame in which that segment's offset is authored (the bone's actual parent, which
     * for an identity-rotation link equals the historically-used pelvis/scapula frame, so the
     * existing legs/arms entries are unchanged in meaning).
     */
    fun chainForEnd(end: Joint): ContactChain? = when (end) {
        // --- Legs: 2-bone limb HIP -> KNEE -> ANKLE (unchanged) ---
        Joint.ANKLE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.ANKLE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)
        // Kneeling: the knee is the end of the thigh (1-bone). middle == end.
        Joint.KNEE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.KNEE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)

        // --- Arms: 2-bone limb SHOULDER -> ELBOW -> HAND (unchanged) ---
        Joint.HAND_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.HAND_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)
        // Forearm / elbow plank: the elbow is the end of the forearm (1-bone). middle == end.
        Joint.ELBOW_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.ELBOW_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)

        // --- Hips: seated / kneeling on the pelvis side. The hip is the end of the (identity)
        //     pelvis->hip offset (1-bone), framed in the pelvis. middle == end. ---
        Joint.HIP_F -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_F)
        Joint.HIP_B -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_B)

        // --- Head: head-stand. The head is the end of the (identity) chest->neck->head chain,
        //     modelled as a 1-bone neck segment framed in the chest. middle == end. ---
        Joint.HEAD_POS -> ContactChain(Joint.CHEST, Joint.CHEST, Joint.HEAD_POS)

        // --- Feet ends: toe / heel stands. The toe/heel is the end of the foot (1-bone,
        //     ANKLE -> TOE/HEEL), framed in the ankle. middle == end. ---
        Joint.TOE_F -> ContactChain(Joint.ANKLE_F, Joint.ANKLE_F, Joint.TOE_F)
        Joint.TOE_B -> ContactChain(Joint.ANKLE_B, Joint.ANKLE_B, Joint.TOE_B)
        Joint.HEEL_F -> ContactChain(Joint.ANKLE_F, Joint.ANKLE_F, Joint.HEEL_F)
        Joint.HEEL_B -> ContactChain(Joint.ANKLE_B, Joint.ANKLE_B, Joint.HEEL_B)

        else -> null
    }

    data class ContactChain(
        val rootJoint: Joint,
        val parentRotationJoint: Joint,
        val middleJoint: Joint
    )

    /**
     * Repositions the root so all registered fixed contacts are honored, then re-bakes each
     * contact limb from the moved root. Mutates the supplied [pose] (its node local
     * transforms and, at the end, runs a fresh FK + flatten). No-ops when there are no
     * contacts, so non-contact poses are untouched.
     */
    fun solve(pose: SkeletonPose, definition: SkeletonDefinition) {
        val contacts = pose.contacts
        if (contacts.isEmpty()) return
        val roots = pose.roots
        if (roots.isEmpty()) return

        for (i in nodeMap.indices) nodeMap[i] = null
        for (root in roots) collectNodes(root)

        val pelvis = nodeMap[Joint.PELVIS.index] ?: return
        // Preserve the authored pelvis orientation so the solver only ever *adds* a posture
        // correction, never wipes a deliberate lean (e.g. Deep Overhead Squat's folded pelvis).
        authoredPelvisRot.copyFrom(pelvis.localRotation)

        // Are any contacts anchored to a horizontal support (ground)? That is what enables the
        // pelvis-tilt posture DOF: feet planted on the floor can be honored by tilting the trunk
        // rather than by lifting the root out of the floor plane.
        var hasGroundContact = false
        for (spec in contacts) {
            val c = spec.contact
            if (c != null && abs(c.normal.y) > 0.5f) { hasGroundContact = true; break }
        }

        for (iter in 0 until MAX_ITERATIONS) {
            // Forward kinematics so world positions reflect the current root placement.
            for (root in roots) root.updateWorldTransforms(zero, identity)

            // 1) Decide how far the root must move to satisfy *reachability* only. A contact is
            //    left exactly where it is when its target is already within the biological band —
            //    the limb is re-baked to AIM AT the contact (honoring it as a surface/anchor)
            //    rather than being forced to full extension. The old code forced straight limbs
            //    whose target sat closer than L1 to `maxReach`, which lifted the root to fake a
            //    straight leg (the Issue A "point-reach, not posture" floating-pelvis bug).
            delta.set(0f, 0f, 0f)
            var moved = false
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                rootWorld.set(parent.worldPosition)
                away.set(rootWorld).subtract(spec.targetWorld)
                val dist = away.mag()

                val maxReach = (spec.length1 + spec.length2) * spec.constraint.effectiveExtensionRatio
                val minCos = cos(spec.constraint.minimumFlexionAngle * DEG2RAD)
                val minReach = sqrt(
                    spec.length1 * spec.length1 + spec.length2 * spec.length2 -
                        2f * spec.length1 * spec.length2 * minCos
                )

                // Posture-aware: only translate to bring an *unreachable* contact into band. A
                // reachable contact is never pushed to full extension.
                val desired = dist.coerceIn(minReach, maxReach)

                val diff = desired - dist
                if (abs(diff) > EPS) {
                    val inv = 1f / max(dist, 1e-5f)
                    dir.set(away.x * inv, away.y * inv, away.z * inv)
                    delta.x += dir.x * diff * RELAX
                    delta.y += dir.y * diff * RELAX
                    delta.z += dir.z * diff * RELAX
                    moved = true
                }
            }

            // Apply the averaged correction (damped Jacobi step). Summing the per-contact
            // corrections and dividing by the contact count yields the mean step, so symmetric
            // contacts don't double-count and N contacts don't overshoot.
            if (moved) {
                delta.divide(contacts.size.toFloat())
                pelvis.localPosition.add(delta)
            }

            // 2) Posture DOF (Issue A): when a reach residual remains (the root alone cannot
            //    satisfy the contacts), share the correction into a pelvis tilt instead of
            //    dumping it all into a single translation. For symmetric ground contacts the
            //    imbalance is zero, so the authored pelvis rotation is preserved (no-op).
            if (moved && hasGroundContact) {
                applyPelvisTilt(pelvis, contacts)
            }

            // 3) Re-bake every contact limb from the (possibly moved/tilted) proximal root to its
            //    target, writing the offsets back into the parent's true local frame. Contacts are
            //    honored as surface/anchor constraints: the end-effector stays on its support
            //    plane, and a straight limb whose target sits inside the proximal-bone length is
            //    re-baked as a (non-degenerate) bent limb — the reachable compromise — instead of
            //    collapsing the knee onto the ankle.
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                val rotNode = nodeMap[spec.parentRotationJoint.index] ?: continue
                val middle = nodeMap[spec.middleJoint.index] ?: continue
                val end = nodeMap[spec.endJoint.index] ?: continue

                rootWorld.set(parent.worldPosition)
                val parentRot = rotNode.worldRotation

                // A straight limb can only be represented when the target is at least one bone
                // (L1) away; inside that radius it would degenerate to a point. Fall back to the
                // triangle IK so the limb stays valid and the contact stays on its surface.
                away.set(rootWorld).subtract(spec.targetWorld)
                val reachMag = away.mag()
                val canBeStraight = spec.straight && reachMag >= spec.length1 - 1e-3f

                if (canBeStraight) {
                    SkeletonMath.solveStraightLimb(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.constraint, ikResult, spec.contact
                    )
                } else {
                    // The parent frame rotation is set by the solver (translation and/or tilt), so
                    // the contact's world-space pole is recomputed each pass and reused directly.
                    SkeletonMath.solveIK(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.pole, spec.constraint, ikResult, spec.contact
                    )
                }

                dir.set(ikResult.joint).subtract(rootWorld)
                SkeletonMath.toLocalDirection(dir, parentRot, middle.localPosition)
                // A 1-bone (degenerate) contact limb has `middle == end` (e.g. a kneeling knee,
                // a planted elbow, a head-stand). Its IK result collapses to a single segment
                // (joint == end == target), so the second offset would be zero and would wipe
                // the bone length. Skip it: the segment is already written above.
                if (middle !== end) {
                    dir.set(ikResult.end).subtract(ikResult.joint)
                    SkeletonMath.toLocalDirection(dir, parentRot, end.localPosition)
                }
            }

            if (!moved) break
        }

        // Final FK + flatten so the finalized pose reflects the solved root placement.
        SkeletonPose.fromHierarchy(roots, pose)
    }

    /**
     * Adds a small pelvis-tilt correction that shares an asymmetric reach residual into a trunk
     * rotation rather than a pure translation. `signedImbalance` is the net horizontal
     * (left/right) offset of the ground contacts relative to their proximal roots; for a symmetric
     * stance it is ~0 and the authored pelvis rotation is left untouched. The correction is
     * composed *with* the authored rotation (never replacing it) and is bounded/deterministic.
     * Allocation-free: reuses the shared matrix scratch.
     */
    private fun applyPelvisTilt(pelvis: SkeletonNode, contacts: List<ContactSpec>) {
        val imb = signedImbalance(contacts, pelvis) ?: return
        if (abs(imb) <= 1e-3f) return

        // Small roll about the world Z axis to balance the contacts, damped so it converges.
        tiltDelta.set(0f, 0f, 1f, imb * TILT_GAIN)

        // pelvis.localRotation = authoredPelvisRot * tiltDelta  (preserves deliberate lean).
        SkeletonMath.rotationToMatrix(authoredPelvisRot, pelvisMatX, pelvisMatY, pelvisMatZ)
        SkeletonMath.rotationToMatrix(tiltDelta, tiltMatX, tiltMatY, tiltMatZ)
        SkeletonMath.multiplyMatrices(pelvisMatX, pelvisMatY, pelvisMatZ, tiltMatX, tiltMatY, tiltMatZ, outMatX, outMatY, outMatZ)
        SkeletonMath.getRotationFromMatrix(outMatX, outMatY, outMatZ, pelvis.localRotation)
    }

    /**
     * Net signed horizontal imbalance of the ground contacts: +1 per contact to the passive side
     * (-Z) and -1 per contact to the active side (+Z), weighted by how far the contact's target is
     * from its proximal root along the body's lateral axis. Returns null when no ground contact is
     * present (so the caller skips the tilt entirely). Symmetric stances sum to ~0.
     */
    private fun signedImbalance(contacts: List<ContactSpec>, pelvis: SkeletonNode): Float? {
        var sum = 0f
        var ground = false
        for (spec in contacts) {
            val c = spec.contact
            if (c == null || abs(c.normal.y) <= 0.5f) continue
            ground = true
            val root = nodeMap[spec.rootJoint.index] ?: continue
            val lateral = spec.targetWorld.z - root.worldPosition.z
            // Passive side (+Z) joints are `_B`/`_P`; active side (-Z) are `_F`/`_A`.
            // Central joints (head) contribute ~0 lateral offset and are harmless either way.
            val name = spec.endJoint.name
            val sign = if (name.endsWith("_B") || name.endsWith("_P")) 1f else -1f
            sum += sign * lateral
        }
        return if (ground) sum else null
    }

    private fun collectNodes(node: SkeletonNode) {
        nodeMap[node.joint.index] = node
        for (child in node.children) collectNodes(child)
    }
}
