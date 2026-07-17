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
        // Coherence invariant (RFC_ENGINE_PIPELINE §5.7): a live pipeline requires finalizer-owned
        // conversion. Vacuously satisfied in M0 (PIPELINE_ACTIVE=false); fail fast at construction
        // rather than at frame time if a future misconfiguration violates it.
        require(!EngineFlags.PIPELINE_ACTIVE || EngineFlags.FINALIZER_OWNS_CONVERSION) {
            "Incoherent flags: PIPELINE_ACTIVE requires FINALIZER_OWNS_CONVERSION (RFC_ENGINE_PIPELINE §5.7)."
        }
    }

    private val finalizer = SkeletonPoseFinalizer(definition)

    // Per-frame history for the dynamics validator rules (velocity/acceleration/discontinuity).
    // Snapshots so a later frame's finalize (which reuses the finalizer's output buffer) cannot
    // alias the previous frame's data.
    private var previous: SkeletonPose? = null
    private var prePrevious: SkeletonPose? = null

    /**
     * Single entry point. In M0 (legacy mode) this is `pose.build(context)` →
     * `finalizer.finalize(...)`, byte-identical to invoking the two directly. Returns a
     * [PipelineResult] with a `null` report (use [produceFrameValidated] for validation).
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        requireLegacyMode()
        val built = pose.build(context)
        val finalized = finalizer.finalize(built)
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

    private fun requireLegacyMode() {
        check(!EngineFlags.PIPELINE_ACTIVE) {
            "SkeletonPipeline active mode is not implemented until M2. " +
                "PIPELINE_ACTIVE must remain false in M0 (RFC_GAP_CLOSURE M0)."
        }
    }
}
