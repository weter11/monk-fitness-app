package com.monkfitness.app.animation

import kotlin.math.*

/**
 * A fixed support contact registered by [BasePose.bakeIkLimb] / the validation pose base
 * when a limb is baked against a [ContactConstraint]. It captures everything the global
 * constraint solver needs to re-solve that limb after the root (pelvis) has been
 * repositioned, without the pose having to micro-manage the root.
 *
 * `endJoint` is the contact end-effector (ANKLE_* / HAND_*). The proximal chain is
 * reconstructed from the fixed skeleton topology: the IK root is the joint whose world
 * position seeds the solve (`rootJoint`: HIP_* / SHOULDER_*), and the parent frame whose
 * rotation the offsets are authored in is `parentRotationJoint` (PELVIS / CHEST).
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
 * Design notes (constitution: ENGINE.md §2/§5 — engine solves motion, poses declare
 * contacts):
 * - It never invents contacts; it only consumes those the pose already registered via the
 *   contact-bearing `bakeIkLimb` calls.
 * - It never moves a non-contact limb's authored shape; it only translates the root, so
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

    /**
     * Maps a contact end-effector to its proximal chain in the fixed skeleton topology.
     * Returns null for joints that are not a supported contact limb (the solver then simply
     * skips registration at bake time).
     */
    fun chainForEnd(end: Joint): ContactChain? = when (end) {
        Joint.ANKLE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.ANKLE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)
        Joint.HAND_A -> ContactChain(Joint.SHOULDER_A, Joint.CHEST, Joint.ELBOW_A)
        Joint.HAND_P -> ContactChain(Joint.SHOULDER_P, Joint.CHEST, Joint.ELBOW_P)
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

        for (iter in 0 until MAX_ITERATIONS) {
            // Forward kinematics so world positions reflect the current root placement.
            for (root in roots) root.updateWorldTransforms(zero, identity)

            // 1) Decide how far the root must move so each contact reaches its target within
            //    the biological band. For a straight limb whose target sits closer than L1
            //    (degenerate collinear solve) the root is pushed out to full extension.
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

                val desired = if (spec.straight && dist < spec.length1) maxReach
                else dist.coerceIn(minReach, maxReach)

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

            // 2) Re-bake every contact limb from the (possibly moved) proximal root to its
            //    target, writing the offsets back into the parent's true local frame.
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                val rotNode = nodeMap[spec.parentRotationJoint.index] ?: continue
                val middle = nodeMap[spec.middleJoint.index] ?: continue
                val end = nodeMap[spec.endJoint.index] ?: continue

                rootWorld.set(parent.worldPosition)
                val parentRot = rotNode.worldRotation

                if (spec.straight) {
                    SkeletonMath.solveStraightLimb(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.constraint, ikResult, spec.contact
                    )
                } else {
                    // The parent frame rotation is never moved by the solver (only the pelvis
                    // translation is), so the contact's world-space pole is constant and can be
                    // reused directly for every re-bake.
                    SkeletonMath.solveIK(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.pole, spec.constraint, ikResult, spec.contact
                    )
                }

                dir.set(ikResult.joint).subtract(rootWorld)
                SkeletonMath.toLocalDirection(dir, parentRot, middle.localPosition)
                dir.set(ikResult.end).subtract(ikResult.joint)
                SkeletonMath.toLocalDirection(dir, parentRot, end.localPosition)
            }

            if (!moved) break
        }

        // Final FK + flatten so the finalized pose reflects the solved root placement.
        SkeletonPose.fromHierarchy(roots, pose)
    }

    private fun collectNodes(node: SkeletonNode) {
        nodeMap[node.joint.index] = node
        for (child in node.children) collectNodes(child)
    }
}
