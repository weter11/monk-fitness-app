package com.monkfitness.app.animation

/**
 * Architecture v2 — the Pose→Intent→IK→Solver→Finalizer→FK→Validator orchestrator (Gap 1).
 *
 * Milestone status (see docs/RFC_ENGINE_PIPELINE.md §6 phases):
 *   - M0 (this commit): scaffold the orchestrator with [PIPELINE_ACTIVE] = false. [produceFrame]
 *     runs the EXACT legacy path — [PoseBuilder.build] (which still builds the full node tree
 *     today) → [SkeletonPoseFinalizer.finalize] → optional [ExerciseValidator.validate]. No
 *     consumer calls this yet; the classical renderers/launchers keep calling `build()` +
 *     `finalize()` directly. Zero behavior change; trivially revertible.
 *   - M2 (follow-up): flip [PIPELINE_ACTIVE] = true and move tree construction into the IK
 *     stage so [PoseBuilder.build] becomes intent-only. That milestone migrates every pose and
 *     is a separate PR gated on a green compile + B6 baseline test.
 *
 * Ownership (RFC §5.1): `SkeletonPipeline` owns the stage *instances* and *ordering*. It does
 * NOT own the [SkeletonPose] data — the pose/definition remain the source of truth, as today.
 * Thread-safety (RFC §3, Issue 5): the pipeline is a thin facade over stateless stage objects;
 * a caller must not re-enter [produceFrame] for the same output concurrently. The shared
 * [SkeletonDefinition] is immutable at frame time (read-only via [EngineFlags]).
 */
class SkeletonPipeline(
    private val definition: SkeletonDefinition,
    private val finalizer: SkeletonPoseFinalizer = SkeletonPoseFinalizer(definition),
    private val validator: ExerciseValidator? = null
) {
    /**
     * Single entry point (legacy mode, M0).
     *
     * Replicates the current consumer contract exactly:
     *   pose.build(context) → SkeletonPose (full tree, as authored today)
     *   finalizer.finalize(pose) → §1.2 nodes written
     *   (validation, if a validator is configured, is performed by [produceFrameValidated])
     *
     * @return the finalized [SkeletonPose]. Mirrors what `SkeletonRenderer`/`SkeletonSnapshotRenderer`
     *         obtain after their own `finalize()` call.
     */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult {
        val built = pose.build(context)
        val finalized = finalizer.finalize(built)
        // Legacy mode: no validator is wired, so report is null (see RFC §5.1 — the validated
        // variant is [produceFrameValidated]).
        return PipelineResult(finalized, null)
    }

    /**
     * [produceFrame] + mandatory validation stage.
     *
     * The validation inputs (camera / environment / frame size / history) are not part of
     * [PoseContext] today, so they are passed explicitly — matching [ExerciseValidator.validate].
     * When [validator] is null this throws, because "validated" was explicitly requested; callers
     * that do not want validation should use [produceFrame].
     */
    fun produceFrameValidated(
        pose: PoseBuilder,
        context: PoseContext,
        camera: Camera,
        environment: EnvironmentDefinition,
        width: Float,
        height: Float,
        previousPose: SkeletonPose? = null,
        prePreviousPose: SkeletonPose? = null,
        deltaTime: Float = 0f
    ): ValidatedFrame {
        val validator = requireNotNull(this.validator) {
            "produceFrameValidated requires a non-null validator; use produceFrame for unvalidated output"
        }
        val finalized = produceFrame(pose, context)
        val report = validator.validate(
            pose = finalized,
            definition = definition,
            environment = environment,
            camera = camera,
            width = width,
            height = height,
            previousPose = previousPose,
            prePreviousPose = prePreviousPose,
            deltaTime = deltaTime
        )
        return ValidatedFrame(finalized, report)
    }

    /** Exposes the finalize step in isolation (testing / debug replay). */
    fun runFinalizeOnly(pose: SkeletonPose): SkeletonPose = finalizer.finalize(pose)
}

/**
 * Result of a non-validated pipeline run. Holds the finalized pose; the report is null because
 * no [ExerciseValidator] was invoked.
 */
data class PipelineResult(
    val pose: SkeletonPose,
    val report: ValidationReport? = null
)

/**
 * Result of a validated pipeline run.
 */
data class ValidatedFrame(
    val pose: SkeletonPose,
    val report: ValidationReport
)
