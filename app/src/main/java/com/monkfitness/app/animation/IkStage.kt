package com.monkfitness.app.animation

/**
 * Branch B — B1 (IkStage extraction, RFC_BRANCH_B_IMPLEMENTATION §2 B1).
 *
 * `IkStage` is the pipeline-owned stage that **consumes** the §1.1 `limbTargets` carrier and
 * performs the limb inverse-kinematics that `bakeIkLimb` used to do inline. It re-derives each
 * limb's `middle`/`end` local positions on the engine-owned node tree (`pose.roots`) from the end
 * joint's declared [WorldTarget], completing the move of limb solving out of the pose authoring
 * path and into the engine.
 *
 * **Reversibility / byte-identity (B1 exit criteria):** the stage is gated by
 * [EngineFlags.IK_STAGE_ACTIVE] (default **false**). When off, `bakeIkLimb` remains the sole limb
 * solver and every pose renders byte-identical to the pre-B1 baseline. When on, the stage re-solves
 * each limb with the *exact* same `SkeletonMath` calls `bakeIkLimb` uses (it reads the full IK
 * context — pole, straight flag and contact — straight off the [WorldTarget] that `bakeIkLimb`
 * recorded), so the rendered frame is unchanged — `IkStageTest` asserts this across the limb and
 * contact-instrument poses.
 *
 * **Parameter recovery.** A [WorldTarget] carries the end `joint`, its `world` target, the authored
 * `pole`, the `straight` flag and the optional `contact` — everything `bakeIkLimb` used. The stage
 * recovers the proximal chain from [ConstraintSolver.chainForEnd] (root / parent-rotation / middle
 * joints) and the bone lengths + per-limb IK constraint from the [SkeletonDefinition]; contacts are
 * already registered in `pose.contacts` by `bakeIkLimb`, so the stage only re-solves (it never
 * double-registers). For a zero-length `pole` it derives the default world pole, exactly as
 * `bakeIkLimb` does.
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
     * `SkeletonPose.fromHierarchy` flattens the tree (mirroring where `bakeIkLimb` writes today).
     */
    fun apply(pose: SkeletonPose, definition: SkeletonDefinition) {
        if (!EngineFlags.IK_STAGE_ACTIVE) return
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

            val isArm = target.joint == Joint.HAND_A || target.joint == Joint.HAND_P ||
                target.joint == Joint.ELBOW_A || target.joint == Joint.ELBOW_P
            val length1 = if (isArm) definition.upperArmLength else definition.thighLength
            val length2 = if (isArm) definition.forearmLength else definition.shinLength
            val constraint = if (isArm) definition.armIKConstraint else definition.legIKConstraint

            // Phase 1 (F5): reset the bone-length stamp once per build, mirroring bakeIkLimb.
            if (pose.isTransformsUpdated) {
                pose.boneLengthsVerified = true
                pose.isTransformsUpdated = false
            }

            val rootWorld = parent.worldPosition
            // Mirror bakeIkLimb exactly: the offsets are authored in the IMMEDIATE parent of the
            // middle joint's world frame (e.g. hip for a leg, shoulder for an arm).
            val parentRot = middle.parent?.worldRotation ?: parent.worldRotation

            val pole = target.pole
            val worldPole = if (pole.mag() < 1e-4f) {
                SkeletonMath.deriveDefaultPole(rootWorld, target.world, tempPole)
            } else {
                pole
            }

            val result = if (target.straight) {
                SkeletonMath.solveStraightLimb(rootWorld, target.world, length1, length2, constraint, ikResult, target.contact)
            } else {
                SkeletonMath.solveIK(rootWorld, target.world, length1, length2, worldPole, constraint, ikResult, target.contact)
            }

            if (result.clampAmount > pose.maxIkClampAmount) {
                pose.maxIkClampAmount = result.clampAmount
            }
            val bonesOk = SkeletonMath.bonesExact(rootWorld, result.joint, result.end, length1, length2)
            pose.boneLengthsVerified = pose.boneLengthsVerified && bonesOk

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
