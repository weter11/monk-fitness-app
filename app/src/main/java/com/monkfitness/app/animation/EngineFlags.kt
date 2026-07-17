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

    // Phase 7 (Gap 7) — COMPLETE. The gaze-as-`headTarget` resolver in the Finalizer is now the
    // sole head/neck writer; the legacy direction-based `buildHead` fallback was removed after
    // `HeadTargetBaselineTest` proved the resolver byte-identical (maxDeviation ~6e-5). The former
    // `HEAD_TARGET_ENABLED` gate is therefore gone (it controlled a branch that no longer exists);
    // gaze resolution is unconditional. No flag replaces it.

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE,
        "FINALIZER_OWNS_CONVERSION" to FINALIZER_OWNS_CONVERSION
    )
}
