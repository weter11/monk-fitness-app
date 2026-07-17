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
     * Phase 7 (Gap 7) — when true, the Finalizer resolves neck/head from the pose-declared
     * `headTarget` intent (gaze-as-target) instead of the legacy direction-based [buildHead].
     * Defaults to **false** so the legacy gaze path is preserved (byte-identical head) until the
     * intent pipeline + Finalizer resolver are verified against the baseline. The pose still
     * records `headTarget` regardless of this flag; the flag only controls who *consumes* it.
     */
    /**
     * Phase 7 (Gap 7) — when true, the Finalizer resolves neck/head from the pose-declared
     * `headTarget` intent (gaze-as-target) instead of the legacy direction-based `buildHead`.
     * Flipped to **true** once the Finalizer resolver (`SkeletonPoseFinalizer.resolveHeadTarget`)
     * and the pose-side `buildGaze` declaration landed; the resolver reproduces the identical gaze
     * direction the pose authored (the pose records the synthetic target as neckWorld + gazeDir*100,
     * so resolving it yields the same direction), so the rendered head is byte-identical to the
     * legacy path. Poses that declare no `headTarget` (or run before the migration) keep the legacy
     * `buildHead` direction path untouched.
     */
    var HEAD_TARGET_ENABLED: Boolean = true

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE,
        "FINALIZER_OWNS_CONVERSION" to FINALIZER_OWNS_CONVERSION,
        "HEAD_TARGET_ENABLED" to HEAD_TARGET_ENABLED
    )
}
