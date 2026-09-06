package com.monkfitness.app.animation

/**
 * Phase 6 (R2) — root-authority boundary assertions
 * (IMPLEMENTATION_RUNTIME_SKELETON plan §P6; RFC_RUNTIME_SKELETON_ARCHITECTURE §5 R2,
 * §3 rotation-space rule, §6 phase boundaries).
 *
 * R2: from the end of build until publish, [ConstraintSolver] is the sole subsystem that
 * translates or rotates the root (pelvis). [SkeletonPipeline.runStages] instruments the phase
 * boundaries with this utility:
 *
 *  - **capture A** at window entry (post-build / post-injection — the last lawful authoring
 *    write),
 *  - **Check 1** after Phase 1 ([IkStage.apply]): the pelvis must be bit-identical to A,
 *  - **capture B** after Phase 2 ([ConstraintSolver.solve]) — the authorized mover's settled
 *    root becomes the protected reference (no equality claim against A),
 *  - **Check 2** after Phase 3/4 ([SkeletonPoseFinalizer.finalize] + publish): the pelvis must
 *    be bit-identical to the protected reference.
 *
 * The protected reference switches ONLY at the explicit solve-branch site in `runStages` —
 * never inferred from retained Frame History (`previous`/`prePrevious`/
 * `previousSmoothingRoot`), per the Phase 5 R10 armed-state lesson (P5 pitfall 1: the history
 * pose shares the reused, per-build-mutated node tree, so history-based inference both
 * false-fires and misses).
 *
 * Debug-only by construction: [SkeletonPipeline] calls [captureRoot] only under
 * `BuildConfig.DEBUG` — release builds compile the mechanism out entirely (same placement as
 * the P1 [RuntimeContextSnapshot] enforcement helper). Comparison is EXACT (bit-identical
 * float values): legitimate movers are known, transforms are otherwise only ever assigned by
 * value, and P0-class characterization guarantees mean no drift tolerance exists to grant.
 *
 * The scope of the ROOT guard is deliberately the ROOT ONLY (pelvis node local position +
 * local rotation): child joints legitimately move via limb re-bakes and FK propagation and
 * are not part of R2.
 *
 * Phase 7 (R3) reuses this utility for the Settled-Contact Guarantee ([SettledContactSnapshot]
 * / [captureSettledContacts]) with the same debug gating and exact-compare semantics, but a
 * DISJOINT reference set: the world positions of the settled-contact end-effectors named by
 * the frame's Settlement Result (RFC §3.2/§4.3) — never the root, never the declaration list.
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
         * Throws [IllegalStateException] ("R2 violation: …") if [pose]'s pelvis transform no
         * longer bit-matches this snapshot. Absent hierarchy / absent pelvis node is NOT a
         * violation here — those are the same structural early-outs the solver itself takes
         * (no root exists to protect); the pipeline pins its capture point before relying on
         * this.
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
     * Captures the pelvis transform of [pose] by value, or `null` when there is no hierarchy /
     * no pelvis node to protect. [capturedAt] labels the boundary for the failure message.
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
     * Phase 7 (R3) — immutable by-value snapshot of the WORLD positions of a frame's settled
     * contact end-effectors (plan §P7; reuses this utility's P6 exact-compare idiom for a
     * multi-joint reference set). Produced from the [SettlementInfo] the ConstraintSolver
     * fixed at Phase 2 exit — the Settlement Result is the SOLE reference source: the joint
     * set comes from its `declaredContactJoints` member, never reconstructed from the pose's
     * Contact Declarations, retained chains, solver heuristics, or Frame History.
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
         * Throws [IllegalStateException] ("R3 violation: …") if any settled contact
         * end-effector's world position in [pose] no longer bit-matches its post-solve
         * snapshot. R3: once the ConstraintSolver settles a contact end-effector, no later
         * subsystem — including the SkeletonPoseFinalizer — may move it until the frame is
         * published. Comparison walks the WHOLE reference set (every joint the Settlement
         * Result lists) with exact float equality, unlike the root snapshot there is no
         * structural early-out: the carrier arrays always hold the flattened world value.
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
     * Phase 7 (R3) — snapshots the world positions of the settled-contact end-effectors
     * listed by the pose's [SettlementInfo] (the canonical Settlement Result: populated by
     * the ConstraintSolver at Phase 2 exit from its final FK + flatten). Returns `null` when
     * the frame has no Settlement Result — the legitimate "no settle ran" case (contact-less
     * CUSTOM frames skip the solve; the early-return path never fixes a Result).
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
