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
 * The scope is deliberately the ROOT ONLY (pelvis node local position + local rotation):
 * child joints legitimately move via limb re-bakes and FK propagation and are not part of R2.
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
