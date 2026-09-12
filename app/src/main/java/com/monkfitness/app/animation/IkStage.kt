package com.monkfitness.app.animation

/**
 * Branch B — B1 (IkStage extraction, RFC_BRANCH_B_IMPLEMENTATION §2 B1), activated by P12.
 *
 * `IkStage` is the pipeline-owned stage that **consumes** the §1.1 `limbTargets` carrier and
 * performs the limb inverse-kinematics realization. It derives each limb's `middle`/`end` local
 * positions on the engine-owned node tree (`pose.roots`) from the declared [WorldTarget].
 *
 * **P12 (R5 activation).** With [IK_STAGE_ACTIVE] the engine-supplied production default
 * (**true**), `IkStage` is the sole Active Limb Solver per §12.0 state 3: the authoring bakes
 * keep their registration effects but their node-realization branch is gated off (§12.7a), the
 * solver-window counter increments at each realization site so the pipeline proves exactly one
 * solver per frame in both configurations (§12.7b), and every Limb Target carries its declared
 * solve inputs — bone lengths + constraint ([WorldTarget.length1]/[WorldTarget.length2]/
 * [WorldTarget.constraint]) — so realization is lossless (B-3): the former `isArm` joint-name
 * heuristic that recovered `definition.armIKConstraint`/`legIKConstraint` and the definition
 * lengths is GONE. A target without declared realization context (only reachable via the
 * shorthand `IntentBuilder.limbTarget`, never written by a registered bake) fails fast under the
 * active stage — no silent definition recovery, no coincidence-dependent parity.
 *
 * Re-solve parity with the authoring bake is preserved by construction: the stage executes the
 * same `SkeletonMath` calls with the same declared arguments, and contacts are already
 * registered in `pose.contacts` by the bake, so the stage only re-solves (it never
 * double-registers). For a zero-length `pole` it derives the default world pole, exactly as
 * `bakeIkLimb` does.
 *
 * **Activation criterion (§12.10) — SATISFIED and enforced by `ActivationGateTest`, which is the
 * authoritative check for every item below (the gate composes the owning suite rather than
 * duplicating it, and fails the moment any criterion becomes false):**
 *  (i)  B-1 authoring consumers eliminated or planning-solve-sanctioned — WP-B:
 *       `ActivationGateTest.criterionOne_b1AuthoringConsumersAreResolved` composes
 *       `LimbSolverOwnershipActivationContractTest.hipFlexorFamilyDoesNotConsumeSolveResultsForAuthoring`
 *       + `PlanningSolveInventoryTest` (one sanctioned planning-solve call site) + the WP-H
 *       composition-only behaviour audit;
 *  (ii) B-2 direct-`solveIK` bypass family migrated to registered implementations — WP-D:
 *       `criterionTwo_directBypassFamilyIsResolved` composes the static sweep
 *       (`noUnauthorizedDirectSolveInProductionPoses`), the per-file-pinned solve inventory
 *       (`noJointNameHeuristicOrUnregisteredLimbSolvePathRemains`) and the zero-consumer
 *       legacy-reconstruction audit;
 *  (iii) B-3 lossless authored limb-data recovery — WP-D:
 *       `criterionThree_declaredLimbContextReachesTheEngineSolver` composes the declared
 *       constraint + bone-length decode tests, the realized-set == declared-set audit over all four
 *       registered paths, and a source check that this file recovers NOTHING from `definition`
 *       (no length/constraint coincidence, no joint-name heuristic);
 *  (iv) §12.7 strengthened single-active-solver enforcement live — WP-G:
 *       `criterionFour_strengthenedEnforcementRejectsASecondRealization` composes
 *       `SingleActiveSolverEnforcementTest` (a second realization of one limb is rejected even when
 *       both executions produce a byte-identical frame) + the counterfactual audit, and pins the
 *       enforcement block as a `check(...)` contract on the registered execution counters;
 *  (v)  §12.9 equivalence harness green — WP-H:
 *       `criterionFive_section129EquivalenceCorpusIsGreen` RUNS the corpus
 *       (`ActivationEquivalenceTest`: 39 representative entries × progress sweep, raw-bit exact over
 *       all 33 joint transforms + the complete §4.4 stamp set + settlement state + publish markers +
 *       kinematic state) and requires zero deltas, zero ownership-premise failures and zero
 *       relaxations — never a smaller activation smoke test;
 *  (vi) validation-probe semantics re-certified against the realized path — WP-F/H:
 *       `criterionSix_validationProbesAreReCertified` composes
 *       `ValidationOwnershipReCertificationTest` (probe reading produced by the ACTIVE
 *       implementation, fresh build window re-armed, R9 write-freeness of the validator) and the
 *       cross-configuration probe matrix.
 * The deployed configuration itself is asserted twice over — statically on the declaration
 * (`RuntimeSolverOwnershipAuditTest`, the §12.7 configuration surface) and at runtime plus for the
 * absence of any environment/system-property channel
 * (`ActivationGateTest.productionConfigurationIsStateThree`) — and the §14 post-activation behaviour
 * is proved on the production path by `ActivationGateTest`
 * (`activatedProductionRejectsASecondRealizationOfTheSameLimb`,
 * `activatedProductionAcceptsAnOrdinaryActiveFrame`,
 * `f1SnapshotFamilyCannotHideASecondRealization`). Flag-ON runs through the pipeline in
 * `RootAuthorityTest` (plan §P6 test (c), a production-path test) and in every
 * `ActivationEquivalenceTest` case.
 */
/**
 * Engine-supplied configuration of the pipeline-owned limb stage ([IkStage]).
 *
 * **P12 (§12.0 state 3): production default `true`** — `IkStage` is the sole Active Limb Solver and
 * the configuration above is valid by the criterion in [IkStage]'s KDoc. The flag remains selectable
 * per R5 ("the enabling flag is a rollout mechanism, not architecture: it selects between two
 * implementations of the same frozen responsibility set"): `false` keeps the authoring bake as the
 * Active Limb Solver for differential/regression work, and the §12.7 strengthened enforcement proves
 * the single-solver invariant in BOTH configurations. Writes occur only through this declared
 * configuration surface — one declaration, zero production writes, reads confined to the four
 * realization-decision files (`RuntimeSolverOwnershipAuditTest`), the runtime value and the absence
 * of any environment/system-property selector pinned by
 * `ActivationGateTest.productionConfigurationIsStateThree`; tests flip the flag in memory only, under
 * flag-scoped restore.
 *
 * **Lifecycle and ownership (§12.7, measured 2026-09-12).** This declaration IS a file-level
 * process-wide `var` — the truthful reading of the configuration surface below, which sits beside its
 * readers ([IkStage.apply] and the three registered authoring gates) because all of them consult it
 * inside one build cycle. R14's "constructor/definition-level knob supplied by the creator" is NOT
 * reached by it: the authoring bakes run inside pose-authored `build()` and receive no engine
 * configuration, so a creator-owned knob needs a new configuration channel into the authoring path —
 * an intent/carrier change that plan §12.4 requires be raised as a SEPARATE clarification proposal,
 * never bundled into an implementation change. What the landed mechanism does guarantee, and what
 * `arch.SingleActiveSolverLifecycleTest` proves behaviourally on the production path: exactly one
 * implementation realizes a declared limb per build cycle in either configuration; the build-window
 * bookkeeping (the F2 re-arm of `boneLengthsVerified`/`straightIntentDropped`) runs in BOTH
 * configurations, so the straight-intent reading describes the current build and never leaks forward
 * from an earlier one; and the reading is re-armed by each build rather than accumulated across
 * builds. The ownership question stays recorded and unresolved in the plan (§12.7) — it is flagged
 * for the architecture owner, never silently redesigned here.
 */
var IK_STAGE_ACTIVE: Boolean = true

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
        if (!IK_STAGE_ACTIVE) return
        // Phase 4 (R5) / P12 §12.7b: the engine-side limb-solver WINDOW is instantiated for this
        // frame — count it for the pipeline's single-active-solver enforcement. Incremented past
        // the rollout gate but before the no-work early-returns so "window instantiated" is
        // counted uniformly (count==0 in the strengthened check means a frame reached the
        // pipeline with NO registered realization — a violation). The bake realization branch
        // counts the SAME counter while the stage is disabled, so a bake+stage double solve is
        // observable even when the two implementations produce identical output.
        // WP-G: the per-execution half (which limbs this window realized, and whether any limb was
        // realized twice inside it) is registered per target below through the single evidence
        // path `SkeletonPose.registerLimbRealization`.
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

            // P12 (§12.4/§12.10-iii, B-3): lossless decode. The realization inputs are the
            // DECLARED values on the Limb Target — the same ones the authoring bake solved with.
            // No arm/leg joint-name heuristic, no definition recovery, no length guessing: an
            // undeclared context means the target did not come through a registered implementation.
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

            // Sanctioned build-scoped re-arm (Phase 2 decision F2 — not a strengthening merge),
            // mirroring bakeIkLimb: the first limb re-baked this build re-arms the optimistic `true`.
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

            // WP-G (§12.7b/c) — per-EXECUTION evidence for this limb, registered at the single
            // authoritative evidence path immediately before the solve executes. A duplicated
            // Limb Target for one joint runs this solve twice inside ONE stage window, which the
            // window count cannot see (identical output); this records it as a second realization.
            pose.registerLimbRealization(target.joint, authoringWindow = false)

            val result = if (target.straight) {
                SkeletonMath.solveStraightLimb(rootWorld, target.world, length1, length2, constraint, ikResult, target.contact)
            } else {
                SkeletonMath.solveIK(rootWorld, target.world, length1, length2, worldPole, constraint, ikResult, target.contact)
            }

            pose.maxIkClampAmount =
                ValidationStampMerge.clamp(pose.maxIkClampAmount, result.clampAmount)
            // Phase 4 (R5): fold the executed solve's fallback outcome (see BasePose.bakeIkLimb).
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
