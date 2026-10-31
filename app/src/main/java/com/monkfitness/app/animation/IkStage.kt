package com.monkfitness.app.animation

/**
 * Engine-supplied configuration of the pipeline-owned limb stage ([IkStage]).
 *
 * Production default is `true`: the stage is the sole Active Limb Solver. The flag stays selectable
 * as a rollout mechanism, not architecture — `false` keeps the authoring bake as the solver for
 * differential and regression work, and the strengthened enforcement proves the single-solver
 * invariant in both configurations. It is a process-wide `var`, read inside one build cycle by the
 * stage and the three authoring gates; tests flip it in memory only, under flag-scoped restore.
 *
 * Exactly one implementation realizes a declared limb per build cycle in either configuration. The
 * build-window bookkeeping (re-arming `boneLengthsVerified`/`straightIntentDropped`) runs in both,
 * so the straight-intent reading describes the current build and never leaks forward from an earlier
 * one. Whether this should be a creator-supplied knob instead of a file-level var is recorded and
 * unresolved — flagged for the architecture owner, not silently redesigned here.
 */
var IK_STAGE_ACTIVE: Boolean = true

/**
 * Pipeline-owned stage that consumes the `limbTargets` carrier and performs limb IK realization:
 * each limb's `middle`/`end` local positions are derived on `pose.roots` from its declared
 * [WorldTarget].
 *
 * Under [IK_STAGE_ACTIVE] this is the sole Active Limb Solver — the authoring bakes keep their
 * registration effects but their node-realization branch is gated off, and the solver-window counter
 * increments at every realization site so the pipeline can prove exactly one solver per frame in
 * both configurations. Every target carries its declared solve inputs (`length1`, `length2`,
 * `constraint`), so realization is lossless: no `isArm` joint-name heuristic, no definition-length
 * recovery, and a target without declared context fails fast rather than silently recovering one.
 *
 * Re-solve parity holds by construction: the same `SkeletonMath` calls with the same declared
 * arguments, contacts already registered by the bake so this only re-solves, and a zero-length pole
 * gets the default world pole exactly as `bakeIkLimb` derives it.
 */
object IkStage {

    // Reused scratch buffers (allocation-free, single-threaded like the finalizer).
    private val ikResult = SkeletonMath.IKResult()
    private val tempV1 = Vector3()
    private val tempPole = Vector3()
    private val zero = Vector3(0f, 0f, 0f)

    /**
     * Re-bakes every limb declared in [pose.limbTargets] onto [pose.roots]. No-op when the carrier
     * is empty or the flag is off. Call after the pose has authored the proximal joints and BEFORE
     * `SkeletonPose.fromHierarchy` flattens the tree — the order cannot be swapped.
     */
    fun apply(pose: SkeletonPose, definition: SkeletonDefinition) {
        if (!IK_STAGE_ACTIVE) return
        // Count the limb-solver window for the pipeline's single-active-solver enforcement,
        // incremented past the flag check but before the no-work returns so "window instantiated"
        // is counted uniformly (count == 0 means a frame reached the pipeline with no registered
        // realization — a violation). The bake branch counts the SAME counter while the stage is
        // disabled, so a bake+stage double solve is observable even when both produce identical
        // output. The per-execution half (which limbs, and whether one was realized twice) is
        // registered per target below via `SkeletonPose.registerLimbRealization`.
        pose.limbSolverExecutions++
        val targets = pose.limbTargets
        if (targets.isEmpty()) return
        val roots = pose.roots
        if (roots.isEmpty()) return

        val nodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }
        for (root in roots) collect(root, nodeMap)

        for (target in targets) {
            val chain = ConstraintSolver.chainForEnd(target.joint) ?: continue
            val parent = nodeMap[chain.rootJoint.index] ?: continue
            val middle = nodeMap[chain.middleJoint.index] ?: continue
            val end = nodeMap[target.joint.index] ?: continue

            // Lossless decode: the realization inputs are the DECLARED values on the Limb Target,
            // the same ones the authoring bake solved with. No joint-name heuristic, no definition
            // recovery, no length guessing — an undeclared context means the target did not come
            // through a registered implementation.
            val length1 = target.length1
            val length2 = target.length2
            val constraint = target.constraint
            if (length1.isNaN() || length2.isNaN() || constraint == null) {
                throw IllegalStateException(
                    "R5 violation: Limb Target ${target.joint} carries no declared realization " +
                        "context (length1=$length1 length2=$length2 constraint=$constraint) — " +
                        "every realized limb must be declared through a registered authoring " +
                        "bake (§12.4 lossless intent); silent definition recovery is forbidden"
                )
            }

            // Build-scoped re-arm, not a merge, mirroring bakeIkLimb: the first limb re-baked this
            // build re-arms the optimistic `true`.
            if (pose.isTransformsUpdated) {
                pose.boneLengthsVerified = true
                pose.isTransformsUpdated = false
            }

            val rootWorld = parent.worldPosition
            // Mirror bakeIkLimb: the offsets are authored in the IMMEDIATE parent of the middle
            // joint's world frame (hip for a leg, shoulder for an arm).
            val parentRot = middle.parent?.worldRotation ?: parent.worldRotation

            val pole = target.pole
            val worldPole = if (pole.mag() < 1e-4f) {
                SkeletonMath.deriveDefaultPole(rootWorld, target.world, tempPole)
            } else {
                pole
            }

            // Per-execution evidence, registered at the single evidence path just before the solve.
            // A duplicated Limb Target for one joint runs this solve twice inside ONE window, which
            // the window count cannot see (identical output); this records a second realization.
            pose.registerLimbRealization(target.joint, authoringWindow = false)

            val result = if (target.straight) {
                SkeletonMath.solveStraightLimb(rootWorld, target.world, length1, length2, constraint, ikResult, target.contact)
            } else {
                SkeletonMath.solveIK(rootWorld, target.world, length1, length2, worldPole, constraint, ikResult, target.contact)
            }

            pose.maxIkClampAmount =
                ValidationStampMerge.clamp(pose.maxIkClampAmount, result.clampAmount)
            // Fold the executed solve's fallback outcome, as in [BasePose.bakeIkLimb].
            pose.straightIntentDropped =
                ValidationStampMerge.dropped(
                    pose.straightIntentDropped,
                    if (target.straight) result.straightIntentDropped else false
                )
            val bonesOk = SkeletonMath.bonesExact(rootWorld, result.joint, result.end, length1, length2)
            pose.boneLengthsVerified =
                ValidationStampMerge.verified(pose.boneLengthsVerified, bonesOk)

            tempV1.set(result.joint).subtract(rootWorld)
            SkeletonMath.toLocalDirection(tempV1, parentRot, middle.localPosition)
            tempV1.set(result.end).subtract(result.joint)
            SkeletonMath.toLocalDirection(tempV1, parentRot, end.localPosition)
        }
    }

    private fun collect(node: SkeletonNode, map: Array<SkeletonNode?>) {
        map[node.joint.index] = node
        for (child in node.children) collect(child, map)
    }
}
