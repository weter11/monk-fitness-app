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
    /**
     * Master switch (M0/M2) — when **false** (M0) the engine runs the legacy path
     * (`pose.build()` → `SkeletonPoseFinalizer.finalize()` → optional validation). When **true**
     * (M2, the default) `produceFrame` drives the full ordered stage pipeline (build →
     * ConstraintSolver stage for contact poses → Finalizer FK/chest/gaze) and the Finalizer no
     * longer re-solves under the pipeline.
     *
     * **Milestone ownership (do not over-couple):** the spec's `PIPELINE_ACTIVE ⇒
     * FINALIZER_OWNS_CONVERSION` coherence invariant belongs to **M4** (Finalizer authority), not
     * M2. At M2 `FINALIZER_OWNS_CONVERSION` stays `false` so the read-only chest-frame no-move guard
     * (F1) is not yet active and the output is byte-identical to the pre-M2 baseline. M4 flips it
     * `true` and activates F1. [SkeletonPipeline] therefore does NOT assert the M4 invariant at
     * construction — that would wrongly make M2 impossible.
     */
    var PIPELINE_ACTIVE: Boolean = true

    var SOLVER_OWNS_POSTURE: Boolean = false
    var FINALIZER_OWNS_CONVERSION: Boolean = false

    // Phase 7 (Gap 7) — COMPLETE. The gaze-as-`headTarget` resolver in the Finalizer is now the
    // sole head/neck writer; the legacy direction-based `buildHead` fallback was removed after
    // `HeadTargetBaselineTest` proved the resolver byte-identical (maxDeviation ~6e-5). The former
    // `HEAD_TARGET_ENABLED` gate is therefore gone (it controlled a branch that no longer exists);
    // gaze resolution is unconditional. No flag replaces it.

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "PIPELINE_ACTIVE" to PIPELINE_ACTIVE,
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE,
        "FINALIZER_OWNS_CONVERSION" to FINALIZER_OWNS_CONVERSION
    )
}
