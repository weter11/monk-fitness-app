package com.monkfitness.app.animation

/**
 * Architecture v2 — global feature flags for the phased migration (see
 * `docs/IMPLEMENTATION_BRIDGE.md` §B3). Each flag defaults to **false** so the legacy
 * code path is preserved until the corresponding phase is verified against the baseline
 * (B6 test mapping) and the global flip is committed. Phases keep the legacy branch during
 * migration for rollback, then delete it in a follow-up once green.
 *
 * - [SOLVER_OWNS_POSTURE] — Phase 2 / **M3 (default-on)**: the [ConstraintSolver] seeds the
 *   root/pelvis `Y` from the pose's declared [PostureIntent] and resolves contact conflicts via
 *   `contactPrecedence`, plus applies inter-frame temporal smoothing (F9), instead of the pose
 *   hand-computing `pelvisY`/`pelvisX`. When false, the solver behaves exactly as before
 *   (relaxation + CCD on whatever root the pose authored). Only fires for poses that register an
 *   engine [ContactSpec] (via `bakeIkLimb(... contact=)`); the whole solver is a no-op for
 *   contact-less poses, so every production pose (none register engine contacts today) is
 *   byte-identical regardless of this flag.
 * - [FINALIZER_OWNS_CONVERSION] — Phase 3 / **M4 (default-on)**: the [SkeletonPoseFinalizer] is the
 *   *exclusive* writer of local transforms (world↔local frame conversion,
 *   read-only chest-frame no-move guard (F1/B5): a Solver-settled contact end-effector must never
 *   move during finalization. When false the legacy finalize path runs unchanged. The guard only
 *   activates for poses that registered an engine [ContactSpec]; the reconstruction touches only
 *   the chest subtree (shoulders/arms/neck/head), so it never displaces hand/foot contacts, and the
 *   guard is a no-op that leaves output byte-identical for every contact-less production pose.
 */
object EngineFlags {
    /**
     * Master switch (M0 → M2) — when **false** (M0 default) the engine runs the legacy path
     * (`pose.build()` → `SkeletonPoseFinalizer.finalize()` → optional validation), whether invoked
     * directly by consumers or via [SkeletonPipeline.produceFrame] (which, in legacy mode, just
     * wraps that same path — zero behavior change). When **true** (M2, the current default) the
     * pipeline owns the full ordered stage chain: `pose.build()` → `ConstraintSolver.solve` →
     * `SkeletonPoseFinalizer.finalize()` (→ FK → optional validation). The Finalizer's internal
     * `ConstraintSolver.solve` call was removed in M2 (RFC_ENGINE_PIPELINE §8.1) — the pipeline is
     * now the **sole** caller of both, in fixed order.
     *
     * **Coherence invariant** (RFC_ENGINE_PIPELINE §5.7 / RFC_EXECUTION_CONTRACT §14): under the
     * *full* Architecture-v2 preset an active pipeline also requires [FINALIZER_OWNS_CONVERSION]
     * (a pipeline without finalizer-owned conversion is incoherent). M2 deliberately ships with
     * `PIPELINE_ACTIVE=true` but `FINALIZER_OWNS_CONVERSION=false` to keep the production output
     * **byte-identical** to the pre-M2 baseline — the Finalizer's internal Solver call was the only
     * place that flag's F1/B5 no-move guard could fire, and removing it (plus the guard's no-op
     * status when the flag is off) means the rendered frame is unchanged. The constructor invariant
     * therefore only enforces `FINALIZER_OWNS_CONVERSION` once that flag is itself turned on (M4);
     * M2 keeps it off by design. The pipeline still *is* the exclusive path, so the F1 re-entrancy
     * risk is gone regardless.
     */
    var PIPELINE_ACTIVE: Boolean = true

    /**
     * M3 (default-on) — the [ConstraintSolver] owns the root/pelvis transform: it seeds the pelvis
     * height from the pose's declared [PostureIntent], resolves contact conflicts via
     * `contactPrecedence`, and applies inter-frame smoothing (F9). Fires **only** for poses that
     * registered an engine [ContactSpec]; the solver no-ops on contact-less poses, so all production
     * poses (none register engine contacts) stay byte-identical. Set `false` to restore the pre-M3
     * relaxation-only behaviour.
     */
    var SOLVER_OWNS_POSTURE: Boolean = true
    /**
     * M4 (default-on) — the [SkeletonPoseFinalizer] is the *exclusive* writer of local transforms and
     * enforces the F1/B5 read-only chest-frame guard: a Solver-settled contact end-effector must not
     * move during finalization. Enforces the `reconstructChestFrame` no-move guard. The guard only
     * fires for contact poses and never
     * displaces hand/foot contacts (the chest reconstruction touches only the chest subtree), so it
     * is byte-identical for all production poses. Set `false` to restore the pre-M4 finalize.
     */
    var FINALIZER_OWNS_CONVERSION: Boolean = true

    /**
     * B1 (Branch B, default-off) — the [IkStage] owns limb solving: it consumes the §1.1
     * `limbTargets` carrier (populated by every `bakeIkLimb` call, which now also forwards its
     * end joint + world target into the carrier) and re-derives the limb local positions on the
     * engine-owned node tree, registering contacts for any matching [ContactSpec]. When **false**
     * (the default) `bakeIkLimb` remains the sole limb solver, so every pose is byte-identical to
     * the pre-B1 baseline — B1 is purely additive and reversible. Flip this on (after the
     * `IkStageTest` byte-identity check is green) to make the stage the real solver.
     */
    var IK_STAGE_ACTIVE: Boolean = false

    /**
     * B2 (Branch B, default-on) — the [SkeletonPoseFinalizer] consumes the §1.1 `spineIntent` and
     * `jointIntents` carriers (now populated by every trunk/hip/girdle/extremity authoring helper,
     * which forward their intent through the sole-mutator `IntentBuilder`). The Finalizer re-derives
     * the declared node rotations from the carriers and re-propagates FK, which is idempotent with the
     * node write the helper also performs during build (kept so build-time logic that reads a node's
     * world transform keeps working), so every pose stays byte-identical to the pre-B2 baseline. Set
     * `false` to restore the pre-B2 finalize (carriers recorded but not consumed).
     */
    var FINALIZER_CONSUMES_INTENT: Boolean = true

    // Phase 7 (Gap 7) — COMPLETE. The gaze-as-`headTarget` resolver in the Finalizer is now the
    // sole head/neck writer; the legacy direction-based `buildHead` fallback was removed after
    // `HeadTargetBaselineTest` proved the resolver byte-identical (maxDeviation ~6e-5). The former
    // `HEAD_TARGET_ENABLED` gate is therefore gone (it controlled a branch that no longer exists);
    // gaze resolution is unconditional. No flag replaces it.

    /** Snapshot of every flag, for assertions that a phase is enabled/disabled in tests. */
    fun snapshot(): Map<String, Boolean> = mapOf(
        "PIPELINE_ACTIVE" to PIPELINE_ACTIVE,
        "SOLVER_OWNS_POSTURE" to SOLVER_OWNS_POSTURE,
        "FINALIZER_OWNS_CONVERSION" to FINALIZER_OWNS_CONVERSION,
        "IK_STAGE_ACTIVE" to IK_STAGE_ACTIVE,
        "FINALIZER_CONSUMES_INTENT" to FINALIZER_CONSUMES_INTENT
    )
}
