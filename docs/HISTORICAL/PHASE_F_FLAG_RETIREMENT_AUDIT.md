# Phase F - EngineFlags retirement & architecture-flag audit

**Result:** the `EngineFlags` object is deleted. The only surviving migration
flag, `IK_STAGE_ACTIVE`, is relocated to a top-level `var` in `IkStage.kt`
beside its sole reader. `grep EngineFlags` across `app/` finds nothing.
`./gradlew :app:testDebugUnitTest` -> **BUILD SUCCESSFUL, 283 tests, 0 failures,
0 errors, 0 skipped** (byte-identical: `IK_STAGE_ACTIVE` keeps its `false`
default, so `bakeIkLimb` remains the sole limb solver).

## Changes
| Step | Action | Where |
|------|--------|-------|
| F1 | Delete the `EngineFlags` object + its `snapshot()` helper | `EngineFlags.kt` (removed) |
| - | Relocate `IK_STAGE_ACTIVE` to a top-level `var` co-located with its reader | `IkStage.kt` |
| - | Retarget the gate `if (!EngineFlags.IK_STAGE_ACTIVE)` -> `if (!IK_STAGE_ACTIVE)` | `IkStage.kt:54` |
| - | Update all doc comments referencing `EngineFlags.IK_STAGE_ACTIVE` | `BasePose.kt`, `BaseValidationPose.kt`, `PoseDefinition.kt`, `SkeletonPipeline.kt`, `AGENTS.md` |
| - | Replace the dead `EngineFlags.snapshot()` assertion with a structural entry-point-agreement check | `SkeletonPipelineM0Test.kt` |
| - | Retarget test control writes | `IkStageTest.kt` |

## Repository-wide audit (Branch A)

### 1. No `EngineFlags` references
```
$ grep -rn "EngineFlags" app/
(zero matches)
```
The object and file are gone; the only historical mentions live in design RFCs
(`RFC_LEGACY_ENGINE_RETIREMENT.md`, `PHASE_E_PLAN.md`) that record the pre-F
state and are not code.

### 2. No architecture feature flags remain
The four Phase-B flags (`PIPELINE_ACTIVE`, `SOLVER_OWNS_POSTURE`,
`FINALIZER_OWNS_CONVERSION`, `FINALIZER_CONSUMES_INTENT`) were collapsed to
their true branch and deleted in Phase B; only narrative "collapsed ..." comments
remain (no code reads them). `HEAD_TARGET_ENABLED` was removed with Phase 7.
No `object *Flags` gate exists in the engine.

### 3. No global runtime ownership switches
There is exactly **one** remaining engine-level runtime flag -
`IK_STAGE_ACTIVE` (top-level `var` in `IkStage.kt`, default `false`). It is an
**additive future-decision** flag (the not-yet-flipped dead->live switch of the
B1 `IkStage` migration), not an ownership/rollback switch: with it off the
production limb path is unchanged, and no architecture ownership is decided by
it. It is co-located with its consumer rather than in a global flag object, so
it is not a cross-cutting global switch.

(Flags outside the animation engine - `TIMER_TICKS_ENABLED`,
`VIBRATION_ENABLED`, `ADDITIONAL_POSTURE_TRAINING_ENABLED`,
`ValidationSettings.DEFAULT_ENABLED` - are user-facing app *preferences*, not
architecture/engine-ownership flags, and are out of scope.)

## Conclusion
Architecture ownership in Branch A is now encoded **structurally**, not through
runtime flags:
- The pipeline is unconditionally live - `SkeletonPipeline.runStages` is the
  single, fixed-order caller of `IkStage -> ConstraintSolver -> Finalizer`. Order
  and ownership are expressed by the call structure, not a flag.
- The Finalizer is the exclusive local-transform writer; the Solver owns
  posture/contact settling; conversion ownership is unconditional. Each is a
  structural single-responsibility, no longer a `*_OWNS_*` toggle.
- The one surviving `IK_STAGE_ACTIVE` flag is an additive migration gate for a
  not-yet-activated stage, kept deliberately per the RFC, and lives beside its
  reader - not a global ownership switch.
