package com.monkfitness.app.animation

/**
 * Architecture v2 — global feature flags for the phased migration (see
 * `docs/IMPLEMENTATION_BRIDGE.md` §B3).
 *
 * Phase B (RFC_ENGINE_CLEANUP_PLAN) collapsed the legacy `=false` rollback branches
 * (PIPELINE_ACTIVE, SOLVER_OWNS_POSTURE, FINALIZER_OWNS_CONVERSION, FINALIZER_CONSUMES_INTENT)
 * to their true branch and deleted them — the `=false` branches were proven byte-identical no-ops
 * by their gate tests, so removing them changed no production behaviour. Only `IK_STAGE_ACTIVE`
 * remains: it is explicitly EXCLUDED from Phase B (it is the current production limb path's future
 * additive decision, not legacy removal).
 *
 */
object EngineFlags {
    // Phase B: PIPELINE_ACTIVE collapsed to its true branch and removed.

    // Phase B: SOLVER_OWNS_POSTURE collapsed to its true branch and removed.
    // Phase B: FINALIZER_OWNS_CONVERSION collapsed to its true branch and removed.

    // Phase B: FINALIZER_CONSUMES_INTENT collapsed to its true branch and removed.

    // Phase 7 (Gap 7) — COMPLETE. The gaze-as-`headTarget` resolver in the Finalizer is now the
    // sole head/neck writer; the legacy direction-based `buildHead` fallback was removed after
    // `HeadTargetBaselineTest` proved the resolver byte-identical (maxDeviation ~6e-5). The former
    // `HEAD_TARGET_ENABLED` gate is therefore gone (it controlled a branch that no longer exists);
    // gaze resolution is unconditional. No flag replaces it.

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "IK_STAGE_ACTIVE" to IK_STAGE_ACTIVE
    )
}
