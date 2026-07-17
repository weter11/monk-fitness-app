package com.monkfitness.app.animation

/**
 * Branch B (RFC_BRANCH_B_IMPLEMENTATION §2 B1) — the pipeline-owned **IK stage**.
 *
 * B1 extracts limb solving out of the pose's `bakeIkLimb` and into this single engine stage.
 * The pose now *declares* each limb as a [WorldTarget] (the §1.1 `limbTargets` carrier) via
 * `BasePose.bakeIkLimb` / `BaseValidationPose.bakeIkLimb`, which became forwards that only
 * **record** the exact bake inputs (captured at authoring time, while the node tree's world
 * transforms are current) and **delegate** the actual solve to [bakeLimbCore]. This keeps a
 * `build()`-only consumer producing a fully-baked tree (byte-identical to the legacy inline
 * bake) while the pipeline's [solve] replays the same [SkeletonMath] solve — idempotent,
 * byte-identical.
 *
 * **Byte-identity guarantee (B1 exit criterion):** the baking math in [bakeLimbCore] is a
 * verbatim copy of the former `bakeIkLimb` body — same `solveIK` / `solveStraightLimb`
 * calls, same pole derivation, same clamp / bone-length stamps, same `toLocalDirection`
 * writes. Because the recorded [WorldTarget] carries the exact `rootWorld` / `parentRotation`
 * / `pole` / `lengths` / `constraint` the original call used (those parent/root transforms
 * are authored, never limb-IK-derived), the produced `localPosition` values are bit-for-bit
 * identical to the legacy inline bake. The existing contact-instrument tests
 * ([ConstraintSolverPhase2Test], [ValidatorRomClusterTest], [DeadHangPoseTest] …) therefore
 * stay green with no geometry change.
 *
 * Ownership (RFC_ENGINE_PIPELINE §2/R6): after B1 the [IkStage] is the **sole writer**
 * of limb `localPosition` — the pose never computes or writes it inline anymore; it delegates to
 * [bakeLimbCore]. The [SkeletonPipeline] owns this stage instance and calls [solve] (before
 * the [ConstraintSolver], after `build`) in fixed order, eliminating the old pose-embedded IK.
 */
class IkStage(private val definition: SkeletonDefinition) {

    /**
     * Bakes every declared [WorldTarget] in [pose.limbTargets] into the [pose]'s node tree.
     * No-op when the pose declared no limb targets (the common non-limb production pose).
     * This is the pipeline path; it replays the same [bakeLimbCore] the pose's `bakeIkLimb`
     * forward already ran during `build` (idempotent, byte-identical).
     */
    fun solve(pose: SkeletonPose) {
        if (pose.limbTargets.isEmpty()) return
        val nodeByJoint = mutableMapOf<Joint, SkeletonNode>()
        for (root in pose.roots) collectNodes(root, nodeByJoint)
        for (spec in pose.limbTargets) {
            val middle = nodeByJoint[spec.middleJoint]
            val end = nodeByJoint[spec.endJoint]
            if (middle != null && end != null) {
                IkStage.bakeLimbCore(pose, spec, middle, end)
            }
        }
    }

    private fun collectNodes(node: SkeletonNode, out: MutableMap<Joint, SkeletonNode>) {
        out[node.joint] = node
        for (child in node.children) collectNodes(child, out)
    }

    /**
     * Verbatim re-implementation of the former `bakeIkLimb` core, driven by a recorded
     * [WorldTarget] instead of inline arguments. Writes the `localPosition` of [middleNode] /
     * [endNode] and updates [pose]'s clamp / bone-length stamps. (Contact registration lives in
     * the `bakeIkLimb` forward, which records the identical [ContactSpec] at authoring time.)
     *
     * `internal` + `companion`: the pose-side `bakeIkLimb` forwards (same `animation`
     * module) delegate to it during `build` so a `build()`-only consumer still gets a
     * fully-baked tree, byte-identical to the legacy inline bake.
     */
    internal companion object {
        fun bakeLimbCore(
            pose: SkeletonPose,
            spec: WorldTarget,
            middleNode: SkeletonNode,
            endNode: SkeletonNode
        ) {
            val parentRot = spec.parentRotation
            // Phase 1 (F5): reset the bone-length stamp once per build (mirrors legacy bakeIkLimb).
            if (pose.isTransformsUpdated) {
                pose.boneLengthsVerified = true
                pose.isTransformsUpdated = false
            }
            // Phase 1 (F6): a zero-length pole means the pose omitted one — derive the default world
            // pole so the bend plane is always well-defined (the engine owns this, not the pose).
            val worldPole = if (spec.pole.mag() < 1e-4f) {
                SkeletonMath.deriveDefaultPole(spec.rootWorld, spec.targetWorld, scratchPole)
            } else {
                spec.pole
            }
            val ikResult = if (spec.straight) {
                SkeletonMath.solveStraightLimb(
                    spec.rootWorld, spec.targetWorld, spec.length1, spec.length2,
                    spec.constraint, ikBuffer, spec.contact
                )
            } else {
                SkeletonMath.solveIK(
                    spec.rootWorld, spec.targetWorld, spec.length1, spec.length2,
                    worldPole, spec.constraint, ikBuffer, spec.contact
                )
            }
            // Single source of truth: propagate the solver's clamp amount into the pose.
            if (ikResult.clampAmount > pose.maxIkClampAmount) {
                pose.maxIkClampAmount = ikResult.clampAmount
            }
            // Phase 1 (F5): assert the solved chain preserved both bone lengths exactly and fold
            // the result into the pose's single `boneLengthsVerified` stamp (AND across all limbs).
            val bonesOk = SkeletonMath.bonesExact(
                spec.rootWorld, ikResult.joint, ikResult.end, spec.length1, spec.length2
            )
            pose.boneLengthsVerified = pose.boneLengthsVerified && bonesOk

            // Store the limb offsets in the parent's true local frame (no hand-fed inverse-Z scalar).
            scratchV.set(ikResult.joint).subtract(spec.rootWorld)
            SkeletonMath.toLocalDirection(scratchV, parentRot, middleNode.localPosition)
            scratchV.set(ikResult.end).subtract(ikResult.joint)
            SkeletonMath.toLocalDirection(scratchV, parentRot, endNode.localPosition)
        }

        // Reused scratch buffers (allocation-free across all limbs of a frame).
        private val scratchV = Vector3()
        private val scratchPole = Vector3()
        private val ikBuffer = SkeletonMath.IKResult()
    }
}
