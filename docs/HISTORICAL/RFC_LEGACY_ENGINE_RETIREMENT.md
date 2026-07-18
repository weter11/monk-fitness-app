> [!IMPORTANT]
> **STATUS: SUPERSEDED (historical).** This audit's baseline (`HEAD 038bfae`) and its
> entire inventory (L1–L8: the `EngineFlags` object, the `finalize` compatibility
> bridge, `preConvertPoles`, `buildHead`, the five deprecated members, `ExerciseReview`)
> describe a state the code **no longer has**. All of it was removed by Phases A–G of
> `RFC_ENGINE_CLEANUP_PLAN.md` (actual HEAD `ebab2d6`). The only surviving engine flag
> is `IK_STAGE_ACTIVE` (default `false`) in `IkStage.kt`, kept intentionally as a future
> additive path. Keep for archaeology only.

# RFC — Legacy Engine Retirement Audit

- **Status:** Inventory / Audit only. No code is modified by this document. → **SUPERSEDED** (see banner above)
- **Scope:** Determine precisely what prevents the *complete* removal of the legacy
MonkEngine following the Architecture-v2 migration
(`SkeletonPipeline` → `ConstraintSolver.solve` → `SkeletonPoseFinalizer.finalize` → FK).
- **Baseline (HISTORICAL):** Branch `session/agent_b795bd6c-5a92-47c7-9d9c-5693da550c5a`, HEAD `038bfae` — **obsolete**; actual HEAD is `ebab2d6`.
- **Method:** Static read of `app/src/main` + `app/src/test`; `EngineFlags` defaults are
the live production configuration (`PIPELINE_ACTIVE=true`, `SOLVER_OWNS_POSTURE=true`,
`FINALIZER_OWNS_CONVERSION=true`, `IK_STAGE_ACTIVE=false`, `FINALIZER_CONSUMES_INTENT=true`).

---

## 0. Executive summary

The Architecture-v2 engine is **fully live and is the sole runtime path**. The "legacy
engine" that remains is *not* a parallel runtime subsystem — it is a set of **retained
code paths, dead-but-compiled branches, deprecated members, and feature flags whose
`=false` paths are still compiled and tested**. Complete removal is blocked by five
categories:

1. **Legacy position-driven compatibility bridge** in
   `SkeletonPoseFinalizer.finalize` (`SkeletonPoseFinalizer.kt:580-604`), active whenever
   `pose.roots.isEmpty()`. Reachable only for empty-`SkeletonPose()` scratch/contract
   instances — *not* for any production pose (all production poses populate `roots`).
2. **Feature-flag `=false` rollback branches** retained for byte-identity tests:
   `EngineFlags.PIPELINE_ACTIVE` (solver-skip), `SOLVER_OWNS_POSTURE` (pre-M3 relaxation),
   `FINALIZER_OWNS_CONVERSION`, `FINALIZER_CONSUMES_INTENT`, `IK_STAGE_ACTIVE`.
3. **Deprecated members still compiled**: `AnimationMode`,
   `rememberAnimationController(mode, …)`, `PoseBuilder.defaultCamera`,
   `PoseBuilder.evaluate`, `SkeletonMath.solveIK` frame-relative overload,
   `LegacySkeletonDefinition` typealias, `preConvertPoles` reserved no-op.
4. **Legacy direction-based `buildHead`** still used by 5 validation *instrument* poses
   and one test (`ValidatorRomClusterTest`).
5. **`ExerciseReview` / `ExerciseReviewReport` / `ExerciseSnapshotSequence`** pipeline has
   **no production caller** — it is wired only through `ExerciseGenerationContract` (a data
   class) and exercised solely by tests. It is removable *independently* of the MonkEngine runtime, but
   its symbols are still compile-live via renderers (`SkeletonSnapshotRenderer`).

Additionally, the `PIPELINE_ACTIVE=false` "legacy bypass" documented in
`SkeletonPipeline.kt:29-31,76-77` and `EngineFlags.kt:28-47` is **partly fictional**: the
only runtime effect of `false` is to skip the `ConstraintSolver.solve` call at
`SkeletonPipeline.kt:103`; `finalize` runs unconditionally. The docs overstate a divergent
code path. This is a documentation/hygiene blocker, not a second engine.

---

## 1. Remaining legacy subsystems

| # | Subsystem | Location | Reachable in production? | Notes |
|---|-----------|----------|--------------------------|-------|
| L1 | **Position-driven compatibility bridge** (`else` branch) | `SkeletonPoseFinalizer.kt:580-604` (`ensureHierarchy` / `setupTransforms` / legacy `roots`) | **No** — only when `pose.roots.isEmpty()`; every production pose sets `roots` via `SkeletonFactory` or `roots = listOf(pelvis!!)` | Largest retained legacy block. Also uses `nodesMap`, `roots!!`, `ensureHierarchy()`, `setupTransforms()` — a second skeleton hierarchy parallel to the §1.1 carrier tree. |
| L2 | **`PIPELINE_ACTIVE=false` solver-skip branch** | `SkeletonPipeline.kt:103-106` (gated by `EngineFlags.PIPELINE_ACTIVE`) | No (flag is `true` in prod) | Skips `ConstraintSolver.solve` for contact/non-CUSTOM-posture poses. Docstring claims a full "pre-M0" fallback that does not exist. |
| L3 | **`SOLVER_OWNS_POSTURE=false` pre-M3 relaxation branch** | `ConstraintSolver.kt:80-85,216,260,267,414,456-474` (gated by `EngineFlags.SOLVER_OWNS_POSTURE`) | No (flag is `true`) | The non-posture-seed relaxation path; still compiled verbatim. |
| L4 | **`FINALIZER_OWNS_CONVERSION=false` no-op path** | `SkeletonPoseFinalizer.kt:44,503-508` (`preConvertPoles` is a no-op when flag off) | No (flag is `true`) | `preConvertPoles` is a reserved hook with no behaviour. |
| L5 | **`FINALIZER_CONSUMES_INTENT=false` skip** | `SkeletonPoseFinalizer.applyIntentCarriers` (gated by flag) | No (flag is `true`) | Carrier consumers become no-ops when off. |
| L6 | **`IK_STAGE_ACTIVE=false` bake path** | `SkeletonPipeline.runStages:92-97` + `IkStage.apply` (no-op when off) + `BasePose.bakeIkLimb` | N/A (flag off is the *current* live path) | `bakeIkLimb` is the *sole* limb solver today; `IkStage` is the not-yet-enabled replacement. Flipping the flag is *adding* new engine, not removing legacy. |
| L7 | **`buildHead` direction-based gaze** | `BasePose.kt:31`, `BaseValidationPose.kt:85` | Indirect — only via 5 validation instrument poses | Replaced by carrier `buildGaze`/`headTarget` for production; kept as shared math + used by instruments. |
| L8 | **`ExerciseReview` / `ExerciseReviewReport` / `ExerciseSnapshotSequence` review pipeline** | `ExerciseReview.kt`, `ExerciseReviewReport.kt`, `ExerciseSnapshotSequence.kt`, `ExerciseGenerationContract.kt` | **No production caller** | Only `ExerciseGenerationContract` references the *type*; `ExerciseReview.review(...)` is invoked solely by tests. `SkeletonSnapshotRenderer` emits `ExerciseSnapshotSequence` (live renderer output), but nothing in `main` consumes the review. |

---

## 2. Exercises still using legacy APIs

All **58 production poses** in `app/src/main/.../poses/` and the **registry**
`AnimationRegistry.kt` go through the live pipeline (factory + `build` + carriers). None
call a `@Deprecated` member. The only pose-side legacy usage is in the **validation
instrument** poses (diagnostic probes, intentionally permanent per `AGENTS.md`):

| Pose | Legacy API | Location |
|------|-----------|----------|
| `validation/poses/PikeSitPose.kt` | `buildHead(...)` | `:47` |
| `validation/poses/DeepOverheadSquatPose.kt` | `buildHead(...)` | `:56` |
| `validation/poses/DeadHangPose.kt` | `buildHead(...)` | `:89` |
| `validation/poses/MiddleSplitPose.kt` | `buildHead(...)` | `:59` |
| `validation/poses/BaseValidationPose.kt` | `buildHead(...)` (definition) | `:85` |

Also note: **every production pose still calls `SkeletonFactory.createStandardSkeleton()`
or `createPushUpSkeleton()`** and writes nodes directly via `buildTorso` / `buildPelvis` /
`buildShoulders` / `bakeIkLimb` / `declareJointIntent` / `declarePelvisTilt`. These are
**Shape-Constraint / Articulation direct node writes**, recognized valid (RFC_BRANCH_B_REPLAN §7)
and **NOT legacy to be migrated**. They are the production authoring surface.

---

## 3. Legacy helpers still referenced

| Helper / member | File:line | Referenced by | Disposition |
|-----------------|-----------|---------------|-------------|
| `AnimationMode` enum | `AnimationController.kt:7-11` | only its own deprecated overload | **Dead/deletable** |
| `rememberAnimationController(mode, …)` overload | `AnimationController.kt:142-156` | none | **Dead/deletable** |
| `PoseBuilder.defaultCamera` | `PoseBuilder.kt:4-5` | none (superseded by `metadata.camera`) | **Dead/deletable** |
| `PoseBuilder.evaluate(...)` | `PoseBuilder.kt:12-16` | none | **Dead/deletable** |
| `SkeletonMath.solveIK` frame-relative overload | `SkeletonMath.kt:1122-1130` | none (deprecated "Phase 3") | **Deletable** |
| `LegacySkeletonDefinition` typealias | `SkeletonDefinition.kt:64` | none | **Deletable** |
| `preConvertPoles(...)` | `SkeletonPoseFinalizer.kt:44,51,507-508` | self | **Reserved no-op, removable** |
| `buildHead(...)` | `BasePose.kt:31`, `BaseValidationPose.kt:85` | 5 validation poses + `ValidatorRomClusterTest` | **Migratable to `headTarget`** |
| `ensureHierarchy()` / `setupTransforms()` / legacy `roots` | `SkeletonPoseFinalizer.kt:581-604` | legacy bridge L1 only | **Removable with L1** |
| `buildTorso` / `buildPelvis` / `buildShoulders` | `BasePose.kt:27,66,71` | production poses + validation poses | **Permanent (Shape Constraints)** |

---

## 4. Compile-time dependencies

Symbols that gate or represent the legacy engine, with compile status.

| Symbol | Main (runtime) | Test | Verdict |
|--------|----------------|------|---------|
| `SkeletonEngine` | `ExerciseAnimation.kt:75`, `ValidationPoseLauncher.kt:51`, renderers | 2 test files | **Live runtime** — not legacy |
| `SkeletonProjector` | renderers | 1 test | **Live runtime** |
| `ScreenSpaceCompensation` | renderers | 1 test | **Live runtime** |
| `ProjectedSkeleton` | projector + renderers | 1 test | **Live runtime** |
| `Bone` | `SkeletonEngine.kt` | via engine tests | **Live runtime** |
| `Camera` / `CameraDefinition` | ~55 files | ~28 tests | **Live runtime (core)** |
| `EnvironmentAnchor` / `EnvironmentDefinition` | support + poses | tests | **Live runtime** |
| `SupportContact` / `SupportDefinition` / `SupportMath` / `SupportPoint` / `LeverModel` | ~30 files | tests | **Live runtime (core support model)** |
| `FootDefinition` / `HandDefinition` / `PivotType` | poses, defs | tests | **Live runtime** |
| `MotionDrivers` | `BasePose`, `AlternatingBirdDogPose`, validation | `MotionDriversTest` | **Live runtime** |
| `AnimationState` | `PoseContext` | tests | **Live runtime** |
| `PushUpGeometrySolver` | `BasePushUpPose.kt:78,82,98` | 3 tests | **Live runtime** |
| `ExerciseSnapshot` / `ExerciseSnapshotSequence` | `SkeletonSnapshotRenderer.kt:118,135,137` | 11 tests | **Live runtime (snapshot render)** |
| `ExerciseReview` / `ExerciseReviewReport` | only `ExerciseGenerationContract.kt` (type) | 17 tests | **No prod caller — removable independently** |
| `AnimationMode` | `AnimationController.kt` only | none | **Legacy — deletable** |
| `SkeletonMath.solveIK` (frame-relative) | `SkeletonMath.kt` only | none | **Legacy — deletable** |
| `LegacySkeletonDefinition` | `SkeletonDefinition.kt` only | none | **Legacy — deletable** |
| `PoseBuilder.defaultCamera` / `evaluate` | `PoseBuilder.kt` only | none | **Legacy — deletable** |

**Conclusion:** Of the probed symbols, only `AnimationMode`, the deprecated
`rememberAnimationController` overload, `PoseBuilder.defaultCamera`/`evaluate`,
`SkeletonMath.solveIK` (frame-relative), and `LegacySkeletonDefinition` are genuine legacy
removals. Everything else is live MonkEngine runtime code.

---

## 5. Runtime dependencies (call graph)

```
UI: ExerciseAnimation.kt
  └─ rememberAnimationController(metadata)      [PoseMetadata overload — modern]
  └─ PoseConfig.builder.build(poseContext)      [PoseBuilder.build — modern]
  └─ SkeletonEngine(definition, style)          [live]
  └─ SkeletonRenderer(pose, camera, engine, …)
        └─ SkeletonPipeline(definition).produceFrame(pose)
              ├─ IkStage.apply(pose)              [no-op while IK_STAGE_ACTIVE=false]
              ├─ ConstraintSolver.solve(pose)     [gated: contacts or posture-driven]
              └─ SkeletonPoseFinalizer.finalize(pose)
                    ├─ applyIntentCarriers        [gated FINALIZER_CONSUMES_INTENT]
                    ├─ reconstructChestFrame
                    ├─ resolveHeadTarget
                    ├─ adjustFoot/HandOrientation
                    └─ applyValidationStamps
        └─ SkeletonProjector / ScreenSpaceCompensation / ProjectedSkeleton [live render]

UI: ValidationPoseLauncher.kt
  └─ SkeletonEngine(...)
  └─ PoseBuilder.build(...) → SkeletonPipeline.produceFrameValidated(...)  [validating entry]

Snapshot: SkeletonSnapshotRenderer.kt
  └─ SkeletonPipeline.produceFrame(pose)
  └─ PoseBuilder.build(context)
  └─ emits ExerciseSnapshotSequence            [live, but no prod *consumer* of review]
```

**Key runtime facts:**
- `SkeletonPipeline` is the **sole** caller of `ConstraintSolver.solve` (M2 re-pointing
  complete) and the sole owner of `SkeletonPoseFinalizer`.
- `produceFrameValidated` has **no `main` caller** — only tests exercise it.
- `ExerciseReview.review(...)` has **no `main` caller**.
- `ExerciseAnimation.kt` calls `builder.build()` directly (not via `produceFrame`), then
  hands the already-built pose to `SkeletonRenderer` → `produceFrame`; the Solver is
  therefore skipped for it (it carries no engine contacts and is CUSTOM-posture), which is
  the expected no-op.

---

## 6. Test dependencies on legacy paths

Tests that flip a flag to `false` (exercise a legacy/rollback branch):

| Test | Flag flipped | Line(s) |
|------|--------------|---------|
| `SkeletonPipelineM0Test.kt` | `PIPELINE_ACTIVE=false`, `FINALIZER_OWNS_CONVERSION=false` | 128,129,184 |
| `ConstraintSolverPhase2Test.kt` | `SOLVER_OWNS_POSTURE=false` | 73,84 |
| `PostureUniversalityTest.kt` | `SOLVER_OWNS_POSTURE` on/off | 95,107 |
| `FinalizerOwnsConversionM4Test.kt` | `FINALIZER_OWNS_CONVERSION=false` | 73,82-84,97,105-107 |
| `FinalizerIntentConsumersTest.kt` | `FINALIZER_CONSUMES_INTENT=false` | 93,114 |
| `ChestFrameNoMoveTest.kt` | `FINALIZER_OWNS_CONVERSION=false` | 70 |
| `BranchBFamilyMigrationTest.kt` | `FINALIZER_CONSUMES_INTENT=false` | 77 |
| `IkStageTest.kt` | `IK_STAGE_ACTIVE` on/off | 95,116 |

Tests calling `finalizer.finalize(...)` directly (bypass the pipeline — must be
re-pointed through `produceFrame` before the legacy direct path is removed):

- `IKLimbHelperTest.kt:26,55,83,109,135`
- `GluteBridgePoseTest.kt:30`
- `HeadTargetBaselineTest.kt:67`
- `LumbarThoracicSpineTest.kt:43`
- `WallSlidesPoseTest.kt:30`

Tests referencing deprecated legacy APIs / empty-roots bridge:
- `ValidatorRomClusterTest.kt:334` — **calls `buildHead(...)`** (legacy direction path).
- `HeadTargetBaselineTest.kt:60` — comment reference to `BasePose.buildHead`.
- `SkeletonSnapshotRendererTest.kt:38,51`, `ProceduralAnimationPerformanceRefactorTest.kt:53`
  — construct `SkeletonEngine(...)` (live, not legacy).

---

## 7. Disposition of every dependency

Legend: **R**emovable now · **M**igratable · **P**ermanent (intentionally kept) · **I**ndependent (removable outside engine retirement).

| Dependency | Disposition | Justification |
|-----------|-------------|---------------|
| L1 compatibility bridge (`SkeletonPoseFinalizer.kt:580-604`) | **M** (then R) | Empty-`roots` path; delete once empty-`roots` producers are gone / replaced by carrier tree. |
| L2 `PIPELINE_ACTIVE=false` branch | **R** | Flag is prod-`true`; delete flag + branch + fix docstrings. |
| L3 `SOLVER_OWNS_POSTURE=false` branch | **R** | Flag is prod-`true`; delete branch + flag. |
| L4 `FINALIZER_OWNS_CONVERSION=false` path | **R** | Flag is prod-`true`; delete `preConvertPoles` + flag. |
| L5 `FINALIZER_CONSUMES_INTENT=false` skip | **R** | Flag is prod-`true`; delete flag (consumers become unconditional). |
| L6 `IK_STAGE_ACTIVE` (off = current live) | **P** | This *is* the current production limb solver; flipping is additive, not removal. |
| L7 `buildHead` (5 validation poses) | **M** | Instruments must move to `headTarget`; then `buildHead` math can be deleted. |
| L8 `ExerciseReview` pipeline | **I** | No prod caller; removable independently of engine retirement. |
| `AnimationMode` enum | **R** | Dead; only self-referenced. |
| `rememberAnimationController(mode,…)` | **R** | Dead overload. |
| `PoseBuilder.defaultCamera` | **R** | Superseded by `metadata.camera`. |
| `PoseBuilder.evaluate` | **R** | Superseded by `build(context)`. |
| `SkeletonMath.solveIK` (frame-relative) | **R** | Deprecated; no callers. |
| `LegacySkeletonDefinition` typealias | **R** | Dead alias. |
| `preConvertPoles` hook | **R** | Reserved no-op. |
| `buildTorso`/`buildPelvis`/`buildShoulders`/`bakeIkLimb`/`declare*` | **P** | Shape-Constraint/Articulation authoring surface; production code. |
| `SkeletonEngine`/`SkeletonProjector`/`ScreenSpaceCompensation`/`ProjectedSkeleton`/`Bone` | **P** | Live rendering runtime. |
| `Support*`/`Camera*`/`EnvironmentAnchor`/`FootDefinition`/`HandDefinition`/`PivotType`/`MotionDrivers`/`AnimationState`/`PushUpGeometrySolver` | **P** | Live runtime. |
| `ExerciseSnapshot`/`ExerciseSnapshotSequence` (renderer emit) | **P** | Live snapshot rendering output. |

---

## 8. Dependency graph — required removal order

```
                 (flag defaults are prod-true)
                           │
   ┌───────────────────────┼───────────────────────────────────────────┐
   │                       │                                            │
[R] delete dead members   [R] delete flag=false branches              [I] ExerciseReview pipeline
   AnimationMode              PIPELINE_ACTIVE=false (L2)                  (independent removal)
   rememberAnimationController(mode)
   PoseBuilder.defaultCamera/evaluate
   SkeletonMath.solveIK (frame-rel)
   LegacySkeletonDefinition
   preConvertPoles
           │                       │
           │                       ▼
           │              [R] collapse each flag to its true-branch:
           │                  SOLVER_OWNS_POSTURE (L3)
           │                  FINALIZER_OWNS_CONVERSION (L4/L5)
           │                  FINALIZER_CONSUMES_INTENT (L5)
           │                       │
           │                       ▼
           │              [M] migrate 5 validation poses from buildHead → headTarget
           │                       │
           │                       ▼
           │              [R] delete buildHead math (L7)
           │                       │
           ▼                       ▼
   [Test] re-point direct finalizer.finalize() tests ──► produceFrame
           │
           ▼
   [R] delete L1 compatibility bridge (SkeletonPoseFinalizer.kt:580-604)
       requires: no empty-roots SkeletonPose reaches finalize in prod/tests
       (production poses all set roots; only scratch/contract instances had empty roots)
           │
           ▼
   [R] remove EngineFlags object entirely (all flags collapsed/removed)
```

**Ordering constraints:**
- Dead members (top-left) can be deleted at any time — zero compile coupling to the
  bridge or flags. Do first (cheapest, lowest risk).
- Flag-`false` branches can only be deleted **after** the byte-identity tests that flip
  them are updated/removed (section 6). The branch deletion and the test update are a
  single atomic change per flag.
- `buildHead` deletion requires the 5 validation poses + `ValidatorRomClusterTest` to be
  migrated to `headTarget` first (L7 → M).
- L1 (compatibility bridge) is **last**: it is the only legacy path that is genuinely
  compile-live and could be reached by any `SkeletonPose()` with empty `roots`. Once all
  producers of empty-`roots` instances are eliminated (or the bridge is guarded/removed and
  tests re-pointed), it can be deleted. Note production never reaches it today.
- `ExerciseReview` (L8) is orthogonal — remove whenever convenient, before or after,
  without affecting the MonkEngine runtime.

---

## 9. Proposed retirement sequence

**Phase A — Dead-symbol deletion (no behavioural change, no flag changes).**
1. Delete `AnimationMode` enum and the deprecated `rememberAnimationController(mode, …)` overload (`AnimationController.kt`).
2. Delete `PoseBuilder.defaultCamera` and `PoseBuilder.evaluate` (`PoseBuilder.kt`).
3. Delete `SkeletonMath.solveIK` frame-relative overload (`SkeletonMath.kt:1122-1130`).
4. Delete `LegacySkeletonDefinition` typealias (`SkeletonDefinition.kt:64`).
5. Remove `preConvertPoles` hook and its call (`SkeletonPoseFinalizer.kt:44,51,507-508`).
6. Grep to confirm zero references; compile.

**Phase B — Flag collapse (remove `=false` rollback branches).**
For each flag `PIPELINE_ACTIVE`, `SOLVER_OWNS_POSTURE`, `FINALIZER_OWNS_CONVERSION`,
`FINALIZER_CONSUMES_INTENT`:
1. Update the byte-identity tests that flip it (section 6) to assert the true-branch
   behaviour only (or delete the rollback assertion).
2. Collapse the `if (flag)` to its true branch; delete the `else`/false code.
3. Delete the flag field from `EngineFlags`.
4. Compile + run the per-flag test.
`IK_STAGE_ACTIVE` is **excluded** (it is the current production limb path; removal of its
flag is a separate additive decision, not legacy retirement).

**Phase C — Validation-instrument migration (L7).**
1. Migrate `PikeSitPose`, `DeepOverheadSquatPose`, `DeadHangPose`, `MiddleSplitPose`,
   `BaseValidationPose` from `buildHead(...)` to `headTarget`/`buildGaze`.
2. Update `ValidatorRomClusterTest.kt:334` to drive the carrier path.
3. Delete `buildHead` math from `BasePose.kt:31` and `BaseValidationPose.kt:85`.

**Phase D — Direct-finalize test re-pointing (test hygiene).**
Re-point `IKLimbHelperTest`, `GluteBridgePoseTest`, `HeadTargetBaselineTest`,
`LumbarThoracicSpineTest`, `WallSlidesPoseTest` from `finalizer.finalize(...)` to
`SkeletonPipeline.produceFrame(...)` so the legacy direct entry is no longer exercised.

**Phase E — Compatibility bridge removal (L1).**
1. Confirm no production path produces an empty-`roots` `SkeletonPose` reaching `finalize`
   (verified today: all poses set `roots`). Replace any empty-`roots` scratch/contract
   instance (e.g. `jointsBuffer = SkeletonPose()`) with a roots-populated instance or guard.
2. Delete `SkeletonPoseFinalizer.kt:580-604` (`else` branch) plus `ensureHierarchy`,
   `setupTransforms`, and the legacy `roots` field.
3. Compile + run full suite.

**Phase F — Flag object cleanup.**
Delete `EngineFlags` entirely once all fields are collapsed. Update `SkeletonPipeline`
constructor `require` (lines 48-50) accordingly.

**Phase G — Independent review-pipeline removal (L8, optional).**
Remove `ExerciseReview`, `ExerciseReviewReport`, `ExerciseGenerationContract` review
wiring if the biomechanical-review feature is not productised; otherwise add a production
caller. This is independent of Phases A–F.

---

## 10. What prevents *complete* removal (final answer)

Complete removal is blocked, in strict order of precedence:

1. **Retained flag-`=false` branches** (`L2`–`L5`) — still compiled and still asserted by
   byte-identity tests. Removal requires editing those tests first.
2. **The L1 compatibility bridge** (`SkeletonPoseFinalizer.kt:580-604`) — compile-live,
   reachable by any empty-`roots` `SkeletonPose`; the single genuine "other engine" path
   that must be eliminated before `EngineFlags` can die.
3. **`buildHead` in 5 validation instruments + 1 test** (L7) — blocks deletion of the
   legacy gaze math.
4. **Dead deprecated members** (`AnimationMode`, deprecated overloads, typealias,
   `preConvertPoles`) — pure dead code, removable immediately but still present.
5. **Direct-`finalize` tests** (section 6) — keep a legacy entry point alive.
6. **`ExerciseReview` pipeline** (L8) — removable independently; not strictly part of the
   engine but still compile-live via the snapshot renderer.

No *production* code path runs the legacy engine today. The blockers are entirely
compile-live dead branches, deprecated members, flag-gated rollback paths, and tests that
pin them.
