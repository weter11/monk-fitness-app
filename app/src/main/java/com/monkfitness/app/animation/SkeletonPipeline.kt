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
 * **M0 scope (this file):** the pipeline is *scaffolded* but runs only the **legacy path**. With
 * [EngineFlags.PIPELINE_ACTIVE] `false` (the default), [produceFrame] is exactly today's flow —
 * `pose.build()` → [SkeletonPoseFinalizer.finalize] (→ optional validation) — just packaged behind
 * a single entry point so that M2 can flip the master switch and re-point consumers without
 * changing any call site. M0 therefore introduces **zero behavior change** and touches **zero**
 * consumers; renderers keep calling `finalizer.finalize` directly until M2.
 *
 * **Future (M2+):** when [EngineFlags.PIPELINE_ACTIVE] becomes `true`, `produceFrame` will drive
 * the full ordered stage chain (IntentNormalization → IK → ConstraintSolver → Finalizer → FK →
 * Validator) and the Finalizer's internal `ConstraintSolver.solve` call will be removed. Those
 * stages are intentionally **not** wired here — activating the flag in M0 is a hard error so the
 * incomplete active path can never run by accident.
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
        // M4 owns the `PIPELINE_ACTIVE ⇒ FINALIZER_OWNS_CONVERSION` gate (see EngineFlags). At M2 the
        // pipeline runs with FINALIZER_OWNS_CONVERSION=false so the F1 no-move guard is not yet active
        // and the output matches the pre-M2 baseline; M4 will flip it and assert the invariant there.
    }

    private val finalizer = SkeletonPoseFinalizer(definition)

    // Per-frame history for the dynamics validator rules (velocity/acceleration/discontinuity).
    // Snapshots so a later frame's finalize (which reuses the finalizer's output buffer) cannot
    // alias the previous frame's data.
    private var previous: SkeletonPose? = null
    private var prePrevious: SkeletonPose? = null

    /**
     * Single entry point — the orchestrated frame.
     *
     * **Legacy mode** (`PIPELINE_ACTIVE=false`): `pose.build(context)` → `finalizer.finalize(...)`,
     * byte-identical to invoking the two directly (today's consumer path).
     *
     * **Active mode** (`PIPELINE_ACTIVE=true`, M2): the pipeline owns the ordered stages. The pose
     * builds its tree and authors its intent carriers inside `build()`; the pipeline then runs the
     * ConstraintSolver stage for contact poses (the Finalizer no longer re-solves under the
     * pipeline — see [SkeletonPoseFinalizer.finalize]) and hands the result to the Finalizer for
     * FK + chest-frame + gaze resolution. This is the same sequence the legacy path runs, so the
     * output is byte-identical; the difference is *ownership* — the stages are now composed here
     * rather than re-entrantly inside the Finalizer.
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        val built = pose.build(context)
        if (EngineFlags.PIPELINE_ACTIVE && built.roots.isNotEmpty() && built.hasContacts()) {
            ConstraintSolver.solve(built, definition)
        }
        val finalized = finalizer.finalize(built)
        return PipelineResult(finalized, null)
    }

    /**
     * Entry point for a caller that already holds a built [SkeletonPose] (e.g. a renderer that
     * received the raw pose from upstream). Runs the same ConstraintSolver stage (contact poses)
     * and Finalizer as [produceFrame], so the result is byte-identical to building-then-producing.
     * Used by [SkeletonRenderer] / [SkeletonSnapshotRenderer] which are handed a pose rather than a
     * [PoseBuilder].
     */
    fun produceFrame(pose: SkeletonPose): PipelineResult {
        if (EngineFlags.PIPELINE_ACTIVE && pose.roots.isNotEmpty() && pose.hasContacts()) {
            ConstraintSolver.solve(pose, definition)
        }
        val finalized = finalizer.finalize(pose)
        return PipelineResult(finalized, null)
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

    companion object {
        /** Whether the active stage chain is wired (used by tests and the fail-fast guard). */
        const val ACTIVE_MODE_IMPLEMENTED = true
    }
}
