package com.monkfitness.app.animation

/**
 * Root-authority boundary assertions: from the end of build until publish, [ConstraintSolver] is
 * the sole subsystem that may translate or rotate the root (pelvis). [SkeletonPipeline.runStages]
 * captures A at window entry (the last lawful authoring write), checks the pelvis is bit-identical
 * after the limb stage, switches the protected reference to the settled root at the solve, and
 * checks again after finalize + publish.
 *
 * The reference switches ONLY at the explicit solve site — never inferred from retained Frame
 * History: that pose shares the reused, per-build-mutated node tree, so history-based inference
 * both false-fires and misses.
 *
 * Debug-only by construction: callers gate on `BuildConfig.DEBUG` and release builds compile the
 * mechanism out. Comparison is EXACT: legitimate movers are known, transforms are otherwise only
 * ever assigned by value, and there is no drift tolerance to grant.
 *
 * The root guard covers the ROOT ONLY (pelvis local position + rotation); child joints
 * legitimately move via limb re-bakes and FK propagation. The settled-contact guard reuses the
 * same idiom over a disjoint set: the world positions of the end-effectors named by the frame's
 * Settlement Result — never the root, never the declaration list.
 */
internal object PhaseBoundaryAsserts {

    /** Immutable, by-value snapshot of the root (pelvis) node's working local transform. */
    class RootTransformSnapshot internal constructor(
        private val x: Float,
        private val y: Float,
        private val z: Float,
        private val axisX: Float,
        private val axisY: Float,
        private val axisZ: Float,
        private val angle: Float,
        /** Boundary label this snapshot was taken at (names the protected window on failure). */
        internal val capturedAt: String
    ) {
        /**
         * Throws [IllegalStateException] if [pose]'s pelvis transform no
         * longer bit-matches this snapshot. An absent hierarchy / pelvis is NOT a violation — the
         * same structural early-outs the solver takes, since no root exists to protect.
         */
        fun assertUnchanged(pose: SkeletonPose, windowDescription: String) {
            val pelvis = findPelvis(pose) ?: return
            val pos = pelvis.localPosition
            val rot = pelvis.localRotation
            check(pos.x == x && pos.y == y && pos.z == z &&
                rot.axis.x == axisX && rot.axis.y == axisY && rot.axis.z == axisZ &&
                rot.angle == angle
            ) {
                "R2 violation: the root (pelvis) transform was mutated during " +
                    "$windowDescription, but R2 reserves root translation/rotation to the " +
                    "ConstraintSolver alone between the end of build (captured at " +
                    "$capturedAt) and publish. Authoring subsystems, engine stages and the " +
                    "Finalizer may not move the root."
            }
        }
    }

    /**
     * Captures the pelvis transform by value, or `null` when there is no hierarchy / no pelvis
     * node to protect. [capturedAt] labels the boundary for the failure message.
     */
    fun captureRoot(pose: SkeletonPose, capturedAt: String): RootTransformSnapshot? {
        val pelvis = findPelvis(pose) ?: return null
        val pos = pelvis.localPosition
        val rot = pelvis.localRotation
        return RootTransformSnapshot(
            pos.x, pos.y, pos.z,
            rot.axis.x, rot.axis.y, rot.axis.z, rot.angle,
            capturedAt
        )
    }

    /**
     * Immutable by-value snapshot of the WORLD positions of a frame's settled contact
     * end-effectors. The Settlement Result is the SOLE reference source: the joint set comes from
     * its `declaredContactJoints`, never reconstructed from the pose's Contact Declarations,
     * retained chains, solver heuristics or Frame History.
     */
    class SettledContactSnapshot internal constructor(
        private val joints: Array<Joint>,
        private val x: FloatArray,
        private val y: FloatArray,
        private val z: FloatArray,
        /** Boundary label this snapshot was taken at (names the protected window on failure). */
        internal val capturedAt: String
    ) {
        /**
         * Throws [IllegalStateException] if any settled contact
         * end-effector's world position no longer bit-matches its post-solve snapshot: once the
         * ConstraintSolver settles an end-effector, no later subsystem — including the Finalizer —
         * may move it until the frame is published. Walks the WHOLE reference set with exact
         * equality; unlike the root snapshot there is no structural early-out, since the carrier
         * arrays always hold the flattened world value.
         */
        fun assertUnchanged(pose: SkeletonPose, windowDescription: String) {
            for (i in joints.indices) {
                val current = pose.getJoint(joints[i])
                check(current.x == x[i] && current.y == y[i] && current.z == z[i]) {
                    "R3 violation: settled contact ${joints[i]} was moved during " +
                        "$windowDescription, but R3's Settled-Contact Guarantee freezes every " +
                        "end-effector settled by the ConstraintSolver (captured at $capturedAt) " +
                        "between the solve and completion of finalization — settled=" +
                        "Vector3(${x[i]}, ${y[i]}, ${z[i]}) " +
                        "current=Vector3(${current.x}, ${current.y}, ${current.z}). Where " +
                        "declared intent would move a settled contact the contact wins: the " +
                        "intent application is skipped for that chain (§5 R3)."
                }
            }
        }
    }

    /**
     * Snapshots the world positions of the settled-contact end-effectors listed by the pose's
     * [SettlementInfo]. Returns `null` when the frame has no Settlement Result — the legitimate
     * "no settle ran" case (contact-less CUSTOM frames skip the solve entirely).
     */
    fun captureSettledContacts(pose: SkeletonPose, capturedAt: String): SettledContactSnapshot? {
        val result = pose.settlementResult ?: return null
        val joints = result.declaredContactJoints.toTypedArray()
        val xs = FloatArray(joints.size)
        val ys = FloatArray(joints.size)
        val zs = FloatArray(joints.size)
        for (i in joints.indices) {
            val world = pose.getJoint(joints[i])
            xs[i] = world.x
            ys[i] = world.y
            zs[i] = world.z
        }
        return SettledContactSnapshot(joints, xs, ys, zs, capturedAt)
    }

    private fun findPelvis(pose: SkeletonPose): SkeletonNode? {
        for (root in pose.roots) {
            val found = findPelvis(root)
            if (found != null) return found
        }
        return null
    }

    private fun findPelvis(node: SkeletonNode): SkeletonNode? {
        if (node.joint == Joint.PELVIS) return node
        for (child in node.children) {
            val found = findPelvis(child)
            if (found != null) return found
        }
        return null
    }
}
