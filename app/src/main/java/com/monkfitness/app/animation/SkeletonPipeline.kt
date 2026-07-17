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
 * **M2 scope (this file):** `PIPELINE_ACTIVE` now defaults to **true** and `produceFrame` drives
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
 * **Legacy bypass:** with [EngineFlags.PIPELINE_ACTIVE] `false`, `produceFrame` falls back to the
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
        // Coherence invariant (RFC_ENGINE_PIPELINE §5.7): a live pipeline requires finalizer-owned
        // conversion — but only once that flag is itself enabled (M4). M2 keeps
        // FINALIZER_OWNS_CONVERSION=false by design to stay byte-identical to the pre-M2 baseline,
        // so the constructor fails fast only when the flag is turned on without the invariant met.
        require(!EngineFlags.FINALIZER_OWNS_CONVERSION || EngineFlags.PIPELINE_ACTIVE) {
            "Incoherent flags: FINALIZER_OWNS_CONVERSION requires PIPELINE_ACTIVE (RFC_ENGINE_PIPELINE §5.7)."
        }
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
     * Single entry point. When [EngineFlags.PIPELINE_ACTIVE] is `true` (the M2 default) this drives
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
        // Stage 3 (ConstraintSolver) — posture/contact settling. No-op for the common
        // contact-less production pose, so those are untouched. Guarded exactly as the former
        // Finalizer call was (roots present + contacts present).
        if (EngineFlags.PIPELINE_ACTIVE &&
            pose.roots.isNotEmpty() && pose.hasContacts()) {
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
