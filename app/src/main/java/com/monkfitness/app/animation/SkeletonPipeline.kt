package com.monkfitness.app.animation

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
 * pre-M0 flow (`pose.build()` → `finalizer.finalize()` with *no* pipeline-owned Solver call) so the
 * old path remains reachable for rollback and the M0 byte-identity guarantee still holds.
 *
 * **Ownership & lifetime (RFC_ENGINE_PIPELINE §Issue 5):** a `SkeletonPipeline` owns its stage
 * *instances* (currently the [SkeletonPoseFinalizer] and an optional [ExerciseValidator]) and the
 * per-frame previous/pre-previous pose history used by the dynamics validator rules. It is a
 * long-lived, per-engine/per-definition object — **not** created per frame or per pose. It is not
 * thread-safe (one instance per render loop), matching the existing single-threaded finalizer.
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
    private var previous: SkeletonPose? = null
    private var prePrevious: SkeletonPose? = null

    /**
     * Single entry point for an already-built pose (renderer path). Runs the ordered stage chain
     * (Solver → Finalizer) on [builtPose] and returns the finalized frame. Used by
     * [SkeletonRenderer] / [SkeletonSnapshotRenderer] which receive a `SkeletonPose` that has
     * already been `build()`-constructed. The Solver is skipped when the pose registered no contacts
     * (the common production case), so non-contact poses are untouched.
     */
    fun produceFrame(builtPose: SkeletonPose): PipelineResult {
        val finalized = runStages(builtPose)
        return PipelineResult(finalized, null)
    }

    /**
     * Single entry point. The pipeline is unconditionally live (Phase B collapsed the flag), so this always drives
     * the full ordered stage pipeline: `pose.build(context)` → `ConstraintSolver.solve` (contacts
     * only) → `finalizer.finalize(...)` → FK flatten. When `false` (legacy bypass) it is exactly the
     * pre-M0 flow (`build` → `finalize`, no pipeline-owned Solver) — byte-identical to invoking the
     * two directly. Returns a [PipelineResult] with a `null` report (use [produceFrameValidated] for
     * validation).
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        val built = pose.build(context)
        return PipelineResult(runStages(built), null)
    }

    /**
     * Ordered stage chain shared by both entry points: Solver (contacts only) → Finalizer (FK +
     * flatten). This is the single place the Solver and Finalizer are invoked, in fixed order
     * (RFC_ENGINE_PIPELINE §8.1 — the pipeline is the sole caller of both, preventing re-entrancy).
     */
    private fun runStages(pose: SkeletonPose): SkeletonPose {
        // B1 (IkStage extraction) — the pipeline-owned limb stage consumes the §1.1 `limbTargets`
        // carrier and re-derives each limb's local positions on the engine-owned node tree.
        // (EngineFlags.IK_STAGE_ACTIVE was excluded from Phase B — its flag is a future additive
        // decision, not legacy removal — so the IkStage no-op gate is preserved as-is.) It runs
        // before the ConstraintSolver so contact limbs are re-baked from their targets ahead of the
        // root-repositioning pass, and before the Finalizer's FK.
        IkStage.apply(pose, definition)
        // Stage 3 (ConstraintSolver) — posture/contact settling. Runs for contact poses (M3) and
        // for any pose that names a non-CUSTOM posture intent so the engine owns the coarse root
        // height. A CUSTOM, contact-less production pose is still a pure no-op. (Phase B collapsed
        // PIPELINE_ACTIVE and SOLVER_OWNS_POSTURE to their true branch: the pipeline is always live
        // and posture ownership is always on.)
        val postureDriven = pose.postureIntent.kind != PostureIntent.Kind.CUSTOM
        if (pose.roots.isNotEmpty() && (pose.hasContacts() || postureDriven)) {
            ConstraintSolver.solve(pose, definition)
        }
        // Stage 4+ (Finalizer) — world↔local conversion, extremity derivation, chest-frame
        // reconstruction, FK flatten. The Finalizer no longer calls the Solver itself (M2).
        return finalizer.finalize(pose)
    }

    /**
     * [produceFrame] plus the mandatory validation stage. Requires a validator to have been
     * supplied at construction. Maintains the previous/pre-previous history so the dynamics rules
     * see a coherent frame sequence; call [resetHistory] when the animation restarts/seeks.
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
        val finalized = produceFrame(pose, context).pose
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
        prePrevious = previous
        previous = SkeletonPose().apply { copyFrom(finalized) }
        return ValidatedFrame(finalized, report)
    }

    /** Clears the dynamics history (call on animation restart/seek so velocity rules don't spike). */
    fun resetHistory() {
        previous = null
        prePrevious = null
    }
}
