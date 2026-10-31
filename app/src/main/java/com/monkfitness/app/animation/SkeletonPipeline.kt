package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig

/**
 * Result of a single pipeline frame; [report] is non-null only for frames produced through a
 * validating entry point ([SkeletonPipeline.produceFrameValidated]).
 */
data class PipelineResult(val pose: SkeletonPose, val report: ValidationReport?)

data class ValidatedFrame(val pose: SkeletonPose, val report: ValidationReport)

/**
 * The ordered engine orchestrator: `pose.build()` → ConstraintSolver.solve (contacts only) →
 * SkeletonPoseFinalizer.finalize (FK + flatten) → optional validator.
 *
 * The pipeline is the sole caller of both the Solver and the Finalizer, in fixed order, which
 * removes the re-entrancy a Finalizer-side solve call would create. The Solver no-ops on
 * contact-less poses, so contact-less frames are unchanged.
 *
 * A pipeline owns its stage instances and the Frame History — the previous/pre-previous chain the
 * dynamics rules read plus the settled-root smoothing input — and that history is the engine's
 * ONLY cross-frame temporal state (the Solver keeps none). It is long-lived per
 * engine/definition, never created per frame, and not thread-safe (one instance per render loop).
 */
class SkeletonPipeline(
    private val definition: SkeletonDefinition,
    private val validator: ExerciseValidator? = null
) {
    init {
        // All engine migration flags are collapsed to their true branch, so the pipeline is
        // unconditionally live and there is no coherence invariant left to enforce.
    }

    private val finalizer = SkeletonPoseFinalizer(definition)

    // Per-frame history for the dynamics validator rules (velocity/acceleration/discontinuity).
    // Snapshots, so a later frame's finalize — which reuses the finalizer's output buffer — cannot
    // alias the previous frame's data. The pipeline is the SOLE owner and it advances on EVERY
    // production path via [commitFrameHistory].
    private var previous: SkeletonPose? = null
    private var prePrevious: SkeletonPose? = null

    // The smoothing input for the solver's Inter-Frame Smoothing: the settled root of the most
    // recent frame that RAN THE SOLVER, captured BY VALUE right after that solve (node buffers are
    // reused across builds, so a reference must never be retained). It holds the pelvis node's
    // local position — the root space the solver seeds and eases in — so sequential-playback
    // numerics are unchanged; the pipeline owns it and passes it explicitly, never keyed by pose
    // identity. `null` until this pipeline has solved its first frame.
    private var previousSmoothingRoot: Vector3? = null
    // Debug trip-wire bookkeeping (see [runStages]): whether the most recent frame RAN the solver
    // over a hierarchy with a settled pelvis node, i.e. whether it captured (or should have
    // captured) a smoothing root. Distinguishes "first frame / solve skipped" from "forgot to
    // wire". Never read in release builds.
    private var lastFrameArmedSmoothingCapture = false

    /**
     * Entry point for an already-built pose (renderer path). The caller supplies the engine-owned
     * support model, because a bare SkeletonPose does not carry its declaration source.
     *
     * Both parameters default to the context the supplied frame ALREADY carries: omission
     * preserves it, an explicit argument is forwarded verbatim and wins. Defaulting to the empty
     * model instead would silently drop the frame's support declaration on re-entry (24 of 49
     * registered poses carry one; dropping it moved extremity geometry by up to 22.76 units).
     */
    fun produceFrame(
        builtPose: SkeletonPose,
        environment: EnvironmentDefinition = builtPose.environment,
        supportedPoints: Set<SupportPoint> = builtPose.supportedPoints
    ): PipelineResult {
        injectRuntimeContext(builtPose, environment, supportedPoints)
        val finalized = runStages(builtPose)
        // Frame History is committed on this path too (see [commitFrameHistory]).
        commitFrameHistory(finalized)
        return PipelineResult(finalized, null)
    }

    /**
     * Builder entry point: drives the full ordered stage chain and returns a [PipelineResult] with
     * a `null` report (use [produceFrameValidated] for validation).
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        val finalized = runStages(buildAndInject(pose, context))
        commitFrameHistory(finalized)
        return PipelineResult(finalized, null)
    }

    /**
     * Builder-path front half: `build()` + the single [injectRuntimeContext] call. The validating
     * entry runs the stages *before* committing history so it can validate against the PREVIOUS
     * frame's chain.
     */
    private fun buildAndInject(pose: PoseBuilder, context: PoseContext): SkeletonPose {
        val built = pose.build(context)
        // Stamp the engine-owned support model onto the pose so the Finalizer can derive support
        // planes for EVERY pose from the environment (ground + props) and the declared contacts,
        // instead of a per-pose hardcoded plane: the pose only says WHICH points rest on WHAT, the
        // engine decides the geometry. No-op when nothing is declared.
        // The derivation lives in ONE production place (`SupportDefinition.supportPoints`), also
        // used by the renderer path; a second copy here is exactly how that path diverged.
        injectRuntimeContext(built, pose.metadata.environment, pose.metadata.support.supportPoints)
        return built
    }

    /**
     * The ONLY place the pipeline stamps [SkeletonPose.environment] and
     * [SkeletonPose.supportedPoints]. Both [produceFrame] overloads call it exactly once,
     * immediately before [runStages]; no other pipeline method writes these carriers.
     */
    private fun injectRuntimeContext(
        pose: SkeletonPose,
        environment: EnvironmentDefinition,
        supportedPoints: Set<SupportPoint>
    ) {
        pose.environment = environment
        // The renderer overload's defaults alias the carrier's own set (omission = keep what the
        // frame carries); clearing before re-adding that same instance would empty the carrier, so
        // the rewrite is skipped when the source IS the carrier.
        if (supportedPoints !== pose.supportedPoints) {
            pose.supportedPoints.clear()
            pose.supportedPoints.addAll(supportedPoints)
        }
    }

    /**
     * Ordered stage chain shared by both entry points: Solver (contacts only) → Finalizer (FK +
     * flatten) — the single place both are invoked, in fixed order.
     */
    private fun runStages(pose: SkeletonPose): SkeletonPose {
        // Window-start evidence: the pipeline opens a NEW execution window over this carrier, so a
        // publication marker the finalizer holds from a previous frame of the SAME carrier
        // (sequential playback reuses one carrier per builder) is a legitimate re-arm, not a
        // re-entry violation. Debug-only.
        if (BuildConfig.DEBUG) finalizer.beginPublishWindow()
        // Snapshot the freshly injected context and re-compare after every stage: a carrier-level
        // post-injection write by a stage fails fast. It enforces carrier-level mutation only —
        // deep payload mutation and content-identical rewrites are outside its reach — and release
        // builds compile it out entirely.
        val r8 = if (BuildConfig.DEBUG) RuntimeContextSnapshot.of(pose) else null
        // Root-authority capture A: the window-entry state — build has returned and injection is
        // done, so the pelvis holds the last lawful authoring write. It switches ONLY at the
        // explicit solve site below (capture B), never inferred from retained history: a frame that
        // legitimately skips the solve has no authorized root mover, so A must survive untouched.
        var r2ProtectedRoot =
            if (BuildConfig.DEBUG) PhaseBoundaryAsserts.captureRoot(pose, "capture A (post-build)") else null
        // Post-solve WORLD-position snapshot of the frame's settled-contact end-effectors. Arms
        // ONLY at the explicit solve site below, and only when this solve actually PRODUCED the
        // Settlement Result it references (identity vs prior, never inferred). A frame that skips
        // the solve, or whose solve early-returned above the populate point, arms nothing: no
        // settle means no settled geometry to protect.
        var r3SettledContacts: PhaseBoundaryAsserts.SettledContactSnapshot? = null
        // The pipeline-owned limb stage consumes `limbTargets` and re-derives each limb's local
        // positions on the engine-owned node tree. `IK_STAGE_ACTIVE` is the CONFIGURATION SELECTOR,
        // not a legacy no-op: with the deployed default the stage is the sole Active Limb Solver and
        // the authoring bakes' realization branch is gated off. It runs before the ConstraintSolver
        // so contact limbs are re-baked ahead of the root-repositioning pass, and before the FK.
        IkStage.apply(pose, definition)
        r8?.assertUnchanged(pose, "after IkStage")
        // Check 1 — the limb stage is not a root mover: it writes only middle/end joint locals, so
        // a pelvis change here means a regression.
        if (BuildConfig.DEBUG) {
            r2ProtectedRoot?.assertUnchanged(pose, "Phase 1 (IkStage limb solve window)")
        }
        // Posture/contact settling. Runs for contact poses and for any pose naming a non-CUSTOM
        // posture intent, so the engine owns the coarse root height. A CUSTOM, contact-less pose is
        // still a pure no-op.
        val postureDriven = pose.postureIntent.kind != PostureIntent.Kind.CUSTOM
        // The smoothing input is supplied to the solve as an explicit current-frame argument; the
        // solver derives it from no other source (behaviour is a function of current-frame inputs
        // plus supplied history, never of object identity). The debug `check` distinguishes the
        // legitimate "first frame of the history window" from "forgot to wire": the trip-wire is
        // armed only when the frame about to become `previous` actually RAN the solver over a tree
        // with a pelvis node. If a refactor drops the capture below, the next solve throws here
        // instead of silently losing smoothing.
        if (BuildConfig.DEBUG) {
            check(
                previous == null || !lastFrameArmedSmoothingCapture || previousSmoothingRoot != null
            ) {
                "R10 violation: the committed Frame History carries a solved root eligible " +
                    "for smoothing but no smoothing root was captured — the commit-time " +
                    "capture in runStages was forgotten"
            }
        }
        if (pose.roots.isNotEmpty() && (pose.hasContacts() || postureDriven)) {
            // Arming evidence read BEFORE the solve: the ConstraintSolver fixes a NEW Settlement
            // Result instance on every complete settle, so `!== prior` afterwards is per-frame
            // production evidence. A solve that early-returns above the populate point leaves the
            // stale slot — identity is equal and nothing arms.
            val priorSettlement = if (BuildConfig.DEBUG) pose.settlementResult else null
            ConstraintSolver.solve(pose, definition, previousSmoothingRoot)
            // Capture THIS frame's settled root BY VALUE as the next frame's smoothing history,
            // immediately after the solve (the sole root mover). Read from the pelvis node's local
            // position — the exact value space the solver seeds/eases in, so sequential-playback
            // numerics are unchanged. Node buffers are reused across builds, so the reference must
            // never be retained. A pelvis-less solved tree captures nothing (`solve` early-returns
            // before settling without a pelvis).
            val settledRoot = findPelvisNode(pose.roots)?.localPosition
            if (settledRoot != null) {
                previousSmoothingRoot = Vector3(settledRoot.x, settledRoot.y, settledRoot.z)
            }
            if (BuildConfig.DEBUG) {
                lastFrameArmedSmoothingCapture = settledRoot != null
                // Capture point B — the solve ran (the authorized root mover), so the protected
                // reference switches to the settled root NOW, at the execution site. A pelvis-less
                // solved tree keeps A: no settled root was written, so the authored root must
                // survive.
                r2ProtectedRoot = PhaseBoundaryAsserts.captureRoot(pose, "capture B (post-settlement)")
                // Arm the settled-contact reference ONLY when THIS solve produced the Settlement
                // Result it lists. The snapshot reads the joint set from that result and the world
                // positions the solver's final FK + flatten just wrote.
                if (pose.settlementResult !== priorSettlement) {
                    r3SettledContacts =
                        PhaseBoundaryAsserts.captureSettledContacts(pose, "post-settlement (Phase 2 exit)")
                }
            }
        } else if (BuildConfig.DEBUG) {
            lastFrameArmedSmoothingCapture = false
            // The solve was legitimately skipped (contact-less CUSTOM), so no authorized mover
            // exists after build: the later checks compare against A directly.
        }
        r8?.assertUnchanged(pose, "after ConstraintSolver")
        // Finalizer: world↔local conversion, extremity derivation, chest-frame reconstruction, FK
        // flatten. It no longer calls the Solver itself.
        val finalized = finalizer.finalize(pose)
        // Checked on the INPUT pose: [finalize] copies into its own output buffer, so the question
        // is whether a stage wrote the injected context on the pose that entered the chain.
        r8?.assertUnchanged(pose, "after Finalizer")
        // Check 2 — finalize + publish are not root movers: the published pelvis must be
        // bit-identical to the protected reference (B on solved frames, A on skipped ones). Checked
        // on the FINALIZED pose, whose `roots` are the tree consumers actually receive.
        if (BuildConfig.DEBUG) {
            r2ProtectedRoot?.assertUnchanged(
                finalized,
                "Phase 3/4 (Finalizer + publish window; solver " +
                    (if (pose.hasContacts() || postureDriven) "ran" else "was skipped") +
                    " this frame)"
            )
            // Every settled contact end-effector must still sit at its post-solve world position
            // once finalization has completed. Checked on the FINALIZED pose, so this detects a
            // settled contact displaced by ANY finalization operation (whole-phase check first;
            // finer instrumentation only on triage).
            r3SettledContacts?.assertUnchanged(
                finalized,
                "Phase 3 finalization (post-settlement → completion of " +
                    "SkeletonPoseFinalizer.finalize)"
            )
        }
        // Runtime enforcement over two pieces of execution evidence:
        //  - WINDOW count covers BOTH registered realization sites (authoring bake branch + engine
        //    stage). The stage window must have executed exactly once; any leaked second solver — a
        //    bake that realized while the stage was on, a duplicated stage call, a hidden third
        //    path — raises the count and fires the check even when both produce identical output.
        //    `count == 0` survives only for the legacy skipped-window reading while the stage is
        //    disabled (a zero-limb/custom pose legitimately opens no window).
        //  - PER-EXECUTION evidence: the window count cannot see two realizations of ONE limb inside
        //    ONE window (a duplicated limb target, a second `bakeIkLimb` for the same joint) — same
        //    count, byte-identical frame. `limbDuplicateRealizations` counts those events and the
        //    second check rejects on that number alone: the violation is EXECUTION evidence, never
        //    a comparison of world positions, floats, stamps or hashes.
        if (BuildConfig.DEBUG) {
            check(
                pose.limbSolverExecutions == 1 ||
                    (pose.limbSolverExecutions == 0 && !IK_STAGE_ACTIVE)
            ) {
                "R5 violation: runtime limb-solver windows executed this frame = " +
                    "${pose.limbSolverExecutions} (expected exactly 1; 0 only while the " +
                    "engine-side IK stage is disabled and no registered realization ran)"
            }
            check(pose.limbDuplicateRealizations == 0) {
                "R5 violation: the Active Limb Solver realized a limb more than once inside one " +
                    "build cycle — duplicate realization events = ${pose.limbDuplicateRealizations}, " +
                    "window executions = ${pose.limbSolverExecutions}, total realization events = " +
                    "${pose.limbSolverExecutions + pose.limbDuplicateRealizations}. Exactly one " +
                    "realization per limb per cycle is allowed (§12.7 strengthened single-active-" +
                    "solver enforcement; realized-limb mask = " +
                    "${java.lang.Long.toBinaryString(pose.limbRealizedLimbs)} by Joint.index)"
            }
        }
        pose.limbSolverExecutions = 0
        pose.limbDuplicateRealizations = 0
        pose.limbRealizedLimbs = 0L
        return finalized
    }

    /**
     * The SOLE rotation site of the Frame History chain, called by every production entry point
     * after the frame is produced; the validating path commits only after validation consumed the
     * PREVIOUS chain, preserving dynamics-rule ordering. Snapshots (not references), because the
     * finalizer's output buffer is reused. The smoothing-root capture lives in [runStages].
     */
    private fun commitFrameHistory(finalized: SkeletonPose) {
        prePrevious = previous
        previous = SkeletonPose().apply { copyFrom(finalized) }
    }

    /** Depth-first search of a solved hierarchy for the pelvis (root-authority) node. */
    private fun findPelvisNode(roots: List<SkeletonNode>): SkeletonNode? {
        for (root in roots) {
            val found = findPelvisNode(root)
            if (found != null) return found
        }
        return null
    }

    private fun findPelvisNode(node: SkeletonNode): SkeletonNode? {
        if (node.joint == Joint.PELVIS) return node
        for (child in node.children) {
            val found = findPelvisNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * [produceFrame] plus the mandatory validation stage; a validator must have been supplied at
     * construction. The dynamics rules read the previous/pre-previous history before this frame is
     * committed to it; call [resetHistory] on restart/seek.
     */
    fun produceFrameValidated(
        pose: PoseBuilder,
        context: PoseContext,
        camera: Camera,
        environment: EnvironmentDefinition,
        width: Float,
        height: Float,
        deltaTime: Float
    ): ValidatedFrame {
        val v = validator
            ?: error("produceFrameValidated requires a validator supplied to the SkeletonPipeline constructor.")
        val finalized = runStages(buildAndInject(pose, context))
        val report = v.validate(
            pose = finalized,
            definition = definition,
            environment = environment,
            camera = camera,
            width = width,
            height = height,
            previousPose = previous,
            prePreviousPose = prePrevious,
            deltaTime = deltaTime
        )
        // The validate call above still reads the PREVIOUS chain before this frame commits.
        commitFrameHistory(finalized)
        return ValidatedFrame(finalized, report)
    }

    /** Clears the dynamics history (call on animation restart/seek so velocity rules don't spike). */
    fun resetHistory() {
        previous = null
        prePrevious = null
    }
}
