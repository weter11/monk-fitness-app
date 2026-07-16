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
 */
object EngineFlags {
    var SOLVER_OWNS_POSTURE: Boolean = false

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE
    )
}
