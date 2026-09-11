package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig

/**
 * Result of a single pipeline frame. [report] is non-null only when the frame was produced
 * through a validating entry point ([SkeletonPipeline.produceFrameValidated]).
 */
data class PipelineResult(val pose: SkeletonPose, val report: ValidationReport?)

/** A pipeline frame guaranteed to carry a validation report. */
data class ValidatedFrame(val pose: SkeletonPose, val report: ValidationReport)

/**
 * Architecture v2 — the ordered engine orchestrator (RFC_ENGINE_PIPELINE §3/§5, Gap 1).
 *
 * **M2 scope (this file):** the pipeline is unconditionally live (Phase B collapsed the
 * `PIPELINE_ACTIVE` flag to its true branch) and `produceFrame` drives
 * the full ordered stage chain for every consumer:
 *
 * ```
 * Pose.build() → ConstraintSolver.solve (contacts only) → SkeletonPoseFinalizer.finalize (FK + flatten) → optional Validator
 * ```
 *
 * The Finalizer's internal `ConstraintSolver.solve` call was removed in M2 (RFC_ENGINE_PIPELINE
 * §8.1) — the pipeline is now the **sole** caller of both the Solver and the Finalizer, in fixed
 * order, which eliminates the latent re-entrancy (Finalizer→Solver→Finalizer) that existed before.
 * This is a **re-pointing** change: the Solver+Finalizer code paths are byte-identical to the
 * pre-M2 baseline (Solver still no-ops on contact-less poses; the Finalizer performs exactly the
 * same work it did when it called the Solver itself), so the rendered frame is unchanged.
 *
 * **No legacy bypass:** Phase B removed the `PIPELINE_ACTIVE` flag, so `produceFrame` always drives the
 * full stage pipeline (`pose.build()` → `ConstraintSolver.solve` → `finalizer.finalize()`). The
 * M0 byte-identity guarantee still holds for contact-less poses (the Solver no-ops on them).
 *
 * **Ownership & lifetime (RFC_ENGINE_PIPELINE §Issue 5):** a `SkeletonPipeline` owns its stage
 * *instances* (currently the [SkeletonPoseFinalizer] and an optional [ExerciseValidator]) and the
 * Frame History (RFC_RUNTIME_SKELETON_ARCHITECTURE §4.5) — the previous/pre-previous pose chain
 * consumed by the dynamics validator rules and (Phase 5 / §5 R10) the settled-root smoothing
 * input supplied to the solver's Inter-Frame Smoothing (§4.2). The Frame History is now the
 * engine's ONLY cross-frame temporal state: the ConstraintSolver previously kept a hidden
 * identity-keyed cache of its own (plan violation V3) and was made stateless with respect to
 * previous frames. The pipeline is a long-lived, per-engine/per-definition object — **not**
 * created per frame or per pose. It is not thread-safe (one instance per render loop), matching
 * the existing single-threaded finalizer.
 */
class SkeletonPipeline(
    private val definition: SkeletonDefinition,
    private val validator: ExerciseValidator? = null
) {
    init {
        // Phase B (RFC_ENGINE_CLEANUP_PLAN): all engine migration flags are collapsed to their
        // true branch, so the pipeline is unconditionally live and there is no coherence invariant
        // left to enforce. The constructor is intentionally trivial.
    }

    private val finalizer = SkeletonPoseFinalizer(definition)

    // Per-frame history for the dynamics validator rules (velocity/acceleration/discontinuity).
    // Snapshots so a later frame's finalize (which reuses the finalizer's output buffer) cannot
    // alias the previous frame's data.
    // Phase 5 (R10) — this is the RFC §4.5 Frame History and the pipeline is its SOLE owner.
    // It now advances on EVERY production path via [commitFrameHistory] (both [produceFrame]
    // overloads and [produceFrameValidated]); before P5 it advanced only on the validating path
    // while inter-frame smoothing secretly lived in the solver's identity-keyed WeakHashMap
    // (the V3 violation R10 removes).
    private var previous: SkeletonPose? = null
    private var prePrevious: SkeletonPose? = null

    // Phase 5 (R10) — the Frame History input for the solver's Inter-Frame Smoothing (§4.2):
    // the settled root of the most recent frame that RAN THE SOLVER, captured BY VALUE right
    // after that solve (node buffers are reused across builds, so a reference must never be
    // retained). Same value space the deleted solver cache persisted (the pelvis node's local
    // position — the root space the solver seeds and eases in), so sequential-playback
    // smoothing numerics are unchanged; what changed is WHO holds it and how the next solve
    // receives it: the pipeline owns it and passes it as an explicit current-frame input, and
    // it is never keyed by SkeletonPose identity — every consumer of this pipeline shares the
    // single rolling history (RFC §5 R10; plan Risk 3). `null` until this pipeline has solved
    // its first frame — the legitimate "first frame" case.
    private var previousSmoothingRoot: Vector3? = null
    // Debug trip-wire bookkeeping (see [runStages]): whether the most recent frame RAN the
    // solver over a hierarchy with a settled pelvis node — i.e. whether it captured (or should
    // have captured) a smoothing root for the next frame. Distinguishes a legitimate
    // "first frame / solve skipped" state from "forgot to wire". Never read in release builds.
    private var lastFrameArmedSmoothingCapture = false

    /**
     * Single entry point for an already-built pose (renderer path). Runs the ordered stage chain
     * (Solver → Finalizer) on [builtPose] and returns the finalized frame. Used by
     * [SkeletonRenderer] / [SkeletonSnapshotRenderer] which receive a `SkeletonPose` that has
     * already been `build()`-constructed. The Solver is skipped when the pose registered no contacts
     * (the common production case), so non-contact poses are untouched.
     */
    /**
     * Entry point for an already-built pose (renderer path). Runs the stage chain on [builtPose].
     * The caller supplies the engine-owned support model ([environment] + [supportedPoints]) because
     * this overload receives a bare SkeletonPose with no metadata; the PoseBuilder overload derives
     * both from `metadata` itself. Both paths stamp the SAME carriers so the Finalizer derives
     * support planes identically. A default (empty) environment is byte-identical for a pose that
     * declares none.
     */
    fun produceFrame(
        builtPose: SkeletonPose,
        environment: EnvironmentDefinition = EnvironmentDefinition(),
        supportedPoints: Set<SupportPoint> = emptySet()
    ): PipelineResult {
        injectRuntimeContext(builtPose, environment, supportedPoints)
        val finalized = runStages(builtPose)
        // Phase 5 (R10) — Frame History is committed on this path too (see [commitFrameHistory]).
        commitFrameHistory(finalized)
        return PipelineResult(finalized, null)
    }

    /**
     * Single entry point. The pipeline is unconditionally live (Phase B collapsed the flag), so this always drives
     * the full ordered stage pipeline: `pose.build(context)` → `ConstraintSolver.solve` (contacts
     * only) → `finalizer.finalize(...)` → FK flatten. This is exactly the pre-M0 `build` → `finalize`
     * flow, but with the pipeline-owned Solver folded in. Returns a [PipelineResult] with a `null`
     * report (use [produceFrameValidated] for validation).
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        val finalized = runStages(buildAndInject(pose, context))
        commitFrameHistory(finalized)
        return PipelineResult(finalized, null)
    }

    /**
     * Builder-path front half: `build()` + the single [injectRuntimeContext] call. Shared by
     * [produceFrame] and [produceFrameValidated] (the validating entry must run the stages
     * *before* committing history so it can validate against the PREVIOUS frame's chain).
     */
    private fun buildAndInject(pose: PoseBuilder, context: PoseContext): SkeletonPose {
        val built = pose.build(context)
        // W1b — stamp the engine-owned support model onto the pose so the Finalizer can derive
        // support planes for EVERY pose from the environment (ground + props) and the declared
        // support contacts, instead of any per-pose hardcoded plane. Declarative: the pose only
        // says WHICH points rest on WHAT; the engine decides the geometry. No-op for a pose with
        // no environment/support declared (byte-identical geometry elsewhere).
        // R8: deriving the support-point set from Contact Declarations counts as injection-time
        // derivation (§4.1 Group B producer text), so it feeds the single injection call below.
        val derivedSupportedPoints = HashSet<SupportPoint>()
        for (contact in pose.metadata.support.contacts) {
            derivedSupportedPoints.add(contact.point)
        }
        injectRuntimeContext(built, pose.metadata.environment, derivedSupportedPoints)
        return built
    }

    /**
     * R8 — Runtime Context Injection single-point (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md,
     * Phase 1; RFC_RUNTIME_SKELETON_ARCHITECTURE §5 R8 / §3.1 Frame Context constituency).
     *
     * The ONLY place the pipeline stamps [SkeletonPose.environment] and
     * [SkeletonPose.supportedPoints]. Both [produceFrame] overloads call this exactly once,
     * immediately before [runStages]; no other pipeline method writes these carriers. The
     * renderer overload forwards its caller-supplied support model verbatim; the builder
     * overload derives it from `pose.metadata` at the call site (Contact Declaration
     * derivation is injection-time derivation, kept inside this boundary).
     *
     * Behavior-preserving extraction: each overload previously performed exactly these writes
     * inline, at the same position in the frame sequence.
     */
    private fun injectRuntimeContext(
        pose: SkeletonPose,
        environment: EnvironmentDefinition,
        supportedPoints: Set<SupportPoint>
    ) {
        pose.environment = environment
        pose.supportedPoints.clear()
        pose.supportedPoints.addAll(supportedPoints)
    }

    /**
     * Ordered stage chain shared by both entry points: Solver (contacts only) → Finalizer (FK +
     * flatten). This is the single place the Solver and Finalizer are invoked, in fixed order
     * (RFC_ENGINE_PIPELINE §8.1 — the pipeline is the sole caller of both, preventing re-entrancy).
     */
    private fun runStages(pose: SkeletonPose): SkeletonPose {
        // P8 (§6 Phase 4 / §3.3) — window-start evidence: the pipeline is opening a NEW
        // execution window over this carrier (R11 transfer), so any publication marker the
        // finalizer holds from a previous frame of the SAME carrier (the reused jointsBuffer
        // path — sequential playback produces one carrier per builder) is a legitimate
        // re-arm, not a re-entry violation. Debug-only, same placement as every other
        // boundary instrument in this function.
        if (BuildConfig.DEBUG) finalizer.beginPublishWindow()
        // R8 enforcement (debug builds only) — snapshot the freshly injected context, then
        // re-compare after every stage. Carrier-level post-injection writes by a stage fail
        // fast with IllegalStateException("R8 violation: …"). This is characterization/
        // enforcement of carrier-level mutation, not complete mechanical R8 enforcement:
        // deep payload mutation and content-identical rewrites are outside its reach, and
        // release builds compile the mechanism out entirely (no snapshot, no compare).
        val r8 = if (BuildConfig.DEBUG) RuntimeContextSnapshot.of(pose) else null
        // Phase 6 (R2) — root-authority boundary instrumentation (debug builds only; RFC §5 R2,
        // plan §P6). Capture point A: this is the window-entry state — build has returned and
        // Runtime Context Injection is done, so the pelvis transform holds the last lawful
        // authoring write. `r2ProtectedRoot` is the snapshot later checks compare against; it
        // switches ONLY at the explicit solve site below (capture B) — never inferred from the
        // retained Frame History (P5 pitfall 1): a frame that legitimately skips the solve has
        // no authorized root mover, so A must survive to publish untouched.
        var r2ProtectedRoot =
            if (BuildConfig.DEBUG) PhaseBoundaryAsserts.captureRoot(pose, "capture A (post-build)") else null
        // Phase 7 (R3) — settled-contact boundary instrumentation (debug builds only; RFC §5
        // R3/R7, §3.2, plan §P7). `r3SettledContacts` holds the post-solve WORLD-position
        // snapshot of the frame's settled-contact end-effectors; it arms ONLY at the explicit
        // solve site below, and only when this solve actually PRODUCED the Settlement Result
        // it references (identity-vs-prior evidence — never inferred from retained history or
        // root state, per the P5/P6 armed-state pattern). A frame that skips the solve, or
        // whose solve early-returned above the populate point, arms nothing: no settle means
        // no settled geometry to protect.
        var r3SettledContacts: PhaseBoundaryAsserts.SettledContactSnapshot? = null
        // B1 (IkStage extraction) — the pipeline-owned limb stage consumes the §1.1 `limbTargets`
        // carrier and re-derives each limb's local positions on the engine-owned node tree.
        // P12 (state 3): this gate is the CONFIGURATION SELECTOR, not a legacy no-op — with the
        // deployed default `true` the stage is the sole Active Limb Solver and the authoring bakes'
        // realization branch is gated off (§12.7a). It runs
        // before the ConstraintSolver so contact limbs are re-baked from its targets ahead of the
        // root-repositioning pass, and before the Finalizer's FK.
        IkStage.apply(pose, definition)
        r8?.assertUnchanged(pose, "after IkStage")
        // Phase 6 Check 1 — Phase 1 is not a root mover: the pelvis must be bit-identical to A
        // (covers the engine-side-limb-stage-active configuration; the limb stage writes only
        // middle/end joint locals, so a pelvis change here means a regression).
        if (BuildConfig.DEBUG) {
            r2ProtectedRoot?.assertUnchanged(pose, "Phase 1 (IkStage limb solve window)")
        }
        // Stage 3 (ConstraintSolver) — posture/contact settling. Runs for contact poses (M3) and
        // for any pose that names a non-CUSTOM posture intent so the engine owns the coarse root
        // height. A CUSTOM, contact-less production pose is still a pure no-op. (Phase B collapsed
        // PIPELINE_ACTIVE and SOLVER_OWNS_POSTURE to their true branch: the pipeline is always live
        // and posture ownership is always on.)
        val postureDriven = pose.postureIntent.kind != PostureIntent.Kind.CUSTOM
        // Phase 5 (R10) — the Frame History smoothing input is supplied to the solve as an
        // explicit current-frame argument; the solver derives it from no other source (RFC
        // §5 R10: behavior is a function of current-frame inputs plus supplied history, never
        // of object identity). Debug `check` distinguishes the legitimate "first frame of the
        // history window" from "forgot to wire": the trip-wire is armed only when the frame
        // about to become `previous` actually RAN the solver over a hierarchy with a pelvis
        // node (a frame that skipped the solve — contact-less CUSTOM — legitimately has no
        // smoothing root to capture, exactly like the deleted cache which was written only
        // by solving frames). If a future refactor drops the capture below, the next frame's
        // solve throws here instead of silently losing smoothing.
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
            // Phase 7 (R3) — arming evidence read BEFORE the solve: the ConstraintSolver fixes
            // a NEW Settlement Result instance on every complete settle, so `!== prior` after
            // the call is per-frame production evidence at the execution site (P6 armed-state
            // pattern). A solve that early-returns above the populate point leaves a stale
            // carrier slot — identity is then equal and nothing arms; the reference is never
            // inferred from retained history or root state.
            val priorSettlement = if (BuildConfig.DEBUG) pose.settlementResult else null
            ConstraintSolver.solve(pose, definition, previousSmoothingRoot)
            // Phase 5 (R10) — capture THIS frame's settled root BY VALUE as the next frame's
            // smoothing history, immediately after the solve (the sole root mover, R2). Read
            // from the pelvis node's local position — the exact value space the deleted solver
            // cache persisted (the root space the solver seeds/eases in), so sequential-
            // playback smoothing numerics are unchanged. Node buffers are reused across
            // builds, so the reference must never be retained. A pelvis-less solved tree
            // captures nothing (the old cache could not have been written either — `solve`
            // early-returns before settling without a pelvis).
            val settledRoot = findPelvisNode(pose.roots)?.localPosition
            if (settledRoot != null) {
                previousSmoothingRoot = Vector3(settledRoot.x, settledRoot.y, settledRoot.z)
            }
            if (BuildConfig.DEBUG) {
                lastFrameArmedSmoothingCapture = settledRoot != null
                // Phase 6 capture point B — the solve ran (the authorized root mover). The
                // protected reference for the remaining windows switches to the settled root
                // NOW, at the explicit branch site (the P5 armed-state pattern: the decision
                // is made by instrumentation at the execution site, never inferred from
                // retained history). A pelvis-less solved tree keeps A: no settled root was
                // written, so the authored root remains the state that must survive.
                r2ProtectedRoot = PhaseBoundaryAsserts.captureRoot(pose, "capture B (post-settlement)")
                // Phase 7 capture — arm the settled-contact reference ONLY when THIS solve
                // produced the Settlement Result it lists (new instance vs the pre-call slot).
                // The snapshot reads the reference set from the Settlement Result (the sole
                // canonical producer output) and the world positions the solver's final FK +
                // flatten just wrote into the carrier.
                if (pose.settlementResult !== priorSettlement) {
                    r3SettledContacts =
                        PhaseBoundaryAsserts.captureSettledContacts(pose, "post-settlement (Phase 2 exit)")
                }
            }
        } else if (BuildConfig.DEBUG) {
            lastFrameArmedSmoothingCapture = false
            // Phase 6 branch handling — the solve was legitimately skipped (contact-less
            // CUSTOM), so no authorized mover exists after build: Checks 1/2 compare against
            // A directly (plan §P6). `r2ProtectedRoot` intentionally still holds A here.
        }
        r8?.assertUnchanged(pose, "after ConstraintSolver")
        // Stage 4+ (Finalizer) — world↔local conversion, extremity derivation, chest-frame
        // reconstruction, FK flatten. The Finalizer no longer calls the Solver itself (M2).
        val finalized = finalizer.finalize(pose)
        // Checked on the INPUT pose: [finalize] copies into its own output buffer, so the R8
        // question here is whether any stage wrote the injected context on the pose that
        // entered the chain — not what the output copy carries.
        r8?.assertUnchanged(pose, "after Finalizer")
        // Phase 6 Check 2 — Phases 3/4 (Finalize + publish) are not root movers: the published
        // frame's pelvis must be bit-identical to the protected reference (B on solved frames,
        // A on legitimately skipped frames). Checked on the FINALIZED pose — its `roots` are the
        // same node tree the Finalizer published from, so this asserts on the state consumers
        // actually receive (RFC §6 Phase 4: publication completes with these transforms).
        if (BuildConfig.DEBUG) {
            r2ProtectedRoot?.assertUnchanged(
                finalized,
                "Phase 3/4 (Finalizer + publish window; solver " +
                    (if (pose.hasContacts() || postureDriven) "ran" else "was skipped") +
                    " this frame)"
            )
            // Phase 7 Check — R3 Settled-Contact Guarantee (RFC §5 R3/R7, plan §P7): every
            // settled contact end-effector must still sit at its post-solve world position
            // once Phase 3 finalization has completed. Checked on the FINALIZED pose — the
            // tree consumers receive, so this detects a settled contact displaced by ANY
            // finalization operation (frame-wide walk over the Settlement Result's list; per
            // plan, whole-phase checking first — finer instrumentation only on triage).
            r3SettledContacts?.assertUnchanged(
                finalized,
                "Phase 3 finalization (post-settlement → completion of " +
                    "SkeletonPoseFinalizer.finalize)"
            )
        }
        // Phase 4 (R5) / P12 §12.7b — strengthened runtime enforcement. Two pieces of execution
        // evidence from the registered realization sites:
        //  - WINDOW count (the first block below): the counter covers BOTH registered realization
        //    sites (authoring bake branch + engine stage), so
        //    · flag-ON (stage config): the stage window must have executed exactly once (it counts
        //      at entry); any leaked second solver (a bake that realized while the stage was on, a
        //      duplicated stage call, or a hidden third path) raises the count and the check fires
        //      even when the two implementations produce byte-identical output;
        //    · flag-OFF (authoring config): the bake's per-build realization counts once (the
        //      authoring window is re-armed through the same registration path the sites call); the
        //      `count == 0` case survives ONLY for the legacy skipped-window reading (no registered
        //      realization this frame) — in particular a zero-limb/custom pose, which legitimately
        //      opens no window while the stage is disabled. **In the deployed configuration
        //      (state 3) the disjunct is unreachable** — `IkStage.apply` counts its window at entry,
        //      so a frame that reaches the pipeline always reports exactly one window, and the
        //      enforced formula there is exactly `count == 1` (§12.7b, §12.10 criterion 4).
        //  - PER-EXECUTION evidence (WP-G): the window count has a resolution limit — two
        //    realizations of ONE limb inside ONE window (a duplicated Limb Target handed to the
        //    stage, or a second `bakeIkLimb` call for the same joint in one build) leave the same
        //    window count and a byte-identical frame. `limbDuplicateRealizations` counts those
        //    events, and the second check rejects the frame on that number alone: the violation is
        //    EXECUTION evidence, never a comparison of world positions, floats, stamps or hashes.
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
     * Phase 5 (R10) — the SOLE rotation site of the Frame History pose chain, called by every
     * production entry point after the frame is produced (the validating path commits only
     * after validation consumed the PREVIOUS chain, preserving dynamics-rule ordering).
     * [previous]/[prePrevious] hold snapshots of the finalized frame per the §4.5 rolling
     * two-frame lifetime. Behavior change vs pre-P5: the chain now also advances on the two
     * non-validating [produceFrame] overloads (previously it advanced only on the validating
     * path, while smoothing secretly lived in the solver's identity cache). The smoothing root
     * capture lives in [runStages] (next to the solve it follows); both are pipeline-owned
     * Frame History state.
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
     * [produceFrame] plus the mandatory validation stage. Requires a validator to have been
     * supplied at construction. The dynamics rules read the previous/pre-previous Frame History
     * before this frame is committed to it; call [resetHistory] when the animation
     * restarts/seeks.
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
        // Phase 5 (R10) — history rotation folded into the shared [commitFrameHistory]; the
        // validate call above still reads the PREVIOUS chain before this frame commits.
        commitFrameHistory(finalized)
        return ValidatedFrame(finalized, report)
    }

    /** Clears the dynamics history (call on animation restart/seek so velocity rules don't spike). */
    fun resetHistory() {
        previous = null
        prePrevious = null
    }
}
