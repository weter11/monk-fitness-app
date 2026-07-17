package com.monkfitness.app.animation

/**
 * Architecture v2 — global feature flags for the phased migration (see
 * `docs/IMPLEMENTATION_BRIDGE.md` §B3). Each flag defaults to **false** so the legacy
 * code path is preserved until the corresponding phase is verified against the baseline
 * (B6 test mapping) and the global flip is committed. Phases keep the legacy branch during
 * migration for rollback, then delete it in a follow-up once green.
 *
 * - [SOLVER_OWNS_POSTURE] — Phase 2: the [ConstraintSolver] seeds the root/pelvis `Y` from
 *   the pose's declared [PostureIntent] and resolves contact conflicts via `contactPrecedence`,
 *   instead of the pose hand-computing `pelvisY`/`pelvisX`. When false, the solver behaves
 *   exactly as before (relaxation + CCD on whatever root the pose authored).
 * - [FINALIZER_OWNS_CONVERSION] — Phase 3: the [SkeletonPoseFinalizer] is the *exclusive* writer
 *   of local transforms (world↔local frame conversion, `preConvertPoles`, `toLocalDirection`
 *   bakes, extremity derivation, `reconstructChestFrame`) and enforces the read-only chest-frame
 *   no-move guard (F1): a Solver-settled contact end-effector must never move during finalization.
 *   When false the legacy finalize path runs unchanged.
 */
object EngineFlags {
    var SOLVER_OWNS_POSTURE: Boolean = false
    var FINALIZER_OWNS_CONVERSION: Boolean = false

    /**
     * M0/M2 gate for the [SkeletonPipeline] orchestrator (Gap 1).
     * - false (legacy / M0): the pipeline's [produceFrame] is a thin facade over today's path
     *   (`PoseBuilder.build` → `SkeletonPoseFinalizer.finalize`); no consumer uses the pipeline yet.
     * - true (M2): [produceFrame] moves tree construction into the IK stage and [PoseBuilder.build]
     *   becomes intent-only (§1.1). Flipped in the M2 milestone, gated on a green compile + B6
     *   baseline. Defaults to false so the legacy path is preserved until then.
     */
    var PIPELINE_ACTIVE: Boolean = false

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE,
        "FINALIZER_OWNS_CONVERSION" to FINALIZER_OWNS_CONVERSION,
        "PIPELINE_ACTIVE" to PIPELINE_ACTIVE
    )
}
